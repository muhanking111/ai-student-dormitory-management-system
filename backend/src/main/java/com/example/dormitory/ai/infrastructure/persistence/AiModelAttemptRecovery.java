package com.example.dormitory.ai.infrastructure.persistence;

import com.example.dormitory.ai.application.control.BillingSubject;
import com.example.dormitory.ai.application.run.AiConversationRunStore;
import com.example.dormitory.ai.config.AiAuditStartupGate;
import com.example.dormitory.ai.config.ProductionAiSecurityGate;
import com.example.dormitory.ai.domain.model.ModelUsage;
import com.example.dormitory.ai.port.AiModelAttemptAccountingPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 物理模型请求恢复器。多实例环境只认领 lease 已过期的 STARTED；健康实例持续续租的请求不可被
 * 新实例终结。恢复写入显式复验审计与生产启动门，预算在估算对账完成前始终保持 RESERVED。
 */
@Component
@Order(30)
public class AiModelAttemptRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AiModelAttemptRecovery.class);
    private static final Set<String> TERMINAL_RUN_STATES = Set.of(
            "SUCCEEDED", "DEGRADED", "FAILED", "TIMED_OUT", "CANCELLED");

    private final JdbcAiModelAttemptAccounting accounting;
    private final JdbcAiBudgetService budgets;
    private final JdbcTemplate jdbc;
    private final AiConversationRunStore runStore;
    private final AiAuditStartupGate auditStartupGate;
    private final ProductionAiSecurityGate productionSecurityGate;
    private final TransactionTemplate transactions;
    private final Duration staleAfter;
    private final Clock clock;

    @Autowired
    public AiModelAttemptRecovery(
            JdbcAiModelAttemptAccounting accounting,
            JdbcAiBudgetService budgets,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            AiConversationRunStore runStore,
            AiAuditStartupGate auditStartupGate,
            ProductionAiSecurityGate productionSecurityGate,
            @Value("${dormitory.ai.budget.attempt-recovery-stale-after:PT5M}") Duration staleAfter) {
        this(accounting, budgets, jdbc, transactionManager, runStore, auditStartupGate,
                productionSecurityGate, staleAfter, Clock.systemUTC());
    }

    AiModelAttemptRecovery(
            JdbcAiModelAttemptAccounting accounting,
            JdbcAiBudgetService budgets,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            AiConversationRunStore runStore,
            AiAuditStartupGate auditStartupGate,
            ProductionAiSecurityGate productionSecurityGate,
            Duration staleAfter,
            Clock clock) {
        this.accounting = Objects.requireNonNull(accounting);
        this.budgets = Objects.requireNonNull(budgets);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.runStore = Objects.requireNonNull(runStore);
        this.auditStartupGate = Objects.requireNonNull(auditStartupGate);
        this.productionSecurityGate = Objects.requireNonNull(productionSecurityGate);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        if (staleAfter == null || staleAfter.isZero() || staleAfter.isNegative()) {
            throw new IllegalArgumentException("provider attempt 恢复阈值必须为正数");
        }
        this.staleAfter = staleAfter;
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public void run(ApplicationArguments args) {
        verifySafetyGates();
        recoverExpiredAttemptsAndRuns(clock.instant());
    }

    @Scheduled(
            initialDelayString = "${dormitory.ai.budget.attempt-recovery-initial-delay:PT1M}",
            fixedDelayString = "${dormitory.ai.budget.attempt-recovery-fixed-delay:PT1M}")
    public void recoverStaleAttempts() {
        verifySafetyGates();
        Instant now = clock.instant();
        recoverExpiredAttemptsAndRuns(now);
        int settled = settleKnownTerminalRuns();
        if (settled > 0) {
            log.info("Settled {} terminal AI runs after their provider attempts reached known final usage", settled);
        }
        int expired = budgets.expireDueReservations(now, 100);
        if (expired > 0) {
            log.info("Expired {} unused AI budget reservations and returned their bucket balances", expired);
        }
    }

    private void verifySafetyGates() {
        auditStartupGate.verify();
        productionSecurityGate.verify();
    }

    private void recoverExpiredAttemptsAndRuns(Instant now) {
        accounting.renewOwnedStartedLeases(now);
        int recovered = accounting.markExpiredStartedForReconciliation(now);
        if (recovered > 0) {
            log.warn("Marked {} expired provider attempt leases NEEDS_RECONCILIATION; budget remains reserved",
                    recovered);
        }
        int failedRuns = runStore.failRunsWithReconciliationAttempts();
        if (failedRuns > 0) {
            log.warn("Failed {} interrupted AI runs after their provider attempt leases expired", failedRuns);
        }
    }

    /**
     * 处理取消/失败与 provider 回调竞态：run 可能先以 NEEDS_RECONCILIATION 终结，而 provider
     * 随后仍可靠地写回终态 usage。只有全部 attempt 都已终结且 ledger 已存在时才自动对账；仍有
     * UNKNOWN 的请求继续保留预算，不能用空 usage 猜测后释放。
     */
    public int settleKnownTerminalRuns() {
        List<String> candidates = jdbc.queryForList(
                "SELECT r.public_id FROM ai_run r WHERE r.cost_status='NEEDS_RECONCILIATION' "
                        + "AND r.state IN ('SUCCEEDED','DEGRADED','FAILED','TIMED_OUT','CANCELLED') "
                        + "AND EXISTS (SELECT 1 FROM ai_usage_ledger l "
                        + "WHERE l.billing_subject_kind='RUN' AND l.billing_subject_public_id=r.public_id) "
                        + "AND NOT EXISTS (SELECT 1 FROM ai_provider_attempt a "
                        + "WHERE a.billing_subject_kind='RUN' AND a.billing_subject_public_id=r.public_id "
                        + "AND a.state IN ('STARTED','NEEDS_RECONCILIATION')) "
                        + "ORDER BY r.finished_at,r.id LIMIT 100",
                String.class);
        int settled = 0;
        for (String runId : candidates) {
            try {
                if (Boolean.TRUE.equals(transactions.execute(status -> settleKnownTerminalRun(runId)))) {
                    settled++;
                }
            } catch (JdbcAiBudgetService.AttemptReconciliationRequiredException race) {
                // 新 attempt 在候选扫描后先获得 reservation 行锁；下个周期重新核对。
            } catch (RuntimeException failure) {
                log.warn("Unable to settle terminal AI run {} after known provider usage: {}",
                        runId, failure.getClass().getSimpleName());
            }
        }
        return settled;
    }

    private boolean settleKnownTerminalRun(String runId) {
        List<RunCostState> runs = jdbc.query(
                "SELECT state,cost_status FROM ai_run WHERE public_id=? FOR UPDATE",
                (rs, row) -> new RunCostState(rs.getString("state"), rs.getString("cost_status")), runId);
        if (runs.size() != 1 || !"NEEDS_RECONCILIATION".equals(runs.getFirst().costStatus())
                || !TERMINAL_RUN_STATES.contains(runs.getFirst().state())) {
            return false;
        }
        BillingSubject subject = new BillingSubject(BillingSubject.Kind.RUN, runId);
        if (accounting.unresolvedAttemptCount(subject) > 0) return false;
        AiModelAttemptAccountingPort.UsageTotals totals = accounting.totals(runId);
        if (!totals.hasAttempts()
                || !budgets.commitIfPresent(subject, totals.totalTokens(), totals.costAmount())) {
            return false;
        }
        int updated = jdbc.update("UPDATE ai_run SET input_tokens=?,output_tokens=?,estimated_cost=?,"
                        + "cost_status='FINAL',version=version+1,updated_at=CURRENT_TIMESTAMP "
                        + "WHERE public_id=? AND cost_status='NEEDS_RECONCILIATION'",
                totals.inputTokens(), totals.outputTokens(), totals.costAmount(), runId);
        if (updated != 1) throw new IllegalStateException("terminal run 成本对账 CAS 失败");
        return true;
    }

    /**
     * 由受控运维流程提供保守估算后完成 UNKNOWN 对账。attempt、ledger、预算与 run 成本状态共享事务；
     * 相同事实重放幂等，不同估算会被 ledger 唯一事实校验拒绝。
     */
    public AiModelAttemptAccountingPort.AttemptReceipt reconcileUnknown(
            String attemptPublicId,
            ModelUsage estimatedUsage,
            Duration duration,
            String failureCode) {
        return Objects.requireNonNull(transactions.execute(status -> {
            BillingSubject subject = accounting.subjectForAttempt(attemptPublicId);
            AiModelAttemptAccountingPort.AttemptReceipt receipt = accounting.reconcileUnknown(
                    attemptPublicId, estimatedUsage, duration, failureCode);
            if (accounting.unresolvedAttemptCount(subject) > 0) return receipt;

            AiModelAttemptAccountingPort.UsageTotals totals = accounting.totals(subject.publicId());
            if (!budgets.commitIfPresent(subject, totals.totalTokens(), totals.costAmount())) {
                throw new IllegalStateException("UNKNOWN attempt 对账缺少预算 reservation 事实");
            }
            if (subject.kind() == BillingSubject.Kind.RUN) {
                jdbc.update("UPDATE ai_run SET input_tokens=?,output_tokens=?,estimated_cost=?,"
                                + "cost_status='FINAL',updated_at=CURRENT_TIMESTAMP "
                                + "WHERE public_id=? AND cost_status IN ('NEEDS_RECONCILIATION','UNKNOWN') "
                                + "AND state IN ('SUCCEEDED','DEGRADED','FAILED','TIMED_OUT','CANCELLED')",
                        totals.inputTokens(), totals.outputTokens(), totals.costAmount(), subject.publicId());
            }
            return receipt;
        }));
    }

    private record RunCostState(String state, String costStatus) { }
}
