package com.example.dormitory.ai.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class AiObservability {
    private final MeterRegistry registry;

    public AiObservability(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(String capability, String providerAlias, String outcome, Duration duration) {
        String safeCapability = lowCardinality(capability, "capability");
        String safeProvider = lowCardinality(providerAlias, "provider");
        String safeOutcome = lowCardinality(outcome, "outcome");
        Counter.builder("dormitory.ai.runs")
                .tag("capability", safeCapability)
                .tag("provider", safeProvider)
                .tag("outcome", safeOutcome)
                .register(registry).increment();
        Timer.builder("dormitory.ai.run.duration")
                .tag("capability", safeCapability)
                .tag("provider", safeProvider)
                .tag("outcome", safeOutcome)
                .register(registry).record(duration);
    }

    private String lowCardinality(String value, String field) {
        if (value == null || !value.matches("[A-Z0-9_.-]{1,32}")
                || value.matches("(?i).*[0-9a-f]{8}-[0-9a-f-]{27,}.*")) {
            throw new IllegalArgumentException("AI metric " + field + " 不是低基数标签");
        }
        return value;
    }
}
