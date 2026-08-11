package com.example.dormitory.ai.security;

import com.example.dormitory.ai.application.run.AiActorContext;
import com.example.dormitory.ai.approval.ActionProposalService;
import com.example.dormitory.ai.approval.ActionType;
import com.example.dormitory.ai.approval.ProposalPreview;
import com.example.dormitory.ai.approval.ProposalState;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.BusinessExecutionActor;
import com.example.dormitory.ai.port.AiAuditPort;
import com.example.dormitory.service.OperationsService;
import com.example.dormitory.service.RbacService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActionAuthorizationPolicyBoundaryTest {

    private final ActorAuthorizationFacade actors = mock(ActorAuthorizationFacade.class);
    private final OperationsService operations = mock(OperationsService.class);
    private final ActionAuthorizationPolicy policy = new ActionAuthorizationPolicy(
            actors, operations, mock(AiAuditPort.class));

    @BeforeEach
    void defaultFreshAuthorization() {
        RbacService.AuthorizationSnapshot defaultSnapshot = snapshot(
                true, List.of("ADMIN"), List.of("ai:approval:review", "notice:write", "repair:write"));
        when(actors.snapshot(7L)).thenReturn(defaultSnapshot);
        when(actors.snapshotForUpdate(7L)).thenReturn(defaultSnapshot);
        when(operations.repairAssignmentSnapshot(91L)).thenReturn(Optional.of(
                new OperationsService.RepairAssignmentSnapshot(91L, "待处理", null, null)));
    }

    @Test
    void canAccessRejectsMissingProposalAndNonPositiveRequestActorBeforeRbacLookup() {
        assertAll(
                () -> assertFalse(policy.canAccess(null, repairProposal("REPAIR_ORDER", 91L, "repair:write"))),
                () -> assertFalse(policy.canAccess(actor(7L), null)),
                () -> assertFalse(policy.canAccess(actor(0L), repairProposal(
                        "REPAIR_ORDER", 91L, "repair:write"))));
    }

    @Test
    void freshExecutionAccessRejectsMissingOrDisabledActorsAndReturnsOnlyFreshPermissions() {
        BusinessExecutionActor executionActor = BusinessExecutionActor.from(ActorDescriptor.user(7));
        ActionProposalService.ProposalView notice = noticeProposal("NOTICE", null, "notice:write");

        assertAll(
                () -> assertThrows(RuntimeException.class,
                        () -> policy.requireFreshExecutionAccess(null, notice)),
                () -> assertThrows(RuntimeException.class,
                        () -> policy.requireFreshExecutionAccess(executionActor, null)));

        when(actors.snapshotForUpdate(7L)).thenReturn(snapshot(
                false, List.of("ADMIN"), List.of("ai:approval:review", "notice:write")));
        assertThrows(RuntimeException.class,
                () -> policy.requireFreshExecutionAccess(executionActor, notice));

        when(actors.snapshotForUpdate(7L)).thenReturn(snapshot(
                true, List.of("ADMIN"), List.of("ai:approval:review", "notice:write")));
        assertEquals(Set.of("ai:approval:review", "notice:write"),
                policy.requireFreshExecutionAccess(executionActor, notice));
    }

    @Test
    void noticeAuthorizationRequiresEveryFixedContractFact() {
        assertAll(
                () -> assertFalse(policy.canAccess(actor(7L), noticeProposal(
                        "OTHER", null, "notice:write"))),
                () -> assertFalse(policy.canAccess(actor(7L), noticeProposal(
                        "NOTICE", 91L, "notice:write"))),
                () -> assertFalse(policy.canAccess(actor(7L), noticeProposal(
                        "NOTICE", null, "repair:write"))));

        when(actors.snapshot(7L)).thenReturn(snapshot(
                true, List.of("ADMIN"), List.of("ai:approval:review")));
        assertFalse(policy.canAccess(actor(7L), noticeProposal("NOTICE", null, "notice:write")));

        when(actors.snapshot(7L)).thenReturn(snapshot(
                true, List.of("ADMIN"), List.of("notice:write")));
        assertFalse(policy.canAccess(actor(7L), noticeProposal("NOTICE", null, "notice:write")));
    }

    @Test
    void repairAuthorizationRequiresEveryFixedContractFactAndCurrentResource() {
        assertAll(
                () -> assertFalse(policy.canAccess(actor(7L), proposal(
                        null, "REPAIR_ORDER", 91L, "repair:write"))),
                () -> assertFalse(policy.canAccess(actor(7L), repairProposal(
                        "OTHER", 91L, "repair:write"))),
                () -> assertFalse(policy.canAccess(actor(7L), repairProposal(
                        "REPAIR_ORDER", null, "repair:write"))),
                () -> assertFalse(policy.canAccess(actor(7L), repairProposal(
                        "REPAIR_ORDER", 0L, "repair:write"))),
                () -> assertFalse(policy.canAccess(actor(7L), repairProposal(
                        "REPAIR_ORDER", 91L, "notice:write"))));

        when(actors.snapshot(7L)).thenReturn(snapshot(
                true, List.of("REPAIRER"), List.of("ai:approval:review", "repair:write")));
        assertFalse(policy.canAccess(actor(7L), repairProposal("REPAIR_ORDER", 91L, "repair:write")));

        when(actors.snapshot(7L)).thenReturn(snapshot(
                true, List.of("ADMIN"), List.of("ai:approval:review")));
        assertFalse(policy.canAccess(actor(7L), repairProposal("REPAIR_ORDER", 91L, "repair:write")));

        when(actors.snapshot(7L)).thenReturn(snapshot(
                true, List.of("ADMIN"), List.of("ai:approval:review", "repair:write")));
        when(operations.repairAssignmentSnapshot(91L)).thenReturn(Optional.empty());
        assertFalse(policy.canAccess(actor(7L), repairProposal("REPAIR_ORDER", 91L, "repair:write")));
    }

    @Test
    void requireAccessUsesTheSameHiddenNotFoundPolicyForAllowedAndDeniedRequests() {
        ActionProposalService.ProposalView notice = noticeProposal("NOTICE", null, "notice:write");

        assertDoesNotThrow(() -> policy.requireAccess(actor(7L), notice));

        when(actors.snapshot(7L)).thenReturn(snapshot(true, List.of("ADMIN"), List.of()));
        assertThrows(RuntimeException.class, () -> policy.requireAccess(actor(7L), notice));
    }

    private AiActorContext actor(long userId) {
        return new AiActorContext(userId, "session", "fingerprint", 1, "digest",
                List.of("ADMIN"), List.of("ai:approval:review"), ActorDescriptor.user(Math.max(1, userId)));
    }

    private RbacService.AuthorizationSnapshot snapshot(
            boolean enabled,
            List<String> roles,
            List<String> permissions) {
        return new RbacService.AuthorizationSnapshot(7L, enabled, roles, permissions);
    }

    private ActionProposalService.ProposalView noticeProposal(
            String targetType,
            Long targetId,
            String permission) {
        return proposal(ActionType.NOTICE_CREATE_DRAFT, targetType, targetId, permission);
    }

    private ActionProposalService.ProposalView repairProposal(
            String targetType,
            Long targetId,
            String permission) {
        return proposal(ActionType.REPAIR_ASSIGN, targetType, targetId, permission);
    }

    private ActionProposalService.ProposalView proposal(
            ActionType type,
            String targetType,
            Long targetId,
            String permission) {
        return new ActionProposalService.ProposalView(
                "123e4567-e89b-12d3-a456-426614174000", type, targetType, targetId,
                ProposalPreview.legacy("preview"), "a".repeat(64), "b".repeat(64), permission,
                1, 0, "LOW", ProposalState.PENDING_APPROVAL, 0, Instant.now().plusSeconds(60), null);
    }
}
