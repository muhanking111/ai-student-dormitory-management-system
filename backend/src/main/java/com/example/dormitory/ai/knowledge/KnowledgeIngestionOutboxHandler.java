package com.example.dormitory.ai.knowledge;

import com.example.dormitory.ai.infrastructure.persistence.JdbcAiOutboxRepository;
import com.example.dormitory.ai.outbox.AiOutboxEventHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "dormitory.ai.capabilities", name = "knowledge", havingValue = "true")
public class KnowledgeIngestionOutboxHandler implements AiOutboxEventHandler {

    private static final String EVENT_TYPE = "KnowledgeVersionRegistered.v1";

    private final KnowledgeIngestionService ingestion;
    private final ObjectMapper json;

    public KnowledgeIngestionOutboxHandler(KnowledgeIngestionService ingestion, ObjectMapper json) {
        this.ingestion = ingestion;
        this.json = json;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(EVENT_TYPE);
    }

    @Override
    public void handle(JdbcAiOutboxRepository.ClaimedOutboxEvent event) {
        if (!EVENT_TYPE.equals(event.eventType()) || !"KNOWLEDGE_VERSION".equals(event.aggregateType())
                || !"SERVICE".equals(event.actorKind())
                || !"knowledge-ingestion".equals(event.servicePrincipalCode())) {
            throw new IllegalArgumentException("知识摄取 outbox actor/类型不合法");
        }
        String jobId;
        try {
            var payload = json.readTree(event.payloadRedacted());
            if (!"knowledge-version-registered.v1".equals(payload.path("schemaVersion").asText())) {
                throw new IllegalArgumentException("知识摄取 outbox schema 不受支持");
            }
            jobId = UUID.fromString(payload.path("jobId").asText()).toString();
        } catch (RuntimeException runtime) {
            throw runtime;
        } catch (Exception invalid) {
            throw new IllegalArgumentException("知识摄取 outbox payload 不合法", invalid);
        }
        ingestion.processJob(jobId, event.aggregatePublicId(), event.lockedBy());
    }

    @Override
    public void onDead(JdbcAiOutboxRepository.ClaimedOutboxEvent event, String errorCode) {
        try {
            String jobId = UUID.fromString(json.readTree(event.payloadRedacted()).path("jobId").asText()).toString();
            ingestion.markDead(jobId, event.aggregatePublicId(), "INGESTION_OUTBOX_DEAD");
        } catch (Exception ignored) {
            // payload 本身损坏时没有可信 job ID 可用于状态变更；outbox DEAD 仍是权威告警事实。
        }
    }
}
