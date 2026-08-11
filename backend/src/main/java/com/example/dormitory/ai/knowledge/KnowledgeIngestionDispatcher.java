package com.example.dormitory.ai.knowledge;

import com.example.dormitory.ai.outbox.AiOutboxWorker;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 仅负责提交后唤醒 durable outbox；不再绕过 outbox 直接执行摄取。 */
@Component
public class KnowledgeIngestionDispatcher {
    private final AiOutboxWorker outboxWorker;
    private final ThreadPoolTaskExecutor executor;
    private final boolean enabled;

    public KnowledgeIngestionDispatcher(
            AiOutboxWorker outboxWorker,
            @Qualifier("aiRunTaskExecutor") ThreadPoolTaskExecutor executor,
            @Value("${dormitory.ai.knowledge.auto-process:true}") boolean enabled) {
        this.outboxWorker = outboxWorker;
        this.executor = executor;
        this.enabled = enabled;
    }

    public void dispatchAfterCommit() {
        if (!enabled) return;
        Runnable dispatch = () -> executor.execute(() -> outboxWorker.processAvailable(10));
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { dispatch.run(); }
            });
        } else {
            dispatch.run();
        }
    }
}
