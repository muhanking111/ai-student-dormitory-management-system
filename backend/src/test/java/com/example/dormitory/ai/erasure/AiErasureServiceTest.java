package com.example.dormitory.ai.erasure;

import com.example.dormitory.ai.application.run.AiActorResolver;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiIdempotencyRepository;
import com.example.dormitory.ai.port.AiAuditPort;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class AiErasureServiceTest {

    @Test
    void retryableProofKeepsJobRetryableAndExhaustionMovesItToManualReview() {
        ErasureJobRepository jobs = mock(ErasureJobRepository.class);
        AiAuditPort audit = writableAudit();
        ErasureProofPort proof = mock(ErasureProofPort.class);
        when(proof.eraseAndVerify("VECTOR", "target-hash", "vector-primary"))
                .thenReturn(new ErasureProofPort.ProofResult(
                        ErasureProofPort.ProofResult.Status.RETRYABLE_FAILURE,
                        null, "VECTOR_DELETE_TIMEOUT"));
        ErasureJobRepository.Target target = new ErasureJobRepository.Target(
                7L, "VECTOR", "target-hash", "vector-primary", "PENDING", 0, null, null);
        when(jobs.targets(1L)).thenReturn(List.of(target));
        AiErasureService service = service(jobs, proof, audit);

        service.process(job(1));
        verify(jobs).markTargetFailure(eq(7L), eq("RETRYABLE_FAILED"),
                eq("VECTOR_DELETE_TIMEOUT"), any());
        verify(jobs).finish(eq(1L), eq(2), eq("RETRYABLE_FAILED"),
                eq("ERASURE_RETRY_REQUIRED"), any(), any());

        service.process(job(3));
        verify(jobs).finish(eq(1L), eq(2), eq("NEEDS_REVIEW"),
                eq("ERASURE_RETRY_REQUIRED"), any(), any());
        verify(audit, never()).append(any());
    }

    @Test
    void legalHoldRetainsEveryTargetAndNeverCallsDeletionAdapterOrClaimsSuccess() {
        ErasureJobRepository jobs = mock(ErasureJobRepository.class);
        AiAuditPort audit = writableAudit();
        ErasureProofPort proof = mock(ErasureProofPort.class);
        when(jobs.targets(1L)).thenReturn(List.of(
                new ErasureJobRepository.Target(3L, "RAW_OBJECT", "object-hash", "object-primary",
                        "PENDING", 0, null, null),
                new ErasureJobRepository.Target(4L, "VECTOR", "vector-hash", "vector-primary",
                        "PENDING", 0, null, null)));

        service(jobs, proof, audit).process(new ErasureJobRepository.Job(
                1L, "job-id", "conversation-id", 9L,
                true, "PROCESSING", 2, 1));

        verify(jobs).markTargetRetained(eq(3L), any());
        verify(jobs).markTargetRetained(eq(4L), any());
        verifyNoInteractions(proof);
        verify(jobs).finish(eq(1L), eq(2), eq("PARTIAL"), eq("LEGAL_HOLD"), any(), any());
        verify(audit, never()).append(any());
    }

    @Test
    void unavailableAuditFailsClosedBeforeAnyErasureMutation() {
        ErasureJobRepository jobs = mock(ErasureJobRepository.class);
        AiAuditPort audit = mock(AiAuditPort.class);
        when(audit.writable()).thenReturn(false);

        assertThrows(IllegalStateException.class, () ->
                service(jobs, mock(ErasureProofPort.class), audit).process(job(1)));

        verifyNoInteractions(jobs);
    }

    @Test
    void adapterVerifiedWithoutCryptographicProofFallsBackToManualReview() {
        ErasureJobRepository jobs = mock(ErasureJobRepository.class);
        AiAuditPort audit = writableAudit();
        ErasureProofPort proof = mock(ErasureProofPort.class);
        when(jobs.targets(1L)).thenReturn(List.of(new ErasureJobRepository.Target(
                8L, "VECTOR", "a".repeat(64), "vector-primary", "PENDING", 0, null, null)));
        when(proof.eraseAndVerify("VECTOR", "a".repeat(64), "vector-primary"))
                .thenReturn(new ErasureProofPort.ProofResult(
                        ErasureProofPort.ProofResult.Status.VERIFIED, null, null));

        service(jobs, proof, audit).process(job(1));

        verify(jobs).markTargetFailure(eq(8L), eq("NEEDS_REVIEW"),
                eq("ERASURE_PROOF_INVALID"), any());
        verify(jobs).finish(eq(1L), eq(2), eq("NEEDS_REVIEW"),
                eq("ERASURE_PROOF_UNAVAILABLE"), any(), any());
        verify(audit, never()).append(any());
    }

    @Test
    void mysqlRedactionWithoutVerifiedPostconditionProofFallsBackToManualReview() {
        ErasureJobRepository jobs = mock(ErasureJobRepository.class);
        AiAuditPort audit = writableAudit();
        when(jobs.targets(1L)).thenReturn(List.of(new ErasureJobRepository.Target(
                9L, "MYSQL_CONTENT", "a".repeat(64), "mysql", "PENDING", 0, null, null)));
        when(jobs.redactConversationContent(9L, "conversation-id")).thenReturn(null);

        service(jobs, mock(ErasureProofPort.class), audit).process(job(1));

        verify(jobs).markTargetFailure(eq(9L), eq("NEEDS_REVIEW"),
                eq("ERASURE_PROOF_INVALID"), any());
        verify(jobs).finish(eq(1L), eq(2), eq("NEEDS_REVIEW"),
                eq("ERASURE_PROOF_UNAVAILABLE"), any(), any());
        verify(audit, never()).append(any());
    }

    @Test
    void successfulErasureAuditsTargetBoundTombstoneAndCheckpointEvidence() {
        ErasureJobRepository jobs = mock(ErasureJobRepository.class);
        AiAuditPort audit = writableAudit();
        ErasureJobRepository.Job job = job(1);
        List<ErasureJobRepository.Target> targets = List.of(
                new ErasureJobRepository.Target(3L, "MYSQL_CONTENT", "a".repeat(64), "mysql",
                        "VERIFIED", 1, null, "b".repeat(64)),
                new ErasureJobRepository.Target(4L, "VECTOR", "c".repeat(64), "vector-primary",
                        "VERIFIED", 2, null, "d".repeat(64)));
        when(jobs.targets(job.databaseId())).thenReturn(targets);

        service(jobs, mock(ErasureProofPort.class), audit).process(job);

        ArgumentCaptor<AiAuditPort.AiAuditEvent> events =
                ArgumentCaptor.forClass(AiAuditPort.AiAuditEvent.class);
        verify(audit, times(2)).append(events.capture());
        assertEquals("ERASURE_TOMBSTONE", events.getAllValues().get(0).eventType());
        assertEquals(ErasureEvidenceHasher.hash("ERASURE_TOMBSTONE", job, targets),
                events.getAllValues().get(0).payloadRedactedHash());
        assertEquals("ERASURE_CHECKPOINT", events.getAllValues().get(1).eventType());
        assertEquals(ErasureEvidenceHasher.hash("ERASURE_CHECKPOINT", job, targets),
                events.getAllValues().get(1).payloadRedactedHash());
        assertNotEquals(events.getAllValues().get(0).payloadRedactedHash(),
                events.getAllValues().get(1).payloadRedactedHash());
        assertNotEquals(com.example.dormitory.ai.approval.CanonicalJsonHasher.sha256(
                        job.publicId() + "|tombstone"),
                events.getAllValues().get(0).payloadRedactedHash());
    }

    private AiErasureService service(ErasureJobRepository jobs, ErasureProofPort proof, AiAuditPort audit) {
        return new AiErasureService(
                mock(AiActorResolver.class), jobs,
                mock(ErasureRetentionPolicy.class), proof, audit,
                mock(JdbcAiIdempotencyRepository.class), mock(PlatformTransactionManager.class));
    }

    private AiAuditPort writableAudit() {
        AiAuditPort audit = mock(AiAuditPort.class);
        when(audit.writable()).thenReturn(true);
        return audit;
    }

    private ErasureJobRepository.Job job(int attempts) {
        return new ErasureJobRepository.Job(
                1L, "job-id", "conversation-id", 9L,
                false, "PROCESSING", 2, attempts);
    }
}
