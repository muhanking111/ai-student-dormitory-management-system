package com.example.dormitory.ai.infrastructure.runtime;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.application.run.AiActorContext;
import com.example.dormitory.ai.application.run.AiActorResolver;
import com.example.dormitory.ai.application.run.AiConversationRunStore;
import com.example.dormitory.ai.application.run.AiRunCurrentAccessAuthorizer;
import com.example.dormitory.ai.application.run.AiRunRecords;
import com.example.dormitory.ai.config.AiProperties;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class AiSseEmitterBrokerTest {

    @Test
    void usesSharedAtomicLeaseAndHeartbeatRechecksTheRunCapabilityPermission() {
        AiConversationRunStore store = mock(AiConversationRunStore.class);
        AiActorResolver actors = mock(AiActorResolver.class);
        ThreadPoolTaskScheduler scheduler = mock(ThreadPoolTaskScheduler.class);
        AiSseConnectionLeaseStore leases = mock(AiSseConnectionLeaseStore.class);
        AiRuntimeCrypto crypto = mock(AiRuntimeCrypto.class);
        AiProperties properties = new AiProperties();
        properties.getRuntime().setMaxSseConnectionsPerUser(1);
        properties.getRuntime().setHeartbeatInterval(Duration.ofSeconds(1));
        properties.getRuntime().setEmitterTimeout(Duration.ofMinutes(1));
        AiActorContext actor = new AiActorContext(7L, "session", "f".repeat(64), 1, "d".repeat(64),
                List.of("ADMIN"), List.of("ai:dashboard:query"), ActorDescriptor.user(7L));
        String runId = "11111111-1111-1111-1111-111111111111";
        AiRunRecords.Run run = new AiRunRecords.Run(runId, "22222222-2222-2222-2222-222222222222",
                7L, "DASHBOARD", "RUNNING", "d".repeat(64), "f".repeat(64), "v1",
                Instant.now(), null, null);
        when(store.ownedRun(7L, runId)).thenReturn(run);
        when(store.eventsAfter(7L, runId, 0)).thenReturn(List.of());
        when(crypto.hmacHex("sse-connection", "7")).thenReturn("a".repeat(64));
        AiSseConnectionLeaseStore.Lease lease = new AiSseConnectionLeaseStore.Lease(
                "lease-1", "a".repeat(64), Duration.ofSeconds(3));
        when(leases.acquire(eq("a".repeat(64)), eq(1), any(Duration.class)))
                .thenReturn(Optional.of(lease), Optional.empty());
        when(leases.renew(lease)).thenReturn(true);
        when(actors.stillValid(actor, "ai:dashboard:query")).thenReturn(true);
        AiSseEmitterBroker broker = new AiSseEmitterBroker(
                store, actors, properties, scheduler, leases, crypto,
                mock(AiRunCurrentAccessAuthorizer.class));
        broker.startHeartbeat();

        broker.subscribe(actor, runId, 0);
        AiApiException limited = assertThrows(AiApiException.class,
                () -> broker.subscribe(actor, runId, 0));
        assertEquals("AI_SSE_CONNECTION_LIMIT", limited.errorCode());
        ArgumentCaptor<Runnable> heartbeat = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(heartbeat.capture(), eq(Duration.ofSeconds(1)));
        heartbeat.getValue().run();

        verify(actors, times(3)).stillValid(actor, "ai:dashboard:query");
        verify(leases).renew(lease);
    }
}
