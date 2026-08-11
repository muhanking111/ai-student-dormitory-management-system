package com.example.dormitory.ai.port;

import com.example.dormitory.ai.domain.model.ActorDescriptor;

import java.time.Instant;

public interface AiAuditPort {

    boolean writable();

    void append(AiAuditEvent event);

    record AiAuditEvent(
            String aggregateType,
            String aggregatePublicId,
            String eventType,
            ActorDescriptor actor,
            String payloadRedactedHash,
            Instant occurredAt) {
    }
}
