package com.example.dormitory.ai.outbox;

import com.example.dormitory.ai.infrastructure.persistence.JdbcAiOutboxRepository;

import java.util.Set;

/** 固定注册的 outbox 消费器；事件类型不允许由 payload 动态选择 Bean/方法。 */
public interface AiOutboxEventHandler {

    Set<String> eventTypes();

    void handle(JdbcAiOutboxRepository.ClaimedOutboxEvent event);

    default void onDead(JdbcAiOutboxRepository.ClaimedOutboxEvent event, String errorCode) {
        // 只有拥有明确 NEEDS_REVIEW/QUARANTINED 状态机的聚合才需要覆盖。
    }
}
