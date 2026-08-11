package com.example.dormitory.ai.infrastructure.persistence;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.application.control.BillingSubject;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.ActorKind;
import com.example.dormitory.ai.domain.model.AiCapability;
import com.example.dormitory.ai.domain.model.ModelRequest;
import com.example.dormitory.ai.domain.model.ModelUsage;
import com.example.dormitory.ai.observability.AiOperationalMetrics;
import com.example.dormitory.ai.port.AiModelAttemptAccountingPort;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class JdbcAiModelAttemptAccounting implements AiModelAttemptAccountingPort {

    public static final String ESTIMATION_POLICY_VERSION = "provider-missing-usage-v1";

    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000L);
    private static final java.util.Set<String> REQUEST_KINDS = java.util.Set.of(
            "MODEL_COMPLETE", "MODEL_STREAM", "TOOL_LOOP", "STRUCTURED_REPAIR", "FALLBACK", "EMBEDDING");
    private static final java.util.Set<String> UNRESOLVED_STATES = java.util.Set.of(
            "STARTED", "NEEDS_RECONCILIATION");

    private final JdbcTemplate jdbc;
    private final JdbcAiUsageLedgerRepository ledger;
    private final JdbcAiBudgetService budgets;
    private final TransactionTemplate transactions;
    private final AiOperationalMetrics metrics;
    private final Duration attemptLeaseTimeout;
    private final String ownerInstanceId;

    public JdbcAiModelAttemptAccounting(
            JdbcTemplate jdbc,
            JdbcAiUsageLedgerRepository ledger,
            JdbcAiBudgetService budgets,
            PlatformTransactionManager transactionManager,
            AiOperationalMetrics metrics,
            @Value("${dormitory.ai.budget.attempt-recovery-stale-after:PT5M}") Duration attemptLeaseTimeout) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.budgets = Objects.requireNonNull(budgets);
        this.transactions = new TransactionTemplate(transactionManager);
        this.metrics = Objects.requireNonNull(metrics);
        if (attemptLeaseTimeout == null || attemptLeaseTimeout.isZero() || attemptLeaseTimeout.isNegative()) {
            throw new IllegalArgumentException("provider attempt lease 必须为正数");
        }
        this.attemptLeaseTimeout = attemptLeaseTimeout;
        this.ownerInstanceId = "ai-runtime-" + UUID.randomUUID();
    }

    /**
     * 在 provider supplier 被调用前，原子固定价格快照并登记 STARTED。唯一键冲突必须失败关闭，
     * 不能把同一 attempt number 当作一次新的物理请求再次发往 provider。
     */
    @Override
    public AttemptHandle begin(
            ModelRequest.BillingTrace trace,
            AiCapability capability,
            String providerCode,
            String modelName,
            String requestKind,
            int attemptNo,
            Instant startedAt) {
        validateIdentity(trace, capability, providerCode, modelName, requestKind, attemptNo, startedAt);
        return Objects.requireNonNull(transactions.execute(status -> {
            Instant persistedStartedAt = startedAt.truncatedTo(ChronoUnit.MICROS);
            budgets.requireProviderCallReservation(
                    new BillingSubject(BillingSubject.Kind.RUN, trace.runPublicId()), persistedStartedAt);
            Pricing price = effectivePricing(providerCode, modelName, persistedStartedAt);
            AttemptHandle handle = new AttemptHandle(trace, capability, providerCode, modelName, requestKind,
                    attemptNo, price.id(), price.currency(), price.inputRate(), price.outputRate(),
                    persistedStartedAt);
            Instant leaseExpiresAt = persistedStartedAt.plus(attemptLeaseTimeout).truncatedTo(ChronoUnit.MICROS);
            try {
                jdbc.update("INSERT INTO ai_provider_attempt "
                                + "(public_id,billing_subject_kind,billing_subject_public_id,request_sequence_no,"
                                + "attempt_no,request_kind,actor_kind,actor_user_id,service_principal_code,"
                                + "initiated_by_user_id,effective_subject_user_id,capability,provider_code,model_name,"
                                + "pricing_version_id,currency,input_cost_per_million,output_cost_per_million,"
                                + "estimation_policy_version,state,owner_instance_id,lease_expires_at,started_at,"
                                + "version,created_at,updated_at) "
                                + "VALUES (?,'RUN',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'STARTED',?,?,?,0,"
                                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                        UUID.randomUUID().toString(), trace.runPublicId(), trace.requestSequenceNo(), attemptNo,
                        requestKind, trace.actor().kind().name(), trace.actor().actorUserId(),
                        trace.actor().servicePrincipalCode(), trace.actor().initiatedByUserId(),
                        trace.actor().effectiveSubjectUserId(), capability.name(), providerCode, modelName,
                        price.id(), price.currency(), price.inputRate(), price.outputRate(),
                        ESTIMATION_POLICY_VERSION, ownerInstanceId, Timestamp.from(leaseExpiresAt),
                        Timestamp.from(persistedStartedAt));
            } catch (DuplicateKeyException duplicate) {
                throw new IllegalStateException(
                        "物理 provider attempt 已登记，禁止用相同序号重复调用 provider", duplicate);
            }
            return handle;
        }));
    }

    @Override
    public AttemptReceipt finish(
            AttemptHandle handle,
            ModelUsage usage,
            AttemptOutcome outcome,
            Duration duration,
            String failureCode) {
        validateTerminal(handle, usage, outcome, duration, failureCode);
        FinishResult result = Objects.requireNonNull(transactions.execute(status ->
                finishInTransaction(handle, usage, outcome, duration, failureCode)));
        if (result.appended()) recordMetrics(handle, usage, outcome, duration, result.receipt().costAmount());
        return result.receipt();
    }

    /** 只允许对已经进入人工对账态的物理请求补记保守估算，不能把活跃 STARTED 静默终结。 */
    public AttemptReceipt reconcileUnknown(
            String attemptPublicId,
            ModelUsage estimatedUsage,
            Duration duration,
            String failureCode) {
        if (attemptPublicId == null || !attemptPublicId.matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
                || estimatedUsage == null || estimatedUsage.source() != ModelUsage.Source.ESTIMATED) {
            throw new IllegalArgumentException("UNKNOWN attempt 对账参数不合法");
        }
        AttemptHandle handle = Objects.requireNonNull(transactions.execute(status -> {
            AttemptRow row = lockByPublicId(attemptPublicId);
            if (!"NEEDS_RECONCILIATION".equals(row.state()) && !"FINISHED".equals(row.state())) {
                throw new IllegalStateException("只有 NEEDS_RECONCILIATION attempt 可人工对账");
            }
            if ("FINISHED".equals(row.state()) && !"UNKNOWN".equals(row.attemptOutcome())) {
                throw new IllegalStateException("已由 provider 终结的 attempt 不能改写为 UNKNOWN");
            }
            return row.toHandle();
        }));
        return finish(handle, estimatedUsage, AttemptOutcome.UNKNOWN, duration, failureCode);
    }

    public int markStaleStartedForReconciliation(Instant startedAtOrBefore, Instant observedAt) {
        if (startedAtOrBefore == null || observedAt == null || startedAtOrBefore.isAfter(observedAt)) {
            throw new IllegalArgumentException("attempt 恢复时间窗口不合法");
        }
        return jdbc.update("UPDATE ai_provider_attempt SET state='NEEDS_RECONCILIATION',"
                        + "reconciliation_marked_at=?,version=version+1,updated_at=CURRENT_TIMESTAMP "
                        + "WHERE state='STARTED' AND started_at<=? "
                        + "AND (lease_expires_at IS NULL OR lease_expires_at<=?)",
                Timestamp.from(observedAt), Timestamp.from(startedAtOrBefore), Timestamp.from(observedAt));
    }

    /** 只认领 lease 已过期或旧迁移中无 lease 的 STARTED；不得触碰其他健康实例的请求。 */
    public int markExpiredStartedForReconciliation(Instant observedAt) {
        if (observedAt == null) throw new IllegalArgumentException("attempt 恢复时间不能为空");
        return jdbc.update("UPDATE ai_provider_attempt SET state='NEEDS_RECONCILIATION',"
                        + "reconciliation_marked_at=?,version=version+1,updated_at=CURRENT_TIMESTAMP "
                        + "WHERE state='STARTED' AND (lease_expires_at IS NULL OR lease_expires_at<=?)",
                Timestamp.from(observedAt), Timestamp.from(observedAt));
    }

    /** 当前实例只能续租仍未过期且仍归自己所有的 STARTED，过期后不得重新夺回所有权。 */
    public int renewOwnedStartedLeases(Instant observedAt) {
        if (observedAt == null) throw new IllegalArgumentException("attempt 续租时间不能为空");
        Instant normalized = observedAt.truncatedTo(ChronoUnit.MICROS);
        Instant nextExpiry = normalized.plus(attemptLeaseTimeout).truncatedTo(ChronoUnit.MICROS);
        return jdbc.update("UPDATE ai_provider_attempt SET lease_expires_at=?,version=version+1,"
                        + "updated_at=CURRENT_TIMESTAMP WHERE state='STARTED' AND owner_instance_id=? "
                        + "AND lease_expires_at>?",
                Timestamp.from(nextExpiry), ownerInstanceId, Timestamp.from(normalized));
    }

    public int unresolvedAttemptCount(BillingSubject subject) {
        if (subject == null) throw new IllegalArgumentException("计费主体不能为空");
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM ai_provider_attempt "
                        + "WHERE billing_subject_kind=? AND billing_subject_public_id=? "
                        + "AND state IN ('STARTED','NEEDS_RECONCILIATION')",
                Integer.class, subject.kind().name(), subject.publicId());
        return count == null ? 0 : count;
    }

    public BillingSubject subjectForAttempt(String attemptPublicId) {
        List<BillingSubject> rows = jdbc.query("SELECT billing_subject_kind,billing_subject_public_id "
                        + "FROM ai_provider_attempt WHERE public_id=?",
                (rs, rowNum) -> new BillingSubject(BillingSubject.Kind.valueOf(
                        rs.getString("billing_subject_kind")), rs.getString("billing_subject_public_id")),
                attemptPublicId);
        if (rows.size() != 1) throw new IllegalArgumentException("provider attempt 不存在");
        return rows.getFirst();
    }

    @Override
    public UsageTotals totals(String runPublicId) {
        BillingSubject subject = new BillingSubject(BillingSubject.Kind.RUN, runPublicId);
        JdbcAiUsageLedgerRepository.UsageTotals totals = ledger.totals(subject);
        return new UsageTotals(totals.attemptCount(), totals.inputTokens(), totals.outputTokens(),
                totals.costAmount(), totals.currency(), totals.estimatedAttemptCount());
    }

    private FinishResult finishInTransaction(
            AttemptHandle handle,
            ModelUsage usage,
            AttemptOutcome outcome,
            Duration duration,
            String failureCode) {
        AttemptRow stored = lockByIdentity(handle);
        requireSameIdentity(stored, handle);
        BigDecimal cost = cost(handle, usage);
        String estimationPolicy = usage.source() == ModelUsage.Source.ESTIMATED
                ? ESTIMATION_POLICY_VERSION : null;
        JdbcAiUsageLedgerRepository.UsageAttempt ledgerAttempt = new JdbcAiUsageLedgerRepository.UsageAttempt(
                stored.subject(), handle.trace().requestSequenceNo(), handle.attemptNo(), handle.requestKind(),
                handle.trace().actor(), handle.capability().name(), handle.providerCode(), handle.modelName(), null,
                handle.pricingVersionId(), usage.inputTokens(), usage.outputTokens(), cost, handle.currency(),
                usage.source().name(), outcome.name(), estimationPolicy, duration.toMillis(), failureCode,
                handle.startedAt());
        boolean appended = ledger.appendIdempotent(ledgerAttempt);

        if ("FINISHED".equals(stored.state())) {
            if (!stored.sameTerminal(usage, outcome, cost, duration, failureCode, estimationPolicy)) {
                throw new IllegalStateException("同一物理 provider attempt 出现不一致对账结果");
            }
        } else {
            if (!UNRESOLVED_STATES.contains(stored.state())) {
                throw new IllegalStateException("provider attempt 状态不可终结: " + stored.state());
            }
            int updated = jdbc.update("UPDATE ai_provider_attempt SET state='FINISHED',input_tokens=?,"
                            + "output_tokens=?,cost_amount=?,usage_source=?,attempt_outcome=?,"
                            + "estimation_policy_version=?,duration_ms=?,failure_code=?,finished_at=?,"
                            + "version=version+1,updated_at=CURRENT_TIMESTAMP "
                            + "WHERE id=? AND version=? AND state IN ('STARTED','NEEDS_RECONCILIATION')",
                    usage.inputTokens(), usage.outputTokens(), cost, usage.source().name(), outcome.name(),
                    estimationPolicy == null ? ESTIMATION_POLICY_VERSION : estimationPolicy,
                    duration.toMillis(), failureCode, Timestamp.from(Instant.now()), stored.id(), stored.version());
            if (updated != 1) throw new IllegalStateException("provider attempt 终态 CAS 失败");
        }
        return new FinishResult(receipt(handle, usage, outcome, cost), appended);
    }

    private Pricing effectivePricing(String providerCode, String modelName, Instant startedAt) {
        List<Pricing> prices = jdbc.query(
                "SELECT id,currency,input_cost_per_million,output_cost_per_million "
                        + "FROM ai_pricing_version WHERE provider_code=? AND model_name=? "
                        + "AND effective_from<=? AND (effective_to IS NULL OR effective_to>?) "
                        + "ORDER BY effective_from DESC,id DESC FOR UPDATE",
                (rs, rowNum) -> new Pricing(rs.getLong("id"), rs.getString("currency"),
                        rs.getBigDecimal("input_cost_per_million"),
                        rs.getBigDecimal("output_cost_per_million")),
                providerCode, modelName, Timestamp.from(startedAt), Timestamp.from(startedAt));
        if (prices.size() != 1) {
            throw AiApiException.unavailable("AI_PRICING_UNAVAILABLE",
                    prices.isEmpty() ? "当前模型没有已生效价格版本" : "当前模型价格版本重叠");
        }
        Pricing price = prices.getFirst();
        if (price.inputRate().signum() < 0 || price.outputRate().signum() < 0
                || !price.currency().matches("[A-Z]{3}")) {
            throw AiApiException.unavailable("AI_PRICING_INVALID", "当前模型价格版本不合法");
        }
        return price;
    }

    private AttemptRow lockByIdentity(AttemptHandle handle) {
        List<AttemptRow> rows = jdbc.query(attemptSelect()
                        + " WHERE billing_subject_kind='RUN' AND billing_subject_public_id=? "
                        + "AND request_sequence_no=? AND attempt_no=? FOR UPDATE",
                this::mapAttempt, handle.trace().runPublicId(), handle.trace().requestSequenceNo(),
                handle.attemptNo());
        if (rows.size() != 1) throw new IllegalStateException("provider attempt STARTED 事实不存在");
        return rows.getFirst();
    }

    private AttemptRow lockByPublicId(String publicId) {
        List<AttemptRow> rows = jdbc.query(attemptSelect() + " WHERE public_id=? FOR UPDATE",
                this::mapAttempt, publicId);
        if (rows.size() != 1) throw new IllegalArgumentException("provider attempt 不存在");
        return rows.getFirst();
    }

    private String attemptSelect() {
        return "SELECT id,public_id,billing_subject_kind,billing_subject_public_id,request_sequence_no,"
                + "attempt_no,request_kind,actor_kind,actor_user_id,service_principal_code,"
                + "initiated_by_user_id,effective_subject_user_id,capability,provider_code,model_name,"
                + "pricing_version_id,currency,input_cost_per_million,output_cost_per_million,"
                + "estimation_policy_version,state,input_tokens,output_tokens,cost_amount,usage_source,"
                + "attempt_outcome,duration_ms,failure_code,started_at,version FROM ai_provider_attempt";
    }

    private AttemptRow mapAttempt(ResultSet rs, int rowNum) throws SQLException {
        ActorDescriptor actor = new ActorDescriptor(ActorKind.valueOf(rs.getString("actor_kind")),
                nullableLong(rs, "actor_user_id"), rs.getString("service_principal_code"),
                nullableLong(rs, "initiated_by_user_id"), nullableLong(rs, "effective_subject_user_id"));
        return new AttemptRow(rs.getLong("id"), rs.getString("public_id"),
                new BillingSubject(BillingSubject.Kind.valueOf(rs.getString("billing_subject_kind")),
                        rs.getString("billing_subject_public_id")),
                rs.getInt("request_sequence_no"), rs.getInt("attempt_no"), rs.getString("request_kind"),
                actor, AiCapability.valueOf(rs.getString("capability")), rs.getString("provider_code"),
                rs.getString("model_name"), rs.getLong("pricing_version_id"), rs.getString("currency"),
                rs.getBigDecimal("input_cost_per_million"), rs.getBigDecimal("output_cost_per_million"),
                rs.getString("estimation_policy_version"), rs.getString("state"),
                nullableLong(rs, "input_tokens"), nullableLong(rs, "output_tokens"),
                rs.getBigDecimal("cost_amount"), rs.getString("usage_source"),
                rs.getString("attempt_outcome"), nullableLong(rs, "duration_ms"),
                rs.getString("failure_code"), rs.getTimestamp("started_at").toInstant(), rs.getLong("version"));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static void requireSameIdentity(AttemptRow stored, AttemptHandle handle) {
        if (!stored.subject().equals(new BillingSubject(BillingSubject.Kind.RUN, handle.trace().runPublicId()))
                || stored.requestSequenceNo() != handle.trace().requestSequenceNo()
                || stored.attemptNo() != handle.attemptNo()
                || !stored.requestKind().equals(handle.requestKind())
                || !stored.actor().equals(handle.trace().actor())
                || stored.capability() != handle.capability()
                || !stored.providerCode().equals(handle.providerCode())
                || !stored.modelName().equals(handle.modelName())
                || stored.pricingVersionId() != handle.pricingVersionId()
                || !stored.currency().equals(handle.currency())
                || stored.inputRate().compareTo(handle.inputCostPerMillion()) != 0
                || stored.outputRate().compareTo(handle.outputCostPerMillion()) != 0
                || !sameTimestamp(stored.startedAt(), handle.startedAt())) {
            throw new IllegalStateException("provider attempt handle 与 STARTED 事实不一致");
        }
    }

    private static boolean sameTimestamp(Instant left, Instant right) {
        return left.truncatedTo(ChronoUnit.MICROS).equals(right.truncatedTo(ChronoUnit.MICROS));
    }

    private static AttemptReceipt receipt(
            AttemptHandle handle, ModelUsage usage, AttemptOutcome outcome, BigDecimal cost) {
        return new AttemptReceipt(handle.trace().runPublicId(), handle.trace().requestSequenceNo(),
                handle.attemptNo(), handle.pricingVersionId(), usage.inputTokens(), usage.outputTokens(), cost,
                handle.currency(), usage.source(), outcome);
    }

    private static BigDecimal cost(AttemptHandle handle, ModelUsage usage) {
        return handle.inputCostPerMillion().multiply(BigDecimal.valueOf(usage.inputTokens()))
                .add(handle.outputCostPerMillion().multiply(BigDecimal.valueOf(usage.outputTokens())))
                .divide(ONE_MILLION, 6, RoundingMode.HALF_UP);
    }

    private void recordMetrics(
            AttemptHandle handle,
            ModelUsage usage,
            AttemptOutcome outcome,
            Duration duration,
            BigDecimal cost) {
        try {
            metrics.recordProviderAttempt(new AiOperationalMetrics.ProviderAttemptSample(
                    handle.capability(), handle.providerCode(), handle.modelName(),
                    AiOperationalMetrics.RequestKind.valueOf(handle.requestKind()),
                    switch (outcome) {
                        case SUCCEEDED -> AiOperationalMetrics.AttemptOutcome.SUCCEEDED;
                        case FAILED_RETRYABLE, FAILED_FATAL, UNKNOWN -> AiOperationalMetrics.AttemptOutcome.FAILED;
                        case TIMED_OUT -> AiOperationalMetrics.AttemptOutcome.TIMED_OUT;
                        case CANCELLED -> AiOperationalMetrics.AttemptOutcome.CANCELLED;
                    },
                    usage.source() == ModelUsage.Source.PROVIDER
                            ? AiOperationalMetrics.UsageSource.PROVIDER
                            : AiOperationalMetrics.UsageSource.ESTIMATED,
                    duration, usage.inputTokens(), usage.outputTokens(), cost, handle.currency()));
        } catch (RuntimeException ignored) {
            // 指标写入不可反向改变已经持久化的 attempt/usage 事实。
        }
    }

    private static void validateIdentity(
            ModelRequest.BillingTrace trace,
            AiCapability capability,
            String providerCode,
            String modelName,
            String requestKind,
            int attemptNo,
            Instant startedAt) {
        if (trace == null || capability == null || providerCode == null
                || !providerCode.matches("[A-Za-z0-9][A-Za-z0-9._-]{1,63}")
                || modelName == null || !modelName.matches("[A-Za-z0-9][A-Za-z0-9._-]{1,127}")
                || !REQUEST_KINDS.contains(requestKind) || attemptNo < 1 || startedAt == null) {
            throw new IllegalArgumentException("物理 attempt 身份不合法");
        }
    }

    private static void validateTerminal(
            AttemptHandle handle,
            ModelUsage usage,
            AttemptOutcome outcome,
            Duration duration,
            String failureCode) {
        if (handle == null || usage == null || outcome == null || duration == null || duration.isNegative()
                || (failureCode != null && !failureCode.matches("[A-Z0-9_]{3,64}"))
                || (outcome == AttemptOutcome.UNKNOWN && usage.source() != ModelUsage.Source.ESTIMATED)) {
            throw new IllegalArgumentException("物理 attempt 终态不合法");
        }
    }

    private record Pricing(long id, String currency, BigDecimal inputRate, BigDecimal outputRate) { }

    private record FinishResult(AttemptReceipt receipt, boolean appended) { }

    private record AttemptRow(
            long id,
            String publicId,
            BillingSubject subject,
            int requestSequenceNo,
            int attemptNo,
            String requestKind,
            ActorDescriptor actor,
            AiCapability capability,
            String providerCode,
            String modelName,
            long pricingVersionId,
            String currency,
            BigDecimal inputRate,
            BigDecimal outputRate,
            String estimationPolicyVersion,
            String state,
            Long inputTokens,
            Long outputTokens,
            BigDecimal costAmount,
            String usageSource,
            String attemptOutcome,
            Long durationMs,
            String failureCode,
            Instant startedAt,
            long version) {

        private AttemptHandle toHandle() {
            return new AttemptHandle(new ModelRequest.BillingTrace(subject.publicId(), requestSequenceNo, actor),
                    capability, providerCode, modelName, requestKind, attemptNo, pricingVersionId, currency,
                    inputRate, outputRate, startedAt);
        }

        private boolean sameTerminal(
                ModelUsage usage,
                AttemptOutcome outcome,
                BigDecimal cost,
                Duration duration,
                String expectedFailureCode,
                String expectedEstimationPolicy) {
            return inputTokens != null && inputTokens == usage.inputTokens()
                    && outputTokens != null && outputTokens == usage.outputTokens()
                    && costAmount != null && costAmount.compareTo(cost) == 0
                    && Objects.equals(usageSource, usage.source().name())
                    && Objects.equals(attemptOutcome, outcome.name())
                    && Objects.equals(estimationPolicyVersion, expectedEstimationPolicy == null
                    ? ESTIMATION_POLICY_VERSION : expectedEstimationPolicy)
                    && durationMs != null && durationMs == duration.toMillis()
                    && Objects.equals(failureCode, expectedFailureCode);
        }
    }
}
