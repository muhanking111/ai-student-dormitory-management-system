package com.example.dormitory.ai.application.run;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.config.AiProperties;
import com.example.dormitory.ai.config.AiRuntimeControlService;
import com.example.dormitory.ai.domain.model.AiCapability;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiRuntimeGateEdgeCasesTest {

    @Test
    void streamingMustBeEnabledForLegacyAndActorScopedChecks() {
        AiProperties properties = properties("spring-ai", false, BigDecimal.ONE);
        AiRuntimeControlService controls = enabledControls("spring-ai");
        AiRuntimeGate gate = gate(properties, controls, new MockEnvironment(), List.of(runtime("spring-ai")));

        assertCode("AI_STREAMING_DISABLED",
                () -> gate.requireStreaming(AiCapability.ASSISTANT));
        assertCode("AI_STREAMING_DISABLED",
                () -> gate.requireStreaming(AiCapability.ASSISTANT, 7L));
    }

    @Test
    void providerAvailabilityRejectsEveryMissingControlPlanePrecondition() {
        MockEnvironment production = new MockEnvironment();

        assertFalse(gate(properties("none", true, BigDecimal.ZERO), enabledControls("none"),
                production, List.of()).providerAvailable());

        AiRuntimeControlService disabled = enabledControls("spring-ai");
        when(disabled.providerEnabled("spring-ai")).thenReturn(false);
        assertFalse(gate(properties("spring-ai", true, BigDecimal.ONE), disabled,
                production, List.of(runtime("spring-ai"))).providerAvailable());

        assertFalse(gate(properties("spring-ai", true, BigDecimal.ONE), enabledControls("spring-ai"),
                production, List.of(runtime("fake"))).providerAvailable());

        assertFalse(gate(properties("langchain4j", true, BigDecimal.ONE), enabledControls("langchain4j"),
                production, List.of(runtime("langchain4j"))).providerAvailable());
    }

    @Test
    void providerAvailabilityAllowsReadySpringAiAndRestrictsFakeByProfile() {
        MockEnvironment production = new MockEnvironment();
        assertTrue(gate(properties("spring-ai", true, BigDecimal.ONE), enabledControls("spring-ai"),
                production, List.of(runtime("spring-ai"))).providerAvailable());

        AiProperties fake = properties("fake", true, BigDecimal.ZERO);
        AiRuntimeControlService controls = enabledControls("fake");
        assertFalse(gate(fake, controls, production, List.of(runtime("fake"))).providerAvailable());

        MockEnvironment test = new MockEnvironment();
        test.setActiveProfiles("test");
        assertTrue(gate(fake, controls, test, List.of(runtime("fake"))).providerAvailable());
    }

    @Test
    void providerRequirementDistinguishesDisabledUnapprovedAndMissingBudget() {
        assertCode("AI_PROVIDER_DISABLED", () -> gate(
                properties("none", true, BigDecimal.ZERO), enabledControls("none"),
                new MockEnvironment(), List.of()).requireStreamingProvider(AiCapability.ASSISTANT));

        AiRuntimeControlService disabled = enabledControls("spring-ai");
        when(disabled.providerEnabled("spring-ai")).thenReturn(false);
        assertCode("AI_PROVIDER_DISABLED", () -> gate(
                properties("spring-ai", true, BigDecimal.ONE), disabled,
                new MockEnvironment(), List.of(runtime("spring-ai")))
                .requireStreamingProvider(AiCapability.ASSISTANT));

        assertCode("AI_PROVIDER_NOT_CONFIGURED", () -> gate(
                properties("langchain4j", true, BigDecimal.ONE), enabledControls("langchain4j"),
                new MockEnvironment(), List.of(runtime("langchain4j")))
                .requireStreamingProvider(AiCapability.ASSISTANT));

        assertCode("AI_COST_RESERVATION_UNAVAILABLE", () -> gate(
                properties("spring-ai", true, BigDecimal.ZERO), enabledControls("spring-ai"),
                new MockEnvironment(), List.of(runtime("spring-ai")))
                .requireStreamingProvider(AiCapability.ASSISTANT));
    }

    @Test
    void fakeProviderRequirementAllowsTestButRejectsProduction() {
        AiProperties properties = properties("fake", true, BigDecimal.ZERO);
        AiRuntimeControlService controls = enabledControls("fake");
        assertCode("AI_FAKE_PROVIDER_FORBIDDEN", () -> gate(
                properties, controls, new MockEnvironment(), List.of(runtime("fake")))
                .requireStreamingProvider(AiCapability.ASSISTANT));

        MockEnvironment development = new MockEnvironment();
        development.setActiveProfiles("dev");
        gate(properties, controls, development, List.of(runtime("fake")))
                .requireStreamingProvider(AiCapability.ASSISTANT);
    }

    private static AiRuntimeGate gate(
            AiProperties properties,
            AiRuntimeControlService controls,
            MockEnvironment environment,
            List<AiModelRuntimePort> runtimes) {
        return new AiRuntimeGate(properties, controls, environment, runtimes);
    }

    private static AiProperties properties(String provider, boolean streaming, BigDecimal reservedCost) {
        AiProperties properties = new AiProperties();
        properties.getProvider().setActive(provider);
        properties.getStreaming().setEnabled(streaming);
        properties.getBudget().setReservedCost(reservedCost);
        return properties;
    }

    private static AiRuntimeControlService enabledControls(String provider) {
        AiRuntimeControlService controls = mock(AiRuntimeControlService.class);
        when(controls.masterEnabled()).thenReturn(true);
        when(controls.capabilityEnabled(AiCapability.ASSISTANT)).thenReturn(true);
        when(controls.providerEnabled(provider)).thenReturn(true);
        return controls;
    }

    private static AiModelRuntimePort runtime(String provider) {
        AiModelRuntimePort runtime = mock(AiModelRuntimePort.class);
        when(runtime.providerCode()).thenReturn(provider);
        return runtime;
    }

    private static void assertCode(String expected, Runnable action) {
        AiApiException failure = assertThrows(AiApiException.class, action::run);
        assertEquals(expected, failure.errorCode());
    }
}
