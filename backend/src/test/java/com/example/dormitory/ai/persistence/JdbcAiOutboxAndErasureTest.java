package com.example.dormitory.ai.persistence;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.infrastructure.persistence.JdbcErasureJobRepository;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiErasureTargetRepository;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiOutboxRepository;
import com.example.dormitory.ai.outbox.AiOutboxEventHandler;
import com.example.dormitory.ai.outbox.AiOutboxWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ActiveProfiles("test")
@SpringBootTest
class JdbcAiOutboxAndErasureTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JdbcAiOutboxRepository outbox;

    @Autowired
    private JdbcAiErasureTargetRepository erasureTargets;

    @Autowired
    private JdbcErasureJobRepository erasureJobs;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM ai_outbox_event");
        jdbcTemplate.update("DELETE FROM ai_erasure_target");
        jdbcTemplate.update("DELETE FROM ai_erasure_job");
        jdbcTemplate.update("DELETE FROM ai_run_event");
        jdbcTemplate.update("DELETE FROM ai_run");
        jdbcTemplate.update("DELETE FROM ai_message");
        jdbcTemplate.update("DELETE FROM ai_conversation");
    }

    @Test
    void availableAtIsFlooredToDatabaseMicrosSoTheSameInstantCanClaim() {
        Instant availableAt = Instant.parse("2026-07-14T00:00:00.123456999Z");
        String publicId = outbox.enqueue(new JdbcAiOutboxRepository.OutboxDraft(
                "RISK_SCAN",
                UUID.randomUUID().toString(),
                "RiskScanRequested.v1",
                "{\"schemaVersion\":\"risk-scan-request.v1\"}",
                ActorDescriptor.service("risk-scan", 7L, 7L),
                availableAt));

        assertEquals(availableAt.truncatedTo(ChronoUnit.MICROS), jdbcTemplate.queryForObject(
                "SELECT available_at FROM ai_outbox_event WHERE public_id=?",
                java.sql.Timestamp.class,
                publicId).toInstant());
        assertTrue(outbox.claimNext(
                "worker",
                availableAt,
                Set.of("RiskScanRequested.v1")).isPresent());
    }

    @Test
    void concurrentWorkersClaimOnceThenFailureUsesBackoffAndSuccessIsTerminal() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String publicId = outbox.enqueue(new JdbcAiOutboxRepository.OutboxDraft(
                "KNOWLEDGE_SOURCE",
                UUID.randomUUID().toString(),
                "INDEX_REFRESH_REQUESTED",
                "{\"source\":\"redacted\"}",
                ActorDescriptor.service("outbox-writer", 7L, 7L),
                now));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var claims = List.of("worker-a", "worker-b").stream()
                    .map(worker -> executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return outbox.claimNext(worker, now.plusSeconds(1));
                    }))
                    .toList();
            ready.await();
            start.countDown();
            var results = claims.stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }).filter(java.util.Optional::isPresent).map(java.util.Optional::get).toList();
            assertEquals(1, results.size());
            String worker = results.getFirst().lockedBy();

            Instant retryAt = outbox.markRetryableFailure(
                    publicId, worker, "TEMPORARY_INDEX_ERROR", now.plusSeconds(2), Duration.ofSeconds(5));
            assertEquals(now.plusSeconds(7), retryAt);
            assertTrue(outbox.claimNext(worker, now.plusSeconds(6)).isEmpty());
            assertTrue(outbox.claimNext(worker, retryAt).isPresent());
            assertTrue(outbox.markSucceeded(publicId, worker, retryAt.plusSeconds(1)));
            assertFalse(outbox.markSucceeded(publicId, worker, retryAt.plusSeconds(2)));
            assertEquals("SUCCEEDED", jdbcTemplate.queryForObject(
                    "SELECT state FROM ai_outbox_event WHERE public_id = ?", String.class, publicId));
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT attempts FROM ai_outbox_event WHERE public_id = ?", Integer.class, publicId));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void deterministicProducerKeyIsIdempotentAndCannotBeReusedForDifferentPayload() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String aggregate = UUID.randomUUID().toString();
        JdbcAiOutboxRepository.OutboxDraft draft = new JdbcAiOutboxRepository.OutboxDraft(
                "RISK_SCAN", aggregate, "RiskScanRequested.v1", "{\"scanId\":\"" + aggregate + "\"}",
                ActorDescriptor.service("risk-scan", 7L, 7L), now);

        String first = outbox.enqueueOnce("risk-scan-requested|" + aggregate, draft);
        String replay = outbox.enqueueOnce("risk-scan-requested|" + aggregate, draft);

        assertEquals(first, replay);
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_outbox_event WHERE public_id=?", Integer.class, first));
        JdbcAiOutboxRepository.OutboxDraft mismatched = new JdbcAiOutboxRepository.OutboxDraft(
                "RISK_SCAN", aggregate, "RiskScanRequested.v1", "{\"scanId\":\"different\"}",
                ActorDescriptor.service("risk-scan", 7L, 7L), now);
        assertThrows(IllegalStateException.class,
                () -> outbox.enqueueOnce("risk-scan-requested|" + aggregate, mismatched));
    }

    @Test
    void supportedClaimSkipsForeignEventsAndExpiredLeaseIsRecovered() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String foreign = outbox.enqueue(new JdbcAiOutboxRepository.OutboxDraft(
                "AI_EVAL_RUN", UUID.randomUUID().toString(), "EvalRunRequested.v1", "{}",
                ActorDescriptor.service("ai-eval-worker", 7L, 7L), now));
        String supported = outbox.enqueue(new JdbcAiOutboxRepository.OutboxDraft(
                "RISK_SCAN", UUID.randomUUID().toString(), "RiskScanRequested.v1", "{}",
                ActorDescriptor.service("risk-scan", 7L, 7L), now));

        assertEquals(foreign, outbox.claimNext("eval-worker", now.plusSeconds(1)).orElseThrow().publicId());
        JdbcAiOutboxRepository.ClaimedOutboxEvent claim = outbox.claimNext(
                "worker-a", now.plusSeconds(1), Set.of("RiskScanRequested.v1")).orElseThrow();
        assertEquals(supported, claim.publicId());
        assertEquals(1, outbox.recoverExpiredLeases(now.plusSeconds(31), Duration.ofSeconds(30), 5,
                Set.of("RiskScanRequested.v1")).recoveredCount());
        assertEquals("PROCESSING", jdbcTemplate.queryForObject(
                "SELECT state FROM ai_outbox_event WHERE public_id=?", String.class, foreign));
        JdbcAiOutboxRepository.ClaimedOutboxEvent recovered = outbox.claimNext(
                "worker-b", now.plusSeconds(31), Set.of("RiskScanRequested.v1")).orElseThrow();
        assertEquals(supported, recovered.publicId());
        assertEquals("worker-b", recovered.lockedBy());
    }

    @Test
    void leaseRenewalRequiresCurrentProcessingOwnerAndAbandonedLeaseStillRecovers() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String id = outbox.enqueue(new JdbcAiOutboxRepository.OutboxDraft(
                "RISK_SCAN", UUID.randomUUID().toString(), "RiskScanRequested.v1", "{}",
                ActorDescriptor.service("risk-scan", 7L, 7L), now));
        outbox.claimNext("worker-a", now.plusSeconds(1), Set.of("RiskScanRequested.v1")).orElseThrow();

        assertFalse(outbox.renewLease(id, "worker-b", now.plusSeconds(20)));
        assertTrue(outbox.renewLease(id, "worker-a", now.plusSeconds(20)));
        assertEquals(now.plusSeconds(20), jdbcTemplate.queryForObject(
                "SELECT locked_at FROM ai_outbox_event WHERE public_id=?",
                java.sql.Timestamp.class, id).toInstant());

        assertEquals(0, outbox.recoverExpiredLeases(now.plusSeconds(49), Duration.ofSeconds(30), 5,
                Set.of("RiskScanRequested.v1")).recoveredCount());
        assertEquals(1, outbox.recoverExpiredLeases(now.plusSeconds(51), Duration.ofSeconds(30), 5,
                Set.of("RiskScanRequested.v1")).recoveredCount());
        assertFalse(outbox.renewLease(id, "worker-a", now.plusSeconds(52)));

        String terminalType = "TerminalLeaseTest.v1";
        String terminalId = outbox.enqueue(new JdbcAiOutboxRepository.OutboxDraft(
                "RISK_SCAN", UUID.randomUUID().toString(), terminalType, "{}",
                ActorDescriptor.service("terminal-test", 7L, 7L), now));
        var terminalClaim = outbox.claimNext("terminal-worker", now.plusSeconds(2), Set.of(terminalType))
                .orElseThrow();
        assertTrue(outbox.markSucceeded(terminalId, terminalClaim.lockedBy(), now.plusSeconds(3)));
        assertFalse(outbox.renewLease(terminalId, terminalClaim.lockedBy(), now.plusSeconds(4)));
    }

    @Test
    void workerHeartbeatsWhileHandlerRunsSoLiveLeaseCannotBeRecovered() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String eventType = "LeaseHeartbeatTest.v1";
        String id = outbox.enqueue(new JdbcAiOutboxRepository.OutboxDraft(
                "RISK_SCAN", UUID.randomUUID().toString(), eventType, "{}",
                ActorDescriptor.service("heartbeat-test", 7L, 7L), now));
        CountDownLatch enteredHandler = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        AiOutboxEventHandler blockingHandler = new AiOutboxEventHandler() {
            @Override
            public Set<String> eventTypes() {
                return Set.of(eventType);
            }

            @Override
            public void handle(JdbcAiOutboxRepository.ClaimedOutboxEvent event) {
                enteredHandler.countDown();
                await(releaseHandler);
            }
        };
        AiOutboxWorker worker = new AiOutboxWorker(outbox, List.of(blockingHandler),
                Duration.ofMillis(240), Duration.ofMillis(50), Duration.ofSeconds(1),
                5, false, true);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var processing = executor.submit(() -> worker.processAvailable(1));
            assertTrue(enteredHandler.await(2, TimeUnit.SECONDS));
            Thread.sleep(550);

            assertEquals(0, outbox.recoverExpiredLeases(Instant.now(), Duration.ofMillis(240), 5,
                    Set.of(eventType)).recoveredCount());
            assertEquals("PROCESSING", jdbcTemplate.queryForObject(
                    "SELECT state FROM ai_outbox_event WHERE public_id=?", String.class, id));

            releaseHandler.countDown();
            assertEquals(1, processing.get(2, TimeUnit.SECONDS));
            assertEquals("SUCCEEDED", jdbcTemplate.queryForObject(
                    "SELECT state FROM ai_outbox_event WHERE public_id=?", String.class, id));
        } finally {
            releaseHandler.countDown();
            worker.close();
            executor.shutdownNow();
        }
    }

    @Test
    void retryBudgetTransitionsPoisonEventToDead() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String id = outbox.enqueue(new JdbcAiOutboxRepository.OutboxDraft(
                "RISK_SCAN", UUID.randomUUID().toString(), "RiskScanRequested.v1", "{}",
                ActorDescriptor.service("risk-scan", 7L, 7L), now));

        for (int attempt = 1; attempt <= 3; attempt++) {
            JdbcAiOutboxRepository.ClaimedOutboxEvent claimed = outbox.claimNext(
                    "worker", now.plusSeconds(attempt * 100L), Set.of("RiskScanRequested.v1")).orElseThrow();
            JdbcAiOutboxRepository.FailureResult result = outbox.markFailure(
                    id, claimed.lockedBy(), "RISK_SCAN_TRANSIENT_FAILURE",
                    now.plusSeconds(attempt * 100L), Duration.ofSeconds(1), 3);
            assertEquals(attempt == 3, result.dead());
        }
        assertEquals("DEAD", jdbcTemplate.queryForObject(
                "SELECT state FROM ai_outbox_event WHERE public_id=?", String.class, id));
        assertTrue(outbox.claimNext("other", now.plusSeconds(1_000), Set.of("RiskScanRequested.v1")).isEmpty());
    }

    @Test
    void intentionalKillSwitchDefersWithoutConsumingFailureBudget() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String id = outbox.enqueue(new JdbcAiOutboxRepository.OutboxDraft(
                "RISK_SCAN", UUID.randomUUID().toString(), "RiskScanRequested.v1", "{}",
                ActorDescriptor.service("risk-scan", 7L, 7L), now));
        var claimed = outbox.claimNext("worker", now.plusSeconds(1),
                Set.of("RiskScanRequested.v1")).orElseThrow();

        assertTrue(outbox.defer(id, claimed.lockedBy(), "AI_CAPABILITY_DISABLED", now.plusSeconds(31)));

        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT attempts FROM ai_outbox_event WHERE public_id=?", Integer.class, id));
        assertTrue(outbox.claimNext("worker", now.plusSeconds(30),
                Set.of("RiskScanRequested.v1")).isEmpty());
        assertTrue(outbox.claimNext("worker", now.plusSeconds(31),
                Set.of("RiskScanRequested.v1")).isPresent());
    }

    @Test
    void outboxPublicBoundariesRejectMalformedDraftsKeysWorkersAndTransitions() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String aggregate = UUID.randomUUID().toString();
        ActorDescriptor service = ActorDescriptor.service("boundary-test", 7L, 7L);
        JdbcAiOutboxRepository.OutboxDraft valid = new JdbcAiOutboxRepository.OutboxDraft(
                "RISK_SCAN", aggregate, "RiskScanRequested.v1", "{}", service, now);

        assertThrows(IllegalArgumentException.class, () -> outbox.enqueueOnce(null, valid));
        assertThrows(IllegalArgumentException.class, () -> outbox.enqueueOnce(" ", valid));
        assertThrows(IllegalArgumentException.class, () -> outbox.enqueueOnce("x".repeat(513), valid));

        assertThrows(IllegalArgumentException.class, () -> outbox.claimNext(null, now));
        assertThrows(IllegalArgumentException.class, () -> outbox.claimNext(" ", now));
        assertThrows(IllegalArgumentException.class, () -> outbox.claimNext("x".repeat(129), now));
        assertThrows(IllegalArgumentException.class, () -> outbox.claimNext("worker", null));
        assertThrows(IllegalArgumentException.class, () -> outbox.claimNext("worker", now, null));
        assertThrows(IllegalArgumentException.class, () -> outbox.claimNext("worker", now, Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> outbox.claimNext("worker", now, java.util.Collections.singleton(null)));
        assertThrows(IllegalArgumentException.class,
                () -> outbox.claimNext("worker", now, Set.of("bad event type")));

        String id = outbox.enqueue(valid);
        String worker = outbox.claimNext("worker", now.plusSeconds(1)).orElseThrow().lockedBy();
        assertThrows(IllegalArgumentException.class, () -> outbox.renewLease(id, worker, null));
        assertThrows(IllegalArgumentException.class,
                () -> outbox.renewLease("not-a-uuid", worker, now.plusSeconds(2)));
        assertThrows(IllegalArgumentException.class, () -> outbox.renewLease(id, null, now.plusSeconds(2)));
        assertThrows(IllegalArgumentException.class, () -> outbox.renewLease(id, " ", now.plusSeconds(2)));
        assertThrows(IllegalArgumentException.class,
                () -> outbox.renewLease(id, "x".repeat(129), now.plusSeconds(2)));
        assertThrows(IllegalArgumentException.class, () -> outbox.markSucceeded(id, worker, null));
        assertThrows(IllegalArgumentException.class, () -> outbox.defer(id, worker, null, now));
        assertThrows(IllegalArgumentException.class, () -> outbox.defer(id, worker, "bad-reason", now));
        assertThrows(IllegalArgumentException.class, () -> outbox.defer(id, worker, "VALID_REASON", null));
    }

    @Test
    void outboxFailureAndLeaseRecoveryRejectUnsafeBoundsAndPoisonExpiredLeaseAtLimit() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String id = outbox.enqueue(new JdbcAiOutboxRepository.OutboxDraft(
                "RISK_SCAN", UUID.randomUUID().toString(), "RiskScanRequested.v1", "{}",
                ActorDescriptor.service("boundary-test", 7L, 7L), now));
        String worker = outbox.claimNext("worker", now.plusSeconds(1)).orElseThrow().lockedBy();

        assertThrows(IllegalArgumentException.class,
                () -> outbox.markFailure(id, worker, null, now, Duration.ofSeconds(1), 1));
        assertThrows(IllegalArgumentException.class,
                () -> outbox.markFailure(id, worker, "bad-code", now, Duration.ofSeconds(1), 1));
        assertThrows(IllegalArgumentException.class,
                () -> outbox.markFailure(id, worker, "VALID", null, Duration.ofSeconds(1), 1));
        assertThrows(IllegalArgumentException.class,
                () -> outbox.markFailure(id, worker, "VALID", now, null, 1));
        assertThrows(IllegalArgumentException.class,
                () -> outbox.markFailure(id, worker, "VALID", now, Duration.ZERO, 1));
        assertThrows(IllegalArgumentException.class,
                () -> outbox.markFailure(id, worker, "VALID", now, Duration.ofSeconds(-1), 1));
        assertThrows(IllegalArgumentException.class,
                () -> outbox.markFailure(id, worker, "VALID", now, Duration.ofSeconds(1), 0));
        assertThrows(IllegalArgumentException.class,
                () -> outbox.markFailure(id, worker, "VALID", now, Duration.ofSeconds(1), 101));

        assertThrows(IllegalArgumentException.class,
                () -> outbox.recoverExpiredLeases(null, Duration.ofSeconds(1), 1));
        assertThrows(IllegalArgumentException.class,
                () -> outbox.recoverExpiredLeases(now, null, 1));
        assertThrows(IllegalArgumentException.class,
                () -> outbox.recoverExpiredLeases(now, Duration.ZERO, 1));
        assertThrows(IllegalArgumentException.class,
                () -> outbox.recoverExpiredLeases(now, Duration.ofSeconds(-1), 1));
        assertThrows(IllegalArgumentException.class,
                () -> outbox.recoverExpiredLeases(now, Duration.ofSeconds(1), 0));
        assertThrows(IllegalArgumentException.class,
                () -> outbox.recoverExpiredLeases(now, Duration.ofSeconds(1), 101));
        assertThrows(IllegalArgumentException.class,
                () -> outbox.recoverExpiredLeases(now, Duration.ofSeconds(1), 1, null));
        assertThrows(IllegalArgumentException.class,
                () -> outbox.recoverExpiredLeases(now, Duration.ofSeconds(1), 1,
                        java.util.Collections.singleton(null)));
        assertThrows(IllegalArgumentException.class,
                () -> outbox.recoverExpiredLeases(now, Duration.ofSeconds(1), 1,
                        Set.of("bad event type")));

        JdbcAiOutboxRepository.LeaseRecoveryResult recovered = outbox.recoverExpiredLeases(
                now.plusSeconds(3), Duration.ofSeconds(1), 1);
        assertEquals(1, recovered.recoveredCount());
        assertEquals(List.of(id), recovered.deadEvents().stream()
                .map(JdbcAiOutboxRepository.ClaimedOutboxEvent::publicId).toList());
        assertEquals("DEAD", jdbcTemplate.queryForObject(
                "SELECT state FROM ai_outbox_event WHERE public_id=?", String.class, id));
    }

    @Test
    void outboxDraftRejectsEachUntrustedFieldAndForbiddenActorKind() {
        Instant now = Instant.now();
        String aggregate = UUID.randomUUID().toString();
        ActorDescriptor service = ActorDescriptor.service("boundary-test", 7L, 7L);

        assertThrows(IllegalArgumentException.class, () -> draft(null, aggregate, "Event.v1", "{}", service, now));
        assertThrows(IllegalArgumentException.class, () -> draft("bad type", aggregate, "Event.v1", "{}", service, now));
        assertThrows(IllegalArgumentException.class, () -> draft("TYPE", aggregate, null, "{}", service, now));
        assertThrows(IllegalArgumentException.class, () -> draft("TYPE", aggregate, "bad event", "{}", service, now));
        assertThrows(IllegalArgumentException.class, () -> draft("TYPE", aggregate, "Event.v1", null, service, now));
        assertThrows(IllegalArgumentException.class,
                () -> draft("TYPE", aggregate, "Event.v1", "x".repeat(65_537), service, now));
        assertThrows(IllegalArgumentException.class, () -> draft("TYPE", aggregate, "Event.v1", "{}", null, now));
        assertThrows(IllegalArgumentException.class,
                () -> draft("TYPE", aggregate, "Event.v1", "{}", ActorDescriptor.user(7L), now));
        assertThrows(IllegalArgumentException.class,
                () -> draft("TYPE", aggregate, "Event.v1", "{}", ActorDescriptor.model("model", 7L), now));
        assertThrows(IllegalArgumentException.class, () -> draft("TYPE", aggregate, "Event.v1", "{}", service, null));
        assertThrows(IllegalArgumentException.class,
                () -> draft("TYPE", "not-a-uuid", "Event.v1", "{}", service, now));
    }

    private JdbcAiOutboxRepository.OutboxDraft draft(
            String aggregateType,
            String aggregateId,
            String eventType,
            String payload,
            ActorDescriptor actor,
            Instant availableAt) {
        return new JdbcAiOutboxRepository.OutboxDraft(
                aggregateType, aggregateId, eventType, payload, actor, availableAt);
    }

    @Test
    void erasureTargetUsesOnlyHashedReferencesAndRegistrationIsIdempotent() {
        long jobId = 101L;
        String targetHash = hash('a');
        long first = erasureTargets.register(jobId, "VECTOR", targetHash, "vector-primary");
        long replay = erasureTargets.register(jobId, "VECTOR", targetHash, "vector-primary");

        assertEquals(first, replay);
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_erasure_target", Integer.class));
        assertTrue(erasureTargets.markVerified(first, hash('b'), Instant.now()));
        assertEquals("VERIFIED", jdbcTemplate.queryForObject(
                "SELECT state FROM ai_erasure_target WHERE id = ?", String.class, first));
    }

    @Test
    void conversationLockSerializesRunAcceptanceBeforeErasureAndErasureReturnsConflict() throws Exception {
        long owner = 77L;
        String conversationId = insertConversation(owner);
        long conversationDatabaseId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_conversation WHERE public_id=?", Long.class, conversationId);
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        CountDownLatch runLockHeld = new CountDownLatch(1);
        CountDownLatch releaseRun = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var runCommit = executor.submit(() -> transactions.executeWithoutResult(status -> {
                jdbcTemplate.queryForObject(
                        "SELECT id FROM ai_conversation WHERE id=? FOR UPDATE",
                        Long.class, conversationDatabaseId);
                runLockHeld.countDown();
                await(releaseRun);
                insertActiveRun(conversationDatabaseId, owner, "ACCEPTED");
            }));
            assertTrue(runLockHeld.await(2, TimeUnit.SECONDS));

            var erasureAttempt = executor.submit(() -> assertThrows(AiApiException.class,
                    () -> erasureJobs.create(owner, conversationId, false, Instant.now())));
            assertThrows(TimeoutException.class,
                    () -> erasureAttempt.get(150, TimeUnit.MILLISECONDS));

            releaseRun.countDown();
            runCommit.get(2, TimeUnit.SECONDS);
            AiApiException conflict = erasureAttempt.get(2, TimeUnit.SECONDS);
            assertEquals(HttpStatus.CONFLICT, conflict.status());
            assertEquals("AI_CONVERSATION_HAS_ACTIVE_RUN", conflict.errorCode());
            assertEquals("ACTIVE", jdbcTemplate.queryForObject(
                    "SELECT status FROM ai_conversation WHERE id=?", String.class, conversationDatabaseId));
            assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ai_erasure_job WHERE scope_public_id=?",
                    Integer.class, conversationId));
        } finally {
            releaseRun.countDown();
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCEPTED", "QUEUED", "RUNNING", "STREAMING"})
    void erasureNeverArchivesAConversationWithAnyActiveRunState(String runState) {
        long owner = 79L;
        String conversationId = insertConversation(owner);
        long conversationDatabaseId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_conversation WHERE public_id=?", Long.class, conversationId);
        insertActiveRun(conversationDatabaseId, owner, runState);

        AiApiException conflict = assertThrows(AiApiException.class,
                () -> erasureJobs.create(owner, conversationId, false, Instant.now()));

        assertEquals(HttpStatus.CONFLICT, conflict.status());
        assertEquals("AI_CONVERSATION_HAS_ACTIVE_RUN", conflict.errorCode());
        assertEquals("ACTIVE", jdbcTemplate.queryForObject(
                "SELECT status FROM ai_conversation WHERE id=?", String.class, conversationDatabaseId));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_erasure_job WHERE scope_public_id=?", Integer.class, conversationId));
    }

    @Test
    void mysqlConversationRedactionReturnsProofOnlyAfterPostconditionsAreVerified() {
        long owner = 88L;
        String conversationPublicId = insertConversation(owner);
        long conversationId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_conversation WHERE public_id=?", Long.class, conversationPublicId);
        jdbcTemplate.update("INSERT INTO ai_message "
                        + "(public_id,conversation_id,sequence_no,role,content_redacted,raw_object_key,"
                        + "classification,created_at) VALUES (?,?,1,'USER','待清除正文','raw/private-key','L2',CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(), conversationId);

        String proof = erasureJobs.redactConversationContent(owner, conversationPublicId);

        assertTrue(proof.matches("[0-9a-f]{64}"));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_message WHERE conversation_id=? "
                        + "AND (content_redacted<>'[ERASED]' OR raw_object_key IS NOT NULL)",
                Integer.class, conversationId));
        assertEquals("[ERASED]", jdbcTemplate.queryForObject(
                "SELECT title_redacted FROM ai_conversation WHERE id=?", String.class, conversationId));
        assertEquals("ARCHIVED", jdbcTemplate.queryForObject(
                "SELECT status FROM ai_conversation WHERE id=?", String.class, conversationId));
    }

    private String insertConversation(long owner) {
        String publicId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO ai_conversation "
                        + "(public_id,owner_user_id,surface,context_type,status,title_redacted,created_at,updated_at) "
                        + "VALUES (?,?,'GLOBAL','GLOBAL','ACTIVE','安全竞态测试',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                publicId, owner);
        return publicId;
    }

    private void insertActiveRun(long conversationId, long owner, String state) {
        String messageId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO ai_message "
                        + "(public_id,conversation_id,sequence_no,role,content_redacted,classification,created_at) "
                        + "VALUES (?,?,1,'USER','[REDACTED]','L1',CURRENT_TIMESTAMP)",
                messageId, conversationId);
        long requestMessageId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_message WHERE public_id=?", Long.class, messageId);
        jdbcTemplate.update("INSERT INTO ai_run "
                        + "(public_id,conversation_id,request_message_id,capability,state,version,actor_user_id,"
                        + "session_fingerprint_hash,session_fingerprint_key_version,permission_digest,"
                        + "prompt_version_id,tool_catalog_version_id,retrieval_policy_version,"
                        + "redaction_policy_version,reserved_tokens,reserved_cost,correlation_id,cost_status,"
                        + "created_at,updated_at) VALUES (?,?,?,'ASSISTANT',?,0,?,?,1,?,1,1,'none.v1',"
                        + "'pii-redaction-v2',0,0,?,'FINAL',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(), conversationId, requestMessageId, state, owner,
                "a".repeat(64), "b".repeat(64), UUID.randomUUID().toString());
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) throw new AssertionError("等待并发测试信号超时");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("并发测试被中断", exception);
        }
    }

    private String hash(char value) {
        return String.valueOf(value).repeat(64);
    }
}
