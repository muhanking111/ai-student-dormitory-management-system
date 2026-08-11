package com.example.dormitory.ai.security;

import com.example.dormitory.ai.application.run.AiActorContext;
import com.example.dormitory.ai.approval.ActionProposalService;
import com.example.dormitory.ai.approval.ActionType;
import com.example.dormitory.ai.approval.CanonicalJsonHasher;
import com.example.dormitory.ai.domain.model.BusinessExecutionActor;
import com.example.dormitory.ai.port.AiAuditPort;
import com.example.dormitory.service.OperationsService;
import com.example.dormitory.service.RbacService;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.time.Instant;

/** 所有 proposal 读取、决策与恢复共用的 fresh-RBAC/对象级授权策略。 */
@Component
public final class ActionAuthorizationPolicy {

    private final ActorAuthorizationFacade actors;
    private final OperationsService operations;
    private final AiAuditPort audit;
    public ActionAuthorizationPolicy(
            ActorAuthorizationFacade actors,
            OperationsService operations,
            AiAuditPort audit) {
        this.actors = java.util.Objects.requireNonNull(actors);
        this.operations = java.util.Objects.requireNonNull(operations);
        this.audit = java.util.Objects.requireNonNull(audit);
    }

    public boolean canAccess(AiActorContext requestActor, ActionProposalService.ProposalView proposal) {
        if (requestActor == null || proposal == null || requestActor.userId() < 1) return false;
        return canAccess(requestActor.userId(), proposal);
    }

    public Set<String> requireFreshExecutionAccess(
            BusinessExecutionActor actor,
            ActionProposalService.ProposalView proposal) {
        if (actor == null || proposal == null) {
            throw com.example.dormitory.ai.api.AiApiException.notFound();
        }
        RbacService.AuthorizationSnapshot fresh = actors.snapshotForUpdate(actor.userId());
        if (!canAccess(fresh, proposal)) {
            auditDenied(actor.descriptor(), proposal, "FRESH_EXECUTION");
            throw new FreshAuthorizationDeniedException(
                    com.example.dormitory.ai.api.AiApiException.notFound());
        }
        return Set.copyOf(fresh.permissionCodes());
    }

    private boolean canAccess(long actorUserId, ActionProposalService.ProposalView proposal) {
        RbacService.AuthorizationSnapshot fresh = actors.snapshot(actorUserId);
        return canAccess(fresh, proposal);
    }

    private boolean canAccess(
            RbacService.AuthorizationSnapshot fresh,
            ActionProposalService.ProposalView proposal) {
        if (!fresh.enabled()) return false;
        Set<String> permissions = Set.copyOf(fresh.permissionCodes());
        if (!permissions.contains("ai:approval:review")) return false;
        if (proposal.actionType() == ActionType.NOTICE_CREATE_DRAFT) {
            return "NOTICE".equals(proposal.targetType())
                    && proposal.targetResourceId() == null
                    && "notice:write".equals(proposal.requiredBusinessPermission())
                    && permissions.contains("notice:write");
        }
        return proposal.actionType() == ActionType.REPAIR_ASSIGN
                && "REPAIR_ORDER".equals(proposal.targetType())
                && proposal.targetResourceId() != null
                && proposal.targetResourceId() > 0
                && "repair:write".equals(proposal.requiredBusinessPermission())
                && fresh.roleCodes().contains("ADMIN")
                && permissions.contains("repair:write")
                && operations.repairAssignmentSnapshot(proposal.targetResourceId()).isPresent();
    }

    public void requireAccess(AiActorContext actor, ActionProposalService.ProposalView proposal) {
        requireAccess(actor, proposal, "ACCESS");
    }

    public void requireAccess(
            AiActorContext actor,
            ActionProposalService.ProposalView proposal,
            String operation) {
        if (!canAccess(actor, proposal)) {
            if (actor != null && proposal != null) {
                auditDenied(actor.actor(), proposal, operation);
            }
            throw com.example.dormitory.ai.api.AiApiException.notFound();
        }
    }

    private void auditDenied(
            com.example.dormitory.ai.domain.model.ActorDescriptor actor,
            ActionProposalService.ProposalView proposal,
            String operation) {
        if (operation == null || !operation.matches("[A-Z_]{3,32}")) {
            throw new IllegalArgumentException("提案访问操作码不合法");
        }
        String payloadHash = CanonicalJsonHasher.sha256(
                "proposal-object-denial.v1|" + operation + "|" + proposal.publicId()
                        + "|" + proposal.actionType() + "|" + proposal.targetType()
                        + "|" + proposal.targetResourceId());
        audit.append(new AiAuditPort.AiAuditEvent(
                "ACTION_PROPOSAL", proposal.publicId(), "PROPOSAL_OBJECT_ACCESS_DENIED",
                actor, payloadHash, Instant.now()));
    }
}
