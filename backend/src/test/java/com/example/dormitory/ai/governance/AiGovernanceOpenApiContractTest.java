package com.example.dormitory.ai.governance;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiGovernanceOpenApiContractTest {

    @Test
    void documentsVersionedGovernanceEvaluationCostAndReauthorizedCitationContracts() throws Exception {
        try (var input = getClass().getResourceAsStream("/openapi/ai-v1.yaml")) {
            assertNotNull(input);
            String yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            for (String path : List.of(
                    "/api/ai/prompts", "/api/ai/prompts/{id}",
                    "/api/ai/prompts/{id}/activate", "/api/ai/model-aliases/{alias}/activate",
                    "/api/ai/tool-catalogs", "/api/ai/tool-catalogs/{id}/activate",
                    "/api/ai/eval-runs", "/api/ai/eval-runs/{id}",
                    "/api/ai/audit/costs", "/api/ai/citations/{id}")) {
                assertTrue(yaml.contains(path + ":"), path);
            }
            assertTrue(yaml.contains("enum: [CAPABILITY, PROVIDER, MODEL, SUBJECT_KIND, DAY]"));
            assertTrue(yaml.contains("确定性合同评测"));
            assertTrue(yaml.contains("X-Step-Up-Proof"));
            assertTrue(yaml.contains("Idempotency-Key"));
            assertTrue(yaml.contains("AI_RATE_LIMIT_EXCEEDED"));
            int sourceDetail = yaml.indexOf("/api/ai/knowledge/sources/{id}:");
            int nextPath = yaml.indexOf("/api/ai/knowledge/sources/{id}/uploads:", sourceDetail);
            assertTrue(sourceDetail >= 0 && nextPath > sourceDetail
                    && yaml.substring(sourceDetail, nextPath).contains("patch:"),
                    "知识来源详情必须声明 PATCH/ACL CAS 合同");
            for (String header : List.of("Retry-After", "X-RateLimit-Limit",
                    "X-RateLimit-Remaining", "X-RateLimit-Reset", "X-RateLimit-Policy")) {
                assertTrue(yaml.contains(header + ":"), header);
            }
            assertFalse(yaml.contains("executeSql"));
            assertFalse(yaml.contains("dynamicUrl"));
        }
    }
}
