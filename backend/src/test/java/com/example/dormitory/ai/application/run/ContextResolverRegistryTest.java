package com.example.dormitory.ai.application.run;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.knowledge.KnowledgeAclPolicy;
import com.example.dormitory.ai.knowledge.KnowledgeAssistantService;
import com.example.dormitory.ai.knowledge.KnowledgeVisibility;
import com.example.dormitory.ai.knowledge.PermissionMatchMode;
import com.example.dormitory.ai.knowledge.SafeKnowledgeService;
import com.example.dormitory.ai.port.BusinessReadFacade;
import com.example.dormitory.ai.port.AiToolCallAuditPort;
import com.example.dormitory.ai.security.PiiClassificationService;
import com.example.dormitory.ai.security.PiiRedactionService;
import com.example.dormitory.ai.security.PromptInjectionGuard;
import com.example.dormitory.ai.tool.ToolCatalog;
import com.example.dormitory.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextResolverRegistryTest {

    @Test
    void repairContextUsesOnlyServerSideFixedQueryAndFixedAuthorizedTool() {
        AtomicReference<BusinessReadFacade.BusinessReadRequest> observed = new AtomicReference<>();
        ContextResolverRegistry registry = registry((scope, request) -> {
            observed.set(request);
            return new BusinessReadFacade.BusinessReadResult("repair-ai-context.v1",
                    "{\"repairOrderId\":42,\"description\":\"已脱敏事实\"}", Instant.now());
        });

        ContextResolverRegistry.Resolution result = registry.resolveForRun(UUID.randomUUID().toString(),
                actor(Set.of("ai:assistant:use", "ai:repair:triage", "repair:read")),
                "REPAIR", "REPAIR", 42L, "客户端伪造：另一张维修单正文");

        assertEquals("repair.context.v1", observed.get().queryId());
        assertEquals("42", observed.get().parameters().get("repairOrderId"));
        assertEquals(Set.of("repair.get_context.v1"), result.allowedToolIds());
        assertTrue(result.modelPrompt("问题").contains("已脱敏事实"));
        assertFalse(result.modelPrompt("问题").contains("客户端伪造"));
    }

    @Test
    void dashboardNoticeAndKnowledgeHaveStrictContextShapesAndPermissions() {
        ContextResolverRegistry registry = registry((scope, request) ->
                new BusinessReadFacade.BusinessReadResult("context.v1", "{\"safe\":true}", Instant.now()));

        assertEquals(Set.of("dashboard.query_metric.v1"), registry.resolveForRun(UUID.randomUUID().toString(),
                actor(Set.of("ai:assistant:use", "ai:dashboard:query", "dashboard:read")),
                "DASHBOARD", "DASHBOARD", null, "概览").allowedToolIds());
        assertEquals(Set.of("notice.list_published.v1"), registry.resolveForRun(UUID.randomUUID().toString(),
                actor(Set.of("ai:assistant:use", "notice:read")),
                "NOTICE", "NOTICE", 7L, "公告").allowedToolIds());
        assertThrows(AiApiException.class, () -> registry.resolve(actor(Set.of("ai:assistant:use")),
                "REPAIR", "REPAIR", 42L, "问题"));
        assertThrows(AiApiException.class, () -> registry.resolve(actor(Set.of(
                        "ai:assistant:use", "notice:read")),
                "NOTICE", "NOTICE", null, "问题"));
    }

    @Test
    void knowledgeContextReturnsOnlyAclGroundedCitationOrDeterministicRefusal() {
        SafeKnowledgeService safe = safeKnowledge();
        safe.registerApprovedText(new SafeKnowledgeService.KnowledgeDocument(
                "source", "document", "version", "a".repeat(64), "维修制度",
                "空调报修应在两个工作日内处理。", KnowledgeVisibility.EXPLICIT_ACL,
                PermissionMatchMode.ALL, Set.of("ai:knowledge:read", "repair:read"), true));
        ContextResolverRegistry registry = new ContextResolverRegistry((scope, request) -> {
            throw new AssertionError("知识上下文不应动态调用业务 queryId");
        }, new KnowledgeAssistantService(safe, new PromptInjectionGuard(), classifier()), ToolCatalog.standard(),
                new com.fasterxml.jackson.databind.ObjectMapper(), ignored -> { });

        ContextResolverRegistry.Resolution denied = registry.resolveForRun(UUID.randomUUID().toString(),
                actor(Set.of("ai:assistant:use", "ai:knowledge:read")),
                "KNOWLEDGE", "KNOWLEDGE", null, "空调报修如何处理");
        assertEquals("暂无可靠来源，无法确认", denied.directResponse());
        assertFalse(denied.grounded());
        assertTrue(denied.citations().isEmpty());
        assertFalse(denied.contextJson().contains("两个工作日"));

        ContextResolverRegistry.Resolution allowed = registry.resolveForRun(UUID.randomUUID().toString(),
                actor(Set.of("ai:assistant:use", "ai:knowledge:read", "repair:read")),
                "KNOWLEDGE", "KNOWLEDGE", null, "空调报修如何处理");
        assertTrue(allowed.contextJson().contains("两个工作日"));
        assertEquals(Set.of("knowledge.search.v1"), allowed.allowedToolIds());
        assertTrue(allowed.grounded());
        assertEquals(1, allowed.citations().size());
        assertEquals("source", allowed.citations().getFirst().sourcePublicId());

        ContextResolverRegistry.Resolution global = registry.resolveForRun(UUID.randomUUID().toString(),
                actor(Set.of("ai:assistant:use", "ai:knowledge:read", "repair:read")),
                "GLOBAL", "NONE", null, "空调报修如何处理");
        assertTrue(global.grounded());
        assertEquals(allowed.directResponse(), global.directResponse());
        assertEquals(1, global.citations().size());
    }

    @Test
    void runBoundResolutionAuditsAllFiveFixedReadToolsWithRedactedFacts() {
        List<AiToolCallAuditPort.ToolCallAudit> audits = new ArrayList<>();
        ContextResolverRegistry registry = registry((scope, request) ->
                new BusinessReadFacade.BusinessReadResult(request.queryId(),
                        "{\"safe\":true,\"queryId\":\"" + request.queryId() + "\"}", Instant.now()), audits);
        AiActorContext actor = actor(Set.of(
                "ai:assistant:use", "ai:knowledge:read", "ai:dashboard:query",
                "ai:repair:triage", "repair:read", "dormitory:read", "notice:read"));

        registry.resolveForRun(UUID.randomUUID().toString(), actor,
                "DASHBOARD", "DASHBOARD", null, "已脱敏查询");
        registry.resolveForRun(UUID.randomUUID().toString(), actor,
                "REPAIR", "REPAIR", 42L, "已脱敏查询");
        registry.resolveForRun(UUID.randomUUID().toString(), actor,
                "NOTICE", "NOTICE", 7L, "已脱敏查询");
        registry.resolveForRun(UUID.randomUUID().toString(), actor,
                "KNOWLEDGE", "KNOWLEDGE", null, "已脱敏查询");
        registry.resolveForRun(UUID.randomUUID().toString(), actor,
                "GLOBAL", "NONE", null, "已脱敏查询");

        assertEquals(Set.of(
                        "knowledge.search.v1", "dashboard.query_metric.v1", "repair.get_context.v1",
                        "dormitory.get_capacity_summary.v1", "notice.list_published.v1"),
                audits.stream().map(AiToolCallAuditPort.ToolCallAudit::toolName).collect(java.util.stream.Collectors.toSet()));
        assertTrue(audits.stream().allMatch(audit ->
                audit.authorizationDecision() == AiToolCallAuditPort.AuthorizationDecision.ALLOWED
                        && audit.state() == AiToolCallAuditPort.State.SUCCEEDED
                        && audit.errorCode() == null
                        && audit.responseRedacted() != null));
        assertTrue(audits.stream().anyMatch(audit -> audit.requestRedacted().contains("已脱敏查询")));
        assertTrue(audits.stream().noneMatch(audit ->
                audit.requestRedacted().contains("原始隐私")
                        || audit.responseRedacted().contains("原始隐私")));
    }

    @Test
    void deniedAndFailedContextToolsAreAuditedBeforeTheRequestFails() {
        List<AiToolCallAuditPort.ToolCallAudit> deniedAudits = new ArrayList<>();
        ContextResolverRegistry denied = registry((scope, request) -> {
            throw new AssertionError("拒绝时不应读取业务数据");
        }, deniedAudits);

        assertThrows(AiApiException.class, () -> denied.resolveForRun(
                UUID.randomUUID().toString(), actor(Set.of("ai:assistant:use")),
                "REPAIR", "REPAIR", 42L, "已脱敏查询"));
        assertEquals(1, deniedAudits.size());
        assertEquals(AiToolCallAuditPort.AuthorizationDecision.DENIED,
                deniedAudits.getFirst().authorizationDecision());
        assertEquals(AiToolCallAuditPort.State.DENIED, deniedAudits.getFirst().state());
        assertEquals("AI_TOOL_DENIED", deniedAudits.getFirst().errorCode());

        List<AiToolCallAuditPort.ToolCallAudit> failedAudits = new ArrayList<>();
        ContextResolverRegistry failed = registry((scope, request) -> {
            throw new IllegalStateException("业务读取失败");
        }, failedAudits);
        assertThrows(IllegalStateException.class, () -> failed.resolveForRun(
                UUID.randomUUID().toString(), actor(Set.of(
                        "ai:assistant:use", "ai:dashboard:query", "dashboard:read")),
                "DASHBOARD", "DASHBOARD", null, "已脱敏查询"));
        assertEquals(AiToolCallAuditPort.AuthorizationDecision.ALLOWED,
                failedAudits.getFirst().authorizationDecision());
        assertEquals(AiToolCallAuditPort.State.FAILED, failedAudits.getFirst().state());
        assertEquals("AI_TOOL_EXECUTION_FAILED", failedAudits.getFirst().errorCode());
    }

    @Test
    void preflightWithoutRunPerformsOnlyShapeAndCatalogAuthorizationChecks() {
        java.util.concurrent.atomic.AtomicInteger businessReads = new java.util.concurrent.atomic.AtomicInteger();
        List<AiToolCallAuditPort.ToolCallAudit> audits = new ArrayList<>();
        ContextResolverRegistry registry = registry((scope, request) -> {
            businessReads.incrementAndGet();
            throw new AssertionError("无 run 的预校验不得读取真实业务数据");
        }, audits);
        AiActorContext actor = actor(Set.of(
                "ai:assistant:use", "ai:knowledge:read", "ai:dashboard:query",
                "ai:repair:triage", "repair:read", "dormitory:read", "notice:read"));

        assertEquals(Set.of("dashboard.query_metric.v1"), registry.resolve(
                actor, "DASHBOARD", "DASHBOARD", null, null).allowedToolIds());
        assertEquals(Set.of("repair.get_context.v1"), registry.resolve(
                actor, "REPAIR", "REPAIR", 42L, null).allowedToolIds());
        assertEquals(Set.of("notice.list_published.v1"), registry.resolve(
                actor, "NOTICE", "NOTICE", 7L, null).allowedToolIds());
        assertEquals(Set.of("knowledge.search.v1"), registry.resolve(
                actor, "KNOWLEDGE", "KNOWLEDGE", null, null).allowedToolIds());
        assertEquals(0, businessReads.get());
        assertTrue(audits.isEmpty());
    }

    @Test
    void currentAccessRechecksBusinessFactsWithoutWritingToolAuditsOrReturningBodies() {
        List<BusinessReadFacade.BusinessReadRequest> observed = new ArrayList<>();
        List<AiToolCallAuditPort.ToolCallAudit> audits = new ArrayList<>();
        ContextResolverRegistry registry = registry((scope, request) -> {
            observed.add(request);
            return new BusinessReadFacade.BusinessReadResult(
                    request.queryId(), "{\"sensitive\":\"discarded\"}", Instant.now());
        }, audits);
        AiActorContext actor = actor(Set.of(
                "ai:assistant:use", "ai:knowledge:read", "ai:dashboard:query",
                "ai:repair:triage", "repair:read", "dormitory:read", "notice:read"));

        registry.authorizeCurrentAccess(actor, "DASHBOARD", "DASHBOARD", null);
        registry.authorizeCurrentAccess(actor, "REPAIR", "REPAIR", 42L);
        registry.authorizeCurrentAccess(actor, "NOTICE", "NOTICE", 7L);
        registry.authorizeCurrentAccess(actor, "GLOBAL", "NONE", null);
        registry.authorizeCurrentAccess(actor, "KNOWLEDGE", "KNOWLEDGE", null);

        assertEquals(List.of("dashboard.context.v1", "repair.context.v1", "notice.context.v1"),
                observed.stream().map(BusinessReadFacade.BusinessReadRequest::queryId).toList());
        assertEquals("42", observed.get(1).parameters().get("repairOrderId"));
        assertEquals("7", observed.get(2).parameters().get("noticeId"));
        assertTrue(audits.isEmpty());
    }

    @Test
    void freshRbacAndObjectScopeRejectionsAreDeniedRatherThanAllowedFailures() {
        List<AiToolCallAuditPort.ToolCallAudit> freshRbacAudits = new ArrayList<>();
        ContextResolverRegistry freshRbac = registry((scope, request) -> {
            throw new SecurityException("权限已撤销");
        }, freshRbacAudits);

        assertThrows(SecurityException.class, () -> freshRbac.resolveForRun(
                UUID.randomUUID().toString(), actor(Set.of(
                        "ai:assistant:use", "ai:dashboard:query", "dashboard:read")),
                "DASHBOARD", "DASHBOARD", null, "已脱敏查询"));
        assertDenied(freshRbacAudits.getFirst(), "AI_TOOL_FRESH_AUTH_DENIED");

        List<AiToolCallAuditPort.ToolCallAudit> objectAudits = new ArrayList<>();
        ContextResolverRegistry objectDenied = registry((scope, request) -> {
            throw new BusinessException(HttpStatus.NOT_FOUND, "维修单不存在");
        }, objectAudits);

        assertThrows(BusinessException.class, () -> objectDenied.resolveForRun(
                UUID.randomUUID().toString(), actor(Set.of(
                        "ai:assistant:use", "ai:repair:triage", "repair:read")),
                "REPAIR", "REPAIR", 42L, "已脱敏查询"));
        assertDenied(objectAudits.getFirst(), "AI_TOOL_OBJECT_ACCESS_DENIED");
    }

    private void assertDenied(AiToolCallAuditPort.ToolCallAudit audit, String errorCode) {
        assertEquals(AiToolCallAuditPort.AuthorizationDecision.DENIED, audit.authorizationDecision());
        assertEquals(AiToolCallAuditPort.State.DENIED, audit.state());
        assertEquals(errorCode, audit.errorCode());
    }

    private ContextResolverRegistry registry(BusinessReadFacade reads) {
        return registry(reads, new ArrayList<>());
    }

    private ContextResolverRegistry registry(
            BusinessReadFacade reads,
            List<AiToolCallAuditPort.ToolCallAudit> audits) {
        return new ContextResolverRegistry(reads,
                new KnowledgeAssistantService(safeKnowledge(), new PromptInjectionGuard(), classifier()),
                ToolCatalog.standard(), new com.fasterxml.jackson.databind.ObjectMapper(), audits::add);
    }

    private SafeKnowledgeService safeKnowledge() {
        return new SafeKnowledgeService(new KnowledgeAclPolicy(), new PromptInjectionGuard(), redactor());
    }

    private PiiRedactionService redactor() {
        return new PiiRedactionService("context-test-key-32-bytes-minimum!!"
                .getBytes(StandardCharsets.UTF_8), "v1");
    }

    private PiiClassificationService classifier() {
        return new PiiClassificationService(redactor(), List::of);
    }

    private AiActorContext actor(Set<String> permissions) {
        List<String> sorted = permissions.stream().sorted().toList();
        return new AiActorContext(10, "token", "fingerprint", 1, "digest",
                List.of("ADMIN"), sorted, ActorDescriptor.user(10L));
    }
}
