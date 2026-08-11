package com.example.dormitory.ai.contract;

import com.example.dormitory.ai.domain.model.AiCapability;
import com.example.dormitory.ai.domain.model.AiStreamEvent;
import com.example.dormitory.ai.domain.model.ModelCapability;
import com.example.dormitory.ai.domain.model.ModelRequest;
import com.example.dormitory.ai.domain.model.ModelUsage;
import com.example.dormitory.ai.infrastructure.fake.DeterministicFakeModelGateway;
import com.example.dormitory.ai.port.AiEventStream;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelGatewayContractTest {

    private final DeterministicFakeModelGateway gateway = new DeterministicFakeModelGateway();

    @Test
    void completeIsDeterministicAndReportsEstimatedUsage() {
        ModelRequest request = ModelRequest.text(AiCapability.ASSISTANT, "你好，宿舍");

        var first = gateway.complete(request);
        var second = gateway.complete(request);

        assertEquals(first, second);
        assertEquals("FAKE[ASSISTANT]:你好，宿舍", first.content());
        assertTrue(first.usage().inputTokens() > 0);
        assertTrue(first.usage().outputTokens() > 0);
        assertEquals(ModelUsage.Source.ESTIMATED, first.usage().source());
    }

    @Test
    void streamUsesProjectEventsAndCompletesExactlyOnce() {
        ModelRequest request = ModelRequest.text(AiCapability.KNOWLEDGE, "安全用电");
        List<AiStreamEvent> events = new ArrayList<>();

        gateway.stream(request).consume(events::add);

        assertEquals(AiStreamEvent.Type.STARTED, events.getFirst().type());
        assertEquals(AiStreamEvent.Type.COMPLETED, events.getLast().type());
        assertEquals(1, events.stream().filter(event -> event.type() == AiStreamEvent.Type.COMPLETED).count());
        assertEquals(1, events.stream().filter(event -> event.type() == AiStreamEvent.Type.USAGE).count());
        assertEquals(
                gateway.complete(request).content(),
                events.stream()
                        .filter(event -> event.type() == AiStreamEvent.Type.TEXT_DELTA)
                        .map(AiStreamEvent::textDelta)
                        .reduce("", String::concat));
    }

    @Test
    void cancellationStopsFurtherDeltasAndNeverEmitsCompleted() {
        ModelRequest request = ModelRequest.text(AiCapability.REPAIR, "水管漏水");
        List<AiStreamEvent> events = new ArrayList<>();
        AiEventStream stream = gateway.stream(request);

        stream.consume(event -> {
            events.add(event);
            if (event.type() == AiStreamEvent.Type.TEXT_DELTA) {
                stream.cancel();
            }
        });

        assertTrue(stream.isCancelled());
        assertEquals(1, events.stream().filter(event -> event.type() == AiStreamEvent.Type.TEXT_DELTA).count());
        assertEquals(AiStreamEvent.Type.CANCELLED, events.getLast().type());
        assertFalse(events.stream().anyMatch(event -> event.type() == AiStreamEvent.Type.COMPLETED));
    }

    @Test
    void capabilityProbeNeverClaimsUnsupportedToolsSchemaOrEmbedding() {
        assertEquals("fake", gateway.adapterCode());
        assertEquals(
                Set.of(
                        ModelCapability.COMPLETE,
                        ModelCapability.STREAMING,
                        ModelCapability.USAGE,
                        ModelCapability.CANCELLATION),
                gateway.supportedCapabilities());
        assertFalse(gateway.supports(ModelCapability.TOOLS));
        assertFalse(gateway.supports(ModelCapability.JSON_SCHEMA));
        assertFalse(gateway.supports(ModelCapability.EMBEDDING));
    }

    @Test
    void modelRequestRejectsMissingCapabilityPromptTimeoutAndTokenBounds() {
        assertThrows(IllegalArgumentException.class,
                () -> new ModelRequest(null, "prompt", Set.of(), null, Duration.ofSeconds(1), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelRequest(AiCapability.ASSISTANT, null, Set.of(), null,
                        Duration.ofSeconds(1), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelRequest(AiCapability.ASSISTANT, " ", Set.of(), null,
                        Duration.ofSeconds(1), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelRequest(AiCapability.ASSISTANT, "prompt", Set.of(), null, null, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelRequest(AiCapability.ASSISTANT, "prompt", Set.of(), null,
                        Duration.ZERO, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelRequest(AiCapability.ASSISTANT, "prompt", Set.of(), null,
                        Duration.ofSeconds(-1), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelRequest(AiCapability.ASSISTANT, "prompt", Set.of(), null,
                        Duration.ofSeconds(1), 0));

        ModelRequest request = new ModelRequest(AiCapability.ASSISTANT, "prompt", null, null,
                Duration.ofSeconds(1), 1, null, null, null);
        assertTrue(request.allowedToolIds().isEmpty());
        assertTrue(request.untrustedContent().isEmpty());
    }

    @Test
    void roleSeparatedRequestValidatesTrustedAndUntrustedPromptBoundaries() {
        assertThrows(IllegalArgumentException.class, () -> ModelRequest.roleSeparated(
                AiCapability.KNOWLEDGE, " ", "question", List.of(), Set.of(), null,
                Duration.ofSeconds(1), 1, null));
        assertThrows(IllegalArgumentException.class, () -> ModelRequest.roleSeparated(
                AiCapability.KNOWLEDGE, "x".repeat(100_001), "question", List.of(), Set.of(), null,
                Duration.ofSeconds(1), 1, null));
        assertThrows(IllegalArgumentException.class, () -> ModelRequest.roleSeparated(
                AiCapability.KNOWLEDGE, "bad\u0000system", "question", List.of(), Set.of(), null,
                Duration.ofSeconds(1), 1, null));

        for (String kind : Arrays.asList(null, "", "UNKNOWN")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ModelRequest.UntrustedContent(kind, "content"));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new ModelRequest.UntrustedContent("RAG_CONTEXT_JSON", null));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelRequest.UntrustedContent("RAG_CONTEXT_JSON", " "));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelRequest.UntrustedContent("RAG_CONTEXT_JSON", "x".repeat(200_001)));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelRequest.UntrustedContent("RAG_CONTEXT_JSON", "bad\u0000content"));

        ModelRequest request = ModelRequest.roleSeparated(
                AiCapability.KNOWLEDGE,
                "system\nrule",
                "😀",
                List.of(new ModelRequest.UntrustedContent("RAG_CONTEXT_JSON", "context\tvalue")),
                Set.of("knowledge.search.v1"),
                "v1",
                Duration.ofSeconds(2),
                128,
                null);
        assertEquals(1 + "system\nrule".codePointCount(0, "system\nrule".length())
                        + "context\tvalue".codePointCount(0, "context\tvalue".length()),
                request.providerInputCodePoints());
        assertThrows(UnsupportedOperationException.class,
                () -> request.untrustedContent().add(
                        new ModelRequest.UntrustedContent("TOOL_RESULT", "other")));
    }

    @Test
    void billingTraceRequiresUuidPositiveSequenceAndActor() {
        var actor = com.example.dormitory.ai.domain.model.ActorDescriptor.user(42L);
        assertThrows(IllegalArgumentException.class,
                () -> new ModelRequest.BillingTrace(null, 1, actor));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelRequest.BillingTrace("not-a-uuid", 1, actor));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelRequest.BillingTrace(UUID.randomUUID().toString(), 0, actor));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelRequest.BillingTrace(UUID.randomUUID().toString(), 1, null));

        ModelRequest.BillingTrace trace =
                new ModelRequest.BillingTrace(UUID.randomUUID().toString(), 1, actor);
        assertEquals(actor, trace.actor());
    }
}
