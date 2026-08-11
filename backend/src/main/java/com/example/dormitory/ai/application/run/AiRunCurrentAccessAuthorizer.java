package com.example.dormitory.ai.application.run;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.application.run.AiRunRecords.Conversation;
import com.example.dormitory.ai.application.run.AiRunRecords.Run;
import com.example.dormitory.ai.domain.model.AiCapability;
import com.example.dormitory.ai.security.AiAuditContentAuthorizationService;
import com.example.dormitory.common.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Set;

/** 对历史 run 重新求当前 RBAC 与底层业务对象访问范围的交集。 */
@Component
public final class AiRunCurrentAccessAuthorizer {

    private final AiConversationRunStore store;
    private final ContextResolverRegistry contexts;
    private final AiAuditContentAuthorizationService auditContentAuthorization;

    @Autowired
    public AiRunCurrentAccessAuthorizer(
            AiConversationRunStore store,
            ContextResolverRegistry contexts,
            AiAuditContentAuthorizationService auditContentAuthorization) {
        this.store = java.util.Objects.requireNonNull(store);
        this.contexts = java.util.Objects.requireNonNull(contexts);
        this.auditContentAuthorization = java.util.Objects.requireNonNull(auditContentAuthorization);
    }

    /** 仅保留给不涉及知识 citation 的窄单元测试；生产注入使用上方构造器。 */
    public AiRunCurrentAccessAuthorizer(
            AiConversationRunStore store,
            ContextResolverRegistry contexts) {
        this.store = java.util.Objects.requireNonNull(store);
        this.contexts = java.util.Objects.requireNonNull(contexts);
        this.auditContentAuthorization = null;
    }

    public void requireCurrentAccess(AiActorContext actor, Run run) {
        if (actor == null || run == null || actor.userId() != run.ownerUserId()
                || run.conversationId() == null || run.conversationId().isBlank()) {
            throw AiApiException.notFound();
        }
        Conversation conversation = store.ownedConversation(actor.userId(), run.conversationId());
        if (conversation == null || !run.conversationId().equals(conversation.id())) {
            throw AiApiException.notFound();
        }
        final AiCapability capability;
        try {
            capability = AiCapability.valueOf(run.capability());
        } catch (RuntimeException invalidCapability) {
            throw AiApiException.notFound();
        }

        try {
            switch (capability) {
                case ASSISTANT -> authorizeContext(actor, conversation);
                case DASHBOARD -> {
                    requirePermission(actor, "dashboard:read");
                    requireBinding(conversation, "DASHBOARD", Set.of("DASHBOARD", "COMMAND"), false);
                    // Command runs have no object id; re-resolve them through the canonical
                    // Dashboard context so the current business permission boundary is still checked.
                    if ("COMMAND".equals(conversation.contextType())) {
                        contexts.authorizeCurrentAccess(actor, "DASHBOARD", "DASHBOARD", null);
                    } else {
                        authorizeContext(actor, conversation);
                    }
                }
                case REPAIR -> {
                    requirePermission(actor, "repair:read");
                    requireBinding(conversation, "REPAIR", Set.of("REPAIR"), true);
                    authorizeContext(actor, conversation);
                }
                case NOTICE -> {
                    requirePermission(actor, "notice:read");
                    if ("COMMAND".equals(conversation.contextType())) {
                        requireBinding(conversation, "NOTICE", Set.of("COMMAND"), false);
                    } else {
                        requireBinding(conversation, "NOTICE", Set.of("NOTICE"), true);
                        authorizeContext(actor, conversation);
                    }
                }
                case KNOWLEDGE -> {
                    requirePermission(actor, "ai:knowledge:read");
                    requireBinding(conversation, "GLOBAL", Set.of("KNOWLEDGE"), false);
                    if (auditContentAuthorization != null) {
                        auditContentAuthorization.authorize(actor, run.id(), AiCapability.KNOWLEDGE);
                    }
                }
                case RISK -> requireBinding(
                        conversation, "RISK", Set.of("RISK_CASE", "COMMAND"), false);
                case EVALUATION -> requireBinding(
                        conversation, "EVALUATION", Set.of("EVALUATION", "COMMAND"), false);
            }
        } catch (RuntimeException failure) {
            if (isCurrentAccessDenied(failure)) throw AiApiException.notFound();
            throw failure;
        }
    }

    private void authorizeContext(AiActorContext actor, Conversation conversation) {
        contexts.authorizeCurrentAccess(actor, conversation.surface(),
                conversation.contextType(), conversation.contextId());
    }

    private static void requirePermission(AiActorContext actor, String permission) {
        if (!actor.permissionCodes().contains(permission)) throw AiApiException.notFound();
    }

    private static void requireBinding(
            Conversation conversation,
            String surface,
            Set<String> contextTypes,
            boolean contextIdRequired) {
        Long contextId = conversation.contextId();
        boolean validId = contextIdRequired ? contextId != null && contextId > 0 : contextId == null;
        if (!surface.equals(conversation.surface())
                || !contextTypes.contains(conversation.contextType())
                || !validId) {
            throw AiApiException.notFound();
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
}
