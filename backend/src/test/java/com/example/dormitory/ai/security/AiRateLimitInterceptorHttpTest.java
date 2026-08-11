package com.example.dormitory.ai.security;

import com.example.dormitory.ai.api.AiExceptionHandler;
import com.example.dormitory.ai.config.AiProperties;
import com.example.dormitory.common.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AiRateLimitInterceptorHttpTest {

    @Test
    void returnsReal429RetryAfterAndStableAiEnvelopeForActorLimit() throws Exception {
        Harness harness = harness(1, 2, 10, 5);

        harness.mvc.perform(post("/api/ai/dashboard/queries")
                        .header("X-Test-Actor", "u1").with(remote("10.0.0.8")))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Limit", "1"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(header().exists("X-RateLimit-Reset"));

        harness.mvc.perform(post("/api/ai/dashboard/queries")
                        .header("X-Test-Actor", "u1").with(remote("10.0.0.8")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.data.errorCode").value("AI_RATE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.data.retryable").value(true))
                .andExpect(jsonPath("$.data.metadata.policy").value("RUN"));
        assertEquals(1, harness.controller.runInvocations.get(), "429 必须在昂贵 handler/provider 前拒绝");
    }

    @Test
    void alsoLimitsIndependentActorsSharingTheServerObservedIp() throws Exception {
        Harness harness = harness(1, 2, 10, 5);

        harness.mvc.perform(post("/api/ai/dashboard/queries")
                        .header("X-Test-Actor", "u1").with(remote("10.0.0.9")))
                .andExpect(status().isOk());
        harness.mvc.perform(post("/api/ai/dashboard/queries")
                        .header("X-Test-Actor", "u2").with(remote("10.0.0.9")))
                .andExpect(status().isOk());
        harness.mvc.perform(post("/api/ai/dashboard/queries")
                        .header("X-Test-Actor", "u3").with(remote("10.0.0.9")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.data.errorCode").value("AI_RATE_LIMIT_EXCEEDED"));
    }

    @Test
    void approvalAddsProposalBucketAcrossDifferentActorsAndIps() throws Exception {
        Harness harness = harness(100, 10, 100, 1);
        String proposal = "0f9b4f96-5145-46eb-91fd-3c58a7ad1117";

        harness.mvc.perform(post("/api/ai/proposals/{id}/approve", proposal)
                        .header("X-Test-Actor", "u1").with(remote("10.0.0.1")))
                .andExpect(status().isOk());
        harness.mvc.perform(post("/api/ai/proposals/{id}/approve", proposal)
                        .header("X-Test-Actor", "u2").with(remote("10.0.0.2")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.data.metadata.policy").value("APPROVAL"));
    }

    private Harness harness(int runsPerMinute, int ipMultiplier,
                            int approvalsPerMinute, int approvalsPerProposal) {
        AiRateLimitProperties properties = new AiRateLimitProperties();
        properties.setRunsPerMinute(runsPerMinute);
        properties.setIpMultiplier(ipMultiplier);
        properties.setApprovalsPerMinute(approvalsPerMinute);
        properties.setApprovalsPerProposalPerMinute(approvalsPerProposal);
        AiProperties ai = new AiProperties();
        ai.setEnabled(true);
        AiRateLimitIdentityResolver identities = request -> new AiRateLimitIdentityResolver.Identity(
                request.getHeader("X-Test-Actor"), "ip-" + request.getRemoteAddr());
        AiRateLimitInterceptor interceptor = new AiRateLimitInterceptor(
                new AiRateLimitPolicy(properties), new FixedWindowStore(), identities, properties, ai);
        ProbeController controller = new ProbeController();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new AiExceptionHandler())
                .addInterceptors(interceptor)
                .build();
        return new Harness(mvc, controller);
    }

    private RequestPostProcessor remote(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private record Harness(MockMvc mvc, ProbeController controller) { }

    private static final class FixedWindowStore implements AiRateLimitStore {
        private final Map<String, Integer> counts = new HashMap<>();

        @Override
        public synchronized Decision consume(List<Bucket> buckets) {
            for (Bucket bucket : buckets) {
                if (counts.getOrDefault(bucket.key(), 0) >= bucket.limit()) {
                    return Decision.denied(60);
                }
            }
            int remaining = Integer.MAX_VALUE;
            for (Bucket bucket : buckets) {
                int count = counts.merge(bucket.key(), 1, Integer::sum);
                remaining = Math.min(remaining, bucket.limit() - count);
            }
            return Decision.allowed(remaining, 60);
        }
    }

    @RestController
    @RequestMapping("/api/ai")
    private static final class ProbeController {
        private final AtomicInteger runInvocations = new AtomicInteger();

        @PostMapping(value = "/dashboard/queries", produces = MediaType.APPLICATION_JSON_VALUE)
        ResponseEntity<ApiResponse<String>> run() {
            runInvocations.incrementAndGet();
            return ResponseEntity.ok(ApiResponse.ok("accepted"));
        }

        @PostMapping(value = "/proposals/{id}/approve", produces = MediaType.APPLICATION_JSON_VALUE)
        ResponseEntity<ApiResponse<String>> approve(@PathVariable String id) {
            return ResponseEntity.ok(ApiResponse.ok(id));
        }
    }
}
