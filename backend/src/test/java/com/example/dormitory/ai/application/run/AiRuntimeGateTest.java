package com.example.dormitory.ai.application.run;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.config.AiProperties;
import com.example.dormitory.ai.config.AiRuntimeControlService;
import com.example.dormitory.ai.domain.model.AiCapability;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiRuntimeGateTest {

    @Test
    void allowsTheConfiguredSpringAiAdapterWhenItsRuntimeBeanIsPresent() {
        AiRuntimeControlService controls = enabledControls("spring-ai");
        AiProperties properties = properties("spring-ai");
        AiModelRuntimePort springAi = runtime("spring-ai");
        AiRuntimeGate gate = new AiRuntimeGate(
                properties, controls, new MockEnvironment(), List.of(runtime("fake"), springAi));

        assertDoesNotThrow(() -> gate.requireStreamingProvider(AiCapability.ASSISTANT));
    }

    @Test
    void failsClosedWhenTheConfiguredAdapterBeanIsMissing() {
        AiRuntimeControlService springControls = enabledControls("spring-ai");
        AiRuntimeGate missing = new AiRuntimeGate(properties("spring-ai"), springControls,
                new MockEnvironment(), List.of(runtime("fake")));
        assertThrows(AiApiException.class,
                () -> missing.requireStreamingProvider(AiCapability.ASSISTANT));
    }

    @Test
    void fakeAdapterRemainsRestrictedToDevOrTestProfiles() {
        AiProperties properties = properties("fake");
        AiRuntimeControlService controls = enabledControls("fake");
        AiRuntimeGate productionLike = new AiRuntimeGate(
                properties, controls, new MockEnvironment(), List.of(runtime("fake")));
        assertThrows(AiApiException.class,
                () -> productionLike.requireStreamingProvider(AiCapability.ASSISTANT));

        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        AiRuntimeGate development = new AiRuntimeGate(properties, controls, dev, List.of(runtime("fake")));
        assertDoesNotThrow(() -> development.requireStreamingProvider(AiCapability.ASSISTANT));
    }

    private AiProperties properties(String provider) {
        AiProperties properties = new AiProperties();
        properties.getProvider().setActive(provider);
        properties.getStreaming().setEnabled(true);
        if ("spring-ai".equals(provider)) properties.getBudget().setReservedCost(BigDecimal.ONE);
        return properties;
    }

    private AiRuntimeControlService enabledControls(String provider) {
        AiRuntimeControlService controls = mock(AiRuntimeControlService.class);
        when(controls.masterEnabled()).thenReturn(true);
        when(controls.capabilityEnabled(AiCapability.ASSISTANT)).thenReturn(true);
        when(controls.providerEnabled(provider)).thenReturn(true);
        return controls;
    }

    private AiModelRuntimePort runtime(String provider) {
        AiModelRuntimePort runtime = mock(AiModelRuntimePort.class);
        when(runtime.providerCode()).thenReturn(provider);
        return runtime;
    }
}
