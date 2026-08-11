package com.example.dormitory.ai.contract;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiOpenApiContractTest {

    @Test
    void documentsAllCriticalSafetySurfacesWithoutSecretsOrDynamicExecution() throws Exception {
        try (var input = getClass().getResourceAsStream("/openapi/ai-v1.yaml")) {
            assertNotNull(input);
            String yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            for (String path : List.of(
                    "/api/ai/conversations", "/api/ai/runs/{id}/events",
                    "/api/ai/knowledge/sources", "/api/ai/dashboard/queries",
                    "/api/ai/conversations/{id}", "/api/ai/erasure-jobs/{id}",
                    "/api/ai/risk-cases", "/api/ai/risk-scans",
                    "/api/ai/proposals/{id}/approve", "/api/ai/executions/{id}/reconfirm",
                    "/api/ai/audit/runs/{id}/content", "/api/ai/operations/kill-switches",
                    "/api/security/csrf", "/api/security/step-up")) {
                assertTrue(yaml.contains(path + ":"), path);
            }
            assertTrue(yaml.contains("X-Step-Up-Proof"));
            assertTrue(yaml.contains("Idempotency-Key"));
            assertTrue(yaml.contains("text/event-stream"));
            assertTrue(yaml.contains("DashboardQueryIntent:"));
            assertTrue(yaml.contains("DashboardQueryResponse:"));
            assertTrue(yaml.contains("DashboardDataCitation:"));
            assertTrue(yaml.contains("ConversationDetailEnvelope:"));
            assertTrue(yaml.contains("ConversationDetail:"));
            assertTrue(yaml.contains("ConversationMessage:"));
            assertTrue(yaml.contains("ConversationCitationSummary:"));
            assertTrue(yaml.contains("历史会话引用摘要不得返回 quote 或 confidence"));
            assertTrue(yaml.contains("const: DashboardQueryIntent.v1"));
            assertTrue(yaml.contains("const: dashboard-metrics.v1"));
            int proposalListStart = yaml.indexOf("  /api/ai/proposals:\n");
            int proposalDetailStart = yaml.indexOf("  /api/ai/proposals/{id}:", proposalListStart);
            assertTrue(proposalListStart >= 0 && proposalDetailStart > proposalListStart);
            String proposalListContract = yaml.substring(proposalListStart, proposalDetailStart);
            assertTrue(proposalListContract.contains("name: actionType"));
            assertTrue(proposalListContract.contains("enum: [REPAIR_ASSIGN, NOTICE_CREATE_DRAFT]"));
            assertTrue(proposalListContract.contains("enum: [DRAFT, PENDING_APPROVAL, APPROVED, EXECUTING,"
                    + " NEEDS_REVIEW, SUCCEEDED, REJECTED, EXPIRED, STALE, CANCELLED, FAILED]"));
            assertFalse(yaml.matches("(?is).*(sk-[a-z0-9_-]{8,}|api[_-]?key\\s*[:=]\\s*[^}\\n]+).*"));
            assertFalse(yaml.contains("executeSql"));
            assertFalse(yaml.contains("dynamicUrl"));
        }
    }
}
