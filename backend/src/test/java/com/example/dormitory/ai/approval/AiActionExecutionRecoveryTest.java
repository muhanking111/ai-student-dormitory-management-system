package com.example.dormitory.ai.approval;

import com.example.dormitory.ai.config.AiProperties;
import com.example.dormitory.ai.port.AiAuditPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class AiActionExecutionRecoveryTest {

    @Test
    void staleExecutionMovesToManualReviewAndWritesSystemAuditWithoutReplay() {
        ActionProposalRepository repository = mock(ActionProposalRepository.class);
        AiAuditPort audit = mock(AiAuditPort.class);
        AiProperties properties = new AiProperties();
        String proposalId = "123e4567-e89b-12d3-a456-426614174000";
        when(audit.writable()).thenReturn(true);
        when(repository.markStaleExecutionsNeedsReview(any())).thenReturn(List.of(proposalId));

        int recovered = new AiActionExecutionRecovery(repository, audit, properties)
                .recoverNow(Instant.parse("2026-07-11T12:00:00Z"));

        assertEquals(1, recovered);
        verify(audit).append(org.mockito.ArgumentMatchers.argThat(event ->
                event.aggregatePublicId().equals(proposalId)
                        && event.eventType().equals("EXECUTION_NEEDS_REVIEW")
                        && event.actor().servicePrincipalCode().equals("AI_EXECUTION_RECOVERY")));
    }

    @Test
    void auditOutageBlocksRecoveryMutation() {
        ActionProposalRepository repository = mock(ActionProposalRepository.class);
        AiAuditPort audit = mock(AiAuditPort.class);
        when(audit.writable()).thenReturn(false);
        assertThrows(IllegalStateException.class, () ->
                new AiActionExecutionRecovery(repository, audit, new AiProperties()).recoverNow(Instant.now()));
        verify(repository, never()).markStaleExecutionsNeedsReview(any());
    }

    @Test
    void periodicScanFindsLeaseThatExpiresAfterStartupWithoutReplayingIt() {
        ActionProposalRepository repository = mock(ActionProposalRepository.class);
        AiAuditPort audit = mock(AiAuditPort.class);
        AiProperties properties = new AiProperties();
        properties.setEnabled(true);
        String proposalId = "123e4567-e89b-12d3-a456-426614174000";
        when(audit.writable()).thenReturn(true);
        when(repository.markStaleExecutionsNeedsReview(any()))
                .thenReturn(List.of())
                .thenReturn(List.of(proposalId));
        AiActionExecutionRecovery recovery = new AiActionExecutionRecovery(repository, audit, properties);

        recovery.recoverPeriodically();
        recovery.recoverPeriodically();

        verify(repository, times(2)).markStaleExecutionsNeedsReview(any());
        verify(audit).append(org.mockito.ArgumentMatchers.argThat(event ->
                event.aggregatePublicId().equals(proposalId)
                        && event.eventType().equals("EXECUTION_NEEDS_REVIEW")));
    }
}
