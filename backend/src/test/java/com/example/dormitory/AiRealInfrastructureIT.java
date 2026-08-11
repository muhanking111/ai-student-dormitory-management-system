package com.example.dormitory;

import com.example.dormitory.ai.application.control.BillingSubject;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiAuditChainRepository;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiIdempotencyRepository;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiOutboxRepository;
import com.example.dormitory.ai.infrastructure.persistence.AiSchemaMigrationInitializer;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiBudgetService;
import com.example.dormitory.ai.security.AiRateLimitStore;
import com.example.dormitory.ai.security.RedisAiRateLimitStore;
import com.example.dormitory.ai.infrastructure.runtime.RedisAiSseConnectionLeaseStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

@ActiveProfiles("real-it")
@SpringBootTest
@ContextConfiguration(initializers = RealInfrastructureIT.RootEnvInitializer.class)
class AiRealInfrastructureIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcAiIdempotencyRepository idempotency;
    @Autowired JdbcAiOutboxRepository outbox;
    @Autowired JdbcAiBudgetService budgets;
    @Autowired AiSchemaMigrationInitializer aiSchemaMigration;

    @Test
    void fakePricingBootstrapIsMysqlTimestampCompatibleAndIdempotent() {
        jdbc.update("DELETE FROM ai_pricing_version WHERE provider_code='fake' "
                + "AND model_name='deterministic-fake-v1' "
                + "AND source_reference='test://fake-zero-price-v1'");

        aiSchemaMigration.migrate();
        aiSchemaMigration.migrate();

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_pricing_version WHERE provider_code='fake' "
                        + "AND model_name='deterministic-fake-v1' "
                        + "AND source_reference='test://fake-zero-price-v1'",
                Integer.class));
        Instant effectiveFrom = jdbc.queryForObject(
                "SELECT effective_from FROM ai_pricing_version WHERE provider_code='fake' "
                        + "AND model_name='deterministic-fake-v1' "
                        + "AND source_reference='test://fake-zero-price-v1'",
                (resultSet, row) -> resultSet.getTimestamp(1).toInstant());
        assertNotNull(effectiveFrom);
        assertTrue(effectiveFrom.isBefore(Instant.now()));
    }

    @Test
    void realMysqlAndRedisPersistAiControlFactsAndHashChain() throws Exception {
        String product = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>)
                connection -> connection.getMetaData().getDatabaseProductName());
        assertNotNull(product);
        assertTrue(product.toLowerCase().contains("mysql"), product);
        Integer aiTables = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema=DATABASE() AND LEFT(LOWER(table_name), 3) = 'ai_'", Integer.class);
        assertEquals(44, aiTables);

        String redisKey = "dormitory:ai:real-it:" + UUID.randomUUID();
        redis.opsForValue().set(redisKey, "isolated", Duration.ofMinutes(1));
        assertEquals("isolated", redis.opsForValue().get(redisKey));
        assertTrue(Boolean.TRUE.equals(redis.delete(redisKey)));

        byte[] auditKey = "real-ai-audit-it-key-32-bytes-v1".getBytes(StandardCharsets.UTF_8);
        JdbcAiAuditChainRepository audit = new JdbcAiAuditChainRepository(
                jdbc, transactions, version -> version == 1 ? auditKey : null);
        String aggregateId = UUID.randomUUID().toString();
        String correlationId = UUID.randomUUID().toString();
        String payloadHash = "a".repeat(64);
        audit.append(new JdbcAiAuditChainRepository.AuditAppendCommand(
                "REAL_IT", "AI_REAL_IT", aggregateId, "CONTROL_FACT_VERIFIED",
                ActorDescriptor.system("AI_REAL_IT"), null, 1, null, payloadHash,
                correlationId, Instant.now(), 1));
        assertTrue(audit.verify("REAL_IT", "AI_REAL_IT", aggregateId));

        Long adminId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username=?", Long.class,
                RealInfrastructureIT.RootEnvInitializer.rootEnvironment().get("BOOTSTRAP_ADMIN_USERNAME"));
        assertNotNull(adminId);
        String resourceId = UUID.randomUUID().toString();
        JdbcAiIdempotencyRepository.Reservation reservation = idempotency.reserve(
                new JdbcAiIdempotencyRepository.Scope(adminId, "AI_REAL_IT", resourceId,
                        "real-it-" + UUID.randomUUID()), "b".repeat(64), Instant.now().plusSeconds(60));
        assertEquals(JdbcAiIdempotencyRepository.ReservationStatus.CREATED, reservation.status());
        idempotency.complete(reservation.recordId(), 200, resourceId);
        assertEquals(200, idempotency.completedResponse(reservation.recordId()).orElseThrow().status());

        jdbc.update("DELETE FROM ai_idempotency_record WHERE id=?", reservation.recordId());
        jdbc.update("DELETE FROM ai_audit_event WHERE aggregate_public_id=?", aggregateId);
        jdbc.update("DELETE FROM ai_audit_chain_head WHERE aggregate_public_id=?", aggregateId);
    }

    @Test
    void redisRateLimitIsAtomicAndSharedAcrossApplicationInstances() {
        String actorKey = "dormitory:ai:rate-limit:real-it:" + UUID.randomUUID() + ":actor";
        String ipKey = actorKey + ":ip";
        RedisAiRateLimitStore firstInstance = new RedisAiRateLimitStore(redis);
        RedisAiRateLimitStore secondInstance = new RedisAiRateLimitStore(redis);
        List<AiRateLimitStore.Bucket> buckets = List.of(
                new AiRateLimitStore.Bucket(actorKey, 1, Duration.ofMinutes(1)),
                new AiRateLimitStore.Bucket(ipKey, 5, Duration.ofMinutes(1)));
        try {
            assertTrue(firstInstance.consume(buckets).allowed());
            AiRateLimitStore.Decision denied = secondInstance.consume(buckets);
            assertTrue(!denied.allowed());
            assertTrue(denied.resetAfterSeconds() > 0);
        } finally {
            redis.delete(List.of(actorKey, ipKey));
        }
    }

    @Test
    void realMysqlOutboxCasAllowsOneWorkerAndRecoversExpiredLease() throws Exception {
        String aggregate = UUID.randomUUID().toString();
        Instant now = Instant.ofEpochSecond(Instant.now().getEpochSecond(), 123_456_999);
        String eventId = outbox.enqueueOnce("real-it|" + aggregate,
                new JdbcAiOutboxRepository.OutboxDraft(
                        "REAL_IT", aggregate, "RealInfrastructureRequested.v1", "{}",
                        ActorDescriptor.service("real-it-outbox", 1L, 1L), now));
        Instant storedAvailableAt = jdbc.queryForObject(
                "SELECT available_at FROM ai_outbox_event WHERE public_id=?",
                (resultSet, row) -> resultSet.getTimestamp(1).toInstant(), eventId);
        assertEquals(now.truncatedTo(ChronoUnit.MICROS), storedAvailableAt);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var claims = List.of("mysql-worker-a", "mysql-worker-b").stream().map(worker -> executor.submit(() -> {
                ready.countDown();
                start.await();
                return outbox.claimNext(worker, now, Set.of("RealInfrastructureRequested.v1"));
            })).toList();
            ready.await();
            start.countDown();
            long winners = 0;
            for (var claim : claims) if (claim.get().isPresent()) winners++;
            assertEquals(1, winners);
            assertEquals(1, outbox.recoverExpiredLeases(now.plusSeconds(130), Duration.ofMinutes(2)));
            var recovered = outbox.claimNext("mysql-worker-restarted", now.plusSeconds(131),
                    Set.of("RealInfrastructureRequested.v1")).orElseThrow();
            assertEquals(eventId, recovered.publicId());
            assertTrue(outbox.markSucceeded(eventId, recovered.lockedBy(), now.plusSeconds(132)));
        } finally {
            executor.shutdownNow();
            jdbc.update("DELETE FROM ai_outbox_event WHERE public_id=?", eventId);
        }
    }

    @Test
    void realMysqlExpiresUnusedBudgetReservationOnceAcrossConcurrentInstances() throws Exception {
        Instant expiresAt = Instant.now().plusSeconds(60);
        String subjectId = UUID.randomUUID().toString();
        BillingSubject subject = new BillingSubject(BillingSubject.Kind.EVAL, subjectId);
        long firstBucket = insertBudgetBucket("real-expiry-a-" + subjectId, expiresAt);
        long secondBucket = insertBudgetBucket("real-expiry-b-" + subjectId, expiresAt);
        budgets.reserve(subject, List.of(firstBucket, secondBucket), 1_000,
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
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ai_budget_reservation "
                    + "WHERE billing_subject_public_id=? AND state='EXPIRED'", Integer.class, subjectId));
            assertEquals(0L, jdbc.queryForObject(
                    "SELECT SUM(reserved_tokens) FROM ai_budget_bucket WHERE id IN (?,?)",
                    Long.class, firstBucket, secondBucket));
        } finally {
            executor.shutdownNow();
            jdbc.update("DELETE FROM ai_budget_reservation WHERE billing_subject_public_id=?", subjectId);
            jdbc.update("DELETE FROM ai_budget_bucket WHERE id IN (?,?)", firstBucket, secondBucket);
        }
    }

    @Test
    void redisSseLeaseIsAtomicSharedRenewableAndReleasableAcrossInstances() {
        String prefix = "dormitory:ai:sse:real-it:" + UUID.randomUUID();
        String actor = "a".repeat(64);
        String redisKey = prefix + ":actor:" + actor;
        RedisAiSseConnectionLeaseStore first = new RedisAiSseConnectionLeaseStore(redis, prefix);
        RedisAiSseConnectionLeaseStore second = new RedisAiSseConnectionLeaseStore(redis, prefix);
        try {
            var lease = first.acquire(actor, 1, Duration.ofSeconds(30)).orElseThrow();
            assertFalse(second.acquire(actor, 1, Duration.ofSeconds(30)).isPresent());
            assertTrue(second.renew(lease));
            second.release(lease);
            assertTrue(second.acquire(actor, 1, Duration.ofSeconds(30)).isPresent());
        } finally {
            redis.delete(redisKey);
        }
    }

    private long insertBudgetBucket(String scopeKey, Instant periodStart) {
        jdbc.update("INSERT INTO ai_budget_bucket "
                        + "(quota_policy_id,scope_type,scope_key,capability,provider_code,period_type,"
                        + "period_start,period_end,token_limit,cost_limit,reserved_tokens,committed_tokens,"
                        + "reserved_cost,committed_cost,currency,version,created_at,updated_at) "
                        + "VALUES (?,'SERVICE',?,'EVALUATION','offline','TEST',?,?,10000,10,0,0,0,0,"
                        + "'CNY',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                Math.abs(UUID.randomUUID().getMostSignificantBits()), scopeKey,
                Timestamp.from(periodStart.minusSeconds(60)), Timestamp.from(periodStart.plusSeconds(3600)));
        return jdbc.queryForObject("SELECT id FROM ai_budget_bucket WHERE scope_key=?", Long.class, scopeKey);
    }
}
