package com.example.dormitory.ai.persistence;

import com.example.dormitory.ai.application.control.BillingSubject;
import com.example.dormitory.ai.application.control.BudgetExceededException;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiBudgetService;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiUsageLedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ActiveProfiles("test")
@SpringBootTest
class JdbcAiBudgetAndUsageTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JdbcAiBudgetService budgetService;

    @Autowired
    private JdbcAiUsageLedgerRepository usageRepository;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM ai_usage_ledger");
        jdbcTemplate.update("DELETE FROM ai_budget_reservation");
        jdbcTemplate.update("DELETE FROM ai_budget_bucket");
    }

    @Test
    void concurrentReservationsCannotBothCrossTheMysqlAuthoritativeLimit() throws Exception {
        long bucketId = insertBucket(100, "10.000000");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<String> first = reserveTask(bucketId, ready, start);
            Callable<String> second = reserveTask(bucketId, ready, start);
            var futureA = executor.submit(first);
            var futureB = executor.submit(second);
            ready.await();
            start.countDown();
            List<String> winners = java.util.stream.Stream.of(futureA.get(), futureB.get())
                    .filter(value -> value != null)
                    .toList();

            assertEquals(1, winners.size());
            assertEquals(80L, longValue("SELECT reserved_tokens FROM ai_budget_bucket WHERE id = ?", bucketId));
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ai_budget_reservation", Integer.class));

            budgetService.release(new BillingSubject(BillingSubject.Kind.RUN, winners.getFirst()));
            assertEquals(0L, longValue("SELECT reserved_tokens FROM ai_budget_bucket WHERE id = ?", bucketId));
            assertEquals("RELEASED", jdbcTemplate.queryForObject(
                    "SELECT state FROM ai_budget_reservation", String.class));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void multiBucketReservationIsAllOrNothingAndCommitReconcilesReservedAmounts() {
        long roomy = insertBucket(100, "10.000000");
        long tooSmall = insertBucket(50, "5.000000");
        BillingSubject failed = new BillingSubject(BillingSubject.Kind.EVAL, UUID.randomUUID().toString());

        assertThrows(BudgetExceededException.class, () -> budgetService.reserve(
                failed,
                List.of(roomy, tooSmall),
                80,
                new BigDecimal("8.000000"),
                Instant.now().plusSeconds(600)));
        assertEquals(0L, longValue("SELECT reserved_tokens FROM ai_budget_bucket WHERE id = ?", roomy));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_budget_reservation", Integer.class));

        BillingSubject committed = new BillingSubject(BillingSubject.Kind.RUN, UUID.randomUUID().toString());
        budgetService.reserve(
                committed,
                List.of(roomy),
                60,
                new BigDecimal("6.000000"),
                Instant.now().plusSeconds(600));
        budgetService.commit(committed, 55, new BigDecimal("5.500000"));

        assertEquals(0L, longValue("SELECT reserved_tokens FROM ai_budget_bucket WHERE id = ?", roomy));
        assertEquals(55L, longValue("SELECT committed_tokens FROM ai_budget_bucket WHERE id = ?", roomy));
        assertEquals(new BigDecimal("5.500000"), jdbcTemplate.queryForObject(
                "SELECT committed_cost FROM ai_budget_bucket WHERE id = ?",
                BigDecimal.class,
                roomy));
        assertEquals("COMMITTED", jdbcTemplate.queryForObject(
                "SELECT state FROM ai_budget_reservation WHERE billing_subject_public_id = ?",
                String.class,
                committed.publicId()));
    }

    @Test
    void everyPhysicalAttemptIsAppendOnlyAndUniquelyAddressed() {
        BillingSubject subject = new BillingSubject(BillingSubject.Kind.RUN, UUID.randomUUID().toString());
        var first = usageAttempt(subject, 1);
        var retry = usageAttempt(subject, 2);

        usageRepository.append(first);
        usageRepository.append(retry);

        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_usage_ledger", Integer.class));
        assertThrows(DuplicateKeyException.class, () -> usageRepository.append(retry));
        assertEquals(List.of(1, 2), jdbcTemplate.queryForList(
                "SELECT attempt_no FROM ai_usage_ledger ORDER BY attempt_no", Integer.class));
    }

    private Callable<String> reserveTask(long bucketId, CountDownLatch ready, CountDownLatch start) {
        return () -> {
            String subjectId = UUID.randomUUID().toString();
            ready.countDown();
            start.await();
            try {
                budgetService.reserve(
                        new BillingSubject(BillingSubject.Kind.RUN, subjectId),
                        List.of(bucketId),
                        80,
                        new BigDecimal("8.000000"),
                        Instant.now().plusSeconds(600));
                return subjectId;
            } catch (BudgetExceededException expected) {
                return null;
            }
        };
    }

    private JdbcAiUsageLedgerRepository.UsageAttempt usageAttempt(BillingSubject subject, int attempt) {
        return new JdbcAiUsageLedgerRepository.UsageAttempt(
                subject,
                1,
                attempt,
                "MODEL_COMPLETE",
                ActorDescriptor.user(7L),
                "ASSISTANT",
                "fake",
                "fake-v1",
                null,
                null,
                12,
                8,
                new BigDecimal("0.001000"),
                "CNY",
                "ESTIMATED",
                Instant.now());
    }

    private long insertBucket(long tokenLimit, String costLimit) {
        jdbcTemplate.update(
                "INSERT INTO ai_budget_bucket "
                        + "(quota_policy_id, scope_type, scope_key, capability, provider_code, period_type, "
                        + "period_start, period_end, token_limit, cost_limit, reserved_tokens, committed_tokens, "
                        + "reserved_cost, committed_cost, currency, version, created_at, updated_at) "
                        + "VALUES (?, 'USER', ?, 'ASSISTANT', 'fake', 'DAILY', ?, ?, ?, ?, 0, 0, 0, 0, 'CNY', 0, ?, ?)",
                System.nanoTime(),
                UUID.randomUUID().toString(),
                Timestamp.from(Instant.now().minusSeconds(60)),
                Timestamp.from(Instant.now().plusSeconds(3600)),
                tokenLimit,
                new BigDecimal(costLimit),
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()));
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM ai_budget_bucket", Long.class);
    }

    private long longValue(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
