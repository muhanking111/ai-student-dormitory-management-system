package com.example.dormitory.ai.persistence;

import com.example.dormitory.ai.application.control.BillingSubject;
import com.example.dormitory.ai.application.control.BudgetExceededException;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiBudgetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ActiveProfiles("test")
@SpringBootTest(properties =
        "dormitory.ai.audit.hmac-key=0123456789abcdef0123456789abcdef")
class JdbcAiBudgetServiceEdgeCasesTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JdbcAiBudgetService budgets;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM ai_usage_ledger");
        jdbc.update("DELETE FROM ai_provider_attempt");
        jdbc.update("DELETE FROM ai_budget_reservation");
        jdbc.update("DELETE FROM ai_budget_bucket");
    }

    @Test
    void commitRejectsTokenAndCostFactsAboveTheReservedMaximum() {
        BillingSubject tokenSubject = subject();
        budgets.reserve(tokenSubject, List.of(insertBucket()), 100,
                new BigDecimal("0.100000"), Instant.now().plusSeconds(60));
        assertThrows(BudgetExceededException.class,
                () -> budgets.commit(tokenSubject, 101, new BigDecimal("0.100000")));

        BillingSubject costSubject = subject();
        budgets.reserve(costSubject, List.of(insertBucket()), 100,
                new BigDecimal("0.100000"), Instant.now().plusSeconds(60));
        assertThrows(BudgetExceededException.class,
                () -> budgets.commit(costSubject, 100, new BigDecimal("0.100001")));
    }

    @Test
    void activeProviderAttemptBlocksBothCommitAndRelease() {
        BillingSubject subject = subject();
        budgets.reserve(subject, List.of(insertBucket()), 100,
                new BigDecimal("0.100000"), Instant.now().plusSeconds(60));
        insertStartedAttempt(subject);

        assertThrows(JdbcAiBudgetService.AttemptReconciliationRequiredException.class,
                () -> budgets.commit(subject, 50, new BigDecimal("0.050000")));
        assertThrows(JdbcAiBudgetService.AttemptReconciliationRequiredException.class,
                () -> budgets.release(subject));
    }

    @Test
    void expiryRejectsMixedStatesAndKeepsSubjectsWithAnyFutureReservation() {
        Instant observedAt = Instant.now();
        BillingSubject futureSubject = subject();
        long futureFirst = insertBucket();
        long futureSecond = insertBucket();
        budgets.reserve(futureSubject, List.of(futureFirst, futureSecond), 100,
                new BigDecimal("0.100000"), observedAt.minusSeconds(10));
        jdbc.update("UPDATE ai_budget_reservation SET expires_at=? WHERE billing_subject_public_id=? "
                        + "AND budget_bucket_id=?",
                Timestamp.from(observedAt.plusSeconds(60)), futureSubject.publicId(), futureSecond);

        assertEquals(0, budgets.expireDueReservations(observedAt, 100));

        BillingSubject mixedSubject = subject();
        long mixedFirst = insertBucket();
        long mixedSecond = insertBucket();
        budgets.reserve(mixedSubject, List.of(mixedFirst, mixedSecond), 100,
                new BigDecimal("0.100000"), observedAt.minusSeconds(10));
        jdbc.update("UPDATE ai_budget_reservation SET state='RELEASED' "
                        + "WHERE billing_subject_public_id=? AND budget_bucket_id=?",
                mixedSubject.publicId(), mixedFirst);

        assertThrows(IllegalStateException.class,
                () -> budgets.expireDueReservations(observedAt, 100));
    }

    @Test
    void existingProviderUsagePreventsAutomaticReservationExpiry() {
        Instant observedAt = Instant.now();
        BillingSubject subject = subject();
        budgets.reserve(subject, List.of(insertBucket()), 100,
                new BigDecimal("0.100000"), observedAt.minusSeconds(10));
        insertUsage(subject, observedAt.minusSeconds(5));

        assertEquals(0, budgets.expireDueReservations(observedAt, 100));
        assertEquals("RESERVED", jdbc.queryForObject(
                "SELECT state FROM ai_budget_reservation WHERE billing_subject_public_id=?",
                String.class, subject.publicId()));
    }

    @Test
    void releasedReservationCannotCommitAndCommittedReplayMustMatchCost() {
        BillingSubject released = subject();
        budgets.reserve(released, List.of(insertBucket()), 100,
                new BigDecimal("0.100000"), Instant.now().plusSeconds(60));
        budgets.release(released);
        assertFalse(budgets.commitIfPresent(released, 0, BigDecimal.ZERO));

        BillingSubject committed = subject();
        budgets.reserve(committed, List.of(insertBucket()), 100,
                new BigDecimal("0.100000"), Instant.now().plusSeconds(60));
        budgets.commit(committed, 50, new BigDecimal("0.050000"));
        assertThrows(IllegalStateException.class,
                () -> budgets.commitIfPresent(committed, 50, new BigDecimal("0.040000")));
    }

    private BillingSubject subject() {
        return new BillingSubject(BillingSubject.Kind.RUN, UUID.randomUUID().toString());
    }

    private long insertBucket() {
        jdbc.update("INSERT INTO ai_budget_bucket "
                        + "(quota_policy_id,scope_type,scope_key,capability,provider_code,period_type,"
                        + "period_start,period_end,token_limit,cost_limit,reserved_tokens,committed_tokens,"
                        + "reserved_cost,committed_cost,currency,version,created_at,updated_at) "
                        + "VALUES (?, 'USER', ?, 'ASSISTANT', 'fake', 'DAILY', ?, ?, 1000, 1, 0, 0, 0, 0, "
                        + "'CNY', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                System.nanoTime(), UUID.randomUUID().toString(),
                Timestamp.from(Instant.now().minusSeconds(60)),
                Timestamp.from(Instant.now().plusSeconds(3600)));
        return jdbc.queryForObject("SELECT MAX(id) FROM ai_budget_bucket", Long.class);
    }

    private void insertStartedAttempt(BillingSubject subject) {
        Instant now = Instant.now();
        jdbc.update("INSERT INTO ai_provider_attempt "
                        + "(public_id,billing_subject_kind,billing_subject_public_id,request_sequence_no,attempt_no,"
                        + "request_kind,actor_kind,actor_user_id,initiated_by_user_id,effective_subject_user_id,"
                        + "capability,provider_code,model_name,pricing_version_id,currency,input_cost_per_million,"
                        + "output_cost_per_million,estimation_policy_version,state,owner_instance_id,lease_expires_at,"
                        + "started_at,created_at,updated_at) "
                        + "VALUES(?,?,?,1,1,'MODEL_STREAM','USER',7,7,7,'ASSISTANT','fake','fake-model',1,'CNY',"
                        + "0,0,'test-v1','STARTED','test-instance',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(), subject.kind().name(), subject.publicId(),
                Timestamp.from(now.plusSeconds(60)), Timestamp.from(now));
    }

    private void insertUsage(BillingSubject subject, Instant occurredAt) {
        jdbc.update("INSERT INTO ai_usage_ledger "
                        + "(billing_subject_kind,billing_subject_public_id,request_sequence_no,attempt_no,request_kind,"
                        + "actor_kind,actor_user_id,initiated_by_user_id,effective_subject_user_id,capability,"
                        + "provider_code,model_name,input_tokens,output_tokens,cost_amount,currency,usage_source,"
                        + "attempt_outcome,duration_ms,occurred_at,created_at) "
                        + "VALUES(?,?,1,1,'MODEL_STREAM','USER',7,7,7,'ASSISTANT','fake','fake-model',1,1,0,"
                        + "'CNY','ACTUAL','SUCCEEDED',1,?,CURRENT_TIMESTAMP)",
                subject.kind().name(), subject.publicId(), Timestamp.from(occurredAt));
    }
}
