package com.example.dormitory.ai.infrastructure.runtime;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.application.run.AiActorContext;
import com.example.dormitory.ai.application.run.AiActorResolver;
import com.example.dormitory.ai.application.run.AiConversationRunStore;
import com.example.dormitory.ai.application.run.AiRunCurrentAccessAuthorizer;
import com.example.dormitory.ai.application.run.AiRunEventPublisher;
import com.example.dormitory.ai.application.run.AiRunRecords.Event;
import com.example.dormitory.ai.application.run.AiRunRecords.Run;
import com.example.dormitory.ai.config.AiProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class AiSseEmitterBroker implements AiRunEventPublisher {

    private static final Set<String> TERMINAL_TYPES = Set.of("run.completed", "run.failed");
    private static final Set<String> TERMINAL_STATES = Set.of("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT");

    private final AiConversationRunStore store;
    private final AiActorResolver actorResolver;
    private final AiProperties properties;
    private final ThreadPoolTaskScheduler scheduler;
    private final AiSseConnectionLeaseStore connectionLeases;
    private final AiRuntimeCrypto crypto;
    private final AiRunCurrentAccessAuthorizer runAccessAuthorizer;
    private final ConcurrentMap<String, List<Subscriber>> subscribersByRun = new ConcurrentHashMap<>();

    public AiSseEmitterBroker(
            AiConversationRunStore store,
            AiActorResolver actorResolver,
            AiProperties properties,
            @Qualifier("aiHeartbeatScheduler") ThreadPoolTaskScheduler scheduler,
            AiSseConnectionLeaseStore connectionLeases,
            AiRuntimeCrypto crypto,
            AiRunCurrentAccessAuthorizer runAccessAuthorizer) {
        this.store = store;
        this.actorResolver = actorResolver;
        this.properties = properties;
        this.scheduler = scheduler;
        this.connectionLeases = connectionLeases;
        this.crypto = crypto;
        this.runAccessAuthorizer = java.util.Objects.requireNonNull(runAccessAuthorizer);
    }

    @PostConstruct
    void startHeartbeat() {
        Duration interval = properties.getRuntime().getHeartbeatInterval();
        if (interval == null || interval.isNegative() || interval.isZero()) interval = Duration.ofSeconds(15);
        scheduler.scheduleAtFixedRate(this::heartbeat, interval);
    }

    public SseEmitter subscribe(AiActorContext actor, String runId, long lastEventId) {
        Run run = store.ownedRun(actor.userId(), runId);
        if (run == null) throw AiApiException.notFound();
        String requiredPermission = permissionFor(run.capability());
        requireInitialAccess(actor, run, requiredPermission);
        String actorOpaqueKey = crypto.hmacHex("sse-connection", Long.toString(actor.userId()));
        Duration leaseTtl = leaseTtl();
        AiSseConnectionLeaseStore.Lease lease;
        try {
            lease = connectionLeases.acquire(actorOpaqueKey,
                            properties.getRuntime().getMaxSseConnectionsPerUser(), leaseTtl)
                    .orElse(null);
        } catch (AiSseLeaseStoreUnavailableException unavailable) {
            throw new AiApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_SSE_LEASE_UNAVAILABLE",
                    "SSE 连接控制面不可用", true, 5, runId, List.of(), Map.of());
        }
        if (lease == null) {
            throw new AiApiException(HttpStatus.TOO_MANY_REQUESTS, "AI_SSE_CONNECTION_LIMIT",
                    "SSE 连接数已达上限", true, 5, runId, List.of(), Map.of());
        }
        long timeout = Math.max(1_000, properties.getRuntime().getEmitterTimeout().toMillis());
        SseEmitter emitter = new SseEmitter(timeout);
        Subscriber subscriber = new Subscriber(runId, actor, requiredPermission, lease, emitter, lastEventId);
        subscribersByRun.compute(runId, (ignored, current) -> {
            List<Subscriber> copy = current == null ? new ArrayList<>() : new ArrayList<>(current);
            copy.add(subscriber);
            return copy;
        });
        emitter.onCompletion(() -> remove(subscriber));
        emitter.onTimeout(() -> {
            remove(subscriber);
            emitter.complete();
        });
        emitter.onError(error -> remove(subscriber));

        try {
            for (Event event : store.eventsAfter(actor.userId(), runId, lastEventId)) {
                if (!isCurrentlyAuthorized(subscriber)) {
                    subscriber.complete();
                    return emitter;
                }
                subscriber.sendReplay(event);
            }
            subscriber.finishReplay();
            Run currentRun = store.ownedRun(actor.userId(), runId);
            if (currentRun != null && TERMINAL_STATES.contains(currentRun.state())) {
                // The initial replay can race the transaction that flips the run
                // state before its terminal event becomes visible. Re-read from
                // the last sent sequence so a completed run is never closed
                // without its result event.
                if (!subscriber.hasTerminalEvent()) {
                    for (Event event : store.eventsAfter(actor.userId(), runId, subscriber.lastSent())) {
                        if (!isCurrentlyAuthorized(subscriber)) {
                            subscriber.complete();
                            return emitter;
                        }
                        subscriber.sendReplay(event);
                        if (subscriber.hasTerminalEvent()) break;
                    }
                }
                if (subscriber.hasTerminalEvent()) subscriber.complete();
            }
        } catch (RuntimeException exception) {
            remove(subscriber);
            emitter.completeWithError(exception);
        }
        return emitter;
    }

    @Override
    public void publish(Event event) {
        List<Subscriber> subscribers = subscribersByRun.getOrDefault(event.runId(), List.of());
        for (Subscriber subscriber : List.copyOf(subscribers)) {
            if (!isCurrentlyAuthorized(subscriber)) {
                subscriber.complete();
                continue;
            }
            subscriber.sendLive(event);
        }
    }

    private void heartbeat() {
        for (List<Subscriber> subscribers : List.copyOf(subscribersByRun.values())) {
            for (Subscriber subscriber : List.copyOf(subscribers)) {
                if (!isCurrentlyAuthorized(subscriber)) {
                    subscriber.complete();
                    continue;
                }
                try {
                    if (!connectionLeases.renew(subscriber.lease)) {
                        subscriber.complete();
                        continue;
                    }
                    subscriber.sendHeartbeat();
                } catch (RuntimeException exception) {
                    subscriber.complete();
                }
            }
        }
    }

    private void remove(Subscriber subscriber) {
        subscriber.closed.set(true);
        subscribersByRun.computeIfPresent(subscriber.runId, (ignored, current) -> {
            List<Subscriber> copy = new ArrayList<>(current);
            copy.remove(subscriber);
            return copy.isEmpty() ? null : copy;
        });
        if (subscriber.leaseReleased.compareAndSet(false, true)) {
            try {
                connectionLeases.release(subscriber.lease);
            } catch (AiSseLeaseStoreUnavailableException ignored) {
                // 租约有 TTL；释放故障不能让 servlet 完成路径反向挂死。
            }
        }
    }

    private Duration leaseTtl() {
        Duration heartbeat = properties.getRuntime().getHeartbeatInterval();
        if (heartbeat == null || heartbeat.isZero() || heartbeat.isNegative()) heartbeat = Duration.ofSeconds(15);
        Duration ttl = heartbeat.multipliedBy(3);
        if (ttl.compareTo(Duration.ofSeconds(30)) < 0) ttl = Duration.ofSeconds(30);
        return ttl.compareTo(Duration.ofMinutes(10)) > 0 ? Duration.ofMinutes(10) : ttl;
    }

    private String permissionFor(String capability) {
        return switch (capability) {
            case "ASSISTANT" -> "ai:assistant:use";
            case "KNOWLEDGE" -> "ai:knowledge:read";
            case "DASHBOARD" -> "ai:dashboard:query";
            case "REPAIR" -> "ai:repair:triage";
            case "NOTICE" -> "ai:notice:draft";
            case "RISK" -> "ai:risk:read";
            case "EVALUATION" -> "ai:eval:run";
            default -> throw new AiApiException(HttpStatus.NOT_FOUND, "AI_RESOURCE_NOT_FOUND",
                    "资源不存在", false);
        };
    }

    private void requireInitialAccess(AiActorContext actor, Run run, String requiredPermission) {
        if (!actorResolver.stillValid(actor, requiredPermission)) {
            throw AiApiException.notFound();
        }
        runAccessAuthorizer.requireCurrentAccess(actor, run);
    }

    private boolean isCurrentlyAuthorized(Subscriber subscriber) {
        if (!actorResolver.stillValid(subscriber.actor, subscriber.requiredPermission)) return false;
        try {
            Run run = store.ownedRun(subscriber.actor.userId(), subscriber.runId);
            if (run == null || !subscriber.requiredPermission.equals(permissionFor(run.capability()))) {
                return false;
            }
            runAccessAuthorizer.requireCurrentAccess(subscriber.actor, run);
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private final class Subscriber {
        private final String runId;
        private final AiActorContext actor;
        private final String requiredPermission;
        private final AiSseConnectionLeaseStore.Lease lease;
        private final SseEmitter emitter;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean leaseReleased = new AtomicBoolean();
        private final List<Event> pending = new ArrayList<>();
        private boolean replaying = true;
        private boolean terminalEventSent;
        private long lastSent;

        private Subscriber(
                String runId,
                AiActorContext actor,
                String requiredPermission,
                AiSseConnectionLeaseStore.Lease lease,
                SseEmitter emitter,
                long lastSent) {
            this.runId = runId;
            this.actor = actor;
            this.requiredPermission = requiredPermission;
            this.lease = lease;
            this.emitter = emitter;
            this.lastSent = lastSent;
        }

        private synchronized void sendReplay(Event event) {
            sendPersisted(event);
        }

        private synchronized void sendLive(Event event) {
            if (closed.get()) return;
            if (replaying) {
                pending.add(event);
                return;
            }
            sendPersisted(event);
            if (TERMINAL_TYPES.contains(event.type())) complete();
        }

        private synchronized void finishReplay() {
            replaying = false;
            for (Event event : pending.stream().sorted(Comparator.comparingLong(Event::sequence)).toList()) {
                if (!isCurrentlyAuthorized(this)) {
                    complete();
                    break;
                }
                sendPersisted(event);
                if (TERMINAL_TYPES.contains(event.type())) {
                    complete();
                    break;
                }
            }
            pending.clear();
        }

        private synchronized long lastSent() {
            return lastSent;
        }

        private synchronized boolean hasTerminalEvent() {
            return terminalEventSent;
        }

        private synchronized void sendHeartbeat() {
            if (closed.get() || replaying) return;
            Event heartbeat = new Event(null, runId, lastSent, "heartbeat", Instant.now(),
                    Map.of("serverTime", Instant.now().toString()));
            try {
                emitter.send(SseEmitter.event().name("heartbeat").data(heartbeat));
            } catch (IOException | IllegalStateException exception) {
                complete();
            }
        }

        private void sendPersisted(Event event) {
            if (closed.get()) return;
            if (event.sequence() <= lastSent) {
                if (TERMINAL_TYPES.contains(event.type())) terminalEventSent = true;
                return;
            }
            try {
                emitter.send(SseEmitter.event()
                        .id(event.eventId())
                        .name(event.type())
                        .data(event));
                lastSent = event.sequence();
                if (TERMINAL_TYPES.contains(event.type())) terminalEventSent = true;
            } catch (IOException | IllegalStateException exception) {
                complete();
            }
        }

        private void complete() {
            if (!closed.compareAndSet(false, true)) return;
            remove(this);
            try {
                emitter.complete();
            } catch (IllegalStateException ignored) {
                // Another servlet completion callback won the race.
            }
        }
    }
}
