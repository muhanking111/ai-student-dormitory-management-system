package com.example.dormitory.ai.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiRateLimitPolicyTest {

    private final AiRateLimitPolicy policy = new AiRateLimitPolicy(new AiRateLimitProperties());

    @Test
    void classifiesEveryExpensiveAndWriteSurface() {
        assertPolicy("POST", "/api/ai/conversations/c1/messages", "RUN");
        assertPolicy("POST", "/api/ai/dashboard/queries", "RUN");
        assertPolicy("POST", "/api/ai/eval-runs", "RUN");
        assertPolicy("POST", "/api/ai/knowledge/sources/s1/uploads", "UPLOAD_CREATE");
        assertPolicy("PUT", "/api/ai/knowledge/uploads/u1/content", "UPLOAD_CONTENT");
        assertPolicy("POST", "/api/ai/knowledge/uploads/u1/finalize", "UPLOAD_FINALIZE");
        assertPolicy("POST", "/api/ai/knowledge/sources/s1/versions", "INGESTION");
        assertPolicy("POST", "/api/ai/risk-scans", "RISK_SCAN");
        assertPolicy("POST", "/api/ai/proposals/p1/approve", "APPROVAL");
        assertPolicy("POST", "/api/ai/proposals/p1/reject", "APPROVAL");
        assertPolicy("POST", "/api/ai/executions/p1/reconfirm", "APPROVAL");
        assertPolicy("POST", "/api/ai/operations/kill-switches/CAPABILITY/risk/disable", "WRITE");
    }

    @Test
    void approvalCarriesAnIndependentResourceBucketAndSafeReadsAreIgnored() {
        AiRateLimitPolicy.Rule approval = policy.resolve(
                "POST", "/api/ai/proposals/0f9b4f96-5145-46eb-91fd-3c58a7ad1117/approve").orElseThrow();

        assertTrue(approval.resourceKey().isPresent());
        assertEquals(5, approval.resourceLimit());
        assertFalse(policy.resolve("GET", "/api/ai/proposals").isPresent());
        assertFalse(policy.resolve("HEAD", "/api/ai/runs/r1").isPresent());
        assertFalse(policy.resolve("OPTIONS", "/api/ai/runs/r1").isPresent());
        assertFalse(policy.resolve("POST", "/api/not-ai").isPresent());
    }

    private void assertPolicy(String method, String path, String expected) {
        assertEquals(expected, policy.resolve(method, path).orElseThrow().policyCode());
    }
}
