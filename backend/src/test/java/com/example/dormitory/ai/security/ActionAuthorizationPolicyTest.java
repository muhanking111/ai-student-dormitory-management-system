package com.example.dormitory.ai.security;

import com.example.dormitory.ai.application.run.AiActorContext;
import com.example.dormitory.ai.approval.ActionProposalService;
import com.example.dormitory.ai.approval.ActionType;
import com.example.dormitory.ai.approval.ProposalState;
import com.example.dormitory.ai.port.AiAuditPort;
import com.example.dormitory.service.OperationsService;
import com.example.dormitory.service.RbacService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActionAuthorizationPolicyTest {

    @Test
    void repairProposalRequiresFreshAdminWritePermissionAndExistingTarget() {
        ActorAuthorizationFacade facade = mock(ActorAuthorizationFacade.class);
        OperationsService operations = mock(OperationsService.class);
        ActionAuthorizationPolicy policy = new ActionAuthorizationPolicy(
                facade, operations, mock(AiAuditPort.class));
        AiActorContext actor = actor(Set.of("ai:approval:review", "repair:write"));
        when(facade.snapshot(7L)).thenReturn(new RbacService.AuthorizationSnapshot(
                7L, true, List.of("ADMIN"), List.of("ai:approval:review", "repair:write")));
        when(operations.repairAssignmentSnapshot(91L)).thenReturn(Optional.of(
                new OperationsService.RepairAssignmentSnapshot(91L, "待处理", null, null)));

        assertTrue(policy.canAccess(actor, proposal(ActionType.REPAIR_ASSIGN, "REPAIR_ORDER", 91L,
                "repair:write")));
        when(facade.snapshot(7L)).thenReturn(new RbacService.AuthorizationSnapshot(
                7L, true, List.of("REPAIRER"), List.of("ai:approval:review", "repair:write")));
        assertFalse(policy.canAccess(actor, proposal(ActionType.REPAIR_ASSIGN, "REPAIR_ORDER", 91L,
                "repair:write")));
    }

    @Test
    void noticeProposalKeepsNoticeWriteContractAndRejectsStalePermissionSnapshot() {
        ActorAuthorizationFacade facade = mock(ActorAuthorizationFacade.class);
        ActionAuthorizationPolicy policy = new ActionAuthorizationPolicy(
                facade, mock(OperationsService.class), mock(AiAuditPort.class));
        AiActorContext actor = actor(Set.of("ai:approval:review", "notice:write"));
        when(facade.snapshot(7L)).thenReturn(new RbacService.AuthorizationSnapshot(
                7L, true, List.of("ADMIN"), List.of("ai:approval:review")));

        assertFalse(policy.canAccess(actor, proposal(ActionType.NOTICE_CREATE_DRAFT, "NOTICE", null,
                "notice:write")));
    }

    @Test
    void directObjectDenialsAreAuditedWithTheRequestedOperationBeforeReturningHiddenNotFound() {
        ActorAuthorizationFacade facade = mock(ActorAuthorizationFacade.class);
        AiAuditPort audit = mock(AiAuditPort.class);
        ActionAuthorizationPolicy policy = new ActionAuthorizationPolicy(
                facade, mock(OperationsService.class), audit);
        AiActorContext actor = actor(Set.of("ai:approval:review"));
        ActionProposalService.ProposalView proposal = proposal(
                ActionType.NOTICE_CREATE_DRAFT, "NOTICE", null, "notice:write");
        when(facade.snapshot(7L)).thenReturn(new RbacService.AuthorizationSnapshot(
                7L, true, List.of("ADMIN"), List.of("ai:approval:review")));

        for (String operation : List.of("GET", "APPROVE", "REJECT", "RECONFIRM")) {
            assertThrows(RuntimeException.class, () -> policy.requireAccess(actor, proposal, operation));
        }

        var events = org.mockito.ArgumentCaptor.forClass(AiAuditPort.AiAuditEvent.class);
        verify(audit, times(4)).append(events.capture());
        assertTrue(events.getAllValues().stream().allMatch(event ->
                event.eventType().equals("PROPOSAL_OBJECT_ACCESS_DENIED")
                        && event.aggregatePublicId().equals(proposal.publicId())
                        && event.actor().actorUserId().equals(7L)));
        assertEquals(4, events.getAllValues().stream()
                .map(AiAuditPort.AiAuditEvent::payloadRedactedHash).distinct().count());
    }

    @Test
    void freshRbacDenialAppendsAuditBeforeRaisingCommitMarker() {
        ActorAuthorizationFacade facade = mock(ActorAuthorizationFacade.class);
        AiAuditPort audit = mock(AiAuditPort.class);
        ActionAuthorizationPolicy policy = new ActionAuthorizationPolicy(
                facade, mock(OperationsService.class), audit);
        ActionProposalService.ProposalView proposal = proposal(
                ActionType.NOTICE_CREATE_DRAFT, "NOTICE", null, "notice:write");
        when(facade.snapshotForUpdate(7L)).thenReturn(new RbacService.AuthorizationSnapshot(
                7L, true, List.of("ADMIN"), List.of("ai:approval:review")));

        FreshAuthorizationDeniedException denial = assertThrows(
                FreshAuthorizationDeniedException.class, () -> policy.requireFreshExecutionAccess(
                com.example.dormitory.ai.domain.model.BusinessExecutionActor.from(
                        com.example.dormitory.ai.domain.model.ActorDescriptor.user(7L)), proposal));

        assertEquals("AI_RESOURCE_NOT_FOUND", denial.hiddenFailure().errorCode());
        verify(facade).snapshotForUpdate(7L);
        verify(audit).append(any(AiAuditPort.AiAuditEvent.class));
    }

    private AiActorContext actor(Set<String> permissions) {
        return new AiActorContext(7L, "session", "fingerprint", 1, "digest",
                List.of("ADMIN"), List.copyOf(permissions),
                com.example.dormitory.ai.domain.model.ActorDescriptor.user(7L));
    }

    private ActionProposalService.ProposalView proposal(
            ActionType type, String targetType, Long targetId, String permission) {
        return new ActionProposalService.ProposalView("123e4567-e89b-12d3-a456-426614174000", type,
                targetType, targetId, com.example.dormitory.ai.approval.ProposalPreview.legacy("preview"),
                "a".repeat(64), "b".repeat(64), permission,
                1, 0, "LOW", ProposalState.PENDING_APPROVAL, 0, Instant.now().plusSeconds(60), null);
    }
}
