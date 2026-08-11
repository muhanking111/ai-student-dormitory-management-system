package com.example.dormitory.ai.outbox;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.infrastructure.persistence.AiSchemaMigrationInitializer;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiOutboxRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * MySQL 权威的多实例 worker。CAS claim、过期 lease 恢复、指数退避和 DEAD
 * 都落在 outbox 行上；handler 必须以 event public ID/聚合终态保持可重入。
 */
@Component
public class AiOutboxWorker implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AiOutboxWorker.class);

    private final JdbcAiOutboxRepository repository;
    private final Map<String, AiOutboxEventHandler> handlers;
    private final Set<String> supportedTypes;
    private final String workerId;
    private final Duration leaseTimeout;
    private final Duration heartbeatInterval;
    private final Duration retryBaseDelay;
    private final int maxAttempts;
    private final boolean autoProcess;
    private final boolean aiEnabled;
    private final BooleanSupplier schemaReady;
    private final ScheduledThreadPoolExecutor heartbeatExecutor;

    @Autowired
    public AiOutboxWorker(
            JdbcAiOutboxRepository repository,
            List<AiOutboxEventHandler> handlers,
            @Value("${dormitory.ai.outbox.lease-timeout:PT2M}") Duration leaseTimeout,
            @Value("${dormitory.ai.outbox.heartbeat-interval:PT30S}") Duration heartbeatInterval,
            @Value("${dormitory.ai.outbox.retry-base-delay:PT2S}") Duration retryBaseDelay,
            @Value("${dormitory.ai.outbox.max-attempts:5}") int maxAttempts,
            @Value("${dormitory.ai.outbox.auto-process:true}") boolean autoProcess,
            @Value("${dormitory.ai.enabled:false}") boolean aiEnabled,
            AiSchemaMigrationInitializer schemaInitializer) {
        this(repository, handlers, leaseTimeout, heartbeatInterval, retryBaseDelay,
                maxAttempts, autoProcess, aiEnabled, schemaInitializer::isSchemaReady);
    }

    public AiOutboxWorker(
            JdbcAiOutboxRepository repository,
            List<AiOutboxEventHandler> handlers,
            Duration leaseTimeout,
            Duration heartbeatInterval,
            Duration retryBaseDelay,
            int maxAttempts,
            boolean autoProcess,
            boolean aiEnabled) {
        this(repository, handlers, leaseTimeout, heartbeatInterval, retryBaseDelay,
                maxAttempts, autoProcess, aiEnabled, () -> true);
    }

    private AiOutboxWorker(
            JdbcAiOutboxRepository repository,
            List<AiOutboxEventHandler> handlers,
            Duration leaseTimeout,
            Duration heartbeatInterval,
            Duration retryBaseDelay,
            int maxAttempts,
            boolean autoProcess,
            boolean aiEnabled,
            BooleanSupplier schemaReady) {
        this.repository = repository;
        if (leaseTimeout == null || leaseTimeout.isZero() || leaseTimeout.isNegative()
                || heartbeatInterval == null || heartbeatInterval.toMillis() < 1
                || heartbeatInterval.compareTo(leaseTimeout.dividedBy(2)) > 0
                || retryBaseDelay == null || retryBaseDelay.isZero() || retryBaseDelay.isNegative()
                || maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException("outbox worker 配置不合法");
        }
        Map<String, AiOutboxEventHandler> indexed = new LinkedHashMap<>();
        for (AiOutboxEventHandler handler : handlers) {
            for (String eventType : handler.eventTypes()) {
                if (eventType == null || !eventType.matches("[A-Za-z0-9_.-]{1,64}")
                        || indexed.putIfAbsent(eventType, handler) != null) {
                    throw new IllegalStateException("outbox handler 事件类型重复或不合法: " + eventType);
                }
            }
        }
        this.handlers = Map.copyOf(indexed);
        this.supportedTypes = Set.copyOf(indexed.keySet());
        this.workerId = "ai-outbox-" + UUID.randomUUID();
        this.leaseTimeout = leaseTimeout;
        this.heartbeatInterval = heartbeatInterval;
        this.retryBaseDelay = retryBaseDelay;
        this.maxAttempts = maxAttempts;
        this.autoProcess = autoProcess;
        this.aiEnabled = aiEnabled;
        this.schemaReady = schemaReady;
        this.heartbeatExecutor = heartbeatExecutor(workerId);
    }

    @Scheduled(
            initialDelayString = "${dormitory.ai.outbox.initial-delay:PT3S}",
            fixedDelayString = "${dormitory.ai.outbox.interval:PT5S}")
    public void scheduledDrain() {
        if (autoProcess) processAvailable(25);
    }

    public int processAvailable(int limit) {
        if (limit < 1 || limit > 1_000) throw new IllegalArgumentException("outbox drain limit 不合法");
        if (!aiEnabled || !schemaReady.getAsBoolean()) return 0;
        if (supportedTypes.isEmpty()) return 0;
        JdbcAiOutboxRepository.LeaseRecoveryResult recovery = repository.recoverExpiredLeases(
                Instant.now(), leaseTimeout, maxAttempts, supportedTypes);
        for (JdbcAiOutboxRepository.ClaimedOutboxEvent dead : recovery.deadEvents()) {
            AiOutboxEventHandler handler = handlers.get(dead.eventType());
            if (handler != null) handler.onDead(dead, "WORKER_LEASE_EXPIRED");
        }
        int processed = 0;
        while (processed < limit) {
            JdbcAiOutboxRepository.ClaimedOutboxEvent event = repository.claimNext(
                    workerId, Instant.now(), supportedTypes).orElse(null);
            if (event == null) break;
            processClaimed(event);
            processed++;
        }
        return processed;
    }

    private void processClaimed(JdbcAiOutboxRepository.ClaimedOutboxEvent event) {
        AiOutboxEventHandler handler = handlers.get(event.eventType());
        if (handler == null) throw new IllegalStateException("已领取未知 outbox 事件");
        LeaseHeartbeat heartbeat = new LeaseHeartbeat(event, Thread.currentThread());
        heartbeat.start();
        try {
            handler.handle(event);
            heartbeat.stop();
            heartbeat.requireOwned();
            if (!repository.markSucceeded(event.publicId(), event.lockedBy(), Instant.now())) {
                throw new LeaseOwnershipLostException("outbox 成功状态 CAS 未命中，lease 已不再属于当前 worker");
            }
        } catch (LeaseOwnershipLostException lost) {
            heartbeat.stop();
            LOG.error("AI outbox lease ownership lost; terminal transition blocked eventType={} publicId={} worker={}",
                    event.eventType(), event.publicId(), event.lockedBy());
            throw lost;
        } catch (AiApiException deferred) {
            heartbeat.stop();
            heartbeat.requireOwned();
            if (deferred.retryable() && (deferred.errorCode().endsWith("_DISABLED")
                    || "AI_CAPABILITY_DISABLED".equals(deferred.errorCode()))) {
                long delay = deferred.retryAfterSeconds() == null
                        ? 30L : Math.max(1L, deferred.retryAfterSeconds());
                if (!repository.defer(event.publicId(), event.lockedBy(), deferred.errorCode(),
                        Instant.now().plusSeconds(delay))) {
                    throw new IllegalStateException("outbox defer 状态 CAS 未命中", deferred);
                }
                return;
            }
            fail(event, handler, deferred);
        } catch (RuntimeException failure) {
            heartbeat.stop();
            heartbeat.requireOwned();
            fail(event, handler, failure);
        } finally {
            heartbeat.stop();
            heartbeat.clearLeaseLossInterrupt();
        }
    }

    private void fail(
            JdbcAiOutboxRepository.ClaimedOutboxEvent event,
            AiOutboxEventHandler handler,
            RuntimeException failure) {
        String errorCode = "OUTBOX_HANDLER_FAILED";
        try {
            JdbcAiOutboxRepository.FailureResult result = repository.markFailure(
                    event.publicId(), event.lockedBy(), errorCode, Instant.now(), retryBaseDelay, maxAttempts);
            if (result.dead()) handler.onDead(event, errorCode);
            LOG.warn("AI outbox handler failed eventType={} attempt={} terminal={} failureType={}",
                    event.eventType(), result.attempts(), result.dead(), safeFailureType(failure));
        } catch (RuntimeException terminalConflict) {
            LOG.error("AI outbox failure transition rejected; lease may have moved eventType={} publicId={} worker={}",
                    event.eventType(), event.publicId(), event.lockedBy());
            throw terminalConflict;
        }
    }

    private String safeFailureType(Throwable failure) {
        Throwable root = failure;
        for (int depth = 0; depth < 12 && root.getCause() != null; depth++) root = root.getCause();
        if (root instanceof java.sql.SQLException sql) {
            String state = sql.getSQLState() == null ? "UNKNOWN" : sql.getSQLState();
            return "SQLException:" + state + ":" + sql.getErrorCode();
        }
        return root.getClass().getSimpleName();
    }

    @Override
    @PreDestroy
    public void close() {
        heartbeatExecutor.shutdownNow();
    }

    private ScheduledThreadPoolExecutor heartbeatExecutor(String id) {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, id + "-lease-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    private final class LeaseHeartbeat {
        private final Object monitor = new Object();
        private final JdbcAiOutboxRepository.ClaimedOutboxEvent event;
        private final Thread handlerThread;
        private ScheduledFuture<?> future;
        private boolean stopped;
        private boolean ownershipLost;
        private boolean interruptedHandler;
        private RuntimeException renewalFailure;

        private LeaseHeartbeat(
                JdbcAiOutboxRepository.ClaimedOutboxEvent event,
                Thread handlerThread) {
            this.event = event;
            this.handlerThread = handlerThread;
        }

        private void start() {
            long intervalMillis = heartbeatInterval.toMillis();
            future = heartbeatExecutor.scheduleAtFixedRate(
                    this::renew, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        }

        private void renew() {
            synchronized (monitor) {
                if (stopped || ownershipLost) return;
                try {
                    if (!repository.renewLease(event.publicId(), event.lockedBy(), Instant.now())) {
                        loseOwnership(null);
                    }
                } catch (RuntimeException failure) {
                    loseOwnership(failure);
                }
            }
        }

        private void loseOwnership(RuntimeException failure) {
            ownershipLost = true;
            renewalFailure = failure;
            interruptedHandler = true;
            LOG.error("AI outbox lease heartbeat failed closed eventType={} publicId={} worker={} reason={}",
                    event.eventType(), event.publicId(), event.lockedBy(),
                    failure == null ? "LEASE_NOT_OWNED" : failure.getClass().getSimpleName());
            handlerThread.interrupt();
        }

        private void stop() {
            ScheduledFuture<?> scheduled;
            synchronized (monitor) {
                stopped = true;
                scheduled = future;
            }
            if (scheduled != null) scheduled.cancel(false);
        }

        private void requireOwned() {
            synchronized (monitor) {
                if (!ownershipLost) return;
                throw new LeaseOwnershipLostException(
                        "outbox lease heartbeat 失败，已阻止当前 handler 写入终态", renewalFailure);
            }
        }

        private void clearLeaseLossInterrupt() {
            synchronized (monitor) {
                if (interruptedHandler && Thread.currentThread() == handlerThread) Thread.interrupted();
            }
        }
    }

    private static final class LeaseOwnershipLostException extends IllegalStateException {
        private LeaseOwnershipLostException(String message) {
            super(message);
        }

        private LeaseOwnershipLostException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
