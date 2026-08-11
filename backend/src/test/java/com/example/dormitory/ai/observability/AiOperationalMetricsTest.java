package com.example.dormitory.ai.observability;

import com.example.dormitory.ai.domain.model.AiCapability;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiOperationalMetricsTest {

    @Test
    void recordsTheP6OperationalSurfaceWithOnlyRegisteredLowCardinalityTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiOperationalMetrics metrics = new AiOperationalMetrics(registry, vocabulary());

        metrics.recordRun(new AiOperationalMetrics.RunSample(
                AiCapability.ASSISTANT, "fake", "fake-v1",
                AiOperationalMetrics.RunOutcome.SUCCEEDED, AiOperationalMetrics.DegradeMode.NONE,
                Duration.ofMillis(120), Duration.ofMillis(20), 12, 8,
                new BigDecimal("0.001200"), "CNY"));
        metrics.recordProviderAttempt(new AiOperationalMetrics.ProviderAttemptSample(
                AiCapability.ASSISTANT, "fake", "fake-v1",
                AiOperationalMetrics.RequestKind.MODEL_STREAM,
                AiOperationalMetrics.AttemptOutcome.SUCCEEDED,
                AiOperationalMetrics.UsageSource.ESTIMATED,
                Duration.ofMillis(110), 12, 8, new BigDecimal("0.001200"), "CNY"));
        metrics.recordTool(new AiOperationalMetrics.ToolSample(
                AiCapability.ASSISTANT, "knowledge.search.v1",
                AiOperationalMetrics.AttemptOutcome.SUCCEEDED, Duration.ofMillis(10)));
        metrics.recordRetrieval(new AiOperationalMetrics.RetrievalSample(
                AiCapability.KNOWLEDGE, "memory-v1",
                AiOperationalMetrics.AttemptOutcome.SUCCEEDED, Duration.ofMillis(8), 2));
        metrics.recordProposal("REPAIR_ASSIGN", "PENDING_APPROVAL");
        metrics.recordQuotaRejection(AiCapability.ASSISTANT, "USER");
        metrics.recordSecurityBlock(AiCapability.ASSISTANT, "PII_L3");

        assertEquals(1.0, registry.get("dormitory.ai.run.total").counter().count());
        assertEquals(12.0, registry.get("dormitory.ai.run.tokens").tag("direction", "input")
                .summary().totalAmount());
        assertEquals(1.0, registry.get("dormitory.ai.provider.attempt.total").counter().count());
        assertEquals(1.0, registry.get("dormitory.ai.tool.total").counter().count());
        assertEquals(2.0, registry.get("dormitory.ai.retrieval.citations").summary().totalAmount());
        assertEquals(1.0, registry.get("dormitory.ai.proposal.total").counter().count());
        assertEquals(1.0, registry.get("dormitory.ai.quota.rejection.total").counter().count());
        assertEquals(1.0, registry.get("dormitory.ai.security.block.total").counter().count());
        assertFalse(registry.getMeters().stream().flatMap(meter -> meter.getId().getTags().stream())
                .anyMatch(tag -> Set.of("user", "user_id", "run", "run_id", "correlation_id")
                        .contains(tag.getKey())));
    }

    @Test
    void rejectsUnregisteredOrIdentifierShapedTagValues() {
        AiOperationalMetrics metrics = new AiOperationalMetrics(new SimpleMeterRegistry(), vocabulary());

        assertThrows(IllegalArgumentException.class, () -> metrics.recordRun(
                new AiOperationalMetrics.RunSample(AiCapability.ASSISTANT, "fake", "unregistered-model",
                        AiOperationalMetrics.RunOutcome.FAILED, AiOperationalMetrics.DegradeMode.NONE,
                        Duration.ZERO, null, 0, 0, BigDecimal.ZERO, "CNY")));
        assertThrows(IllegalArgumentException.class, () -> new AiOperationalMetrics.TagVocabulary(
                Set.of("run-123e4567-e89b-12d3-a456-426614174000"), Set.of("fake-v1"),
                Set.of("knowledge.search.v1"), Set.of("memory-v1")));
    }

    private static AiOperationalMetrics.TagVocabulary vocabulary() {
        return new AiOperationalMetrics.TagVocabulary(
                Set.of("fake"), Set.of("fake-v1"), Set.of("knowledge.search.v1"), Set.of("memory-v1"));
    }
}
