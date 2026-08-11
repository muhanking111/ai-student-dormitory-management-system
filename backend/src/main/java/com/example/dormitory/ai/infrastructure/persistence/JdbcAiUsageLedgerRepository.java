package com.example.dormitory.ai.infrastructure.persistence;

import com.example.dormitory.ai.application.control.BillingSubject;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.ActorKind;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;

@Repository
public class JdbcAiUsageLedgerRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcAiUsageLedgerRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void append(UsageAttempt attempt) {
        jdbcTemplate.update(
                "INSERT INTO ai_usage_ledger "
                        + "(billing_subject_kind, billing_subject_public_id, request_sequence_no, attempt_no, "
                        + "request_kind, actor_kind, actor_user_id, service_principal_code, initiated_by_user_id, "
                        + "effective_subject_user_id, "
                        + "capability, provider_code, model_name, provider_request_id_hash, pricing_version_id, "
                        + "input_tokens, output_tokens, cost_amount, currency, usage_source, attempt_outcome, "
                        + "estimation_policy_version, duration_ms, failure_code, occurred_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                attempt.subject().kind().name(),
                attempt.subject().publicId(),
                attempt.requestSequenceNo(),
                attempt.attemptNo(),
                attempt.requestKind(),
                attempt.actor().kind().name(),
                attempt.actor().actorUserId(),
                attempt.actor().servicePrincipalCode(),
                attempt.actor().initiatedByUserId(),
                attempt.actor().effectiveSubjectUserId(),
                attempt.capability(),
                attempt.providerCode(),
                attempt.modelName(),
                attempt.providerRequestIdHash(),
                attempt.pricingVersionId(),
                attempt.inputTokens(),
                attempt.outputTokens(),
                attempt.costAmount(),
                attempt.currency(),
                attempt.usageSource(),
                attempt.attemptOutcome(),
                attempt.estimationPolicyVersion(),
                attempt.durationMs(),
                attempt.failureCode(),
                Timestamp.from(attempt.occurredAt()));
    }

    public boolean appendIdempotent(UsageAttempt attempt) {
        try {
            append(attempt);
            return true;
        } catch (DuplicateKeyException duplicate) {
            StoredAttempt stored = find(attempt.subject(), attempt.requestSequenceNo(), attempt.attemptNo());
            if (!stored.sameFact(attempt)) {
                throw new IllegalStateException("同一物理 provider attempt 出现不一致终态", duplicate);
            }
            return false;
        }
    }

    public UsageTotals totals(BillingSubject subject) {
        java.util.List<String> currencies = jdbcTemplate.queryForList(
                "SELECT DISTINCT currency FROM ai_usage_ledger WHERE billing_subject_kind=? "
                        + "AND billing_subject_public_id=? ORDER BY currency",
                String.class, subject.kind().name(), subject.publicId());
        if (currencies.size() > 1) throw new IllegalStateException("同一计费主体包含多币种 attempt");
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) AS attempts,COALESCE(SUM(input_tokens),0) AS input_tokens,"
                        + "COALESCE(SUM(output_tokens),0) AS output_tokens,"
                        + "COALESCE(SUM(cost_amount),0) AS cost_amount,"
                        + "COALESCE(SUM(CASE WHEN usage_source='ESTIMATED' THEN 1 ELSE 0 END),0) AS estimated "
                        + "FROM ai_usage_ledger WHERE billing_subject_kind=? AND billing_subject_public_id=?",
                (rs, rowNum) -> new UsageTotals(rs.getInt("attempts"), rs.getLong("input_tokens"),
                        rs.getLong("output_tokens"), rs.getBigDecimal("cost_amount"),
                        currencies.isEmpty() ? "CNY" : currencies.getFirst(), rs.getInt("estimated")),
                subject.kind().name(), subject.publicId());
    }

    private StoredAttempt find(BillingSubject subject, int requestSequenceNo, int attemptNo) {
        return jdbcTemplate.query(
                "SELECT request_kind,actor_kind,actor_user_id,service_principal_code,initiated_by_user_id,"
                        + "effective_subject_user_id,"
                        + "capability,provider_code,model_name,provider_request_id_hash,pricing_version_id,"
                        + "input_tokens,output_tokens,cost_amount,currency,usage_source,attempt_outcome,"
                        + "estimation_policy_version,duration_ms,failure_code FROM ai_usage_ledger "
                        + "WHERE billing_subject_kind=? "
                        + "AND billing_subject_public_id=? AND request_sequence_no=? AND attempt_no=?",
                (rs, rowNum) -> new StoredAttempt(
                        rs.getString("request_kind"), rs.getString("actor_kind"), nullableLong(rs, "actor_user_id"),
                        rs.getString("service_principal_code"), nullableLong(rs, "initiated_by_user_id"),
                        nullableLong(rs, "effective_subject_user_id"),
                        rs.getString("capability"), rs.getString("provider_code"), rs.getString("model_name"),
                        rs.getString("provider_request_id_hash"), nullableLong(rs, "pricing_version_id"),
                        rs.getLong("input_tokens"), rs.getLong("output_tokens"), rs.getBigDecimal("cost_amount"),
                        rs.getString("currency"), rs.getString("usage_source"), rs.getString("attempt_outcome"),
                        rs.getString("estimation_policy_version"), rs.getLong("duration_ms"),
                        rs.getString("failure_code")),
                subject.kind().name(), subject.publicId(), requestSequenceNo, attemptNo).stream()
                .findFirst().orElseThrow(() -> new IllegalStateException("物理 attempt 唯一键冲突但事实不存在"));
    }

    private static Long nullableLong(java.sql.ResultSet resultSet, String column) throws java.sql.SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    public record UsageAttempt(
            BillingSubject subject,
            int requestSequenceNo,
            int attemptNo,
            String requestKind,
            ActorDescriptor actor,
            String capability,
            String providerCode,
            String modelName,
            String providerRequestIdHash,
            Long pricingVersionId,
            long inputTokens,
            long outputTokens,
            BigDecimal costAmount,
            String currency,
            String usageSource,
            String attemptOutcome,
            String estimationPolicyVersion,
            long durationMs,
            String failureCode,
            Instant occurredAt) {

        public UsageAttempt {
            if (subject == null || requestSequenceNo < 1 || attemptNo < 1
                    || requestKind == null || requestKind.isBlank()
                    || actor == null || capability == null || capability.isBlank()
                    || providerCode == null || providerCode.isBlank()
                    || modelName == null || modelName.isBlank()
                    || (providerRequestIdHash != null
                    && !providerRequestIdHash.matches("[0-9a-fA-F]{64}"))
                    || inputTokens < 0 || outputTokens < 0
                    || costAmount == null || costAmount.signum() < 0
                    || currency == null || !currency.matches("[A-Z]{3}")
                    || usageSource == null || !usageSource.matches("PROVIDER|ESTIMATED")
                    || attemptOutcome == null || !attemptOutcome.matches(
                    "SUCCEEDED|FAILED_RETRYABLE|FAILED_FATAL|TIMED_OUT|CANCELLED|UNKNOWN")
                    || (estimationPolicyVersion != null
                    && !estimationPolicyVersion.matches("[A-Za-z0-9][A-Za-z0-9._-]{1,63}"))
                    || durationMs < 0
                    || (failureCode != null && !failureCode.matches("[A-Z0-9_]{3,64}"))
                    || occurredAt == null) {
                throw new IllegalArgumentException("usage attempt 不合法");
            }
            if (("ESTIMATED".equals(usageSource)) != (estimationPolicyVersion != null)) {
                throw new IllegalArgumentException("估算 usage 必须绑定版本化估算策略");
            }
            if (actor.kind() == ActorKind.USER && actor.actorUserId() == null) {
                throw new IllegalArgumentException("USER usage 必须保留真实 actor");
            }
        }

        public UsageAttempt(
                BillingSubject subject,
                int requestSequenceNo,
                int attemptNo,
                String requestKind,
                ActorDescriptor actor,
                String capability,
                String providerCode,
                String modelName,
                String providerRequestIdHash,
                Long pricingVersionId,
                long inputTokens,
                long outputTokens,
                BigDecimal costAmount,
                String currency,
                String usageSource,
                Instant occurredAt) {
            this(subject, requestSequenceNo, attemptNo, requestKind, actor, capability, providerCode, modelName,
                    providerRequestIdHash, pricingVersionId, inputTokens, outputTokens, costAmount, currency,
                    usageSource, "SUCCEEDED", "ESTIMATED".equals(usageSource)
                            ? "provider-missing-usage-v1" : null, 0, null, occurredAt);
        }
    }

    public record UsageTotals(
            int attemptCount,
            long inputTokens,
            long outputTokens,
            BigDecimal costAmount,
            String currency,
            int estimatedAttemptCount) {
    }

    private record StoredAttempt(
            String requestKind,
            String actorKind,
            Long actorUserId,
            String servicePrincipalCode,
            Long initiatedByUserId,
            Long effectiveSubjectUserId,
            String capability,
            String providerCode,
            String modelName,
            String providerRequestIdHash,
            Long pricingVersionId,
            long inputTokens,
            long outputTokens,
            BigDecimal costAmount,
            String currency,
            String usageSource,
            String attemptOutcome,
            String estimationPolicyVersion,
            long durationMs,
            String failureCode) {
        private boolean sameFact(UsageAttempt attempt) {
            return requestKind.equals(attempt.requestKind())
                    && actorKind.equals(attempt.actor().kind().name())
                    && java.util.Objects.equals(actorUserId, attempt.actor().actorUserId())
                    && java.util.Objects.equals(servicePrincipalCode, attempt.actor().servicePrincipalCode())
                    && java.util.Objects.equals(initiatedByUserId, attempt.actor().initiatedByUserId())
                    && java.util.Objects.equals(effectiveSubjectUserId, attempt.actor().effectiveSubjectUserId())
                    && capability.equals(attempt.capability())
                    && providerCode.equals(attempt.providerCode())
                    && modelName.equals(attempt.modelName())
                    && java.util.Objects.equals(providerRequestIdHash, attempt.providerRequestIdHash())
                    && java.util.Objects.equals(pricingVersionId, attempt.pricingVersionId())
                    && inputTokens == attempt.inputTokens() && outputTokens == attempt.outputTokens()
                    && costAmount.compareTo(attempt.costAmount()) == 0
                    && currency.equals(attempt.currency()) && usageSource.equals(attempt.usageSource())
                    && attemptOutcome.equals(attempt.attemptOutcome())
                    && java.util.Objects.equals(estimationPolicyVersion, attempt.estimationPolicyVersion())
                    && durationMs == attempt.durationMs()
                    && java.util.Objects.equals(failureCode, attempt.failureCode());
        }
    }
}
