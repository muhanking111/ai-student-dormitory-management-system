package com.example.dormitory.ai.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiObservabilityTest {
    @Test
    void recordsOnlyLowCardinalityTagsAndRejectsRunOrUserIdentifiers() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiObservability observability = new AiObservability(registry);
        observability.record("DASHBOARD", "DETERMINISTIC", "SUCCEEDED", Duration.ofMillis(12));
        assertEquals(1.0, registry.get("dormitory.ai.runs").counter().count());
        assertThrows(IllegalArgumentException.class, () -> observability.record(
                "run-123e4567-e89b-12d3-a456-426614174000", "FAKE", "FAILED", Duration.ZERO));
    }
}
