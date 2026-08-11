package com.example.dormitory.ai.security;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.application.run.AiActorContext;
import com.example.dormitory.ai.application.run.AiConversationRunStore;
import com.example.dormitory.ai.application.run.AiRunRecords;
import com.example.dormitory.ai.application.run.ContextResolverRegistry;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.AiCapability;
import com.example.dormitory.ai.knowledge.KnowledgeVersionRepository;
import com.example.dormitory.ai.knowledge.KnowledgeVisibility;
import com.example.dormitory.ai.port.ObjectStoragePort;
import com.example.dormitory.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiAuditContentAuthorizationServiceTest {

    @Test
    void globalContentWithoutOwnerOrBusinessObjectScopeFailsClosed() {
        AiConversationRunStore store = mock(AiConversationRunStore.class);
        ContextResolverRegistry contexts = mock(ContextResolverRegistry.class);
        KnowledgeVersionRepository versions = mock(KnowledgeVersionRepository.class);
        AiActorContext auditor = actor(Set.of("ai:assistant:use"));
        when(store.auditAuthorizationScope("other-global-run")).thenReturn(
                scope(10L, "GLOBAL", "NONE", null));
        AiAuditContentAuthorizationService service = new AiAuditContentAuthorizationService(
                store, contexts, versions);

        assertNotFound(service, auditor, "other-global-run", AiCapability.ASSISTANT);
    }

    @Test
    void noticeCommandContentWithoutOwnerOrBusinessObjectScopeFailsClosed() {
        AiConversationRunStore store = mock(AiConversationRunStore.class);
        ContextResolverRegistry contexts = mock(ContextResolverRegistry.class);
        KnowledgeVersionRepository versions = mock(KnowledgeVersionRepository.class);
        AiActorContext auditor = actor(Set.of("ai:notice:draft", "notice:read"));
        when(store.auditAuthorizationScope("other-notice-command")).thenReturn(
                scope(10L, "NOTICE", "COMMAND", null));
        AiAuditContentAuthorizationService service = new AiAuditContentAuthorizationService(
                store, contexts, versions);

        assertNotFound(service, auditor, "other-notice-command", AiCapability.NOTICE);
    }

    @Test
    void repairContentRebuildsCurrentObjectScopeAndKnowledgeCitationsUseCurrentAcl() {
        AiConversationRunStore store = mock(AiConversationRunStore.class);
        ContextResolverRegistry contexts = mock(ContextResolverRegistry.class);
        KnowledgeVersionRepository versions = mock(KnowledgeVersionRepository.class);
        AiActorContext actor = actor(Set.of(
                "ai:audit:read", "ai:audit:content:read", "ai:assistant:use",
                "ai:repair:triage", "repair:read", "source:repair-policy:read"));
        when(store.auditAuthorizationScope("run-1")).thenReturn(new AiRunRecords.AuditAuthorizationScope(
                9L, "REPAIR", "REPAIR", 42L, List.of("version-1")));
        when(versions.findByPublicId("version-1")).thenReturn(Optional.of(version("version-1", "source-1")));
        when(versions.canRead("version-1", Set.copyOf(actor.permissionCodes()))).thenReturn(true);
        AiAuditContentAuthorizationService service = new AiAuditContentAuthorizationService(
                store, contexts, versions);

        assertDoesNotThrow(() -> service.authorize(actor, "run-1", AiCapability.ASSISTANT));

        verify(contexts).authorizeCurrentAccess(actor, "REPAIR", "REPAIR", 42L);
        verify(versions).canRead("version-1", Set.copyOf(actor.permissionCodes()));
    }

    @Test
    void revokedCitationAndMissingRepairResourceBindingFailClosedAsNotFound() {
        AiConversationRunStore store = mock(AiConversationRunStore.class);
        ContextResolverRegistry contexts = mock(ContextResolverRegistry.class);
        KnowledgeVersionRepository versions = mock(KnowledgeVersionRepository.class);
        AiActorContext actor = actor(Set.of(
                "ai:audit:read", "ai:audit:content:read", "ai:assistant:use",
                "ai:repair:triage", "repair:read"));
        AiAuditContentAuthorizationService service = new AiAuditContentAuthorizationService(
                store, contexts, versions);
        when(store.auditAuthorizationScope("run-revoked")).thenReturn(new AiRunRecords.AuditAuthorizationScope(
                9L, "GLOBAL", "NONE", null, List.of("version-revoked")));
        when(versions.findByPublicId("version-revoked"))
                .thenReturn(Optional.of(version("version-revoked", "source-1")));
        when(versions.canRead("version-revoked", Set.copyOf(actor.permissionCodes()))).thenReturn(false);

        AiApiException revoked = assertThrows(AiApiException.class,
                () -> service.authorize(actor, "run-revoked", AiCapability.ASSISTANT));
        assertEquals("AI_RESOURCE_NOT_FOUND", revoked.errorCode());
        verify(contexts, never()).authorizeCurrentAccess(eq(actor), eq("GLOBAL"), eq("NONE"), eq(null));

        when(store.auditAuthorizationScope("run-unbound")).thenReturn(new AiRunRecords.AuditAuthorizationScope(
                9L, "REPAIR", "COMMAND", null, List.of()));
        AiApiException unbound = assertThrows(AiApiException.class,
                () -> service.authorize(actor, "run-unbound", AiCapability.REPAIR));
        assertEquals("AI_RESOURCE_NOT_FOUND", unbound.errorCode());
    }

    @Test
    void sourceKillSwitchBlocksAuditContentBeforeCitationBodyCanBeRead() {
        AiConversationRunStore store = mock(AiConversationRunStore.class);
        ContextResolverRegistry contexts = mock(ContextResolverRegistry.class);
        KnowledgeVersionRepository versions = mock(KnowledgeVersionRepository.class);
        AiActorContext actor = actor(Set.of("ai:audit:read", "ai:audit:content:read", "ai:assistant:use"));
        when(store.auditAuthorizationScope("run-killed")).thenReturn(new AiRunRecords.AuditAuthorizationScope(
                9L, "GLOBAL", "NONE", null, List.of("version-killed")));
        when(versions.findByPublicId("version-killed"))
                .thenReturn(Optional.of(version("version-killed", "source-killed")));
        AiAuditContentAuthorizationService service = new AiAuditContentAuthorizationService(
                store, contexts, versions, source -> !"source-killed".equals(source));

        AiApiException denied = assertThrows(AiApiException.class,
                () -> service.authorize(actor, "run-killed", AiCapability.ASSISTANT));

        assertEquals("AI_RESOURCE_NOT_FOUND", denied.errorCode());
        verify(versions, never()).canRead(eq("version-killed"), eq(Set.copyOf(actor.permissionCodes())));
    }

    @Test
    void authorizesEverySupportedSurfaceUsingCurrentPermissionsAndObjectScope() {
        AiConversationRunStore store = mock(AiConversationRunStore.class);
        ContextResolverRegistry contexts = mock(ContextResolverRegistry.class);
        KnowledgeVersionRepository versions = mock(KnowledgeVersionRepository.class);
        AiActorContext actor = actor(Set.of(
                "ai:dashboard:query", "dashboard:read", "notice:read", "ai:risk:read"));
        AiAuditContentAuthorizationService service = new AiAuditContentAuthorizationService(
                store, contexts, versions);
        when(store.auditAuthorizationScope("dashboard")).thenReturn(scope(
                "DASHBOARD", "DASHBOARD", null));
        when(store.auditAuthorizationScope("notice-command")).thenReturn(scope(
                "NOTICE", "COMMAND", null));
        when(store.auditAuthorizationScope("notice-object")).thenReturn(scope(
                "NOTICE", "NOTICE", 8L));
        when(store.auditAuthorizationScope("risk")).thenReturn(scope("RISK", "NONE", null));
        when(store.auditAuthorizationScope("global")).thenReturn(scope("GLOBAL", "NONE", null));
        when(store.auditAuthorizationScope("knowledge")).thenReturn(scope("KNOWLEDGE", "NONE", null));

        assertDoesNotThrow(() -> service.authorize(actor, "dashboard", AiCapability.DASHBOARD));
        assertDoesNotThrow(() -> service.authorize(actor, "notice-command", AiCapability.NOTICE));
        assertDoesNotThrow(() -> service.authorize(actor, "notice-object", AiCapability.NOTICE));
        assertDoesNotThrow(() -> service.authorize(actor, "risk", AiCapability.RISK));
        assertDoesNotThrow(() -> service.authorize(actor, "global", AiCapability.ASSISTANT));
        assertDoesNotThrow(() -> service.authorize(actor, "knowledge", AiCapability.KNOWLEDGE));

        verify(contexts).authorizeCurrentAccess(actor, "DASHBOARD", "DASHBOARD", null);
        verify(contexts).authorizeCurrentAccess(actor, "NOTICE", "NOTICE", 8L);
        verify(contexts, never()).authorizeCurrentAccess(actor, "NOTICE", "COMMAND", null);
    }

    @Test
    void hidesPermissionDenialsUnsupportedSurfacesAndEveryContextMismatchAsNotFound() {
        AiConversationRunStore store = mock(AiConversationRunStore.class);
        ContextResolverRegistry contexts = mock(ContextResolverRegistry.class);
        KnowledgeVersionRepository versions = mock(KnowledgeVersionRepository.class);
        AiAuditContentAuthorizationService service = new AiAuditContentAuthorizationService(
                store, contexts, versions);
        AiActorContext none = actor(Set.of());
        AiActorContext dashboard = actor(Set.of("ai:dashboard:query", "dashboard:read"));
        AiActorContext notice = actor(Set.of("notice:read"));
        AiActorContext repair = actor(Set.of("ai:repair:triage", "repair:read"));

        when(store.auditAuthorizationScope("dashboard-permission")).thenReturn(
                scope("DASHBOARD", "DASHBOARD", null));
        when(store.auditAuthorizationScope("dashboard-type")).thenReturn(
                scope("DASHBOARD", "NOTICE", null));
        when(store.auditAuthorizationScope("dashboard-id")).thenReturn(
                scope("DASHBOARD", "DASHBOARD", 1L));
        when(store.auditAuthorizationScope("notice-permission")).thenReturn(
                scope("NOTICE", "NOTICE", 1L));
        when(store.auditAuthorizationScope("notice-command-capability")).thenReturn(
                scope("NOTICE", "COMMAND", null));
        when(store.auditAuthorizationScope("notice-type")).thenReturn(
                scope("NOTICE", "REPAIR", 1L));
        when(store.auditAuthorizationScope("notice-null-id")).thenReturn(
                scope("NOTICE", "NOTICE", null));
        when(store.auditAuthorizationScope("notice-zero-id")).thenReturn(
                scope("NOTICE", "NOTICE", 0L));
        when(store.auditAuthorizationScope("repair-null-id")).thenReturn(
                scope("REPAIR", "REPAIR", null));
        when(store.auditAuthorizationScope("repair-zero-id")).thenReturn(
                scope("REPAIR", "REPAIR", 0L));
        when(store.auditAuthorizationScope("risk-permission")).thenReturn(
                scope("RISK", "NONE", null));
        when(store.auditAuthorizationScope("approval")).thenReturn(
                scope("APPROVAL", "NONE", null));
        when(store.auditAuthorizationScope("audit")).thenReturn(scope("AUDIT", "NONE", null));
        when(store.auditAuthorizationScope("unknown")).thenReturn(scope("UNKNOWN", "NONE", null));

        assertNotFound(service, none, "dashboard-permission", AiCapability.DASHBOARD);
        assertNotFound(service, dashboard, "dashboard-type", AiCapability.DASHBOARD);
        assertNotFound(service, dashboard, "dashboard-id", AiCapability.DASHBOARD);
        assertNotFound(service, none, "notice-permission", AiCapability.NOTICE);
        assertNotFound(service, notice, "notice-command-capability", AiCapability.ASSISTANT);
        assertNotFound(service, notice, "notice-type", AiCapability.NOTICE);
        assertNotFound(service, notice, "notice-null-id", AiCapability.NOTICE);
        assertNotFound(service, notice, "notice-zero-id", AiCapability.NOTICE);
        assertNotFound(service, repair, "repair-null-id", AiCapability.REPAIR);
        assertNotFound(service, repair, "repair-zero-id", AiCapability.REPAIR);
        assertNotFound(service, none, "risk-permission", AiCapability.RISK);
        assertNotFound(service, none, "approval", AiCapability.ASSISTANT);
        assertNotFound(service, none, "audit", AiCapability.ASSISTANT);
        assertNotFound(service, none, "unknown", AiCapability.ASSISTANT);
    }

    @Test
    void hidesMissingCitationsAndExplicitCurrentAccessDenialsAsNotFound() {
        AiConversationRunStore store = mock(AiConversationRunStore.class);
        ContextResolverRegistry contexts = mock(ContextResolverRegistry.class);
        KnowledgeVersionRepository versions = mock(KnowledgeVersionRepository.class);
        AiActorContext repair = actor(Set.of("ai:repair:triage", "repair:read"));
        AiAuditContentAuthorizationService service = new AiAuditContentAuthorizationService(
                store, contexts, versions);
        when(store.auditAuthorizationScope("missing-citation")).thenReturn(
                new AiRunRecords.AuditAuthorizationScope(
                        9L, "GLOBAL", "NONE", null, List.of("missing-version")));
        when(versions.findByPublicId("missing-version")).thenReturn(Optional.empty());
        assertNotFound(service, repair, "missing-citation", AiCapability.ASSISTANT);

        when(store.auditAuthorizationScope("resolver-denied")).thenReturn(
                scope("REPAIR", "REPAIR", 42L));
        doThrow(
                new SecurityException("current actor cannot read repair"),
                new AiApiException(HttpStatus.FORBIDDEN, "AI_REPAIR_FORBIDDEN", "维修单不可见", false),
                new BusinessException(HttpStatus.NOT_FOUND, "维修单不存在"))
                .when(contexts).authorizeCurrentAccess(repair, "REPAIR", "REPAIR", 42L);
        assertNotFound(service, repair, "resolver-denied", AiCapability.REPAIR);
        assertNotFound(service, repair, "resolver-denied", AiCapability.REPAIR);
        assertNotFound(service, repair, "resolver-denied", AiCapability.REPAIR);
    }

    @Test
    void propagatesCurrentAccessInfrastructureFailuresWithoutMaskingThemAsNotFound() {
        AiConversationRunStore store = mock(AiConversationRunStore.class);
        ContextResolverRegistry contexts = mock(ContextResolverRegistry.class);
        KnowledgeVersionRepository versions = mock(KnowledgeVersionRepository.class);
        AiActorContext repair = actor(Set.of("ai:repair:triage", "repair:read"));
        AiAuditContentAuthorizationService service = new AiAuditContentAuthorizationService(
                store, contexts, versions);
        when(store.auditAuthorizationScope("resolver-unavailable")).thenReturn(
                scope("REPAIR", "REPAIR", 42L));
        IllegalStateException infrastructureFailure = new IllegalStateException("database unavailable");
        AiApiException aiUnavailable = AiApiException.unavailable(
                "AI_BUSINESS_READ_UNAVAILABLE", "业务读取暂不可用");
        BusinessException businessUnavailable = new BusinessException(
                HttpStatus.SERVICE_UNAVAILABLE, "业务服务暂不可用");
        doThrow(infrastructureFailure, aiUnavailable, businessUnavailable)
                .when(contexts).authorizeCurrentAccess(repair, "REPAIR", "REPAIR", 42L);

        assertSame(infrastructureFailure, assertThrows(IllegalStateException.class,
                () -> service.authorize(repair, "resolver-unavailable", AiCapability.REPAIR)));
        assertSame(aiUnavailable, assertThrows(AiApiException.class,
                () -> service.authorize(repair, "resolver-unavailable", AiCapability.REPAIR)));
        assertSame(businessUnavailable, assertThrows(BusinessException.class,
                () -> service.authorize(repair, "resolver-unavailable", AiCapability.REPAIR)));
    }

    private void assertNotFound(
            AiAuditContentAuthorizationService service,
            AiActorContext actor,
            String runId,
            AiCapability capability) {
        AiApiException error = assertThrows(AiApiException.class,
                () -> service.authorize(actor, runId, capability));
        assertEquals("AI_RESOURCE_NOT_FOUND", error.errorCode());
    }

    private AiRunRecords.AuditAuthorizationScope scope(String surface, String contextType, Long contextId) {
        return scope(9L, surface, contextType, contextId);
    }

    private AiRunRecords.AuditAuthorizationScope scope(
            long ownerUserId,
            String surface,
            String contextType,
            Long contextId) {
        return new AiRunRecords.AuditAuthorizationScope(
                ownerUserId, surface, contextType, contextId, List.of());
    }

    private KnowledgeVersionRepository.KnowledgeVersion version(String id, String sourceId) {
        return new KnowledgeVersionRepository.KnowledgeVersion(1, id, 2, "document-1", 3, sourceId,
                "v1", "a".repeat(64), KnowledgeVisibility.EXPLICIT_ACL,
                new ObjectStoragePort.ObjectReference("object", "version", "etag"),
                "text/plain", 10, "plain-v1", "chunk-v1", "ACTIVE", null);
    }

    private AiActorContext actor(Set<String> permissions) {
        return new AiActorContext(9L, "session", "f".repeat(64), 1, "d".repeat(64),
                List.of("AUDITOR"), permissions.stream().sorted().toList(), ActorDescriptor.user(9L));
    }
}
