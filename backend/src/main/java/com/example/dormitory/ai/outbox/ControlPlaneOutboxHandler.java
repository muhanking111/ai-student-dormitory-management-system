package com.example.dormitory.ai.outbox;

import com.example.dormitory.ai.infrastructure.persistence.JdbcAiOutboxRepository;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Proposal/execution 事件首期投递到内部观测投影。业务写已经在人工请求事务完成，
 * 该消费者只确认可重放事件，绝不再次调用业务 Service。
 */
@Component
public class ControlPlaneOutboxHandler implements AiOutboxEventHandler {

    private static final Set<String> TYPES = Set.of(
            "ActionProposalCreated.v1", "ActionProposalStateChanged.v1",
            "ActionExecutionSucceeded.v1", "RiskCaseOpened.v1",
            "KnowledgeSourceChanged.v1", "KnowledgeVersionActivated.v1",
            "AiRunCompleted.v1", "FeedbackRecorded.v1",
            "AI_PROMPT_VERSION_CREATED", "AI_PROMPT_VERSION_ACTIVATED",
            "AI_MODEL_ALIAS_ACTIVATED", "AI_TOOL_CATALOG_ACTIVATED");

    @Override
    public Set<String> eventTypes() {
        return TYPES;
    }

    @Override
    public void handle(JdbcAiOutboxRepository.ClaimedOutboxEvent event) {
        if (!TYPES.contains(event.eventType()) || event.payloadRedacted() == null
                || event.payloadRedacted().length() > 65_536) {
            throw new IllegalArgumentException("控制面 outbox 事件不合法");
        }
        // ai_outbox_event.SUCCEEDED 是首期可重放投影 receipt；这里绝不执行任意动态动作。
    }
}
