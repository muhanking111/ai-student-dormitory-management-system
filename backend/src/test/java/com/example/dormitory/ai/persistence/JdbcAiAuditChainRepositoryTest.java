package com.example.dormitory.ai.persistence;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.config.AiAuditStartupGate;
import com.example.dormitory.ai.config.AiProperties;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiAuditChainRepository;
import com.example.dormitory.ai.infrastructure.runtime.AiRuntimeAuditWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ActiveProfiles("test")
@SpringBootTest
class JdbcAiAuditChainRepositoryTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private JdbcAiAuditChainRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM ai_tool_call");
        jdbcTemplate.update("DELETE FROM ai_audit_event");
        jdbcTemplate.update("DELETE FROM ai_audit_chain_head");
        Map<Integer, byte[]> keyring = Map.of(
                1, "test-audit-hmac-key-v1-32-bytes!".getBytes(StandardCharsets.UTF_8),
                2, "test-audit-hmac-key-v2-32-bytes!".getBytes(StandardCharsets.UTF_8));
        repository = new JdbcAiAuditChainRepository(jdbcTemplate, transactionManager, keyring::get);
    }

    @Test
    void concurrentAppendsAllocateOneLinearHashChainWithoutForks() throws Exception {
        String aggregate = UUID.randomUUID().toString();
        int eventCount = 12;
        CountDownLatch ready = new CountDownLatch(eventCount);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(eventCount);
        try {
            var futures = IntStream.range(0, eventCount)
                    .mapToObj(index -> executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return repository.append(command(aggregate, "EVENT_" + index, 1));
                    }))
                    .toList();
            ready.await();
            start.countDown();
            for (var future : futures) future.get();

            List<Map<String, Object>> events = jdbcTemplate.queryForList(
                    "SELECT sequence_no, previous_event_hash, event_hash, integrity_key_version, "
                            + "canonicalization_version FROM ai_audit_event ORDER BY sequence_no");
            assertEquals(eventCount, events.size());
            for (int index = 0; index < events.size(); index++) {
                assertEquals((long) index + 1, ((Number) events.get(index).get("SEQUENCE_NO")).longValue());
                if (index > 0) {
                    assertEquals(events.get(index - 1).get("EVENT_HASH"),
                            events.get(index).get("PREVIOUS_EVENT_HASH"));
                }
                assertEquals("v1", events.get(index).get("CANONICALIZATION_VERSION"));
            }
            assertEquals((long) eventCount, jdbcTemplate.queryForObject(
                    "SELECT last_sequence_no FROM ai_audit_chain_head", Long.class));
            assertTrue(repository.verify("RUN", "RUN", aggregate));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void sameAggregateAppendsTwiceWithinOuterTransactionCommitOneLinearChain() {
        String aggregate = UUID.randomUUID().toString();
        TransactionTemplate outerTransaction = new TransactionTemplate(transactionManager);

        assertDoesNotThrow(() -> outerTransaction.executeWithoutResult(status -> {
            repository.append(command(
                    "PROPOSAL", "RISK_SCAN", aggregate, "RISK_SCAN_REQUESTED", 1));
            repository.append(command(
                    "PROPOSAL", "RISK_SCAN", aggregate, "RISK_SCAN_COMPLETED", 1));
        }));

        List<Map<String, Object>> events = jdbcTemplate.queryForList(
                "SELECT sequence_no, previous_event_hash, event_hash FROM ai_audit_event "
                        + "WHERE chain_scope=? AND aggregate_type=? AND aggregate_public_id=? "
                        + "ORDER BY sequence_no",
                "PROPOSAL", "RISK_SCAN", aggregate);
        assertEquals(2, events.size());
        assertEquals(1L, ((Number) events.get(0).get("SEQUENCE_NO")).longValue());
        assertEquals(2L, ((Number) events.get(1).get("SEQUENCE_NO")).longValue());
        assertEquals(events.get(0).get("EVENT_HASH"), events.get(1).get("PREVIOUS_EVENT_HASH"));
        assertEquals(2L, jdbcTemplate.queryForObject(
                "SELECT last_sequence_no FROM ai_audit_chain_head "
                        + "WHERE chain_scope=? AND aggregate_type=? AND aggregate_public_id=?",
                Long.class, "PROPOSAL", "RISK_SCAN", aggregate));
        assertTrue(repository.verify("PROPOSAL", "RISK_SCAN", aggregate));
    }

    @Test
    void runtimeWriterVerifiesHistoricalEventsAfterConfiguredKeyRotation() {
        AiProperties properties = new AiProperties();
        properties.getAudit().setHmacKey("runtime-audit-current-key-v1-32-bytes!!");
        properties.getAudit().setActiveKeyVersion(1);
        AiRuntimeAuditWriter writer = new AiRuntimeAuditWriter(jdbcTemplate, transactionManager, properties);
        String aggregate = UUID.randomUUID().toString();
        writer.append("RUN", "RUN", aggregate, "RUN_ACCEPTED", ActorDescriptor.system("ai-test"),
                null, 1, null, "a".repeat(64), UUID.randomUUID().toString());

        properties.getAudit().setPreviousHmacKey("runtime-audit-current-key-v1-32-bytes!!");
        properties.getAudit().setPreviousKeyVersion(1);
        properties.getAudit().setHmacKey("runtime-audit-current-key-v2-32-bytes!!");
        properties.getAudit().setActiveKeyVersion(2);
        writer.append("RUN", "RUN", aggregate, "RUN_COMPLETED", ActorDescriptor.system("ai-test"),
                null, 1, null, "b".repeat(64), UUID.randomUUID().toString());

        writer.requireValidChain("RUN", "RUN", aggregate);
        properties.getAudit().setPreviousHmacKey("");
        assertThrows(com.example.dormitory.ai.api.AiApiException.class,
                () -> writer.requireValidChain("RUN", "RUN", aggregate));
    }

    @Test
    void runtimeWriterKeepsEveryRetainedKeyVersionAcrossMultipleRotations() {
        AiProperties properties = new AiProperties();
        properties.getAudit().setHmacKeyring(Map.of(
                1, "runtime-audit-retained-key-v1-32-bytes!!",
                2, "runtime-audit-retained-key-v2-32-bytes!!",
                3, "runtime-audit-retained-key-v3-32-bytes!!"));
        AiRuntimeAuditWriter writer = new AiRuntimeAuditWriter(jdbcTemplate, transactionManager, properties);
        String aggregate = UUID.randomUUID().toString();
        for (int version = 1; version <= 3; version++) {
            properties.getAudit().setActiveKeyVersion(version);
            writer.append("RUN", "RUN", aggregate, "ROTATED_" + version, ActorDescriptor.system("ai-test"),
                    null, 1, null, String.valueOf(version).repeat(64), UUID.randomUUID().toString());
        }

        writer.requireValidChain("RUN", "RUN", aggregate);
        assertEquals(List.of(1, 2, 3), jdbcTemplate.queryForList(
                "SELECT integrity_key_version FROM ai_audit_event WHERE aggregate_public_id=? ORDER BY sequence_no",
                Integer.class, aggregate));
    }

    @Test
    void runtimeWriterAndStartupGateFailClosedWhenPersistedHistoricalKeyIsRemoved() {
        AiProperties properties = new AiProperties();
        properties.setEnabled(true);
        properties.getAudit().setHmacKeyring(Map.of(
                1, "runtime-audit-retained-key-v1-32-bytes!!",
                2, "runtime-audit-retained-key-v2-32-bytes!!"));
        properties.getAudit().setActiveKeyVersion(1);
        AiRuntimeAuditWriter writer = new AiRuntimeAuditWriter(jdbcTemplate, transactionManager, properties);
        String aggregate = UUID.randomUUID().toString();
        writer.append("RUN", "RUN", aggregate, "RUN_ACCEPTED", ActorDescriptor.system("ai-test"),
                null, 1, null, hash('a'), UUID.randomUUID().toString());

        properties.getAudit().setActiveKeyVersion(2);
        properties.getAudit().setHmacKeyring(Map.of(
                2, "runtime-audit-retained-key-v2-32-bytes!!"));

        assertFalse(writer.writable());
        assertThrows(IllegalStateException.class, () -> new AiAuditStartupGate(properties, writer).verify());
        AiApiException blocked = assertThrows(AiApiException.class,
                () -> writer.append("RUN", "RUN", aggregate, "RUN_COMPLETED",
                        ActorDescriptor.system("ai-test"), null, 1, null, hash('b'),
                        UUID.randomUUID().toString()));
        assertEquals("AI_AUDIT_UNAVAILABLE", blocked.errorCode());
        assertEquals("AI 审计控制面不可用", blocked.getMessage());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_audit_event WHERE aggregate_public_id = ?", Integer.class, aggregate));
        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT last_sequence_no FROM ai_audit_chain_head WHERE aggregate_public_id = ?",
                Long.class, aggregate));
    }

    @Test
    void runtimeWriterChecksPersistedHeadKeyVersionEvenWhenEventsUseKnownKeys() {
        AiProperties properties = new AiProperties();
        properties.getAudit().setHmacKeyring(Map.of(
                2, "runtime-audit-retained-key-v2-32-bytes!!"));
        properties.getAudit().setActiveKeyVersion(2);
        AiRuntimeAuditWriter writer = new AiRuntimeAuditWriter(jdbcTemplate, transactionManager, properties);
        String aggregate = UUID.randomUUID().toString();
        writer.append("RUN", "RUN", aggregate, "RUN_ACCEPTED", ActorDescriptor.system("ai-test"),
                null, 1, null, hash('a'), UUID.randomUUID().toString());
        jdbcTemplate.update(
                "UPDATE ai_audit_chain_head SET integrity_key_version = ? WHERE aggregate_public_id = ?",
                1, aggregate);

        assertFalse(writer.writable());
    }

    @Test
    void runtimeWriterRequiresStrongKeysForEveryPersistedVersion() {
        AiProperties properties = new AiProperties();
        properties.getAudit().setHmacKeyring(Map.of(
                1, "runtime-audit-retained-key-v1-32-bytes!!",
                2, "runtime-audit-retained-key-v2-32-bytes!!"));
        AiRuntimeAuditWriter writer = new AiRuntimeAuditWriter(jdbcTemplate, transactionManager, properties);
        String aggregate = UUID.randomUUID().toString();
        properties.getAudit().setActiveKeyVersion(1);
        writer.append("RUN", "RUN", aggregate, "ROTATED_1", ActorDescriptor.system("ai-test"),
                null, 1, null, hash('a'), UUID.randomUUID().toString());
        properties.getAudit().setActiveKeyVersion(2);
        writer.append("RUN", "RUN", aggregate, "ROTATED_2", ActorDescriptor.system("ai-test"),
                null, 1, null, hash('b'), UUID.randomUUID().toString());

        properties.getAudit().setHmacKeyring(Map.of(
                1, "too-short",
                2, "runtime-audit-retained-key-v2-32-bytes!!"));
        assertFalse(writer.writable());

        properties.getAudit().setHmacKeyring(Map.of(
                1, "runtime-audit-retained-key-v1-32-bytes!!",
                2, "runtime-audit-retained-key-v2-32-bytes!!"));
        assertTrue(writer.writable());
    }

    @Test
    void startupGateFailsClosedWhenPersistedHeadHashDoesNotMatchTail() {
        AiProperties properties = runtimeAuditProperties();
        AiRuntimeAuditWriter writer = new AiRuntimeAuditWriter(jdbcTemplate, transactionManager, properties);
        String aggregate = UUID.randomUUID().toString();
        writer.append("RUN", "RUN", aggregate, "RUN_ACCEPTED", ActorDescriptor.system("ai-test"),
                null, 1, null, hash('a'), UUID.randomUUID().toString());
        AiAuditStartupGate gate = new AiAuditStartupGate(properties, writer);

        assertDoesNotThrow(gate::verify);
        jdbcTemplate.update(
                "UPDATE ai_audit_chain_head SET last_event_hash = ? WHERE aggregate_public_id = ?",
                hash('f'), aggregate);

        assertThrows(IllegalStateException.class, gate::verify);
    }

    @Test
    void startupGateFailsClosedWhenPersistedTailEventWasDeleted() {
        AiProperties properties = runtimeAuditProperties();
        AiRuntimeAuditWriter writer = new AiRuntimeAuditWriter(jdbcTemplate, transactionManager, properties);
        String aggregate = UUID.randomUUID().toString();
        writer.append("RUN", "RUN", aggregate, "RUN_ACCEPTED", ActorDescriptor.system("ai-test"),
                null, 1, null, hash('a'), UUID.randomUUID().toString());
        writer.append("RUN", "RUN", aggregate, "RUN_COMPLETED", ActorDescriptor.system("ai-test"),
                null, 1, null, hash('b'), UUID.randomUUID().toString());
        jdbcTemplate.update(
                "DELETE FROM ai_audit_event WHERE aggregate_public_id = ? AND sequence_no = ?",
                aggregate, 2);

        assertThrows(IllegalStateException.class, () -> new AiAuditStartupGate(properties, writer).verify());
    }

    @Test
    void startupGateFailsClosedWhenPersistedSequenceCountHasAGap() {
        AiProperties properties = runtimeAuditProperties();
        AiRuntimeAuditWriter writer = new AiRuntimeAuditWriter(jdbcTemplate, transactionManager, properties);
        String aggregate = UUID.randomUUID().toString();
        writer.append("RUN", "RUN", aggregate, "RUN_ACCEPTED", ActorDescriptor.system("ai-test"),
                null, 1, null, hash('a'), UUID.randomUUID().toString());
        writer.append("RUN", "RUN", aggregate, "RUN_STARTED", ActorDescriptor.system("ai-test"),
                null, 1, null, hash('b'), UUID.randomUUID().toString());
        writer.append("RUN", "RUN", aggregate, "RUN_COMPLETED", ActorDescriptor.system("ai-test"),
                null, 1, null, hash('c'), UUID.randomUUID().toString());
        jdbcTemplate.update(
                "DELETE FROM ai_audit_event WHERE aggregate_public_id = ? AND sequence_no = ?",
                aggregate, 2);

        assertThrows(IllegalStateException.class, () -> new AiAuditStartupGate(properties, writer).verify());
    }

    @Test
    void keyRotationStartsOnTheNewEventAndKeepsThePreviousHashLink() {
        String aggregate = UUID.randomUUID().toString();
        var first = repository.append(command(aggregate, "CREATED", 1));
        var rotated = repository.append(command(aggregate, "UPDATED", 2));

        assertEquals(1, first.integrityKeyVersion());
        assertEquals(2, rotated.integrityKeyVersion());
        assertEquals(first.eventHash(), rotated.previousEventHash());
        assertEquals("HMAC-SHA256", rotated.integrityAlgorithm());
        assertEquals("v1", rotated.canonicalizationVersion());
        assertTrue(repository.verify("RUN", "RUN", aggregate));
    }

    @Test
    void verificationFailsWhenTailEventIsDeleted() {
        String aggregate = UUID.randomUUID().toString();
        repository.append(command(aggregate, "CREATED", 1));
        repository.append(command(aggregate, "UPDATED", 1));

        jdbcTemplate.update("DELETE FROM ai_audit_event WHERE aggregate_public_id = ? AND sequence_no = 2",
                aggregate);

        assertFalse(repository.verify("RUN", "RUN", aggregate));
    }

    @Test
    void verificationFailsWhenAllEventsAreDeletedButHeadRemains() {
        String aggregate = UUID.randomUUID().toString();
        repository.append(command(aggregate, "CREATED", 1));

        jdbcTemplate.update("DELETE FROM ai_audit_event WHERE aggregate_public_id = ?", aggregate);

        assertFalse(repository.verify("RUN", "RUN", aggregate));
    }

    @Test
    void verificationFailsWhenPersistedHeadIsTampered() {
        String aggregate = UUID.randomUUID().toString();
        repository.append(command(aggregate, "CREATED", 1));

        jdbcTemplate.update("UPDATE ai_audit_chain_head SET last_event_hash = ? WHERE aggregate_public_id = ?",
                hash('f'), aggregate);

        assertFalse(repository.verify("RUN", "RUN", aggregate));
    }

    @Test
    void appendFailsClosedWithoutWritingWhenPersistedTailWasDeleted() {
        String aggregate = UUID.randomUUID().toString();
        repository.append(command(aggregate, "CREATED", 1));
        JdbcAiAuditChainRepository.AuditEventRecord tail =
                repository.append(command(aggregate, "UPDATED", 2));
        jdbcTemplate.update(
                "DELETE FROM ai_audit_event WHERE aggregate_public_id = ? AND sequence_no = ?",
                aggregate, tail.sequenceNo());

        assertThrows(IllegalStateException.class,
                () -> repository.append(command(aggregate, "MUST_NOT_APPEND", 2)));

        assertEquals(1, eventCount(aggregate));
        assertHeadUnchanged(aggregate, tail.sequenceNo(), tail.eventHash(), tail.integrityKeyVersion());
    }

    @Test
    void appendFailsClosedWithoutWritingWhenPersistedHeadHashWasTampered() {
        String aggregate = UUID.randomUUID().toString();
        JdbcAiAuditChainRepository.AuditEventRecord tail =
                repository.append(command(aggregate, "CREATED", 1));
        String tamperedHash = hash('f');
        jdbcTemplate.update(
                "UPDATE ai_audit_chain_head SET last_event_hash = ? WHERE aggregate_public_id = ?",
                tamperedHash, aggregate);

        assertThrows(IllegalStateException.class,
                () -> repository.append(command(aggregate, "MUST_NOT_APPEND", 1)));

        assertEquals(1, eventCount(aggregate));
        assertHeadUnchanged(aggregate, tail.sequenceNo(), tamperedHash, tail.integrityKeyVersion());
    }

    @Test
    void appendFailsClosedWithoutWritingWhenPersistedHeadKeyVersionWasTampered() {
        String aggregate = UUID.randomUUID().toString();
        JdbcAiAuditChainRepository.AuditEventRecord tail =
                repository.append(command(aggregate, "CREATED", 1));
        jdbcTemplate.update(
                "UPDATE ai_audit_chain_head SET integrity_key_version = ? WHERE aggregate_public_id = ?",
                2, aggregate);

        assertThrows(IllegalStateException.class,
                () -> repository.append(command(aggregate, "MUST_NOT_APPEND", 2)));

        assertEquals(1, eventCount(aggregate));
        assertHeadUnchanged(aggregate, tail.sequenceNo(), tail.eventHash(), 2);
    }

    private int eventCount(String aggregate) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_audit_event WHERE aggregate_public_id = ?",
                Integer.class,
                aggregate);
    }

    private void assertHeadUnchanged(String aggregate, long sequence, String eventHash, int keyVersion) {
        Map<String, Object> head = jdbcTemplate.queryForMap(
                "SELECT last_sequence_no, last_event_hash, integrity_key_version "
                        + "FROM ai_audit_chain_head WHERE aggregate_public_id = ?",
                aggregate);
        assertEquals(sequence, ((Number) head.get("LAST_SEQUENCE_NO")).longValue());
        assertEquals(eventHash, head.get("LAST_EVENT_HASH"));
        assertEquals(keyVersion, ((Number) head.get("INTEGRITY_KEY_VERSION")).intValue());
    }

    private JdbcAiAuditChainRepository.AuditAppendCommand command(
            String aggregate,
            String eventType,
            int keyVersion) {
        return command("RUN", "RUN", aggregate, eventType, keyVersion);
    }

    private JdbcAiAuditChainRepository.AuditAppendCommand command(
            String chainScope,
            String aggregateType,
            String aggregate,
            String eventType,
            int keyVersion) {
        return new JdbcAiAuditChainRepository.AuditAppendCommand(
                chainScope,
                aggregateType,
                aggregate,
                eventType,
                ActorDescriptor.user(9L),
                hash('a'),
                1,
                hash('b'),
                hash('c'),
                UUID.randomUUID().toString(),
                Instant.now(),
                keyVersion);
    }

    private String hash(char value) {
        return String.valueOf(value).repeat(64);
    }

    private AiProperties runtimeAuditProperties() {
        AiProperties properties = new AiProperties();
        properties.setEnabled(true);
        properties.getAudit().setHmacKeyring(Map.of(
                1, "runtime-audit-retained-key-v1-32-bytes!!"));
        properties.getAudit().setActiveKeyVersion(1);
        return properties;
    }
}
