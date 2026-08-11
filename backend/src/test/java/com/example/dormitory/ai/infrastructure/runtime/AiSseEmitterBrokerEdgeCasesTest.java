package com.example.dormitory.ai.infrastructure.runtime;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.application.run.AiActorContext;
import com.example.dormitory.ai.application.run.AiActorResolver;
import com.example.dormitory.ai.application.run.AiConversationRunStore;
import com.example.dormitory.ai.application.run.AiRunCurrentAccessAuthorizer;
import com.example.dormitory.ai.application.run.AiRunRecords.Event;
import com.example.dormitory.ai.application.run.AiRunRecords.Run;
import com.example.dormitory.ai.config.AiProperties;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiSseEmitterBrokerEdgeCasesTest {

    @ParameterizedTest
    @MethodSource("invalidHeartbeatIntervals")
    void invalidHeartbeatIntervalsFallBackToFifteenSeconds(Duration configured) {
        Fixture fixture = new Fixture();
        fixture.properties.getRuntime().setHeartbeatInterval(configured);

        fixture.broker.startHeartbeat();

        verify(fixture.scheduler).scheduleAtFixedRate(any(Runnable.class), eq(Duration.ofSeconds(15)));
    }

    static Stream<Duration> invalidHeartbeatIntervals() {
        return Stream.of(null, Duration.ZERO, Duration.ofSeconds(-1));
    }

    @ParameterizedTest
    @MethodSource("leaseTtlCases")
    void leaseTtlHonorsFallbackAndBounds(Duration heartbeat, Duration expectedTtl) {
        Fixture fixture = new Fixture();
        fixture.properties.getRuntime().setHeartbeatInterval(heartbeat);

        fixture.broker.subscribe(fixture.actor, fixture.runId, 0);

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(fixture.leases).acquire(eq(fixture.actorKey), eq(3), ttl.capture());
        assertEquals(expectedTtl, ttl.getValue());
    }

    static Stream<Arguments> leaseTtlCases() {
        return Stream.of(
                Arguments.of(null, Duration.ofSeconds(45)),
                Arguments.of(Duration.ZERO, Duration.ofSeconds(45)),
                Arguments.of(Duration.ofSeconds(-1), Duration.ofSeconds(45)),
                Arguments.of(Duration.ofSeconds(20), Duration.ofMinutes(1)),
                Arguments.of(Duration.ofMinutes(5), Duration.ofMinutes(10)));
    }

    @Test
    void emitterTimeoutHasOneSecondMinimum() {
        Fixture fixture = new Fixture();
        fixture.properties.getRuntime().setEmitterTimeout(Duration.ZERO);

        SseEmitter emitter = fixture.broker.subscribe(fixture.actor, fixture.runId, 0);

        assertEquals(1_000L, emitter.getTimeout());
    }

    @ParameterizedTest
    @MethodSource("capabilityPermissions")
    void heartbeatRevalidatesPermissionMappedFromEveryCapability(
            String capability, String expectedPermission) {
        Fixture fixture = new Fixture();
        when(fixture.store.ownedRun(fixture.actor.userId(), fixture.runId))
                .thenReturn(fixture.run(capability, "RUNNING"));
        Runnable heartbeat = fixture.captureHeartbeat();

        fixture.broker.subscribe(fixture.actor, fixture.runId, 0);
        heartbeat.run();

        verify(fixture.actors, times(2)).stillValid(fixture.actor, expectedPermission);
    }

    static Stream<Arguments> capabilityPermissions() {
        return Stream.of(
                Arguments.of("ASSISTANT", "ai:assistant:use"),
                Arguments.of("KNOWLEDGE", "ai:knowledge:read"),
                Arguments.of("DASHBOARD", "ai:dashboard:query"),
                Arguments.of("REPAIR", "ai:repair:triage"),
                Arguments.of("NOTICE", "ai:notice:draft"),
                Arguments.of("RISK", "ai:risk:read"),
                Arguments.of("EVALUATION", "ai:eval:run"));
    }

    @Test
    void unknownCapabilityIsHiddenBeforeAcquiringALease() {
        Fixture fixture = new Fixture();
        when(fixture.store.ownedRun(fixture.actor.userId(), fixture.runId))
                .thenReturn(fixture.run("UNSUPPORTED", "RUNNING"));

        AiApiException failure = assertThrows(AiApiException.class,
                () -> fixture.broker.subscribe(fixture.actor, fixture.runId, 0));

        assertEquals(HttpStatus.NOT_FOUND, failure.status());
        assertEquals("AI_RESOURCE_NOT_FOUND", failure.errorCode());
        verify(fixture.leases, never()).acquire(anyString(), anyInt(), any(Duration.class));
    }

    @Test
    void currentObjectDenialBeforeReplayIsHiddenWithoutAcquiringLease() {
        Fixture fixture = new Fixture();
        doThrow(AiApiException.notFound()).when(fixture.access)
                .requireCurrentAccess(eq(fixture.actor), any(Run.class));

        AiApiException failure = assertThrows(AiApiException.class,
                () -> fixture.broker.subscribe(fixture.actor, fixture.runId, 0));

        assertEquals(HttpStatus.NOT_FOUND, failure.status());
        verify(fixture.leases, never()).acquire(anyString(), anyInt(), any(Duration.class));
        verify(fixture.store, never()).eventsAfter(anyLong(), anyString(), anyLong());
    }

    @Test
    void currentObjectDenialDuringReplayClosesBeforeSendingHistoricalResult() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.store.eventsAfter(fixture.actor.userId(), fixture.runId, 0))
                .thenReturn(List.of(fixture.event(1, "run.completed")));
        org.mockito.Mockito.doNothing().doThrow(AiApiException.notFound()).when(fixture.access)
                .requireCurrentAccess(eq(fixture.actor), any(Run.class));

        SseEmitter emitter = fixture.broker.subscribe(fixture.actor, fixture.runId, 0);

        assertTrue(emittedEvents(emitter).isEmpty());
        verify(fixture.leases).release(fixture.lease);
    }

    @Test
    void leaseControlPlaneFailureIsMappedToRetryableServiceUnavailable() {
        Fixture fixture = new Fixture();
        when(fixture.leases.acquire(eq(fixture.actorKey), eq(3), any(Duration.class)))
                .thenThrow(new AiSseLeaseStoreUnavailableException("down", new IllegalStateException("redis")));

        AiApiException failure = assertThrows(AiApiException.class,
                () -> fixture.broker.subscribe(fixture.actor, fixture.runId, 0));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.status());
        assertEquals("AI_SSE_LEASE_UNAVAILABLE", failure.errorCode());
        assertEquals(5, failure.retryAfterSeconds());
        assertTrue(failure.retryable());
    }

    @Test
    void replayFailureRemovesSubscriberAndReleaseFailureDoesNotEscape() {
        Fixture fixture = new Fixture();
        when(fixture.store.eventsAfter(fixture.actor.userId(), fixture.runId, 0))
                .thenThrow(new IllegalStateException("query failed"));
        doThrow(new AiSseLeaseStoreUnavailableException("down", new IllegalStateException("redis")))
                .when(fixture.leases).release(fixture.lease);

        assertDoesNotThrow(() -> fixture.broker.subscribe(fixture.actor, fixture.runId, 0));

        verify(fixture.leases).release(fixture.lease);
    }

    @Test
    void terminalRunAfterReplayCompletesAndReleasesLease() {
        Fixture fixture = new Fixture();
        when(fixture.store.ownedRun(fixture.actor.userId(), fixture.runId))
                .thenReturn(fixture.run("ASSISTANT", "RUNNING"),
                        fixture.run("ASSISTANT", "SUCCEEDED"));
        when(fixture.store.eventsAfter(fixture.actor.userId(), fixture.runId, 0))
                .thenReturn(List.of(), List.of(fixture.event(4, "run.completed")));

        fixture.broker.subscribe(fixture.actor, fixture.runId, 0);

        verify(fixture.leases).release(fixture.lease);
    }

    @Test
    void terminalStateWithoutVisibleEventKeepsLeaseUntilTerminalEventArrives() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.store.ownedRun(fixture.actor.userId(), fixture.runId))
                .thenReturn(fixture.run("ASSISTANT", "RUNNING"),
                        fixture.run("ASSISTANT", "SUCCEEDED"));
        when(fixture.store.eventsAfter(fixture.actor.userId(), fixture.runId, 0))
                .thenReturn(List.of(), List.of());

        SseEmitter emitter = fixture.broker.subscribe(fixture.actor, fixture.runId, 0);

        assertTrue(emittedEvents(emitter).isEmpty());
        verify(fixture.leases, never()).release(fixture.lease);

        fixture.broker.publish(fixture.event(4, "run.completed"));

        assertEquals(List.of(4L), emittedEvents(emitter).stream().map(Event::sequence).toList());
        verify(fixture.leases).release(fixture.lease);
    }

    @Test
    void terminalLiveEventCompletesOnceAndDuplicateSequenceIsSkipped() throws Exception {
        Fixture fixture = new Fixture();
        SseEmitter emitter = fixture.broker.subscribe(fixture.actor, fixture.runId, 0);

        fixture.broker.publish(fixture.event(1, "text.delta"));
        fixture.broker.publish(fixture.event(1, "usage"));
        fixture.broker.publish(fixture.event(2, "run.completed"));

        assertEquals(List.of(1L, 2L), emittedEvents(emitter).stream().map(Event::sequence).toList());
        verify(fixture.leases, times(1)).release(fixture.lease);
    }

    @Test
    void liveEventsPublishedDuringReplayAreSortedAndDeduplicated() throws Exception {
        Fixture fixture = new Fixture();
        Runnable heartbeat = fixture.captureHeartbeat();
        when(fixture.store.eventsAfter(fixture.actor.userId(), fixture.runId, 0)).thenAnswer(ignored -> {
            fixture.broker.publish(fixture.event(4, "text.delta"));
            fixture.broker.publish(fixture.event(2, "text.delta"));
            fixture.broker.publish(fixture.event(3, "usage"));
            heartbeat.run();
            return List.of(fixture.event(2, "text.delta"));
        });

        SseEmitter emitter = fixture.broker.subscribe(fixture.actor, fixture.runId, 0);

        assertEquals(List.of(2L, 3L, 4L), emittedEvents(emitter).stream().map(Event::sequence).toList());
        verify(fixture.leases).renew(fixture.lease);
        fixture.broker.publish(fixture.event(5, "run.failed"));
        verify(fixture.leases).release(fixture.lease);
    }

    @Test
    void terminalEventPublishedDuringReplayIsFlushedBeforeEmitterCompletes() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.store.eventsAfter(fixture.actor.userId(), fixture.runId, 0)).thenAnswer(ignored -> {
            fixture.broker.publish(fixture.event(2, "run.completed"));
            return List.of(fixture.event(1, "run.started"));
        });

        SseEmitter emitter = fixture.broker.subscribe(fixture.actor, fixture.runId, 0);

        assertEquals(List.of(1L, 2L), emittedEvents(emitter).stream().map(Event::sequence).toList());
        verify(fixture.leases).release(fixture.lease);
    }

    @Test
    void terminalStateSeenAfterAStaleReplayRefetchesTheMissingTerminalEvent() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.store.ownedRun(fixture.actor.userId(), fixture.runId))
                .thenReturn(fixture.run("ASSISTANT", "RUNNING"), fixture.run("ASSISTANT", "SUCCEEDED"));
        when(fixture.store.eventsAfter(eq(fixture.actor.userId()), eq(fixture.runId), anyLong()))
                .thenAnswer(invocation -> ((long) invocation.getArgument(2)) == 0
                        ? List.of(fixture.event(1, "run.accepted"), fixture.event(2, "run.queued"),
                                fixture.event(3, "run.started"))
                        : List.of(fixture.event(4, "run.completed")));

        SseEmitter emitter = fixture.broker.subscribe(fixture.actor, fixture.runId, 0);

        assertEquals(List.of(1L, 2L, 3L, 4L), emittedEvents(emitter).stream().map(Event::sequence).toList());
        verify(fixture.leases).release(fixture.lease);
    }

    @Test
    void heartbeatClosesInvalidActorWithoutRenewingLease() {
        Fixture fixture = new Fixture();
        when(fixture.actors.stillValid(fixture.actor, "ai:assistant:use")).thenReturn(true, false);
        Runnable heartbeat = fixture.captureHeartbeat();
        fixture.broker.subscribe(fixture.actor, fixture.runId, 0);

        heartbeat.run();

        verify(fixture.leases, never()).renew(fixture.lease);
        verify(fixture.leases).release(fixture.lease);
    }

    @Test
    void currentObjectDenialClosesHeartbeatAndLiveEventWithoutSendingPayload() throws Exception {
        Fixture heartbeatFixture = new Fixture();
        Runnable heartbeat = heartbeatFixture.captureHeartbeat();
        heartbeatFixture.broker.subscribe(heartbeatFixture.actor, heartbeatFixture.runId, 0);
        doThrow(AiApiException.notFound()).when(heartbeatFixture.access)
                .requireCurrentAccess(eq(heartbeatFixture.actor), any(Run.class));

        heartbeat.run();

        verify(heartbeatFixture.leases, never()).renew(heartbeatFixture.lease);
        verify(heartbeatFixture.leases).release(heartbeatFixture.lease);

        Fixture liveFixture = new Fixture();
        SseEmitter emitter = liveFixture.broker.subscribe(liveFixture.actor, liveFixture.runId, 0);
        doThrow(AiApiException.notFound()).when(liveFixture.access)
                .requireCurrentAccess(eq(liveFixture.actor), any(Run.class));

        liveFixture.broker.publish(liveFixture.event(1, "run.completed"));

        assertTrue(emittedEvents(emitter).isEmpty());
        verify(liveFixture.leases).release(liveFixture.lease);
    }

    @Test
    void heartbeatClosesWhenLeaseRenewalFails() {
        Fixture fixture = new Fixture();
        when(fixture.leases.renew(fixture.lease)).thenReturn(false);
        Runnable heartbeat = fixture.captureHeartbeat();
        fixture.broker.subscribe(fixture.actor, fixture.runId, 0);

        heartbeat.run();

        verify(fixture.leases).release(fixture.lease);
    }

    @ParameterizedTest
    @MethodSource("runLossModes")
    void heartbeatClosesWhenRunDisappearsOrLookupFails(boolean throwOnLookup) {
        Fixture fixture = new Fixture();
        Run running = fixture.run("ASSISTANT", "RUNNING");
        if (throwOnLookup) {
            when(fixture.store.ownedRun(fixture.actor.userId(), fixture.runId))
                    .thenReturn(running, running)
                    .thenThrow(new IllegalStateException("query failed"));
        } else {
            when(fixture.store.ownedRun(fixture.actor.userId(), fixture.runId))
                    .thenReturn(running, running, null);
        }
        Runnable heartbeat = fixture.captureHeartbeat();
        fixture.broker.subscribe(fixture.actor, fixture.runId, 0);

        heartbeat.run();

        verify(fixture.leases).release(fixture.lease);
    }

    static Stream<Boolean> runLossModes() {
        return Stream.of(false, true);
    }

    @Test
    void heartbeatSendFailureCompletesAndReleasesLease() {
        Fixture fixture = new Fixture();
        Runnable heartbeat = fixture.captureHeartbeat();
        SseEmitter emitter = fixture.broker.subscribe(fixture.actor, fixture.runId, 0);
        emitter.complete();

        heartbeat.run();

        verify(fixture.leases).release(fixture.lease);
    }

    @SuppressWarnings("unchecked")
    private static List<Event> emittedEvents(SseEmitter emitter) throws ReflectiveOperationException {
        Field field = ResponseBodyEmitter.class.getDeclaredField("earlySendAttempts");
        field.setAccessible(true);
        Set<ResponseBodyEmitter.DataWithMediaType> attempts =
                (Set<ResponseBodyEmitter.DataWithMediaType>) field.get(emitter);
        return attempts.stream()
                .map(ResponseBodyEmitter.DataWithMediaType::getData)
                .filter(Event.class::isInstance)
                .map(Event.class::cast)
                .toList();
    }

    private static final class Fixture {
        private final AiConversationRunStore store = mock(AiConversationRunStore.class);
        private final AiActorResolver actors = mock(AiActorResolver.class);
        private final ThreadPoolTaskScheduler scheduler = mock(ThreadPoolTaskScheduler.class);
        private final AiSseConnectionLeaseStore leases = mock(AiSseConnectionLeaseStore.class);
        private final AiRuntimeCrypto crypto = mock(AiRuntimeCrypto.class);
        private final AiRunCurrentAccessAuthorizer access = mock(AiRunCurrentAccessAuthorizer.class);
        private final AiProperties properties = new AiProperties();
        private final AiActorContext actor = new AiActorContext(
                7L, "session", "f".repeat(64), 1, "d".repeat(64),
                List.of("ADMIN"), List.of(), ActorDescriptor.user(7L));
        private final String runId = "11111111-1111-1111-1111-111111111111";
        private final String actorKey = "a".repeat(64);
        private final AiSseConnectionLeaseStore.Lease lease =
                new AiSseConnectionLeaseStore.Lease("lease-1", actorKey, Duration.ofSeconds(30));
        private final AiSseEmitterBroker broker;

        private Fixture() {
            properties.getRuntime().setHeartbeatInterval(Duration.ofSeconds(1));
            properties.getRuntime().setEmitterTimeout(Duration.ofMinutes(1));
            properties.getRuntime().setMaxSseConnectionsPerUser(3);
            when(store.ownedRun(actor.userId(), runId)).thenReturn(run("ASSISTANT", "RUNNING"));
            when(store.eventsAfter(eq(actor.userId()), eq(runId), anyLong())).thenReturn(List.of());
            when(crypto.hmacHex("sse-connection", Long.toString(actor.userId()))).thenReturn(actorKey);
            when(leases.acquire(eq(actorKey), eq(3), any(Duration.class))).thenReturn(Optional.of(lease));
            when(leases.renew(lease)).thenReturn(true);
            when(actors.stillValid(eq(actor), anyString())).thenReturn(true);
            broker = new AiSseEmitterBroker(store, actors, properties, scheduler, leases, crypto, access);
        }

        private Runnable captureHeartbeat() {
            broker.startHeartbeat();
            ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler).scheduleAtFixedRate(task.capture(), any(Duration.class));
            return task.getValue();
        }

        private Run run(String capability, String state) {
            return new Run(runId, "22222222-2222-2222-2222-222222222222",
                    actor.userId(), capability, state, "d".repeat(64), "f".repeat(64), "v1",
                    Instant.parse("2026-07-13T00:00:00Z"), null, null);
        }

        private Event event(long sequence, String type) {
            return new Event("event-" + sequence + "-" + type.replace('.', '-'), runId,
                    sequence, type, Instant.parse("2026-07-13T00:00:00Z"), Map.of());
        }
    }
}
