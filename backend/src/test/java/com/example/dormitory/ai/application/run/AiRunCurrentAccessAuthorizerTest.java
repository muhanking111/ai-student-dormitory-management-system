package com.example.dormitory.ai.application.run;

import com.example.dormitory.ai.application.run.AiRunRecords.Conversation;
import com.example.dormitory.ai.application.run.AiRunRecords.Run;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.AiCapability;
import com.example.dormitory.ai.security.AiAuditContentAuthorizationService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AiRunCurrentAccessAuthorizerTest {

    @Test
    void dashboardCommandRunRechecksAsDashboardContext() {
        AiConversationRunStore store = mock(AiConversationRunStore.class);
        ContextResolverRegistry contexts = mock(ContextResolverRegistry.class);
        AiActorContext actor = new AiActorContext(7L, "session", "f".repeat(64), 1, "d".repeat(64),
                List.of("ADMIN"), List.of("dashboard:read", "ai:dashboard:query"), ActorDescriptor.user(7L));
        Run run = new Run("run-dashboard-command", "conversation-dashboard-command", 7L, "DASHBOARD", "SUCCEEDED",
                "d".repeat(64), "f".repeat(64), "v1", Instant.now(), null, null);
        org.mockito.Mockito.when(store.ownedConversation(actor.userId(), run.conversationId()))
                .thenReturn(new Conversation(run.conversationId(), "DASHBOARD", "COMMAND", null,
                        "ACTIVE", "命令查询", Instant.now(), Instant.now()));

        new AiRunCurrentAccessAuthorizer(store, contexts).requireCurrentAccess(actor, run);

        verify(contexts).authorizeCurrentAccess(actor, "DASHBOARD", "DASHBOARD", null);
    }

    @Test
    void knowledgeRunRechecksCitationAclOnCurrentAccess() {
        AiConversationRunStore store = mock(AiConversationRunStore.class);
        ContextResolverRegistry contexts = mock(ContextResolverRegistry.class);
        AiAuditContentAuthorizationService auditContent = mock(AiAuditContentAuthorizationService.class);
        AiActorContext actor = actor();
        Run run = run();
        org.mockito.Mockito.when(store.ownedConversation(actor.userId(), run.conversationId()))
                .thenReturn(conversation());

        new AiRunCurrentAccessAuthorizer(store, contexts, auditContent).requireCurrentAccess(actor, run);

        verify(auditContent).authorize(eq(actor), eq(run.id()), eq(AiCapability.KNOWLEDGE));
    }

    @Test
    void revokedKnowledgeCitationFailsClosedDuringCurrentAccess() {
        AiConversationRunStore store = mock(AiConversationRunStore.class);
        ContextResolverRegistry contexts = mock(ContextResolverRegistry.class);
        AiAuditContentAuthorizationService auditContent = mock(AiAuditContentAuthorizationService.class);
        AiActorContext actor = actor();
        Run run = run();
        org.mockito.Mockito.when(store.ownedConversation(actor.userId(), run.conversationId()))
                .thenReturn(conversation());
        doThrow(com.example.dormitory.ai.api.AiApiException.notFound())
                .when(auditContent).authorize(eq(actor), eq(run.id()), eq(AiCapability.KNOWLEDGE));

        assertThrows(com.example.dormitory.ai.api.AiApiException.class,
                () -> new AiRunCurrentAccessAuthorizer(store, contexts, auditContent)
                        .requireCurrentAccess(actor, run));
    }

    private static AiActorContext actor() {
        return new AiActorContext(7L, "session", "f".repeat(64), 1, "d".repeat(64),
                List.of("ADMIN"), List.of("ai:knowledge:read"), ActorDescriptor.user(7L));
    }

    private static Conversation conversation() {
        return new Conversation("conversation-1", "GLOBAL", "KNOWLEDGE", null,
                "ACTIVE", "知识问答", Instant.now(), Instant.now());
    }

    private static Run run() {
        return new Run("run-1", "conversation-1", 7L, "KNOWLEDGE", "RUNNING",
                "d".repeat(64), "f".repeat(64), "v1", Instant.now(), null, null);
    }
}
