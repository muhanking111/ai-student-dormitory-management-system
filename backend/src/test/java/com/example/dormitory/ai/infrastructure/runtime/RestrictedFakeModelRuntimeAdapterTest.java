package com.example.dormitory.ai.infrastructure.runtime;

import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.AiCapability;
import com.example.dormitory.ai.domain.model.ModelRequest;
import com.example.dormitory.ai.domain.model.ModelUsage;
import com.example.dormitory.ai.port.AiModelAttemptAccountingPort;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RestrictedFakeModelRuntimeAdapterTest {

    private final RestrictedFakeModelRuntimeAdapter runtime = new RestrictedFakeModelRuntimeAdapter();

    @Test
    void acceptsOnlyFixedAssistantReadToolsAndNeverDynamicOrProposalTools() {
        runtime.stream(request(Set.of("repair.get_context.v1")));
        assertEquals(1, runtime.invocationCount());

        assertThrows(SecurityException.class, () -> runtime.stream(request(Set.of("dynamic.http.v1"))));
        assertThrows(SecurityException.class,
                () -> runtime.stream(request(Set.of("repair.propose_assignment.v1"))));
        assertEquals(1, runtime.invocationCount());
    }

    @Test
    void productionFakePathWritesOneCostedPhysicalAttempt() {
        RecordingAccounting accounting = new RecordingAccounting();
        RestrictedFakeModelRuntimeAdapter accounted = new RestrictedFakeModelRuntimeAdapter(accounting);
        ModelRequest request = new ModelRequest(AiCapability.ASSISTANT, "安全问题", Set.of(), null,
                Duration.ofSeconds(2), 100,
                new ModelRequest.BillingTrace(UUID.randomUUID().toString(), 1, ActorDescriptor.user(7L)));

        accounted.stream(request).consume(ignored -> { });

        assertEquals(List.of(1), accounting.attempts);
        assertEquals(List.of(AiModelAttemptAccountingPort.AttemptOutcome.SUCCEEDED), accounting.outcomes);
        assertEquals(1, accounted.invocationCount());
    }

    private ModelRequest request(Set<String> tools) {
        return new ModelRequest(AiCapability.ASSISTANT, "安全问题", tools, null,
                Duration.ofSeconds(2), 100);
    }

    private static final class RecordingAccounting implements AiModelAttemptAccountingPort {
        private final List<Integer> attempts = new ArrayList<>();
        private final List<AttemptOutcome> outcomes = new ArrayList<>();

        @Override
        public AttemptHandle begin(ModelRequest.BillingTrace trace, AiCapability capability,
                                   String providerCode, String modelName, String requestKind,
                                   int attemptNo, Instant startedAt) {
            attempts.add(attemptNo);
            return new AttemptHandle(trace, capability, providerCode, modelName, requestKind, attemptNo,
                    1L, "CNY", BigDecimal.ZERO, BigDecimal.ZERO, startedAt);
        }

        @Override
        public AttemptReceipt finish(AttemptHandle handle, ModelUsage usage, AttemptOutcome outcome,
                                     Duration duration, String failureCode) {
            outcomes.add(outcome);
            return new AttemptReceipt(handle.trace().runPublicId(), handle.trace().requestSequenceNo(),
                    handle.attemptNo(), handle.pricingVersionId(), usage.inputTokens(), usage.outputTokens(),
                    BigDecimal.ZERO, handle.currency(), usage.source(), outcome);
        }

        @Override
        public UsageTotals totals(String runPublicId) {
            return new UsageTotals(outcomes.size(), 0, 0, BigDecimal.ZERO, "CNY", 0);
        }
    }
}
