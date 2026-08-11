package com.example.dormitory.ai.infrastructure.runtime;

import com.example.dormitory.ai.port.AiAuditPort;

import java.util.UUID;

public final class RuntimeAiAuditAdapter implements AiAuditPort {
    private final AiRuntimeAuditWriter writer;

    public RuntimeAiAuditAdapter(AiRuntimeAuditWriter writer) {
        this.writer = java.util.Objects.requireNonNull(writer);
    }

    @Override
    public boolean writable() {
        return writer.writable();
    }

    @Override
    public void append(AiAuditEvent event) {
        writer.append("PROPOSAL", event.aggregateType(), event.aggregatePublicId(), event.eventType(),
                event.actor(), null, 1, null, event.payloadRedactedHash(), UUID.randomUUID().toString());
    }
}
