package com.example.dormitory.ai.evaluation;

import com.example.dormitory.ai.dashboard.DashboardIntentParser;
import com.example.dormitory.ai.dashboard.MetricCatalog;
import com.example.dormitory.ai.dashboard.UnsupportedMetricException;
import com.example.dormitory.ai.approval.ActionProposalService;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.BusinessActorScope;
import com.example.dormitory.ai.domain.model.BusinessExecutionActor;
import com.example.dormitory.ai.notice.NoticeDraftService;
import com.example.dormitory.ai.port.BusinessReadFacade;
import com.example.dormitory.ai.repair.RepairTriageService;
import com.example.dormitory.ai.security.PromptInjectionGuard;
import com.example.dormitory.ai.security.PiiClassificationService;
import com.example.dormitory.ai.security.PiiRedactionService;
import com.example.dormitory.ai.knowledge.KnowledgeAclPolicy;
import com.example.dormitory.ai.knowledge.KnowledgeAssistantService;
import com.example.dormitory.ai.knowledge.KnowledgeVisibility;
import com.example.dormitory.ai.knowledge.PermissionMatchMode;
import com.example.dormitory.ai.knowledge.SafeKnowledgeService;
import com.example.dormitory.ai.risk.RiskExplanationPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class OfflineEvalTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void dashboardIntentDatasetHasExactDeterministicOutcomes() throws Exception {
        DashboardIntentParser parser = new DashboardIntentParser(MetricCatalog.defaults());
        for (JsonNode item : dataset("dashboard-intent-v1.jsonl")) {
            JsonNode input = item.path("input");
            JsonNode expected = item.path("expected");
            if ("REFUSE".equals(expected.path("securityOutcome").asText())) {
                assertThrows(UnsupportedMetricException.class,
                        () -> parser.parse(input.path("question").asText()), item.path("caseKey").asText());
            } else {
                assertTrue(parser.parse(input.path("question").asText()).metricIds()
                        .contains(expected.path("metricId").asText()), item.path("caseKey").asText());
            }
        }
    }

    @Test
    void securityRedteamDatasetMeetsZeroToleranceBlockContract() throws Exception {
        PromptInjectionGuard guard = new PromptInjectionGuard();
        int blockedExpected = 0;
        int blockedActual = 0;
        for (JsonNode item : dataset("security-redteam-v1.jsonl")) {
            boolean expected = item.path("expected").path("blocked").asBoolean();
            boolean actual = guard.inspect(item.path("input").path("text").asText()).blocked();
            if (expected) blockedExpected++;
            if (actual) blockedActual++;
            assertEquals(expected, actual, item.path("caseKey").asText());
        }
        assertEquals(blockedExpected, blockedActual);
        assertTrue(blockedExpected > 0);
    }

    @Test
    void knowledgeGroundingDatasetExecutesAclGroundingAndRefusalContract() throws Exception {
        PiiRedactionService redactor = new PiiRedactionService(
                "offline-eval-tokenization-key-v1".getBytes(StandardCharsets.UTF_8), "eval-v1");
        PromptInjectionGuard guard = new PromptInjectionGuard();
        SafeKnowledgeService knowledge = new SafeKnowledgeService(new KnowledgeAclPolicy(), guard, redactor);
        knowledge.registerApprovedText(new SafeKnowledgeService.KnowledgeDocument(
                "source-eval", "document-eval", "version-eval", "content-hash-eval", "维修制度",
                "普通维修应当在两个工作日内处理。", KnowledgeVisibility.EXPLICIT_ACL,
                PermissionMatchMode.ANY, Set.of("repair:read"), true));
        KnowledgeAssistantService assistant = new KnowledgeAssistantService(
                knowledge, guard, new PiiClassificationService(redactor, List::of));

        for (JsonNode item : dataset("knowledge-grounding-v1.jsonl")) {
            JsonNode input = item.path("input");
            Set<String> permissions = objectMapper.convertValue(input.path("permissions"),
                    objectMapper.getTypeFactory().constructCollectionType(Set.class, String.class));
            boolean grounded = assistant.answer(input.path("query").asText(), permissions, 5).grounded();
            assertEquals(item.path("expected").path("grounded").asBoolean(), grounded,
                    item.path("caseKey").asText());
        }
    }

    @Test
    void riskExplanationDatasetUsesOnlyRuleEvidenceAndHumanVerificationLanguage() throws Exception {
        RiskExplanationPolicy policy = new RiskExplanationPolicy();
        for (JsonNode item : dataset("risk-explanation-v1.jsonl")) {
            JsonNode input = item.path("input");
            JsonNode expected = item.path("expected");
            Map<String, Object> evidence = objectMapper.convertValue(input.path("evidence"),
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
            String explanation = policy.safeFallback(input.path("riskType").asText(), evidence);
            assertTrue(explanation.contains(expected.path("mustMention").asText()),
                    item.path("caseKey").asText());
            for (JsonNode forbidden : expected.path("forbiddenPatterns")) {
                assertFalse(explanation.contains(forbidden.asText()), item.path("caseKey").asText());
            }
        }
    }

    @Test
    void repairTriageDatasetHasExactCategoryUrgencyAndInjectionDegradation() throws Exception {
        ActionProposalService proposals = mock(ActionProposalService.class);
        BusinessActorScope scope = new BusinessActorScope(
                ActorDescriptor.user(9L), Set.of("ai:repair:triage", "repair:read"),
                Map.of("REPAIR_ORDER", Set.of(1L)));
        BusinessExecutionActor actor = BusinessExecutionActor.from(ActorDescriptor.user(9L));
        for (JsonNode item : dataset("repair-triage-v1.jsonl")) {
            JsonNode input = item.path("input");
            JsonNode expected = item.path("expected");
            String payload = objectMapper.writeValueAsString(Map.of(
                    "repairOrderId", 1,
                    "status", "待处理",
                    "description", input.path("description").asText(),
                    "type", input.path("type").asText()));
            BusinessReadFacade reads = (ignoredScope, request) -> new BusinessReadFacade.BusinessReadResult(
                    "repair-context.v1", payload, Instant.parse("2026-07-10T00:00:00Z"));
            RepairTriageService service = new RepairTriageService(
                    reads, proposals, new PromptInjectionGuard(), objectMapper);

            var result = service.triage(1L, scope, actor, "eval-idempotency", "a".repeat(64),
                    java.util.UUID.randomUUID().toString());

            assertEquals(expected.path("category").asText(), result.category(), item.path("caseKey").asText());
            assertEquals(expected.path("urgency").asText(), result.urgency(), item.path("caseKey").asText());
            assertEquals(expected.path("degraded").asBoolean(), result.degraded(), item.path("caseKey").asText());
        }
    }

    @Test
    void noticeDraftDatasetHasExactPureTextSafetyOutcomes() throws Exception {
        ActionProposalService proposals = mock(ActionProposalService.class);
        NoticeDraftService service = new NoticeDraftService(
                proposals,
                new PromptInjectionGuard(),
                new PiiRedactionService("offline-eval-tokenization-key-v1".getBytes(StandardCharsets.UTF_8), "eval-v1"),
                objectMapper);
        BusinessExecutionActor actor = BusinessExecutionActor.from(ActorDescriptor.user(9L));
        for (JsonNode item : dataset("notice-draft-v1.jsonl")) {
            JsonNode input = item.path("input");
            JsonNode expected = item.path("expected");
            var result = service.draft(new NoticeDraftService.DraftCommand(
                            input.path("points").asText(), input.path("type").asText(),
                            input.path("tone").asText(), input.path("audience").asText()),
                    actor, Set.of("ai:notice:draft", "notice:read", "notice:write"),
                    "eval-idempotency", "b".repeat(64), java.util.UUID.randomUUID().toString());
            assertEquals(expected.path("blocked").asBoolean(), result.blocked(), item.path("caseKey").asText());
            if (!result.blocked()) {
                assertFalse(result.content().contains("<"), item.path("caseKey").asText());
                assertEquals("草稿", result.status(), item.path("caseKey").asText());
            }
        }
    }

    @Test
    void allDatasetsAreVersionedSyntheticAndContainNoObviousSecretsOrRawPhoneNumbers() throws Exception {
        for (String name : List.of("dashboard-intent-v1.jsonl", "knowledge-grounding-v1.jsonl",
                "repair-triage-v1.jsonl", "notice-draft-v1.jsonl",
                "security-redteam-v1.jsonl", "risk-explanation-v1.jsonl")) {
            String text = resource(name);
            assertFalse(text.matches("(?s).*1[3-9]\\d{9}.*"), name);
            assertFalse(text.matches("(?is).*(sk-[a-z0-9_-]{8,}|api[_-]?key\\s*[:=]).*"), name);
            assertTrue(name.contains("-v1"));
            assertFalse(dataset(name).isEmpty());
        }
    }

    private List<JsonNode> dataset(String name) throws Exception {
        return resource(name).lines().filter(line -> !line.isBlank())
                .map(line -> {
                    try { return objectMapper.readTree(line); }
                    catch (Exception exception) { throw new IllegalArgumentException(name, exception); }
                }).toList();
    }

    private String resource(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/ai/eval/" + name)) {
            if (input == null) throw new IllegalStateException("missing eval dataset: " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
