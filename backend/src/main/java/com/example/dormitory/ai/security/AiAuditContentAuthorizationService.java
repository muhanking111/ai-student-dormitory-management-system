package com.example.dormitory.ai.security;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.application.run.AiActorContext;
import com.example.dormitory.ai.application.run.AiConversationRunStore;
import com.example.dormitory.ai.application.run.AiRunRecords.AuditAuthorizationScope;
import com.example.dormitory.ai.application.run.ContextResolverRegistry;
import com.example.dormitory.ai.domain.model.AiCapability;
import com.example.dormitory.ai.config.AiRuntimeControlService;
import com.example.dormitory.ai.knowledge.KnowledgeVersionRepository;
import com.example.dormitory.common.BusinessException;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.function.Predicate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/** 在审计正文解密前，按当前知识 ACL 与当前业务对象范围重新求权限交集。 */
@Component
public final class AiAuditContentAuthorizationService {

    private final AiConversationRunStore store;
    private final ContextResolverRegistry contexts;
    private final KnowledgeVersionRepository knowledgeVersions;
    private final Predicate<String> sourceEnabled;

    @Autowired
    public AiAuditContentAuthorizationService(
            AiConversationRunStore store,
            ContextResolverRegistry contexts,
            KnowledgeVersionRepository knowledgeVersions,
            AiRuntimeControlService controls) {
        this(store, contexts, knowledgeVersions, controls::sourceEnabled);
    }

    AiAuditContentAuthorizationService(
            AiConversationRunStore store,
            ContextResolverRegistry contexts,
            KnowledgeVersionRepository knowledgeVersions) {
        this(store, contexts, knowledgeVersions, ignored -> true);
    }

    AiAuditContentAuthorizationService(
            AiConversationRunStore store,
            ContextResolverRegistry contexts,
            KnowledgeVersionRepository knowledgeVersions,
            Predicate<String> sourceEnabled) {
        this.store = java.util.Objects.requireNonNull(store);
        this.contexts = java.util.Objects.requireNonNull(contexts);
        this.knowledgeVersions = java.util.Objects.requireNonNull(knowledgeVersions);
        this.sourceEnabled = java.util.Objects.requireNonNull(sourceEnabled);
    }

    public void authorize(AiActorContext actor, String runId, AiCapability capability) {
        AuditAuthorizationScope scope = store.auditAuthorizationScope(runId);
        Set<String> permissions = Set.copyOf(actor.permissionCodes());
        for (String versionId : scope.citationDocumentVersionIds()) {
            KnowledgeVersionRepository.KnowledgeVersion version = knowledgeVersions.findByPublicId(versionId)
                    .orElseThrow(AiApiException::notFound);
            if (!sourceEnabled.test(version.sourcePublicId())
                    || !knowledgeVersions.canRead(versionId, permissions)) {
                throw AiApiException.notFound();
            }
        }
        try {
            switch (scope.surface()) {
                case "REPAIR" -> authorizeRepair(actor, scope);
                case "DASHBOARD" -> authorizeDashboard(actor, scope);
                case "NOTICE" -> authorizeNotice(actor, scope, capability);
                case "RISK" -> requirePermission(permissions, "ai:risk:read");
                case "APPROVAL", "AUDIT" -> throw AiApiException.notFound();
                case "GLOBAL" -> requireOwner(actor, scope);
                case "KNOWLEDGE" -> {
                    // 知识正文由 citation ACL 重验；当前上层不开放知识 run 正文读取。
                }
                default -> throw AiApiException.notFound();
            }
        } catch (RuntimeException failure) {
            if (isCurrentAccessDenied(failure)) throw AiApiException.notFound();
            throw failure;
        }
    }

    private static boolean isCurrentAccessDenied(RuntimeException failure) {
        if (failure instanceof SecurityException) return true;
        if (failure instanceof AiApiException aiFailure) {
            return isAccessDeniedStatus(aiFailure.status());
        }
        if (failure instanceof BusinessException businessFailure) {
            return isAccessDeniedStatus(businessFailure.getStatus());
        }
        return false;
    }

    private static boolean isAccessDeniedStatus(HttpStatus status) {
        return status == HttpStatus.UNAUTHORIZED
                || status == HttpStatus.FORBIDDEN
                || status == HttpStatus.NOT_FOUND;
    }

    private void authorizeRepair(AiActorContext actor, AuditAuthorizationScope scope) {
        requirePermission(Set.copyOf(actor.permissionCodes()), "ai:repair:triage");
        requirePermission(Set.copyOf(actor.permissionCodes()), "repair:read");
        if (!"REPAIR".equals(scope.contextType()) || scope.contextId() == null || scope.contextId() < 1) {
            throw AiApiException.notFound();
        }
        contexts.authorizeCurrentAccess(actor, "REPAIR", "REPAIR", scope.contextId());
    }

    private void authorizeDashboard(AiActorContext actor, AuditAuthorizationScope scope) {
        requirePermission(Set.copyOf(actor.permissionCodes()), "ai:dashboard:query");
        requirePermission(Set.copyOf(actor.permissionCodes()), "dashboard:read");
        if (!"DASHBOARD".equals(scope.contextType()) || scope.contextId() != null) {
            throw AiApiException.notFound();
        }
        contexts.authorizeCurrentAccess(actor, "DASHBOARD", "DASHBOARD", null);
    }

    private void authorizeNotice(
            AiActorContext actor,
            AuditAuthorizationScope scope,
            AiCapability capability) {
        requirePermission(Set.copyOf(actor.permissionCodes()), "notice:read");
        if ("COMMAND".equals(scope.contextType()) && capability == AiCapability.NOTICE) {
            requireOwner(actor, scope);
            return;
        }
        if (!"NOTICE".equals(scope.contextType()) || scope.contextId() == null || scope.contextId() < 1) {
            throw AiApiException.notFound();
        }
        contexts.authorizeCurrentAccess(actor, "NOTICE", "NOTICE", scope.contextId());
    }

    private void requirePermission(Set<String> permissions, String required) {
        if (!permissions.contains(required)) throw AiApiException.notFound();
    }

    private void requireOwner(AiActorContext actor, AuditAuthorizationScope scope) {
        if (actor.userId() != scope.ownerUserId()) throw AiApiException.notFound();
    }
}
