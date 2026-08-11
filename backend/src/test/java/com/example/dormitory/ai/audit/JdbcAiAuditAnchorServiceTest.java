package com.example.dormitory.ai.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.time.LocalDate;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ActiveProfiles("test")
@SpringBootTest
class JdbcAiAuditAnchorServiceTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM ai_audit_anchor");
        jdbcTemplate.update("DELETE FROM ai_audit_event");
        jdbcTemplate.update("DELETE FROM ai_audit_chain_head");
    }

    @Test
    void anchorsAStableDailyRootAndReturnsTheExistingReceiptOnRetry() {
        insertHead("RUN", "RUN", "00000000-0000-0000-0000-000000000002", 2, "b".repeat(64));
        insertHead("RUN", "RUN", "00000000-0000-0000-0000-000000000001", 3, "a".repeat(64));
        AtomicInteger calls = new AtomicInteger();
        AuditAnchorSink sink = request -> {
            calls.incrementAndGet();
            return new AuditAnchorSink.Receipt("fake-append-only", "c".repeat(64));
        };
        JdbcAiAuditAnchorService service = new JdbcAiAuditAnchorService(
                jdbcTemplate, transactionManager, sink, (scope, type, aggregate) -> { });

        var first = service.anchor(LocalDate.of(2026, 7, 10), "RUN");
        var retried = service.anchor(LocalDate.of(2026, 7, 10), "RUN");

        assertEquals("ANCHORED", first.state());
        assertEquals(first.rootHash(), retried.rootHash());
        assertEquals("c".repeat(64), retried.externalReceiptHash());
        assertEquals(5, retried.eventCount());
        assertEquals(1, calls.get());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_audit_anchor WHERE anchor_date = ? AND chain_scope = ?",
                Integer.class, LocalDate.of(2026, 7, 10), "RUN"));
    }

    @Test
    void persistsFailureWithoutInventingAReceipt() {
        insertHead("RUN", "RUN", "00000000-0000-0000-0000-000000000001", 1, "a".repeat(64));
        AuditAnchorSink sink = request -> { throw new IllegalStateException("sink unavailable"); };
        JdbcAiAuditAnchorService service = new JdbcAiAuditAnchorService(
                jdbcTemplate, transactionManager, sink, (scope, type, aggregate) -> { });

        assertThrows(AuditAnchorUnavailableException.class,
                () -> service.anchor(LocalDate.of(2026, 7, 10), "RUN"));

        var row = jdbcTemplate.queryForMap("SELECT state, external_receipt_hash FROM ai_audit_anchor");
        assertEquals("FAILED", row.get("STATE"));
        assertEquals(null, row.get("EXTERNAL_RECEIPT_HASH"));
    }

    @Test
    void refusesToOverwriteAnAnchoredDayWhenTheRootHasChanged() {
        insertHead("RUN", "RUN", "00000000-0000-0000-0000-000000000001", 1, "a".repeat(64));
        JdbcAiAuditAnchorService service = new JdbcAiAuditAnchorService(
                jdbcTemplate,
                transactionManager,
                request -> new AuditAnchorSink.Receipt("fake-append-only", "d".repeat(64)),
                (scope, type, aggregate) -> { });
        service.anchor(LocalDate.of(2026, 7, 10), "RUN");
        jdbcTemplate.update("UPDATE ai_audit_event SET event_hash=? WHERE chain_scope='RUN' AND sequence_no=1",
                "e".repeat(64));

        var exception = assertThrows(AuditAnchorIntegrityException.class,
                () -> service.anchor(LocalDate.of(2026, 7, 10), "RUN"));
        assertTrue(exception.getMessage().contains("root"));
    }

    @Test
    void excludesEventsAfterTheAnchoredDayCutoffEvenWhenCurrentHeadHasAdvanced() {
        String aggregate = "00000000-0000-0000-0000-000000000001";
        insertHead("RUN", "RUN", aggregate, 1, "a".repeat(64));
        insertEvent("RUN", "RUN", aggregate, 2, "f".repeat(64),
                Instant.parse("2026-07-11T01:00:00Z"));
        jdbcTemplate.update("UPDATE ai_audit_chain_head SET last_sequence_no=2,last_event_hash=?",
                "f".repeat(64));
        JdbcAiAuditAnchorService service = new JdbcAiAuditAnchorService(
                jdbcTemplate,
                transactionManager,
                request -> new AuditAnchorSink.Receipt("fake-append-only", "d".repeat(64)),
                (scope, type, aggregateId) -> { });

        var anchored = service.anchor(LocalDate.of(2026, 7, 10), "RUN");

        assertEquals(1, anchored.eventCount());
    }

    @Test
    void anchorsFromOneRepeatableReadSnapshotWhenOccurredAtChangesAfterVerification() throws Exception {
        String visible = "00000000-0000-0000-0000-000000000011";
        String movedAfterCutoff = "00000000-0000-0000-0000-000000000012";
        insertHead("RUN", "RUN", visible, 1, "a".repeat(64));
        insertHead("RUN", "RUN", movedAfterCutoff, 1, "b".repeat(64));
        CountDownLatch verificationReached = new CountDownLatch(1);
        CountDownLatch tamperCommitted = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var tamper = executor.submit(() -> {
                if (!verificationReached.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("verifier 未到达并发窗口");
                }
                try {
                    jdbcTemplate.update(
                            "UPDATE ai_audit_event SET occurred_at=? WHERE aggregate_public_id=?",
                            Timestamp.from(Instant.parse("2026-07-11T01:00:00Z")),
                            movedAfterCutoff);
                } finally {
                    tamperCommitted.countDown();
                }
                return null;
            });
            JdbcAiAuditAnchorService service = new JdbcAiAuditAnchorService(
                    jdbcTemplate,
                    transactionManager,
                    request -> new AuditAnchorSink.Receipt("fake-append-only", "d".repeat(64)),
                    (scope, type, aggregate) -> {
                        if (!movedAfterCutoff.equals(aggregate)) return;
                        verificationReached.countDown();
                        try {
                            if (!tamperCommitted.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("并发篡改未提交");
                            }
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("等待并发篡改时被中断", exception);
                        }
                    });

            var anchored = service.anchor(LocalDate.of(2026, 7, 10), "RUN");
            tamper.get(5, TimeUnit.SECONDS);

            assertEquals(2, anchored.eventCount());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void verifiesInsideRepeatableReadButCallsExternalSinkAfterCommit() {
        String aggregate = "00000000-0000-0000-0000-000000000013";
        insertHead("RUN", "RUN", aggregate, 1, "a".repeat(64));
        AtomicReference<Integer> verifierIsolation = new AtomicReference<>();
        AtomicBoolean sinkTransactionActive = new AtomicBoolean(true);
        JdbcAiAuditAnchorService service = new JdbcAiAuditAnchorService(
                jdbcTemplate,
                transactionManager,
                request -> {
                    sinkTransactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
                    return new AuditAnchorSink.Receipt("fake-append-only", "d".repeat(64));
                },
                (scope, type, id) -> verifierIsolation.set(
                        TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()));

        service.anchor(LocalDate.of(2026, 7, 10), "RUN");

        assertEquals(Connection.TRANSACTION_REPEATABLE_READ, verifierIsolation.get());
        assertFalse(sinkTransactionActive.get());
    }

    private void insertHead(String scope, String type, String publicId, long sequence, String hash) {
        jdbcTemplate.update(
                "INSERT INTO ai_audit_chain_head "
                        + "(chain_scope, aggregate_type, aggregate_public_id, last_sequence_no, "
                        + "last_event_hash, integrity_key_version, version, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 1, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                scope, type, publicId, sequence, hash);
        for (long value = 1; value <= sequence; value++) {
            insertEvent(scope, type, publicId, value,
                    value == sequence ? hash : String.format("%064x", value),
                    Instant.parse("2026-07-10T12:00:00Z").plusSeconds(value));
        }
    }

    private void insertEvent(
            String scope,
            String type,
            String aggregate,
            long sequence,
            String eventHash,
            Instant occurredAt) {
        jdbcTemplate.update("INSERT INTO ai_audit_event "
                        + "(public_id,chain_scope,aggregate_type,aggregate_public_id,sequence_no,event_type,"
                        + "actor_kind,service_principal_code,payload_redacted_hash,previous_event_hash,event_hash,"
                        + "integrity_alg,integrity_key_version,canonicalization_version,correlation_id,occurred_at,created_at) "
                        + "VALUES (?,?,?,?,?,'TEST_EVENT','SYSTEM','audit-test',?,NULL,?,'HMAC-SHA256',1,'v1',?,?,CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(), scope, type, aggregate, sequence, "1".repeat(64), eventHash,
                UUID.randomUUID().toString(), Timestamp.from(occurredAt));
    }
}
