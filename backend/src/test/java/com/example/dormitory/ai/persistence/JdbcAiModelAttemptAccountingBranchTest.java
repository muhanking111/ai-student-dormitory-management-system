package com.example.dormitory.ai.persistence;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.application.control.BillingSubject;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.AiCapability;
import com.example.dormitory.ai.domain.model.ModelRequest;
import com.example.dormitory.ai.domain.model.ModelUsage;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiBudgetService;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiModelAttemptAccounting;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiUsageLedgerRepository;
import com.example.dormitory.ai.observability.AiOperationalMetrics;
import com.example.dormitory.ai.port.AiModelAttemptAccountingPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ActiveProfiles("test")
@SpringBootTest(properties =
        "dormitory.ai.audit.hmac-key=0123456789abcdef0123456789abcdef")
class JdbcAiModelAttemptAccountingBranchTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JdbcAiModelAttemptAccounting accounting;

    @Autowired
    private JdbcAiBudgetService budgets;

    @Autowired
    private JdbcAiUsageLedgerRepository ledger;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private AiOperationalMetrics metrics;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM ai_usage_ledger");
        jdbc.update("DELETE FROM ai_provider_attempt");
        jdbc.update("DELETE FROM ai_budget_reservation");
        jdbc.update("DELETE FROM ai_budget_bucket");
        jdbc.update("DELETE FROM ai_pricing_version");
    }

    @Test
    void constructorRejectsNullZeroAndNegativeAttemptLease() {
        for (Duration invalid : new Duration[]{null, Duration.ZERO, Duration.ofSeconds(-1)}) {
            assertThrows(IllegalArgumentException.class, () -> new JdbcAiModelAttemptAccounting(
                    jdbc, ledger, budgets, transactionManager, metrics, invalid));
        }
    }

    @Test
    void duplicateBeginAndDriftedHandleFailBeforeAnotherProviderFactIsRecorded() {
        Instant startedAt = Instant.now();
        insertPrice("spring-ai", "branch-model", "CNY", "1.000000", "2.000000",
                startedAt.minusSeconds(60), null);
        ModelRequest.BillingTrace trace = trace();
        reserveFor(trace, "spring-ai", startedAt.plusSeconds(600));
        AiModelAttemptAccountingPort.AttemptHandle handle = accounting.begin(
                trace, AiCapability.ASSISTANT, "spring-ai", "branch-model",
                "MODEL_STREAM", 1, startedAt);

        assertThrows(IllegalStateException.class, () -> accounting.begin(
                trace, AiCapability.ASSISTANT, "spring-ai", "branch-model",
                "MODEL_STREAM", 1, startedAt));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_provider_attempt", Integer.class));

        AiModelAttemptAccountingPort.AttemptHandle drifted = new AiModelAttemptAccountingPort.AttemptHandle(
                handle.trace(), handle.capability(), handle.providerCode(), "different-model",
                handle.requestKind(), handle.attemptNo(), handle.pricingVersionId(), handle.currency(),
                handle.inputCostPerMillion(), handle.outputCostPerMillion(), handle.startedAt());
        assertThrows(IllegalStateException.class, () -> accounting.finish(
                drifted, usage(), AiModelAttemptAccountingPort.AttemptOutcome.SUCCEEDED,
                Duration.ofMillis(10), null));
        assertEquals("STARTED", jdbc.queryForObject(
                "SELECT state FROM ai_provider_attempt", String.class));
    }

    @Test
    void reconcileUnknownRejectsStartedAndProviderFinishedAttempts() {
        Instant startedAt = Instant.now();
        insertPrice("spring-ai", "reconcile-model", "CNY", "1.000000", "2.000000",
                startedAt.minusSeconds(60), null);
        ModelRequest.BillingTrace trace = trace();
        reserveFor(trace, "spring-ai", startedAt.plusSeconds(600));
        AiModelAttemptAccountingPort.AttemptHandle handle = accounting.begin(
                trace, AiCapability.ASSISTANT, "spring-ai", "reconcile-model",
                "MODEL_COMPLETE", 1, startedAt);
        String attemptPublicId = jdbc.queryForObject(
                "SELECT public_id FROM ai_provider_attempt", String.class);

        assertThrows(IllegalStateException.class, () -> accounting.reconcileUnknown(
                attemptPublicId, estimatedUsage(), Duration.ofMillis(20), "OUTCOME_UNKNOWN"));

        accounting.finish(handle, usage(), AiModelAttemptAccountingPort.AttemptOutcome.SUCCEEDED,
                Duration.ofMillis(10), null);
        assertThrows(IllegalStateException.class, () -> accounting.reconcileUnknown(
                attemptPublicId, estimatedUsage(), Duration.ofMillis(20), "OUTCOME_UNKNOWN"));
    }

    @Test
    void duplicateFinishMustMatchEveryPersistedTerminalFact() {
        Instant startedAt = Instant.now();
        insertPrice("spring-ai", "terminal-model", "CNY", "1.000000", "2.000000",
                startedAt.minusSeconds(60), null);
        ModelRequest.BillingTrace trace = trace();
        reserveFor(trace, "spring-ai", startedAt.plusSeconds(600));
        AiModelAttemptAccountingPort.AttemptHandle handle = accounting.begin(
                trace, AiCapability.ASSISTANT, "spring-ai", "terminal-model",
                "TOOL_LOOP", 1, startedAt);
        accounting.finish(handle, usage(), AiModelAttemptAccountingPort.AttemptOutcome.SUCCEEDED,
                Duration.ofMillis(10), null);

        assertThrows(IllegalStateException.class, () -> accounting.finish(
                handle, usage(), AiModelAttemptAccountingPort.AttemptOutcome.SUCCEEDED,
                Duration.ofMillis(11), null));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_ledger", Integer.class));
    }

    @Test
    void publicRecoveryQueriesRejectInvalidWindowsSubjectsAndUnknownAttempts() {
        Instant now = Instant.now();
        assertThrows(IllegalArgumentException.class,
                () -> accounting.markStaleStartedForReconciliation(null, now));
        assertThrows(IllegalArgumentException.class,
                () -> accounting.markStaleStartedForReconciliation(now.plusSeconds(1), now));
        assertThrows(IllegalArgumentException.class,
                () -> accounting.markExpiredStartedForReconciliation(null));
        assertThrows(IllegalArgumentException.class,
                () -> accounting.renewOwnedStartedLeases(null));
        assertThrows(IllegalArgumentException.class,
                () -> accounting.unresolvedAttemptCount(null));
        assertThrows(IllegalArgumentException.class,
                () -> accounting.subjectForAttempt(UUID.randomUUID().toString()));
        assertThrows(IllegalArgumentException.class,
                () -> accounting.reconcileUnknown("bad-id", estimatedUsage(), Duration.ZERO, "UNKNOWN"));
        assertThrows(IllegalArgumentException.class,
                () -> accounting.reconcileUnknown(UUID.randomUUID().toString(), usage(),
                        Duration.ZERO, "UNKNOWN"));
    }

    @Test
    void overlappingAndMalformedEffectivePricesFailClosed() {
        Instant startedAt = Instant.now();
        insertPrice("overlap", "model", "CNY", "1.000000", "2.000000",
                startedAt.minusSeconds(120), null);
        insertPrice("overlap", "model", "CNY", "1.500000", "2.500000",
                startedAt.minusSeconds(60), null);
        ModelRequest.BillingTrace overlapTrace = trace();
        reserveFor(overlapTrace, "overlap", startedAt.plusSeconds(600));
        assertThrows(AiApiException.class, () -> accounting.begin(
                overlapTrace, AiCapability.ASSISTANT, "overlap", "model",
                "FALLBACK", 1, startedAt));

        insertPrice("invalid-price", "model", "cny", "1.000000", "2.000000",
                startedAt.minusSeconds(60), null);
        ModelRequest.BillingTrace invalidTrace = trace();
        reserveFor(invalidTrace, "invalid-price", startedAt.plusSeconds(600));
        assertThrows(AiApiException.class, () -> accounting.begin(
                invalidTrace, AiCapability.ASSISTANT, "invalid-price", "model",
                "EMBEDDING", 1, startedAt));
    }

    @Test
    void timedOutAttemptPersistsItsDistinctTerminalOutcome() {
        Instant startedAt = Instant.now();
        insertPrice("spring-ai", "timeout-model", "CNY", "1.000000", "2.000000",
                startedAt.minusSeconds(60), null);
        ModelRequest.BillingTrace trace = trace();
        reserveFor(trace, "spring-ai", startedAt.plusSeconds(600));
        AiModelAttemptAccountingPort.AttemptHandle handle = accounting.begin(
                trace, AiCapability.ASSISTANT, "spring-ai", "timeout-model",
                "MODEL_STREAM", 1, startedAt);

        accounting.finish(handle, estimatedUsage(), AiModelAttemptAccountingPort.AttemptOutcome.TIMED_OUT,
                Duration.ofSeconds(2), "PROVIDER_TIMEOUT");

        assertEquals("TIMED_OUT", jdbc.queryForObject(
                "SELECT attempt_outcome FROM ai_provider_attempt", String.class));
    }

    private ModelRequest.BillingTrace trace() {
        return new ModelRequest.BillingTrace(UUID.randomUUID().toString(), 1, ActorDescriptor.user(7L));
    }

    private ModelUsage usage() {
        return new ModelUsage(100, 20, ModelUsage.Source.PROVIDER);
    }

    private ModelUsage estimatedUsage() {
        return new ModelUsage(100, 20, ModelUsage.Source.ESTIMATED);
    }

    private void reserveFor(ModelRequest.BillingTrace trace, String provider, Instant expiresAt) {
        long bucketId = insertBucket(provider);
        budgets.reserve(new BillingSubject(BillingSubject.Kind.RUN, trace.runPublicId()),
                List.of(bucketId), 5_000, new BigDecimal("1.000000"), expiresAt);
    }

    private long insertPrice(
            String provider,
            String model,
            String currency,
            String input,
            String output,
            Instant from,
            Instant to) {
        jdbc.update("INSERT INTO ai_pricing_version "
                        + "(provider_code,model_name,currency,input_cost_per_million,output_cost_per_million,"
                        + "effective_from,effective_to,source_reference,created_at,updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                provider, model, currency, new BigDecimal(input), new BigDecimal(output),
                Timestamp.from(from), to == null ? null : Timestamp.from(to), "test://branch-price");
        return jdbc.queryForObject("SELECT MAX(id) FROM ai_pricing_version", Long.class);
    }

    private long insertBucket(String provider) {
        jdbc.update("INSERT INTO ai_budget_bucket "
                        + "(quota_policy_id,scope_type,scope_key,capability,provider_code,period_type,"
                        + "period_start,period_end,token_limit,cost_limit,reserved_tokens,committed_tokens,"
                        + "reserved_cost,committed_cost,currency,version,created_at,updated_at) "
                        + "VALUES (?, 'USER', ?, 'ASSISTANT', ?, 'DAILY', ?, ?, 10000, 10, 0, 0, 0, 0, "
                        + "'CNY', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                System.nanoTime(), UUID.randomUUID().toString(), provider,
                Timestamp.from(Instant.now().minusSeconds(60)),
                Timestamp.from(Instant.now().plusSeconds(3600)));
        return jdbc.queryForObject("SELECT MAX(id) FROM ai_budget_bucket", Long.class);
    }
}
