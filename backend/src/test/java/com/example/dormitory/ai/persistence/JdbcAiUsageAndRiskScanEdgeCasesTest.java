package com.example.dormitory.ai.persistence;

import com.example.dormitory.ai.application.control.BillingSubject;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiUsageLedgerRepository;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiOutboxRepository;
import com.example.dormitory.ai.infrastructure.persistence.JdbcRiskScanRepository;
import com.example.dormitory.ai.risk.RiskScanScope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdbcAiUsageAndRiskScanEdgeCasesTest {

    private static final Instant NOW = Instant.parse("2026-07-13T12:00:00Z");

    private JdbcTemplate jdbc;
    private JdbcAiUsageLedgerRepository usage;
    private JdbcAiOutboxRepository outbox;
    private JdbcRiskScanRepository scans;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:usage-risk-edges-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql"),
                new ClassPathResource("ai-schema.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        usage = new JdbcAiUsageLedgerRepository(jdbc);
        outbox = new JdbcAiOutboxRepository(jdbc);
        scans = new JdbcRiskScanRepository(jdbc, new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void outboxProducerReplayComparesEveryBoundFactAndStaleWorkersCannotDefer() {
        Instant availableAt = NOW;
        String aggregate = UUID.randomUUID().toString();
        ActorDescriptor actor = ActorDescriptor.service("outbox-test", 7L, 7L);
        JdbcAiOutboxRepository.OutboxDraft baseline = new JdbcAiOutboxRepository.OutboxDraft(
                "RISK_SCAN", aggregate, "RiskScanRequested.v1", "{}", actor, availableAt);

        List<JdbcAiOutboxRepository.OutboxDraft> mismatches = List.of(
                new JdbcAiOutboxRepository.OutboxDraft(
                        "OTHER", aggregate, "RiskScanRequested.v1", "{}", actor, availableAt),
                new JdbcAiOutboxRepository.OutboxDraft(
                        "RISK_SCAN", UUID.randomUUID().toString(), "RiskScanRequested.v1", "{}", actor, availableAt),
                new JdbcAiOutboxRepository.OutboxDraft(
                        "RISK_SCAN", aggregate, "OtherEvent.v1", "{}", actor, availableAt),
                new JdbcAiOutboxRepository.OutboxDraft(
                        "RISK_SCAN", aggregate, "RiskScanRequested.v1", "{\"changed\":true}", actor, availableAt),
                new JdbcAiOutboxRepository.OutboxDraft(
                        "RISK_SCAN", aggregate, "RiskScanRequested.v1", "{}",
                        ActorDescriptor.system("outbox-test"), availableAt),
                new JdbcAiOutboxRepository.OutboxDraft(
                        "RISK_SCAN", aggregate, "RiskScanRequested.v1", "{}",
                        ActorDescriptor.service("other-service", 7L, 7L), availableAt),
                new JdbcAiOutboxRepository.OutboxDraft(
                        "RISK_SCAN", aggregate, "RiskScanRequested.v1", "{}",
                        ActorDescriptor.service("outbox-test", 8L, 7L), availableAt),
                new JdbcAiOutboxRepository.OutboxDraft(
                        "RISK_SCAN", aggregate, "RiskScanRequested.v1", "{}",
                        ActorDescriptor.service("outbox-test", 7L, 8L), availableAt));

        for (int index = 0; index < mismatches.size(); index++) {
            int candidateIndex = index;
            String key = "outbox-fact-" + index;
            String first = outbox.enqueueOnce(key, baseline);
            assertEquals(first, outbox.enqueueOnce(key, new JdbcAiOutboxRepository.OutboxDraft(
                    baseline.aggregateType(), baseline.aggregatePublicId(), baseline.eventType(),
                    baseline.payloadRedacted(), baseline.actor(), availableAt.plusSeconds(1))));
            IllegalStateException conflict = assertThrows(IllegalStateException.class,
                    () -> outbox.enqueueOnce(key, mismatches.get(candidateIndex)));
            assertTrue(conflict.getMessage().contains("已绑定不同事件"));
        }

        String claimedId = outbox.enqueue(baseline);
        String worker = outbox.claimNext("worker-a", availableAt.plusSeconds(1)).orElseThrow().lockedBy();
        assertFalse(outbox.defer(claimedId, "worker-b", "AI_DISABLED", availableAt.plusSeconds(10)));
        assertEquals(0, outbox.recoverExpiredLeases(
                availableAt.plusSeconds(2), Duration.ofSeconds(5)));
        assertEquals("worker-a", worker);
    }

    @Test
    void usageAttemptRejectsEveryMalformedPublicFieldAndInconsistentEstimationFacts() {
        BillingSubject subject = subject();
        ActorDescriptor actor = ActorDescriptor.service("usage-test", 7L, 7L);

        assertInvalid(null, 1, 1, "MODEL_COMPLETE", actor, "ASSISTANT", "fake", "fake-v1",
                null, 1, 1, BigDecimal.ONE, "CNY", "PROVIDER", "SUCCEEDED", null, 0, null, NOW);
        assertInvalid(subject, 0, 1, "MODEL_COMPLETE", actor, "ASSISTANT", "fake", "fake-v1",
                null, 1, 1, BigDecimal.ONE, "CNY", "PROVIDER", "SUCCEEDED", null, 0, null, NOW);
        assertInvalid(subject, 1, 0, "MODEL_COMPLETE", actor, "ASSISTANT", "fake", "fake-v1",
                null, 1, 1, BigDecimal.ONE, "CNY", "PROVIDER", "SUCCEEDED", null, 0, null, NOW);
        for (String requestKind : new String[] {null, " "}) {
            assertInvalid(subject, 1, 1, requestKind, actor, "ASSISTANT", "fake", "fake-v1",
                    null, 1, 1, BigDecimal.ONE, "CNY", "PROVIDER", "SUCCEEDED", null, 0, null, NOW);
        }
        assertInvalid(subject, 1, 1, "MODEL_COMPLETE", null, "ASSISTANT", "fake", "fake-v1",
                null, 1, 1, BigDecimal.ONE, "CNY", "PROVIDER", "SUCCEEDED", null, 0, null, NOW);
        for (String capability : new String[] {null, " "}) {
            assertInvalid(subject, 1, 1, "MODEL_COMPLETE", actor, capability, "fake", "fake-v1",
                    null, 1, 1, BigDecimal.ONE, "CNY", "PROVIDER", "SUCCEEDED", null, 0, null, NOW);
        }
        for (String provider : new String[] {null, " "}) {
            assertInvalid(subject, 1, 1, "MODEL_COMPLETE", actor, "ASSISTANT", provider, "fake-v1",
                    null, 1, 1, BigDecimal.ONE, "CNY", "PROVIDER", "SUCCEEDED", null, 0, null, NOW);
        }
        for (String model : new String[] {null, " "}) {
            assertInvalid(subject, 1, 1, "MODEL_COMPLETE", actor, "ASSISTANT", "fake", model,
                    null, 1, 1, BigDecimal.ONE, "CNY", "PROVIDER", "SUCCEEDED", null, 0, null, NOW);
        }
        assertInvalid(subject, 1, 1, "MODEL_COMPLETE", actor, "ASSISTANT", "fake", "fake-v1",
                "not-a-hash", 1, 1, BigDecimal.ONE, "CNY", "PROVIDER", "SUCCEEDED", null, 0, null, NOW);
        assertInvalid(subject, 1, 1, "MODEL_COMPLETE", actor, "ASSISTANT", "fake", "fake-v1",
                null, -1, 1, BigDecimal.ONE, "CNY", "PROVIDER", "SUCCEEDED", null, 0, null, NOW);
        assertInvalid(subject, 1, 1, "MODEL_COMPLETE", actor, "ASSISTANT", "fake", "fake-v1",
                null, 1, -1, BigDecimal.ONE, "CNY", "PROVIDER", "SUCCEEDED", null, 0, null, NOW);
        for (BigDecimal cost : new BigDecimal[] {null, BigDecimal.valueOf(-1)}) {
            assertInvalid(subject, 1, 1, "MODEL_COMPLETE", actor, "ASSISTANT", "fake", "fake-v1",
                    null, 1, 1, cost, "CNY", "PROVIDER", "SUCCEEDED", null, 0, null, NOW);
        }
        for (String currency : new String[] {null, "cny"}) {
            assertInvalid(subject, 1, 1, "MODEL_COMPLETE", actor, "ASSISTANT", "fake", "fake-v1",
                    null, 1, 1, BigDecimal.ONE, currency, "PROVIDER", "SUCCEEDED", null, 0, null, NOW);
        }
        for (String source : new String[] {null, "METERED"}) {
            assertInvalid(subject, 1, 1, "MODEL_COMPLETE", actor, "ASSISTANT", "fake", "fake-v1",
                    null, 1, 1, BigDecimal.ONE, "CNY", source, "SUCCEEDED", null, 0, null, NOW);
        }
        for (String outcome : new String[] {null, "BROKEN"}) {
            assertInvalid(subject, 1, 1, "MODEL_COMPLETE", actor, "ASSISTANT", "fake", "fake-v1",
                    null, 1, 1, BigDecimal.ONE, "CNY", "PROVIDER", outcome, null, 0, null, NOW);
        }
        assertInvalid(subject, 1, 1, "MODEL_COMPLETE", actor, "ASSISTANT", "fake", "fake-v1",
                null, 1, 1, BigDecimal.ONE, "CNY", "PROVIDER", "SUCCEEDED", "x", 0, null, NOW);
        assertInvalid(subject, 1, 1, "MODEL_COMPLETE", actor, "ASSISTANT", "fake", "fake-v1",
                null, 1, 1, BigDecimal.ONE, "CNY", "PROVIDER", "SUCCEEDED", null, -1, null, NOW);
        assertInvalid(subject, 1, 1, "MODEL_COMPLETE", actor, "ASSISTANT", "fake", "fake-v1",
                null, 1, 1, BigDecimal.ONE, "CNY", "PROVIDER", "SUCCEEDED", null, 0, "bad", NOW);
        assertInvalid(subject, 1, 1, "MODEL_COMPLETE", actor, "ASSISTANT", "fake", "fake-v1",
                null, 1, 1, BigDecimal.ONE, "CNY", "PROVIDER", "SUCCEEDED", null, 0, null, null);

        assertInvalid(subject, 1, 1, "MODEL_COMPLETE", actor, "ASSISTANT", "fake", "fake-v1",
                null, 1, 1, BigDecimal.ONE, "CNY", "ESTIMATED", "SUCCEEDED", null, 0, null, NOW);
        assertInvalid(subject, 1, 1, "MODEL_COMPLETE", actor, "ASSISTANT", "fake", "fake-v1",
                null, 1, 1, BigDecimal.ONE, "CNY", "PROVIDER", "SUCCEEDED", "estimate-v1", 0, null, NOW);

        JdbcAiUsageLedgerRepository.UsageAttempt provider = new JdbcAiUsageLedgerRepository.UsageAttempt(
                subject, 1, 1, "MODEL_COMPLETE", actor, "ASSISTANT", "fake", "fake-v1",
                "a".repeat(64), null, 1, 1, BigDecimal.ONE, "CNY", "PROVIDER", NOW);
        assertEquals("PROVIDER", provider.usageSource());
        assertEquals(null, provider.estimationPolicyVersion());
    }

    @Test
    void idempotentUsageReplayComparesEveryPersistedFactAndTotalsRejectMixedCurrencies() {
        BillingSubject subject = subject();
        JdbcAiUsageLedgerRepository.UsageAttempt attempt = validUsage(subject);
        assertTrue(usage.appendIdempotent(attempt));
        assertFalse(usage.appendIdempotent(attempt));

        Object[][] mutations = {
                {"request_kind", "OTHER", "MODEL_COMPLETE"},
                {"actor_kind", "SYSTEM", "SERVICE"},
                {"actor_user_id", 99L, null},
                {"service_principal_code", "other-service", "usage-test"},
                {"initiated_by_user_id", 8L, 7L},
                {"effective_subject_user_id", 8L, 7L},
                {"capability", "RISK", "ASSISTANT"},
                {"provider_code", "other", "fake"},
                {"model_name", "other-v1", "fake-v1"},
                {"provider_request_id_hash", "b".repeat(64), "a".repeat(64)},
                {"pricing_version_id", 99L, null},
                {"input_tokens", 12L, 10L},
                {"output_tokens", 7L, 5L},
                {"cost_amount", new BigDecimal("2.000000"), new BigDecimal("1.000000")},
                {"currency", "USD", "CNY"},
                {"usage_source", "ESTIMATED", "PROVIDER"},
                {"attempt_outcome", "FAILED_FATAL", "SUCCEEDED"},
                {"estimation_policy_version", "estimate-v2", null},
                {"duration_ms", 11L, 10L},
                {"failure_code", "FAILED", null}
        };
        for (Object[] mutation : mutations) {
            String column = (String) mutation[0];
            jdbc.update("UPDATE ai_usage_ledger SET " + column + "=?", mutation[1]);
            IllegalStateException conflict = assertThrows(IllegalStateException.class,
                    () -> usage.appendIdempotent(attempt), column);
            assertTrue(conflict.getMessage().contains("不一致终态"), column);
            jdbc.update("UPDATE ai_usage_ledger SET " + column + "=?", mutation[2]);
        }

        usage.append(new JdbcAiUsageLedgerRepository.UsageAttempt(
                subject, 2, 1, "MODEL_COMPLETE", ActorDescriptor.service("usage-test", 7L, 7L),
                "ASSISTANT", "fake", "fake-v1", null, null, 2, 3,
                BigDecimal.ONE, "USD", "PROVIDER", "SUCCEEDED", null, 1, null, NOW));
        assertThrows(IllegalStateException.class, () -> usage.totals(subject));
    }

    @Test
    void riskScanCoversValidationTerminalReceiptsFailuresAndCorruptSnapshots() {
        RiskScanScope scope = RiskScanScope.full(7L, Set.of("repair:read"), NOW);
        String digest = "a".repeat(64);

        assertThrows(IllegalArgumentException.class,
                () -> scans.create(0, List.of(), List.of(), digest, scope, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> scans.create(7, null, List.of(), digest, scope, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> scans.create(7, List.of(), null, digest, scope, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> scans.create(7, List.of(), List.of(), digest, null, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> scans.create(8, List.of(), List.of(), digest, scope, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> scans.create(7, List.of(), List.of(), null, scope, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> scans.create(7, List.of(), List.of(), "A".repeat(64), scope, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> scans.create(7, List.of(), List.of(), digest, scope, null));
        assertTrue(scans.work(null).isEmpty());
        assertTrue(scans.work(" ").isEmpty());
        assertTrue(scans.begin(UUID.randomUUID().toString(), NOW).isEmpty());

        JdbcRiskScanRepository.Scan successful = scans.create(
                7, List.of("ADMIN"), List.of("repair:read"), digest, scope, NOW);
        assertEquals("RUNNING", scans.begin(successful.id(), NOW.plusSeconds(1)).orElseThrow().state());
        scans.complete(successful.id(), List.of("repair:v1"), List.of(), 3, 2, 1, NOW.plusSeconds(2));
        assertEquals("SUCCEEDED", scans.get(7, successful.id()).orElseThrow().state());
        assertEquals("SUCCEEDED", scans.begin(successful.id(), NOW.plusSeconds(3)).orElseThrow().state());
        assertThrows(IllegalStateException.class,
                () -> scans.complete(successful.id(), List.of(), List.of(), 0, 0, 0, NOW.plusSeconds(4)));

        JdbcRiskScanRepository.Scan partial = scans.create(
                7, List.of(), List.of("repair:read"), digest, scope, NOW);
        scans.begin(partial.id(), NOW.plusSeconds(1));
        scans.complete(partial.id(), List.of("repair:v1"), List.of("checkin"), 1, 0, 0, NOW.plusSeconds(2));
        JdbcRiskScanRepository.Scan partialResult = scans.get(7, partial.id()).orElseThrow();
        assertEquals("PARTIAL", partialResult.state());
        assertEquals("RISK_PROVIDER_UNAVAILABLE", partialResult.errorCode());

        JdbcRiskScanRepository.Scan failed = scans.create(7, List.of(), List.of(), digest, scope, NOW);
        scans.fail(failed.id(), "RISK_TIMEOUT", false, NOW.plusSeconds(1));
        scans.fail(failed.id(), "RISK_TIMEOUT", false, NOW.plusSeconds(2));
        assertEquals("FAILED", scans.get(7, failed.id()).orElseThrow().state());
        JdbcRiskScanRepository.Scan review = scans.create(7, List.of(), List.of(), digest, scope, NOW);
        scans.fail(review.id(), "RISK_UNCERTAIN", true, NOW.plusSeconds(1));
        assertEquals("NEEDS_REVIEW", scans.get(7, review.id()).orElseThrow().state());

        assertThrows(IllegalArgumentException.class, () -> scans.fail(review.id(), null, true, NOW));
        assertThrows(IllegalArgumentException.class, () -> scans.fail(review.id(), "bad-code", true, NOW));
        assertThrows(IllegalArgumentException.class, () -> scans.fail(review.id(), "VALID", true, null));
        assertThrows(IllegalStateException.class,
                () -> scans.fail(UUID.randomUUID().toString(), "VALID", false, NOW));

        JdbcRiskScanRepository.Scan corrupt = scans.create(7, List.of(), List.of(), digest, scope, NOW);
        jdbc.update("UPDATE ai_risk_scan SET requested_role_codes_text='not-json' WHERE public_id=?", corrupt.id());
        assertThrows(IllegalStateException.class, () -> scans.work(corrupt.id()));
        jdbc.update("UPDATE ai_risk_scan SET requested_role_codes_text='[]',requested_scope_text='' WHERE public_id=?",
                corrupt.id());
        assertThrows(IllegalStateException.class, () -> scans.work(corrupt.id()));
        jdbc.update("UPDATE ai_risk_scan SET requested_scope_text='not-json' WHERE public_id=?", corrupt.id());
        assertThrows(IllegalStateException.class, () -> scans.work(corrupt.id()));
        jdbc.update("UPDATE ai_risk_scan SET provider_versions_text='not-json' WHERE public_id=?", corrupt.id());
        assertThrows(IllegalStateException.class, () -> scans.get(7, corrupt.id()));
    }

    @Test
    void riskScanWrapsSnapshotSerializationFailure() throws Exception {
        ObjectMapper broken = mock(ObjectMapper.class);
        when(broken.writeValueAsString(any())).thenThrow(new JsonProcessingException("broken") { });
        JdbcRiskScanRepository repository = new JdbcRiskScanRepository(jdbc, broken);
        RiskScanScope scope = RiskScanScope.full(7L, Set.of(), NOW);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> repository.create(7, List.of(), List.of(), "a".repeat(64), scope, NOW));

        assertTrue(failure.getMessage().contains("序列化失败"));
    }

    private void assertInvalid(
            BillingSubject subject,
            int requestSequence,
            int attemptNumber,
            String requestKind,
            ActorDescriptor actor,
            String capability,
            String provider,
            String model,
            String providerRequestHash,
            long inputTokens,
            long outputTokens,
            BigDecimal cost,
            String currency,
            String source,
            String outcome,
            String estimationPolicy,
            long duration,
            String failureCode,
            Instant occurredAt) {
        assertThrows(IllegalArgumentException.class, () -> new JdbcAiUsageLedgerRepository.UsageAttempt(
                subject, requestSequence, attemptNumber, requestKind, actor, capability, provider, model,
                providerRequestHash, null, inputTokens, outputTokens, cost, currency, source, outcome,
                estimationPolicy, duration, failureCode, occurredAt));
    }

    private JdbcAiUsageLedgerRepository.UsageAttempt validUsage(BillingSubject subject) {
        return new JdbcAiUsageLedgerRepository.UsageAttempt(
                subject, 1, 1, "MODEL_COMPLETE", ActorDescriptor.service("usage-test", 7L, 7L),
                "ASSISTANT", "fake", "fake-v1", "a".repeat(64), null, 10, 5,
                new BigDecimal("1.000000"), "CNY", "PROVIDER", "SUCCEEDED", null, 10, null, NOW);
    }

    private BillingSubject subject() {
        return new BillingSubject(BillingSubject.Kind.RUN, UUID.randomUUID().toString());
    }
}
