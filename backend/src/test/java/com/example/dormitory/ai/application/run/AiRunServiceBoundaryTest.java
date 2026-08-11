package com.example.dormitory.ai.application.run;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.config.AiProperties;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.AiCapability;
import com.example.dormitory.ai.infrastructure.runtime.AiRuntimeAuditWriter;
import com.example.dormitory.ai.infrastructure.runtime.AiRuntimeCrypto;
import com.example.dormitory.ai.observability.AiObservability;
import com.example.dormitory.ai.security.AiAuditContentAuthorizationService;
import com.example.dormitory.ai.security.PiiClassificationService;
import com.example.dormitory.ai.security.PiiRedactionService;
import com.example.dormitory.ai.security.RecentAuthenticationPolicy;
import com.example.dormitory.common.BusinessException;
import com.example.dormitory.common.PageResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

class AiRunServiceBoundaryTest {

    private final AiRuntimeGate gate = mock(AiRuntimeGate.class);
    private final AiActorResolver actors = mock(AiActorResolver.class);
    private final AiConversationRunStore store = mock(AiConversationRunStore.class);
    private final AiModelRuntimePort runtime = mock(AiModelRuntimePort.class);
    private final ContextResolverRegistry contexts = mock(ContextResolverRegistry.class);
    private final AiRuntimeAuditWriter audit = mock(AiRuntimeAuditWriter.class);
    private final RecentAuthenticationPolicy recent = mock(RecentAuthenticationPolicy.class);
    private final AiAuditContentAuthorizationService contentAuthorization =
            mock(AiAuditContentAuthorizationService.class);
    private final AiRunService service = new AiRunService(
            gate, actors, store, runtime, mock(AiRunEventPublisher.class), crypto(), Runnable::run,
            new ObjectMapper(), recent, audit, mock(AiObservability.class), contexts, contentAuthorization);

    @BeforeEach
    void defaults() {
        when(actors.current(nullable(String.class))).thenReturn(actor(allPermissions()));
        when(runtime.providerCode()).thenReturn("fake");
        when(runtime.modelAlias()).thenReturn("fake-model");
        when(store.cancelRun(any(), anyString())).thenReturn(AiRunRecords.RunMutation.unchanged());
    }

    @Test
    void conversationSurfaceResourceIdAndPaginationContractsFailClosed() {
        for (String surface : Arrays.asList(null, "", "unknown")) {
            assertThrows(AiApiException.class,
                    () -> service.createConversation(surface, "NONE", null));
        }
        String conversationId = UUID.randomUUID().toString();
        AiRunRecords.Conversation conversation = conversation(conversationId, "GLOBAL");
        when(contexts.resolve(any(), eq("GLOBAL"), eq("NONE"), eq(null), eq(null)))
                .thenReturn(new ContextResolverRegistry.Resolution(
                        "GLOBAL", "NONE", null, "{}", Set.of(), null));
        when(store.createConversation(any(), eq("GLOBAL"), eq("NONE"), eq(null)))
                .thenReturn(conversation);
        assertEquals(conversation, service.createConversation(" global ", "NONE", null));

        for (long[] page : List.of(new long[]{0, 10}, new long[]{1, 0}, new long[]{1, 101})) {
            assertThrows(AiApiException.class, () -> service.listConversations(page[0], page[1]));
            assertThrows(AiApiException.class,
                    () -> service.auditRuns(AiRunRecords.AuditRunFilter.none(), page[0], page[1]));
        }
        when(store.listConversations(anyLong(), eq(1L), eq(100L)))
                .thenReturn(new PageResponse<>(List.of(conversation), 1, 1, 100));
        assertEquals(1, service.listConversations(1, 10).records().size());

        for (String id : Arrays.asList(null, "", "not-a-uuid")) {
            assertThrows(AiApiException.class, () -> service.conversation(id));
            assertThrows(AiApiException.class, () -> service.archiveConversation(id));
            assertThrows(AiApiException.class, () -> service.run(id));
            assertThrows(AiApiException.class, () -> service.cancel(id));
        }
    }

    @Test
    void createConversationMapsCurrentObjectDenialDuringResolutionToNotFoundBeforePersistence() {
        RuntimeException denial = new BusinessException(HttpStatus.NOT_FOUND, "维修单不存在");
        doThrow(denial).when(contexts)
                .resolve(any(), eq("REPAIR"), eq("REPAIR"), eq(42L), eq(null));

        AiApiException hidden = assertThrows(AiApiException.class,
                () -> service.createConversation("REPAIR", "REPAIR", 42L));

        assertEquals(HttpStatus.NOT_FOUND, hidden.status());
        assertEquals("AI_RESOURCE_NOT_FOUND", hidden.errorCode());
        verify(store, never()).createConversation(any(), anyString(), anyString(), any());
        verify(contexts, never()).authorizeCurrentAccess(any(), anyString(), anyString(), any());
    }

    @Test
    void createConversationMapsCurrentObjectDenialDuringAuthorizationToNotFoundBeforePersistence() {
        List<RuntimeException> denials = List.of(
                new SecurityException("公告读取权限已撤销"),
                new BusinessException(HttpStatus.NOT_FOUND, "公告不存在"));
        long contextId = 8L;
        for (RuntimeException denial : denials) {
            long currentContextId = contextId;
            ContextResolverRegistry.Resolution resolution = new ContextResolverRegistry.Resolution(
                    "NOTICE", "NOTICE", currentContextId, "{}", Set.of(), null);
            when(contexts.resolve(any(), eq("NOTICE"), eq("NOTICE"), eq(currentContextId), eq(null)))
                    .thenReturn(resolution);
            doThrow(denial).when(contexts)
                    .authorizeCurrentAccess(any(), eq("NOTICE"), eq("NOTICE"), eq(currentContextId));

            AiApiException hidden = assertThrows(AiApiException.class,
                    () -> service.createConversation("NOTICE", "NOTICE", currentContextId));

            assertEquals(HttpStatus.NOT_FOUND, hidden.status());
            assertEquals("AI_RESOURCE_NOT_FOUND", hidden.errorCode());
            contextId++;
        }
        verify(store, never()).createConversation(any(), anyString(), anyString(), any());
        verify(contexts).resolve(any(), eq("NOTICE"), eq("NOTICE"), eq(8L), eq(null));
        verify(contexts).authorizeCurrentAccess(any(), eq("NOTICE"), eq("NOTICE"), eq(8L));
        verify(contexts).resolve(any(), eq("NOTICE"), eq("NOTICE"), eq(9L), eq(null));
        verify(contexts).authorizeCurrentAccess(any(), eq("NOTICE"), eq("NOTICE"), eq(9L));
    }

    @Test
    void listConversationsFiltersRevokedContextsAcrossStorePagesAndRepaginates() {
        List<AiRunRecords.Conversation> revoked = IntStream.rangeClosed(1, 100)
                .mapToObj(index -> conversation(UUID.randomUUID().toString(),
                        "REPAIR", "REPAIR", (long) index))
                .toList();
        AiRunRecords.Conversation firstVisible = conversation(
                UUID.randomUUID().toString(), "GLOBAL", "NONE", null);
        AiRunRecords.Conversation secondVisible = conversation(
                UUID.randomUUID().toString(), "GLOBAL", "NONE", null);
        when(store.listConversations(7L, 1L, 100L))
                .thenReturn(new PageResponse<>(revoked, 102, 1, 100));
        when(store.listConversations(7L, 2L, 100L))
                .thenReturn(new PageResponse<>(List.of(firstVisible, secondVisible), 102, 2, 100));
        doThrow(new SecurityException("维修对象范围已撤销"))
                .when(contexts).authorizeCurrentAccess(any(), eq("REPAIR"), eq("REPAIR"), anyLong());

        PageResponse<AiRunRecords.Conversation> result = service.listConversations(2, 1);

        assertEquals(List.of(secondVisible), result.records());
        assertEquals(2, result.total());
        assertEquals(2, result.page());
        assertEquals(1, result.pageSize());
        verify(store).listConversations(7L, 1L, 100L);
        verify(store).listConversations(7L, 2L, 100L);
        verify(contexts, times(2)).authorizeCurrentAccess(any(), eq("GLOBAL"), eq("NONE"), eq(null));
    }

    @Test
    void conversationMapsCurrentObjectDenialToNotFoundAfterOwnerLookup() {
        String id = UUID.randomUUID().toString();
        AiRunRecords.Conversation conversation = conversation(id, "REPAIR", "REPAIR", 42L);
        when(store.conversationDetail(7L, id))
                .thenReturn(new AiRunRecords.ConversationDetail(conversation, List.of()));
        doThrow(new BusinessException(HttpStatus.NOT_FOUND, "维修单不存在"))
                .when(contexts).authorizeCurrentAccess(any(), eq("REPAIR"), eq("REPAIR"), eq(42L));

        AiApiException denied = assertThrows(AiApiException.class, () -> service.conversation(id));

        assertEquals(HttpStatus.NOT_FOUND, denied.status());
        assertEquals("AI_RESOURCE_NOT_FOUND", denied.errorCode());
        InOrder order = inOrder(store, contexts);
        order.verify(store).conversationDetail(7L, id);
        order.verify(contexts).authorizeCurrentAccess(any(), eq("REPAIR"), eq("REPAIR"), eq(42L));
    }

    @Test
    void conversationDoesNotMaskCurrentAccessInfrastructureFailure() {
        String id = UUID.randomUUID().toString();
        AiRunRecords.Conversation conversation = conversation(id, "NOTICE", "NOTICE", 8L);
        when(store.conversationDetail(7L, id))
                .thenReturn(new AiRunRecords.ConversationDetail(conversation, List.of()));
        IllegalStateException outage = new IllegalStateException("业务读取基础设施不可用");
        doThrow(outage).when(contexts)
                .authorizeCurrentAccess(any(), eq("NOTICE"), eq("NOTICE"), eq(8L));

        assertSame(outage, assertThrows(IllegalStateException.class, () -> service.conversation(id)));
    }

    @Test
    void assistantRunReadStreamAndCancelHideCurrentObjectDenial() {
        String runId = UUID.randomUUID().toString();
        String conversationId = UUID.randomUUID().toString();
        AiRunRecords.Run assistantRun = new AiRunRecords.Run(
                runId, conversationId, 7L, AiCapability.ASSISTANT.name(), "RUNNING",
                "digest", "fingerprint", "v1", Instant.now(), null, null);
        AiRunRecords.Conversation conversation = conversation(conversationId, "REPAIR", "REPAIR", 42L);
        when(store.ownedRun(7L, runId)).thenReturn(assistantRun);
        when(store.ownedConversation(7L, conversationId))
                .thenReturn(conversation);
        doThrow(new SecurityException("维修对象范围已撤销")).when(contexts)
                .authorizeCurrentAccess(any(), eq("REPAIR"), eq("REPAIR"), eq(42L));

        AiApiException readDenied = assertThrows(AiApiException.class, () -> service.run(runId));
        AiApiException streamDenied = assertThrows(AiApiException.class, () -> service.streamActor(runId));
        AiApiException cancelDenied = assertThrows(AiApiException.class, () -> service.cancel(runId));

        assertEquals("AI_RESOURCE_NOT_FOUND", readDenied.errorCode());
        assertEquals("AI_RESOURCE_NOT_FOUND", streamDenied.errorCode());
        assertEquals("AI_RESOURCE_NOT_FOUND", cancelDenied.errorCode());
        verify(store, never()).cancelRun(any(), eq(runId));
    }

    @Test
    void commandRunReadStreamAndCancelHideCurrentRepairObjectDenial() {
        String runId = UUID.randomUUID().toString();
        String conversationId = UUID.randomUUID().toString();
        AiRunRecords.Run repairRun = new AiRunRecords.Run(
                runId, conversationId, 7L, AiCapability.REPAIR.name(), "SUCCEEDED",
                "digest", "fingerprint", "v1", Instant.now(), Instant.now(), null);
        AiRunRecords.Conversation conversation = conversation(conversationId, "REPAIR", "REPAIR", 42L);
        when(store.ownedRun(7L, runId)).thenReturn(repairRun);
        when(store.ownedConversation(7L, conversationId)).thenReturn(conversation);
        doThrow(new SecurityException("维修对象范围已撤销")).when(contexts)
                .authorizeCurrentAccess(any(), eq("REPAIR"), eq("REPAIR"), eq(42L));

        AiApiException readDenied = assertThrows(AiApiException.class, () -> service.run(runId));
        AiApiException streamDenied = assertThrows(AiApiException.class, () -> service.streamActor(runId));
        AiApiException cancelDenied = assertThrows(AiApiException.class, () -> service.cancel(runId));

        assertEquals(HttpStatus.NOT_FOUND, readDenied.status());
        assertEquals("AI_RESOURCE_NOT_FOUND", readDenied.errorCode());
        assertEquals("AI_RESOURCE_NOT_FOUND", streamDenied.errorCode());
        assertEquals("AI_RESOURCE_NOT_FOUND", cancelDenied.errorCode());
        verify(store, never()).cancelRun(any(), eq(runId));
    }

    @Test
    void noticeCommandRunRequiresCurrentBusinessReadPermission() {
        String runId = UUID.randomUUID().toString();
        String conversationId = UUID.randomUUID().toString();
        AiRunRecords.Run noticeRun = new AiRunRecords.Run(
                runId, conversationId, 7L, AiCapability.NOTICE.name(), "SUCCEEDED",
                "digest", "fingerprint", "v1", Instant.now(), Instant.now(), null);
        AiRunRecords.Conversation conversation = conversation(conversationId, "NOTICE", "COMMAND", null);
        when(store.ownedRun(7L, runId)).thenReturn(noticeRun);
        when(store.ownedConversation(7L, conversationId)).thenReturn(conversation);
        when(actors.current("ai:notice:draft")).thenReturn(actor(Set.of("ai:notice:draft")));

        AiApiException denied = assertThrows(AiApiException.class, () -> service.run(runId));

        assertEquals(HttpStatus.NOT_FOUND, denied.status());
        assertEquals("AI_RESOURCE_NOT_FOUND", denied.errorCode());
    }

    @Test
    void createMessageMapsCurrentAccessDenialsToNotFoundBeforeRunCreation() {
        List<RuntimeException> denials = List.of(
                new SecurityException("公告读取权限已撤销"),
                new BusinessException(HttpStatus.NOT_FOUND, "维修单不存在"),
                new AiApiException(HttpStatus.FORBIDDEN, "AI_CONTEXT_PERMISSION_DENIED",
                        "无权读取该页面的 AI 上下文", false));
        int index = 0;
        for (RuntimeException denial : denials) {
            long contextId = 100L + index++;
            String conversationId = UUID.randomUUID().toString();
            AiRunRecords.Conversation conversation = conversation(
                    conversationId, "REPAIR", "REPAIR", contextId);
            when(store.conversationDetail(7L, conversationId))
                    .thenReturn(new AiRunRecords.ConversationDetail(conversation, List.of()));
            doThrow(denial).when(contexts)
                    .authorizeCurrentAccess(any(), eq("REPAIR"), eq("REPAIR"), eq(contextId));

            AiApiException hidden = assertThrows(AiApiException.class,
                    () -> service.createMessage(conversationId, "input", "request-" + contextId));

            assertEquals(HttpStatus.NOT_FOUND, hidden.status());
            assertEquals("AI_RESOURCE_NOT_FOUND", hidden.errorCode());
        }
        verify(store, never()).createRun(any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    void retryMapsCurrentAccessDenialsToNotFoundBeforeChildRunCreation() {
        List<RuntimeException> denials = List.of(
                new SecurityException("公告读取权限已撤销"),
                new BusinessException(HttpStatus.NOT_FOUND, "维修单不存在"),
                new AiApiException(HttpStatus.FORBIDDEN, "AI_CONTEXT_PERMISSION_DENIED",
                        "无权读取该页面的 AI 上下文", false));
        int index = 0;
        for (RuntimeException denial : denials) {
            long contextId = 200L + index++;
            String parentRunId = UUID.randomUUID().toString();
            AiRunRecords.Run parent = run(parentRunId, AiCapability.ASSISTANT, "SUCCEEDED");
            AiRunRecords.Conversation conversation = conversation(
                    UUID.randomUUID().toString(), "NOTICE", "NOTICE", contextId);
            when(store.ownedRun(7L, parentRunId)).thenReturn(parent);
            when(store.retrySource(7L, parentRunId)).thenReturn(new AiRunRecords.RetrySource(
                    parent, conversation, "input", "L1", "a".repeat(64)));
            doThrow(denial).when(contexts)
                    .authorizeCurrentAccess(any(), eq("NOTICE"), eq("NOTICE"), eq(contextId));

            AiApiException hidden = assertThrows(AiApiException.class,
                    () -> service.retry(parentRunId, "retry-" + contextId));

            assertEquals(HttpStatus.NOT_FOUND, hidden.status());
            assertEquals("AI_RESOURCE_NOT_FOUND", hidden.errorCode());
        }
        verify(store, never()).createRetryRun(any(), anyString(), anyString(), anyString());
    }

    @Test
    void listConversationsDoesNotFilterInfrastructureFailure() {
        AiRunRecords.Conversation conversation = conversation(
                UUID.randomUUID().toString(), "NOTICE", "NOTICE", 8L);
        when(store.listConversations(7L, 1L, 100L))
                .thenReturn(new PageResponse<>(List.of(conversation), 1, 1, 100));
        AiApiException outage = AiApiException.unavailable("AI_BUSINESS_READ_UNAVAILABLE", "业务读取不可用");
        doThrow(outage).when(contexts)
                .authorizeCurrentAccess(any(), eq("NOTICE"), eq("NOTICE"), eq(8L));

        assertSame(outage, assertThrows(AiApiException.class, () -> service.listConversations(1, 20)));
    }

    @Test
    void clientRequestFeedbackAndAuditReasonValidationCoverEveryIndependentBound() {
        String conversationId = UUID.randomUUID().toString();
        for (String requestId : Arrays.asList(null, "", "x".repeat(129))) {
            assertThrows(AiApiException.class,
                    () -> service.createMessage(conversationId, "input", requestId));
            assertThrows(AiApiException.class,
                    () -> service.createCommand(AiCapability.REPAIR, "ai:repair:triage", "REPAIR",
                            requestId, "{}", (ignoredActor, ignoredRunId) -> "ok"));
        }

        String messageId = UUID.randomUUID().toString();
        for (int rating : List.of(-2, 0, 2)) {
            assertThrows(AiApiException.class,
                    () -> service.feedback(messageId, rating, List.of(), null));
        }
        assertThrows(AiApiException.class,
                () -> service.feedback(messageId, 1, List.of("bad tag"), null));
        assertThrows(AiApiException.class,
                () -> service.feedback(messageId, 1, List.of("same", "same"), null));
        assertThrows(AiApiException.class,
                () -> service.feedback(messageId, 1,
                        java.util.stream.IntStream.range(0, 11).mapToObj(i -> "tag" + i).toList(), null));
        service.feedback(messageId, -1, null, null);
        service.feedback(messageId, 1, List.of("useful"), " ");
        verify(store).upsertFeedback(any(), eq(messageId), eq(-1), eq(List.of()), eq(null));

        String runId = UUID.randomUUID().toString();
        for (String reason : Arrays.asList(null, "short", "x".repeat(501), "valid\u0000reason")) {
            assertThrows(AiApiException.class,
                    () -> service.auditRunContent(runId, reason, "proof"));
            assertThrows(AiApiException.class,
                    () -> AiRunService.auditContentRequestHash(runId, reason));
        }
        assertEquals(64, AiRunService.auditContentRequestHash(
                runId, "  人工调查异常运行的合规原因  ").length());
        assertThrows(NullPointerException.class,
                () -> service.auditRuns(null, 1, 10));
    }

    @Test
    void runAndStreamAuthorizationMapEveryCapabilityToItsServerPermission() {
        String runId = UUID.randomUUID().toString();
        for (AiCapability capability : AiCapability.values()) {
            AiRunRecords.Run run = run(runId, capability, "SUCCEEDED");
            when(store.ownedRun(7L, runId)).thenReturn(run);
            when(store.ownedConversation(7L, run.conversationId()))
                    .thenReturn(commandConversation(run.conversationId(), capability));
            assertEquals(run, service.run(runId));
            assertEquals(7L, service.streamActor(runId).userId());
        }
        AiRunRecords.Run activeRepair = run(runId, AiCapability.REPAIR, "RUNNING");
        when(store.ownedRun(7L, runId)).thenReturn(activeRepair);
        when(store.ownedConversation(7L, activeRepair.conversationId()))
                .thenReturn(commandConversation(activeRepair.conversationId(), AiCapability.REPAIR));
        service.cancel(runId);
        verify(store).cancelRun(any(), eq(runId));
        assertFalse(service.isTerminal(runId, 7L));
        when(store.ownedRun(7L, runId)).thenReturn(run(runId, AiCapability.REPAIR, "TIMED_OUT"));
        assertTrue(service.isTerminal(runId, 7L));
    }

    @Test
    void retryRejectsNonAssistantCapabilityBeforeCreatingAChildRun() {
        String parentId = UUID.randomUUID().toString();
        when(store.ownedRun(7L, parentId)).thenReturn(run(parentId, AiCapability.REPAIR, "FAILED"));
        AiApiException failure = assertThrows(AiApiException.class,
                () -> service.retry(parentId, "retry-1"));
        assertEquals("AI_RUN_RETRY_UNSUPPORTED", failure.errorCode());
    }

    @Test
    void auditContentRequiresMetadataCapabilityAndCurrentBusinessPermissions() {
        String runId = UUID.randomUUID().toString();
        when(actors.current("ai:audit:content:read")).thenReturn(actor(Set.of("ai:audit:content:read")));
        assertEquals("AI_AUDIT_READ_PERMISSION_REQUIRED", assertThrows(AiApiException.class,
                () -> service.auditRunContent(runId, "调查异常运行的合规原因", "proof")).errorCode());

        when(actors.current("ai:audit:content:read")).thenReturn(actor(Set.of(
                "ai:audit:content:read", "ai:audit:read")));
        when(store.auditRun(runId)).thenReturn(auditDetail(runId, AiCapability.REPAIR));
        assertThrows(AiApiException.class,
                () -> service.auditRunContent(runId, "调查异常运行的合规原因", "proof"));

        when(actors.current("ai:audit:content:read")).thenReturn(actor(Set.of(
                "ai:audit:content:read", "ai:audit:read", "ai:repair:triage")));
        assertThrows(AiApiException.class,
                () -> service.auditRunContent(runId, "调查异常运行的合规原因", "proof"));

        when(actors.current("ai:audit:content:read")).thenReturn(actor(Set.of(
                "ai:audit:content:read", "ai:audit:read", "ai:knowledge:read")));
        when(store.auditRun(runId)).thenReturn(auditDetail(runId, AiCapability.KNOWLEDGE));
        assertEquals("AI_AUDIT_CONTENT_SCOPE_UNAVAILABLE", assertThrows(AiApiException.class,
                () -> service.auditRunContent(runId, "调查异常运行的合规原因", "proof")).errorCode());
    }

    private AiRunRecords.AuditRunDetail auditDetail(String runId, AiCapability capability) {
        return new AiRunRecords.AuditRunDetail(new AiRunRecords.AuditRun(
                runId, capability.name(), "FAILED", "fake", "v1", 0, 0,
                BigDecimal.ZERO, "failure", Instant.now(), Instant.now()), List.of(), List.of());
    }

    private AiRunRecords.Conversation conversation(String id, String surface) {
        return conversation(id, surface, "NONE", null);
    }

    private AiRunRecords.Conversation conversation(
            String id,
            String surface,
            String contextType,
            Long contextId) {
        return new AiRunRecords.Conversation(
                id, surface, contextType, contextId, "ACTIVE", null, Instant.now(), Instant.now());
    }

    private AiRunRecords.Run run(String id, AiCapability capability, String state) {
        return new AiRunRecords.Run(id, UUID.randomUUID().toString(), 7L, capability.name(), state,
                "digest", "fingerprint", "v1", Instant.now(), Instant.now(), null);
    }

    private AiRunRecords.Conversation commandConversation(String id, AiCapability capability) {
        return switch (capability) {
            case ASSISTANT -> conversation(id, "GLOBAL", "NONE", null);
            case KNOWLEDGE -> conversation(id, "GLOBAL", "KNOWLEDGE", null);
            case DASHBOARD -> conversation(id, "DASHBOARD", "DASHBOARD", null);
            case REPAIR -> conversation(id, "REPAIR", "REPAIR", 42L);
            case NOTICE -> conversation(id, "NOTICE", "COMMAND", null);
            case RISK -> conversation(id, "RISK", "RISK_CASE", null);
            case EVALUATION -> conversation(id, "EVALUATION", "COMMAND", null);
        };
    }

    private AiActorContext actor(Set<String> permissions) {
        return new AiActorContext(7L, "token", "fingerprint", 1, "digest",
                List.of("ADMIN"), permissions.stream().sorted().toList(), ActorDescriptor.user(7L));
    }

    private Set<String> allPermissions() {
        return Set.of("ai:assistant:use", "ai:knowledge:read", "ai:dashboard:query",
                "ai:repair:triage", "ai:notice:draft", "ai:risk:read", "ai:eval:run",
                "ai:audit:read", "ai:audit:content:read", "dashboard:read", "repair:read", "notice:read");
    }

    private static AiRuntimeCrypto crypto() {
        AiProperties properties = new AiProperties();
        String key = "run-service-boundary-key-32-bytes-minimum";
        properties.getTokenization().setHmacKey(key);
        properties.getTokenization().setActiveKeyVersion(1);
        PiiRedactionService redaction = new PiiRedactionService(
                key.getBytes(StandardCharsets.UTF_8), "v1");
        return new AiRuntimeCrypto(properties, new PiiClassificationService(redaction, List::of));
    }
}
