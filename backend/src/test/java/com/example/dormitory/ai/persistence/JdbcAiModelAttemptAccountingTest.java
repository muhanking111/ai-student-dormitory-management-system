package com.example.dormitory.ai.persistence;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.AiCapability;
import com.example.dormitory.ai.domain.model.ModelRequest;
import com.example.dormitory.ai.domain.model.ModelUsage;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiModelAttemptAccounting;
import com.example.dormitory.ai.infrastructure.persistence.AiModelAttemptRecovery;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiBudgetService;
import com.example.dormitory.ai.application.control.BillingSubject;
import com.example.dormitory.ai.application.run.AiConversationRunStore;
import com.example.dormitory.ai.port.AiModelAttemptAccountingPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@ActiveProfiles("test")
@SpringBootTest(properties =
        "dormitory.ai.audit.hmac-key=0123456789abcdef0123456789abcdef")
class JdbcAiModelAttemptAccountingTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JdbcAiModelAttemptAccounting accounting;

    @Autowired
    private JdbcAiBudgetService budgets;

    @Autowired
    private AiModelAttemptRecovery recovery;

    @Autowired
    private AiConversationRunStore runStore;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM ai_run_event");
        jdbc.update("DELETE FROM ai_usage_ledger");
        jdbc.update("DELETE FROM ai_provider_attempt");
        jdbc.update("DELETE FROM ai_budget_reservation");
        jdbc.update("DELETE FROM ai_budget_bucket");
        jdbc.update("DELETE FROM ai_run");
        jdbc.update("DELETE FROM ai_message");
        jdbc.update("DELETE FROM ai_conversation");
        jdbc.update("DELETE FROM ai_audit_event");
        jdbc.update("DELETE FROM ai_audit_chain_head");
        jdbc.update("DELETE FROM ai_pricing_version");
    }

    @Test
    void locksEffectivePricingAndAppendsOneCostedRowPerPhysicalAttempt() {
        Instant effective = Instant.parse("2026-07-12T00:00:00Z");
        long pricingId = insertPrice("spring-ai", "approved-chat-v1", "CNY",
                "10.000000", "20.000000", effective.minusSeconds(60), null);
        ModelRequest.BillingTrace trace = trace();
        reserveFor(trace, "spring-ai", effective.plusSeconds(600));

        AiModelAttemptAccountingPort.AttemptHandle handle = accounting.begin(
                trace, AiCapability.ASSISTANT, "spring-ai", "approved-chat-v1",
                "MODEL_STREAM", 1, effective);

        assertEquals("STARTED", jdbc.queryForObject(
                "SELECT state FROM ai_provider_attempt", String.class));
        assertEquals("spring-ai", jdbc.queryForObject(
                "SELECT provider_code FROM ai_provider_attempt", String.class));
        assertEquals("approved-chat-v1", jdbc.queryForObject(
                "SELECT model_name FROM ai_provider_attempt", String.class));
        assertEquals(pricingId, jdbc.queryForObject(
                "SELECT pricing_version_id FROM ai_provider_attempt", Long.class));
        assertEquals(new BigDecimal("10.000000"), jdbc.queryForObject(
                "SELECT input_cost_per_million FROM ai_provider_attempt", BigDecimal.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_ledger", Integer.class));

        AiModelAttemptAccountingPort.AttemptReceipt receipt = accounting.finish(
                handle, new ModelUsage(1_000, 2_000, ModelUsage.Source.PROVIDER),
                AiModelAttemptAccountingPort.AttemptOutcome.SUCCEEDED,
                Duration.ofMillis(80), null);

        assertEquals(pricingId, receipt.pricingVersionId());
        assertEquals(new BigDecimal("0.050000"), receipt.costAmount());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_ledger", Integer.class));
        assertEquals(pricingId, jdbc.queryForObject(
                "SELECT pricing_version_id FROM ai_usage_ledger", Long.class));
        assertEquals("FINISHED", jdbc.queryForObject(
                "SELECT state FROM ai_provider_attempt", String.class));
        assertEquals("SUCCEEDED", jdbc.queryForObject(
                "SELECT attempt_outcome FROM ai_provider_attempt", String.class));
    }

    @Test
    void retryFailureAndSuccessRemainSeparateAndDuplicateEndCallbackIsIdempotent() {
        Instant now = Instant.parse("2026-07-12T01:00:00Z");
        insertPrice("fake", "deterministic-fake-v1", "CNY", "0", "0",
                Instant.EPOCH, null);
        ModelRequest.BillingTrace trace = trace();
        reserveFor(trace, "fake", now.plusSeconds(600));
        var failed = accounting.begin(trace, AiCapability.ASSISTANT, "fake", "deterministic-fake-v1",
                "MODEL_STREAM", 1, now);
        var succeeded = accounting.begin(trace, AiCapability.ASSISTANT, "fake", "deterministic-fake-v1",
                "MODEL_STREAM", 2, now.plusMillis(10));

        var failedReceipt = accounting.finish(failed,
                new ModelUsage(0, 0, ModelUsage.Source.ESTIMATED),
                AiModelAttemptAccountingPort.AttemptOutcome.FAILED_RETRYABLE,
                Duration.ofMillis(10), "AI_PROVIDER_429");
        accounting.finish(succeeded, new ModelUsage(2, 1, ModelUsage.Source.ESTIMATED),
                AiModelAttemptAccountingPort.AttemptOutcome.SUCCEEDED,
                Duration.ofMillis(20), null);
        var replay = accounting.finish(failed,
                new ModelUsage(0, 0, ModelUsage.Source.ESTIMATED),
                AiModelAttemptAccountingPort.AttemptOutcome.FAILED_RETRYABLE,
                Duration.ofMillis(10), "AI_PROVIDER_429");

        assertEquals(failedReceipt, replay);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_ledger", Integer.class));
        assertEquals(2, accounting.totals(trace.runPublicId()).attemptCount());
        assertEquals(3, accounting.totals(trace.runPublicId()).totalTokens());
    }

    @Test
    void staleStartedAttemptNeedsReconciliationAndCannotReleaseReservedBudget() {
        Instant startedAt = Instant.parse("2026-07-12T02:00:00Z");
        Instant recoveredAt = startedAt.plusSeconds(600);
        insertPrice("spring-ai", "approved-chat-v1", "CNY", "10.000000", "20.000000",
                startedAt.minusSeconds(60), null);
        ModelRequest.BillingTrace trace = trace();
        BillingSubject subject = new BillingSubject(BillingSubject.Kind.RUN, trace.runPublicId());
        long bucketId = insertBucket("spring-ai", 10_000, "10.000000");
        budgets.reserve(subject, java.util.List.of(bucketId), 5_000, new BigDecimal("1.000000"),
                recoveredAt.plusSeconds(600));

        accounting.begin(trace, AiCapability.ASSISTANT, "spring-ai", "approved-chat-v1",
                "MODEL_STREAM", 1, startedAt);
        assertEquals(1, accounting.markStaleStartedForReconciliation(
                recoveredAt.minusSeconds(60), recoveredAt));

        assertEquals("NEEDS_RECONCILIATION", jdbc.queryForObject(
                "SELECT state FROM ai_provider_attempt", String.class));
        assertFalse(budgets.releaseIfPresent(subject));
        assertEquals("RESERVED", jdbc.queryForObject(
                "SELECT state FROM ai_budget_reservation", String.class));
        assertEquals(5_000L, jdbc.queryForObject(
                "SELECT reserved_tokens FROM ai_budget_bucket WHERE id=?", Long.class, bucketId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_ledger", Integer.class));
    }

    @Test
    void rollingStartupDoesNotRecoverOrFailAFreshAttemptOwnedByAHealthyInstance() {
        Instant startedAt = Instant.now();
        insertPrice("spring-ai", "approved-chat-v1", "CNY", "10.000000", "20.000000",
                startedAt.minusSeconds(60), null);
        ModelRequest.BillingTrace trace = trace();
        reserveFor(trace, "spring-ai", startedAt.plusSeconds(600));
        insertStreamingRun(trace.runPublicId(), trace.actor().actorUserId());
        accounting.begin(trace, AiCapability.ASSISTANT, "spring-ai", "approved-chat-v1",
                "MODEL_STREAM", 1, startedAt);
        jdbc.update("UPDATE ai_provider_attempt SET owner_instance_id='other-live-instance',"
                        + "lease_expires_at=?",
                Timestamp.from(startedAt.plusSeconds(600)));

        recovery.run(mock(ApplicationArguments.class));
        runStore.failRunsWithReconciliationAttempts();

        assertEquals("STARTED", jdbc.queryForObject(
                "SELECT state FROM ai_provider_attempt", String.class));
        assertEquals("STREAMING", jdbc.queryForObject(
                "SELECT state FROM ai_run WHERE public_id=?", String.class, trace.runPublicId()));
    }

    @Test
    void unknownCrashReconciliationAppendsOneEstimatedLedgerAndCommitsBudgetIdempotently() {
        Instant startedAt = Instant.parse("2026-07-12T03:00:00Z");
        Instant recoveredAt = startedAt.plusSeconds(600);
        insertPrice("spring-ai", "approved-chat-v1", "CNY", "10.000000", "20.000000",
                startedAt.minusSeconds(60), null);
        ModelRequest.BillingTrace trace = trace();
        BillingSubject subject = new BillingSubject(BillingSubject.Kind.RUN, trace.runPublicId());
        long bucketId = insertBucket("spring-ai", 10_000, "10.000000");
        budgets.reserve(subject, java.util.List.of(bucketId), 5_000, new BigDecimal("1.000000"),
                recoveredAt.plusSeconds(600));
        accounting.begin(trace, AiCapability.ASSISTANT, "spring-ai", "approved-chat-v1",
                "MODEL_STREAM", 1, startedAt);
        accounting.markExpiredStartedForReconciliation(recoveredAt);
        String attemptId = jdbc.queryForObject(
                "SELECT public_id FROM ai_provider_attempt", String.class);

        AiModelAttemptAccountingPort.AttemptReceipt first = recovery.reconcileUnknown(
                attemptId, new ModelUsage(600, 400, ModelUsage.Source.ESTIMATED),
                Duration.ofSeconds(30), "AI_PROCESS_RESTARTED");
        AiModelAttemptAccountingPort.AttemptReceipt replay = recovery.reconcileUnknown(
                attemptId, new ModelUsage(600, 400, ModelUsage.Source.ESTIMATED),
                Duration.ofSeconds(30), "AI_PROCESS_RESTARTED");

        assertEquals(first, replay);
        assertEquals(AiModelAttemptAccountingPort.AttemptOutcome.UNKNOWN, first.outcome());
        assertEquals(new BigDecimal("0.014000"), first.costAmount());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_ledger", Integer.class));
        assertEquals("UNKNOWN", jdbc.queryForObject(
                "SELECT attempt_outcome FROM ai_usage_ledger", String.class));
        assertEquals("provider-missing-usage-v1", jdbc.queryForObject(
                "SELECT estimation_policy_version FROM ai_usage_ledger", String.class));
        assertEquals("FINISHED", jdbc.queryForObject(
                "SELECT state FROM ai_provider_attempt", String.class));
        assertEquals("COMMITTED", jdbc.queryForObject(
                "SELECT state FROM ai_budget_reservation", String.class));
        assertEquals(1_000L, jdbc.queryForObject(
                "SELECT committed_tokens FROM ai_budget_bucket WHERE id=?", Long.class, bucketId));
        assertEquals(new BigDecimal("0.014000"), jdbc.queryForObject(
                "SELECT committed_cost FROM ai_budget_bucket WHERE id=?", BigDecimal.class, bucketId));
    }

    @Test
    void interruptedRunIsFailedWithPendingCostWhileUnknownAttemptKeepsBudgetReserved() {
        Instant startedAt = Instant.parse("2026-07-12T04:00:00Z");
        insertPrice("spring-ai", "approved-chat-v1", "CNY", "10.000000", "20.000000",
                startedAt.minusSeconds(60), null);
        ModelRequest.BillingTrace trace = trace();
        BillingSubject subject = new BillingSubject(BillingSubject.Kind.RUN, trace.runPublicId());
        long bucketId = insertBucket("spring-ai", 10_000, "10.000000");
        budgets.reserve(subject, java.util.List.of(bucketId), 5_000, new BigDecimal("1.000000"),
                startedAt.plusSeconds(3600));
        insertStreamingRun(trace.runPublicId(), trace.actor().actorUserId());
        accounting.begin(trace, AiCapability.ASSISTANT, "spring-ai", "approved-chat-v1",
                "MODEL_STREAM", 1, startedAt);
        accounting.markExpiredStartedForReconciliation(startedAt.plusSeconds(600));

        runStore.failRunsWithReconciliationAttempts();

        assertEquals("FAILED", jdbc.queryForObject(
                "SELECT state FROM ai_run WHERE public_id=?", String.class, trace.runPublicId()));
        assertEquals("NEEDS_RECONCILIATION", jdbc.queryForObject(
                "SELECT cost_status FROM ai_run WHERE public_id=?", String.class, trace.runPublicId()));
        assertEquals("RESERVED", jdbc.queryForObject(
                "SELECT state FROM ai_budget_reservation WHERE billing_subject_public_id=?",
                String.class, trace.runPublicId()));
        assertEquals(5_000L, jdbc.queryForObject(
                "SELECT reserved_tokens FROM ai_budget_bucket WHERE id=?", Long.class, bucketId));
    }

    @Test
    void providerAttemptRequiresLiveReservationAndCannotStartAfterBudgetSettlement() {
        Instant now = Instant.parse("2026-07-12T05:00:00Z");
        insertPrice("spring-ai", "approved-chat-v1", "CNY", "10.000000", "20.000000",
                now.minusSeconds(60), null);
        ModelRequest.BillingTrace unreserved = trace();

        assertThrows(IllegalStateException.class, () -> accounting.begin(
                unreserved, AiCapability.ASSISTANT, "spring-ai", "approved-chat-v1",
                "MODEL_STREAM", 1, now));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_provider_attempt", Integer.class));

        ModelRequest.BillingTrace reserved = trace();
        reserveFor(reserved, "spring-ai", now.plusSeconds(600));
        BillingSubject subject = new BillingSubject(BillingSubject.Kind.RUN, reserved.runPublicId());
        var first = accounting.begin(reserved, AiCapability.ASSISTANT, "spring-ai", "approved-chat-v1",
                "MODEL_STREAM", 1, now);
        accounting.finish(first, new ModelUsage(100, 50, ModelUsage.Source.PROVIDER),
                AiModelAttemptAccountingPort.AttemptOutcome.SUCCEEDED, Duration.ofMillis(20), null);
        budgets.commit(subject, 150, new BigDecimal("0.002000"));

        assertThrows(IllegalStateException.class, () -> accounting.begin(
                reserved, AiCapability.ASSISTANT, "spring-ai", "approved-chat-v1",
                "MODEL_STREAM", 2, now.plusSeconds(1)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_provider_attempt", Integer.class));
    }

    @Test
    void knownUsageFinishingAfterRunTerminalStateIsRecoveredWithoutLeakingReservation() {
        Instant startedAt = Instant.parse("2026-07-12T06:00:00Z");
        insertPrice("spring-ai", "approved-chat-v1", "CNY", "10.000000", "20.000000",
                startedAt.minusSeconds(60), null);
        ModelRequest.BillingTrace trace = trace();
        BillingSubject subject = new BillingSubject(BillingSubject.Kind.RUN, trace.runPublicId());
        long bucketId = insertBucket("spring-ai", 10_000, "10.000000");
        budgets.reserve(subject, java.util.List.of(bucketId), 5_000, new BigDecimal("1.000000"),
                startedAt.plusSeconds(3600));
        insertStreamingRun(trace.runPublicId(), trace.actor().actorUserId());
        var handle = accounting.begin(trace, AiCapability.ASSISTANT, "spring-ai", "approved-chat-v1",
                "MODEL_STREAM", 1, startedAt);

        accounting.markExpiredStartedForReconciliation(startedAt.plusSeconds(600));
        runStore.failRunsWithReconciliationAttempts();
        accounting.finish(handle, new ModelUsage(600, 400, ModelUsage.Source.PROVIDER),
                AiModelAttemptAccountingPort.AttemptOutcome.CANCELLED,
                Duration.ofSeconds(15), "UPSTREAM_CANCELLED");

        assertEquals("NEEDS_RECONCILIATION", jdbc.queryForObject(
                "SELECT cost_status FROM ai_run WHERE public_id=?", String.class, trace.runPublicId()));
        assertEquals("RESERVED", jdbc.queryForObject(
                "SELECT state FROM ai_budget_reservation WHERE billing_subject_public_id=?",
                String.class, trace.runPublicId()));

        assertEquals(1, recovery.settleKnownTerminalRuns());
        assertEquals("FINAL", jdbc.queryForObject(
                "SELECT cost_status FROM ai_run WHERE public_id=?", String.class, trace.runPublicId()));
        assertEquals("COMMITTED", jdbc.queryForObject(
                "SELECT state FROM ai_budget_reservation WHERE billing_subject_public_id=?",
                String.class, trace.runPublicId()));
        assertEquals(1_000L, jdbc.queryForObject(
                "SELECT committed_tokens FROM ai_budget_bucket WHERE id=?", Long.class, bucketId));
        assertEquals(new BigDecimal("0.014000"), jdbc.queryForObject(
                "SELECT committed_cost FROM ai_budget_bucket WHERE id=?", BigDecimal.class, bucketId));
    }

    @Test
    void concurrentExpiryReturnsEveryUnusedBucketOnceAndIsIdempotent() throws Exception {
        Instant expiresAt = Instant.parse("2026-07-12T07:00:00Z");
        BillingSubject subject = new BillingSubject(BillingSubject.Kind.EVAL, UUID.randomUUID().toString());
        long firstBucket = insertBucket("offline", 10_000, "10.000000");
        long secondBucket = insertBucket("offline", 10_000, "10.000000");
        budgets.reserve(subject, java.util.List.of(firstBucket, secondBucket), 5_000,
                new BigDecimal("1.000000"), expiresAt);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return budgets.expireDueReservations(expiresAt.plusSeconds(1), 100);
            });
            var second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return budgets.expireDueReservations(expiresAt.plusSeconds(1), 100);
            });
            ready.await();
            start.countDown();

            assertEquals(2, first.get() + second.get());
            assertEquals(0, budgets.expireDueReservations(expiresAt.plusSeconds(2), 100));
        } finally {
            executor.shutdownNow();
        }

        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_budget_reservation WHERE state='EXPIRED'", Integer.class));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT reserved_tokens FROM ai_budget_bucket WHERE id=?", Long.class, firstBucket));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT reserved_tokens FROM ai_budget_bucket WHERE id=?", Long.class, secondBucket));
    }

    @Test
    void expiredReservationWithStartedOrReconciliationAttemptRemainsReserved() {
        Instant startedAt = Instant.parse("2026-07-12T08:00:00Z");
        insertPrice("spring-ai", "approved-chat-v1", "CNY", "10.000000", "20.000000",
                startedAt.minusSeconds(60), null);
        ModelRequest.BillingTrace trace = trace();
        BillingSubject subject = new BillingSubject(BillingSubject.Kind.RUN, trace.runPublicId());
        long bucketId = insertBucket("spring-ai", 10_000, "10.000000");
        budgets.reserve(subject, java.util.List.of(bucketId), 5_000, new BigDecimal("1.000000"),
                startedAt.plusSeconds(60));
        accounting.begin(trace, AiCapability.ASSISTANT, "spring-ai", "approved-chat-v1",
                "MODEL_STREAM", 1, startedAt);

        assertEquals(0, budgets.expireDueReservations(startedAt.plusSeconds(120), 100));
        assertEquals("RESERVED", jdbc.queryForObject(
                "SELECT state FROM ai_budget_reservation", String.class));
        assertEquals(1, accounting.markExpiredStartedForReconciliation(startedAt.plusSeconds(301)));
        assertEquals(0, budgets.expireDueReservations(startedAt.plusSeconds(302), 100));
        assertEquals("RESERVED", jdbc.queryForObject(
                "SELECT state FROM ai_budget_reservation", String.class));
        assertEquals(5_000L, jdbc.queryForObject(
                "SELECT reserved_tokens FROM ai_budget_bucket WHERE id=?", Long.class, bucketId));
    }

    @Test
    void expiredReservationWithFinishedUnsettledUsageFailsClosed() {
        Instant startedAt = Instant.parse("2026-07-12T09:00:00Z");
        insertPrice("spring-ai", "approved-chat-v1", "CNY", "10.000000", "20.000000",
                startedAt.minusSeconds(60), null);
        ModelRequest.BillingTrace trace = trace();
        BillingSubject subject = new BillingSubject(BillingSubject.Kind.RUN, trace.runPublicId());
        long bucketId = insertBucket("spring-ai", 10_000, "10.000000");
        budgets.reserve(subject, java.util.List.of(bucketId), 5_000, new BigDecimal("1.000000"),
                startedAt.plusSeconds(60));
        var handle = accounting.begin(trace, AiCapability.ASSISTANT, "spring-ai", "approved-chat-v1",
                "MODEL_STREAM", 1, startedAt);
        accounting.finish(handle, new ModelUsage(100, 50, ModelUsage.Source.PROVIDER),
                AiModelAttemptAccountingPort.AttemptOutcome.SUCCEEDED, Duration.ofMillis(20), null);

        assertEquals(0, budgets.expireDueReservations(startedAt.plusSeconds(120), 100));
        assertEquals("RESERVED", jdbc.queryForObject(
                "SELECT state FROM ai_budget_reservation", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_ledger", Integer.class));
        assertTrue(budgets.commitIfPresent(subject, 150, new BigDecimal("0.002000")));
        assertEquals("COMMITTED", jdbc.queryForObject(
                "SELECT state FROM ai_budget_reservation", String.class));
    }

    @Test
    void realProviderWithoutAnEffectivePriceFailsBeforeTheProviderCall() {
        ModelRequest.BillingTrace trace = trace();
        reserveFor(trace, "spring-ai", Instant.now().plusSeconds(600));
        AiApiException failure = assertThrows(AiApiException.class, () -> accounting.begin(
                trace, AiCapability.ASSISTANT, "spring-ai", "missing-price-model",
                "MODEL_STREAM", 1, Instant.now()));

        assertEquals("AI_PRICING_UNAVAILABLE", failure.errorCode());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_ledger", Integer.class));
    }

    @Test
    void budgetReservationAndSettlementRejectEveryMalformedPublicArgument() {
        BillingSubject subject = new BillingSubject(BillingSubject.Kind.RUN, UUID.randomUUID().toString());
        long bucketId = insertBucket("fake", 1_000, "1.000000");
        // TIMESTAMP(6) 会把这个纳秒尾数向上舍入；预算边界必须按调用方声明的时刻 fail-closed。
        Instant expiresAt = Instant.parse("2099-07-13T00:00:00.123456789Z");

        assertThrows(IllegalArgumentException.class,
                () -> budgets.reserve(null, java.util.List.of(bucketId), 1, BigDecimal.ZERO, expiresAt));
        assertThrows(IllegalArgumentException.class,
                () -> budgets.reserve(subject, null, 1, BigDecimal.ZERO, expiresAt));
        assertThrows(IllegalArgumentException.class,
                () -> budgets.reserve(subject, java.util.List.of(), 1, BigDecimal.ZERO, expiresAt));
        assertThrows(IllegalArgumentException.class,
                () -> budgets.reserve(subject, java.util.Collections.singletonList(null), 1,
                        BigDecimal.ZERO, expiresAt));
        assertThrows(IllegalArgumentException.class,
                () -> budgets.reserve(subject, java.util.List.of(0L), 1, BigDecimal.ZERO, expiresAt));
        assertThrows(IllegalArgumentException.class,
                () -> budgets.reserve(subject, java.util.List.of(bucketId), 0, BigDecimal.ZERO, expiresAt));
        assertThrows(IllegalArgumentException.class,
                () -> budgets.reserve(subject, java.util.List.of(bucketId), 1, null, expiresAt));
        assertThrows(IllegalArgumentException.class,
                () -> budgets.reserve(subject, java.util.List.of(bucketId), 1, new BigDecimal("-0.1"), expiresAt));
        assertThrows(IllegalArgumentException.class,
                () -> budgets.reserve(subject, java.util.List.of(bucketId), 1, BigDecimal.ZERO, null));
        assertThrows(IllegalArgumentException.class,
                () -> budgets.reserve(subject, java.util.List.of(bucketId, bucketId), 1,
                        BigDecimal.ZERO, expiresAt));

        assertThrows(IllegalArgumentException.class,
                () -> budgets.commitIfPresent(null, 0, BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> budgets.commitIfPresent(subject, -1, BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> budgets.commitIfPresent(subject, 0, null));
        assertThrows(IllegalArgumentException.class,
                () -> budgets.commitIfPresent(subject, 0, new BigDecimal("-0.1")));
        assertThrows(IllegalArgumentException.class, () -> budgets.release(null));
        assertThrows(IllegalArgumentException.class, () -> budgets.releaseIfPresent(null));
        assertThrows(IllegalArgumentException.class,
                () -> budgets.requireProviderCallReservation(null, Instant.now()));
        assertThrows(IllegalArgumentException.class,
                () -> budgets.requireProviderCallReservation(subject, null));
        assertThrows(IllegalArgumentException.class, () -> budgets.expireDueReservations(null, 1));
        assertThrows(IllegalArgumentException.class,
                () -> budgets.expireDueReservations(Instant.now(), 0));
        assertThrows(IllegalArgumentException.class,
                () -> budgets.expireDueReservations(Instant.now(), 1_001));
        assertThrows(IllegalArgumentException.class, () -> budgets.needsAttemptReconciliation(null));
    }

    @Test
    void missingExpiredAndSettledReservationsFailClosedAtProviderDispatchBoundary() {
        BillingSubject missing = new BillingSubject(BillingSubject.Kind.RUN, UUID.randomUUID().toString());
        assertFalse(budgets.commitIfPresent(missing, 0, BigDecimal.ZERO));
        assertFalse(budgets.releaseIfPresent(missing));
        assertThrows(IllegalStateException.class, () -> budgets.commit(missing, 0, BigDecimal.ZERO));
        assertThrows(IllegalStateException.class, () -> budgets.release(missing));
        assertThrows(JdbcAiBudgetService.ProviderReservationUnavailableException.class,
                () -> budgets.requireProviderCallReservation(missing, Instant.now()));

        Instant expiresAt = Instant.now().plusSeconds(60);
        BillingSubject subject = new BillingSubject(BillingSubject.Kind.RUN, UUID.randomUUID().toString());
        long bucketId = insertBucket("fake", 1_000, "1.000000");
        budgets.reserve(subject, java.util.List.of(bucketId), 100, new BigDecimal("0.100000"), expiresAt);
        Instant persistedExpiresAt = jdbc.queryForObject(
                "SELECT expires_at FROM ai_budget_reservation WHERE billing_subject_public_id=?",
                (rs, rowNum) -> rs.getTimestamp(1).toInstant(), subject.publicId());
        assertFalse(persistedExpiresAt.isAfter(expiresAt),
                "持久化精度归一化不得延长 provider 预算有效期");
        budgets.requireProviderCallReservation(subject, expiresAt.minusMillis(1));
        assertThrows(JdbcAiBudgetService.ProviderReservationUnavailableException.class,
                () -> budgets.requireProviderCallReservation(subject, expiresAt));

        assertTrue(budgets.commitIfPresent(subject, 50, new BigDecimal("0.050000")));
        assertTrue(budgets.commitIfPresent(subject, 50, new BigDecimal("0.050000")));
        assertThrows(IllegalStateException.class,
                () -> budgets.commitIfPresent(subject, 51, new BigDecimal("0.050000")));
        assertThrows(JdbcAiBudgetService.ProviderReservationUnavailableException.class,
                () -> budgets.requireProviderCallReservation(subject, expiresAt.minusSeconds(1)));
        assertFalse(budgets.releaseIfPresent(subject));
        assertThrows(IllegalStateException.class, () -> budgets.release(subject));
    }

    @Test
    void mixedReservationStatesAreRejectedInsteadOfPartiallySettledOrReleased() {
        BillingSubject subject = new BillingSubject(BillingSubject.Kind.RUN, UUID.randomUUID().toString());
        long firstBucket = insertBucket("fake", 1_000, "1.000000");
        long secondBucket = insertBucket("fake", 1_000, "1.000000");
        budgets.reserve(subject, java.util.List.of(firstBucket, secondBucket), 100,
                new BigDecimal("0.100000"), Instant.now().plusSeconds(60));
        jdbc.update("UPDATE ai_budget_reservation SET state='RELEASED' WHERE budget_bucket_id=?", firstBucket);

        assertThrows(IllegalStateException.class,
                () -> budgets.commitIfPresent(subject, 50, new BigDecimal("0.050000")));
        assertThrows(IllegalStateException.class, () -> budgets.release(subject));
        assertThrows(IllegalStateException.class, () -> budgets.releaseIfPresent(subject));
    }

    private ModelRequest.BillingTrace trace() {
        return new ModelRequest.BillingTrace(UUID.randomUUID().toString(), 1, ActorDescriptor.user(7L));
    }

    private void reserveFor(ModelRequest.BillingTrace trace, String provider, Instant expiresAt) {
        long bucketId = insertBucket(provider, 10_000, "10.000000");
        budgets.reserve(new BillingSubject(BillingSubject.Kind.RUN, trace.runPublicId()),
                java.util.List.of(bucketId), 5_000, new BigDecimal("1.000000"), expiresAt);
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
                Timestamp.from(from), to == null ? null : Timestamp.from(to), "test://pricing-v1");
        return jdbc.queryForObject("SELECT MAX(id) FROM ai_pricing_version", Long.class);
    }

    private long insertBucket(String provider, long tokenLimit, String costLimit) {
        jdbc.update("INSERT INTO ai_budget_bucket "
                        + "(quota_policy_id,scope_type,scope_key,capability,provider_code,period_type,"
                        + "period_start,period_end,token_limit,cost_limit,reserved_tokens,committed_tokens,"
                        + "reserved_cost,committed_cost,currency,version,created_at,updated_at) "
                        + "VALUES (?, 'USER', ?, 'ASSISTANT', ?, 'DAILY', ?, ?, ?, ?, 0, 0, 0, 0, "
                        + "'CNY', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                System.nanoTime(), UUID.randomUUID().toString(), provider,
                Timestamp.from(Instant.now().minusSeconds(60)), Timestamp.from(Instant.now().plusSeconds(3600)),
                tokenLimit, new BigDecimal(costLimit));
        return jdbc.queryForObject("SELECT MAX(id) FROM ai_budget_bucket", Long.class);
    }

    private void insertStreamingRun(String runPublicId, long actorUserId) {
        String conversationPublicId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ai_conversation "
                        + "(public_id,owner_user_id,surface,context_type,status,created_at,updated_at) "
                        + "VALUES (?,?,'GLOBAL','NONE','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                conversationPublicId, actorUserId);
        long conversationId = jdbc.queryForObject(
                "SELECT id FROM ai_conversation WHERE public_id=?", Long.class, conversationPublicId);
        String messagePublicId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ai_message "
                        + "(public_id,conversation_id,sequence_no,role,client_request_id,request_hash,"
                        + "content_redacted,classification,created_at) "
                        + "VALUES (?,?,1,'USER',?,?,?,'L1',CURRENT_TIMESTAMP)",
                messagePublicId, conversationId, UUID.randomUUID().toString(), "a".repeat(64), "测试请求");
        long messageId = jdbc.queryForObject(
                "SELECT id FROM ai_message WHERE public_id=?", Long.class, messagePublicId);
        jdbc.update("INSERT INTO ai_run "
                        + "(public_id,conversation_id,request_message_id,capability,state,version,actor_user_id,"
                        + "session_fingerprint_hash,session_fingerprint_key_version,permission_digest,"
                        + "prompt_version_id,tool_catalog_version_id,retrieval_policy_version,"
                        + "redaction_policy_version,reserved_tokens,reserved_cost,correlation_id,cost_status,"
                        + "created_at,updated_at) VALUES (?,?,?,'ASSISTANT','STREAMING',0,?,"
                        + "?,1,?,1,1,'none.v1','pii-redaction-v3',5000,1,?,'RESERVED',"
                        + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                runPublicId, conversationId, messageId, actorUserId, "b".repeat(64), "c".repeat(64),
                UUID.randomUUID().toString());
    }
}
