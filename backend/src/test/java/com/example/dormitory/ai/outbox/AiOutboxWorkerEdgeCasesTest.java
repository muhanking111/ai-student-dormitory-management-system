package com.example.dormitory.ai.outbox;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.infrastructure.persistence.AiSchemaMigrationInitializer;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiOutboxRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AiOutboxWorkerEdgeCasesTest {

    private static final String TYPE = "WorkerEdge.v1";

    @Test
    void manualAndScheduledDrainWaitForSchemaMigrationBeforeRepositoryAccess() {
        JdbcAiOutboxRepository repository = mock(JdbcAiOutboxRepository.class);
        AiSchemaMigrationInitializer schemaInitializer = mock(AiSchemaMigrationInitializer.class);
        when(schemaInitializer.isSchemaReady()).thenReturn(false, true);
        when(repository.recoverExpiredLeases(any(Instant.class), any(Duration.class), anyInt(), anySet()))
                .thenReturn(new JdbcAiOutboxRepository.LeaseRecoveryResult(0, List.of()));
        when(repository.claimNext(any(String.class), any(Instant.class), anySet()))
                .thenReturn(Optional.empty());

        AiOutboxWorker worker = new AiOutboxWorker(repository, List.of(handler(TYPE)),
                Duration.ofSeconds(2), Duration.ofMillis(500), Duration.ofSeconds(1),
                5, true, true, schemaInitializer);
        try {
            assertEquals(0, worker.processAvailable(25));
            verifyNoInteractions(repository);

            worker.scheduledDrain();
            verify(repository).recoverExpiredLeases(
                    any(Instant.class), any(Duration.class), anyInt(), anySet());
        } finally {
            worker.close();
        }
    }

    @Test
    void constructorRejectsEveryUnsafeTimingAttemptAndHandlerRegistration() {
        JdbcAiOutboxRepository repository = mock(JdbcAiOutboxRepository.class);
        AiOutboxEventHandler handler = handler(TYPE);
        Duration lease = Duration.ofSeconds(2);
        Duration heartbeat = Duration.ofMillis(500);
        Duration retry = Duration.ofSeconds(1);

        for (Duration invalid : new Duration[] {null, Duration.ZERO, Duration.ofSeconds(-1)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new AiOutboxWorker(repository, List.of(handler), invalid, heartbeat, retry,
                            5, false, true));
        }
        for (Duration invalid : new Duration[] {null, Duration.ZERO, Duration.ofSeconds(2)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new AiOutboxWorker(repository, List.of(handler), lease, invalid, retry,
                            5, false, true));
        }
        for (Duration invalid : new Duration[] {null, Duration.ZERO, Duration.ofSeconds(-1)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new AiOutboxWorker(repository, List.of(handler), lease, heartbeat, invalid,
                            5, false, true));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new AiOutboxWorker(repository, List.of(handler), lease, heartbeat, retry,
                        0, false, true));
        assertThrows(IllegalArgumentException.class,
                () -> new AiOutboxWorker(repository, List.of(handler), lease, heartbeat, retry,
                        101, false, true));

        AiOutboxEventHandler nullType = mock(AiOutboxEventHandler.class);
        when(nullType.eventTypes()).thenReturn(Collections.singleton(null));
        assertThrows(IllegalStateException.class,
                () -> new AiOutboxWorker(repository, List.of(nullType), lease, heartbeat, retry,
                        5, false, true));
        AiOutboxEventHandler badType = handler("bad event type");
        assertThrows(IllegalStateException.class,
                () -> new AiOutboxWorker(repository, List.of(badType), lease, heartbeat, retry,
                        5, false, true));
        assertThrows(IllegalStateException.class,
                () -> new AiOutboxWorker(repository, List.of(handler, handler(TYPE)), lease, heartbeat, retry,
                        5, false, true));
    }

    @Test
    void disabledEmptyAndScheduledGatesAvoidRepositoryWork() {
        JdbcAiOutboxRepository disabledRepository = mock(JdbcAiOutboxRepository.class);
        AiOutboxWorker disabled = worker(disabledRepository, List.of(handler(TYPE)), true, false);
        try {
            assertThrows(IllegalArgumentException.class, () -> disabled.processAvailable(0));
            assertThrows(IllegalArgumentException.class, () -> disabled.processAvailable(1_001));
            assertEquals(0, disabled.processAvailable(1));
            disabled.scheduledDrain();
            verifyNoInteractions(disabledRepository);
        } finally {
            disabled.close();
        }

        JdbcAiOutboxRepository emptyRepository = mock(JdbcAiOutboxRepository.class);
        AiOutboxWorker empty = worker(emptyRepository, List.of(), true, true);
        try {
            assertEquals(0, empty.processAvailable(1));
            empty.scheduledDrain();
            verifyNoInteractions(emptyRepository);
        } finally {
            empty.close();
        }

        JdbcAiOutboxRepository manualRepository = mock(JdbcAiOutboxRepository.class);
        AiOutboxWorker manual = worker(manualRepository, List.of(handler(TYPE)), false, true);
        try {
            manual.scheduledDrain();
            verifyNoInteractions(manualRepository);
        } finally {
            manual.close();
        }
    }

    @Test
    void recoveryDeadEventsAndSuccessfulClaimsReachOnlyTheirRegisteredHandler() {
        JdbcAiOutboxRepository repository = mock(JdbcAiOutboxRepository.class);
        AiOutboxEventHandler handler = handler(TYPE);
        JdbcAiOutboxRepository.ClaimedOutboxEvent dead = event(TYPE, "dead-worker");
        JdbcAiOutboxRepository.ClaimedOutboxEvent claimed = event(TYPE, "live-worker");
        when(repository.recoverExpiredLeases(any(Instant.class), any(Duration.class), anyInt(), anySet()))
                .thenReturn(new JdbcAiOutboxRepository.LeaseRecoveryResult(1, List.of(dead)),
                        new JdbcAiOutboxRepository.LeaseRecoveryResult(0, List.of()));
        when(repository.claimNext(any(String.class), any(Instant.class), anySet()))
                .thenReturn(Optional.empty(), Optional.of(claimed), Optional.empty());
        when(repository.markSucceeded(eq(claimed.publicId()), eq(claimed.lockedBy()), any(Instant.class)))
                .thenReturn(true);
        AiOutboxWorker worker = worker(repository, List.of(handler), false, true);
        try {
            assertEquals(0, worker.processAvailable(5));
            assertEquals(1, worker.processAvailable(5));
            verify(handler).onDead(dead, "WORKER_LEASE_EXPIRED");
            verify(handler).handle(claimed);
            verify(repository).markSucceeded(eq(claimed.publicId()), eq(claimed.lockedBy()), any(Instant.class));
        } finally {
            worker.close();
        }
    }

    @Test
    void handlerFailureRetriesThenDeadLettersAndTransitionConflictsPropagate() {
        JdbcAiOutboxRepository repository = mock(JdbcAiOutboxRepository.class);
        AiOutboxEventHandler handler = handler(TYPE);
        JdbcAiOutboxRepository.ClaimedOutboxEvent first = event(TYPE, "worker-a");
        JdbcAiOutboxRepository.ClaimedOutboxEvent second = event(TYPE, "worker-b");
        doThrow(new IllegalStateException("handler failed")).when(handler)
                .handle(any(JdbcAiOutboxRepository.ClaimedOutboxEvent.class));
        when(repository.recoverExpiredLeases(any(Instant.class), any(Duration.class), anyInt(), anySet()))
                .thenReturn(new JdbcAiOutboxRepository.LeaseRecoveryResult(0, List.of()));
        when(repository.claimNext(any(String.class), any(Instant.class), anySet()))
                .thenReturn(Optional.of(first), Optional.of(second), Optional.empty());
        when(repository.markFailure(eq(first.publicId()), eq(first.lockedBy()), eq("OUTBOX_HANDLER_FAILED"),
                any(Instant.class), any(Duration.class), anyInt()))
                .thenReturn(new JdbcAiOutboxRepository.FailureResult(false, 1, Instant.now().plusSeconds(1)));
        when(repository.markFailure(eq(second.publicId()), eq(second.lockedBy()), eq("OUTBOX_HANDLER_FAILED"),
                any(Instant.class), any(Duration.class), anyInt()))
                .thenReturn(new JdbcAiOutboxRepository.FailureResult(true, 5, null));
        AiOutboxWorker worker = worker(repository, List.of(handler), false, true);
        try {
            assertEquals(1, worker.processAvailable(1));
            assertEquals(1, worker.processAvailable(1));
            verify(handler).onDead(second, "OUTBOX_HANDLER_FAILED");
        } finally {
            worker.close();
        }

        JdbcAiOutboxRepository conflictRepository = mock(JdbcAiOutboxRepository.class);
        AiOutboxEventHandler conflictHandler = handler(TYPE);
        JdbcAiOutboxRepository.ClaimedOutboxEvent conflictEvent = event(TYPE, "worker-c");
        doThrow(new IllegalStateException("handler failed")).when(conflictHandler).handle(conflictEvent);
        stubSingleClaim(conflictRepository, conflictEvent);
        when(conflictRepository.markFailure(any(String.class), any(String.class), any(String.class),
                any(Instant.class), any(Duration.class), anyInt()))
                .thenThrow(new IllegalStateException("failure CAS moved"));
        AiOutboxWorker conflictWorker = worker(conflictRepository, List.of(conflictHandler), false, true);
        try {
            IllegalStateException conflict = assertThrows(IllegalStateException.class,
                    () -> conflictWorker.processAvailable(1));
            assertEquals("failure CAS moved", conflict.getMessage());
        } finally {
            conflictWorker.close();
        }
    }

    @Test
    void governanceDisableErrorsDeferButOtherApiFailuresUseRetryBudget() {
        JdbcAiOutboxRepository deferredRepository = mock(JdbcAiOutboxRepository.class);
        AiOutboxEventHandler deferredHandler = handler(TYPE);
        JdbcAiOutboxRepository.ClaimedOutboxEvent deferredEvent = event(TYPE, "worker-defer");
        doThrow(AiApiException.unavailable("AI_MASTER_DISABLED", "disabled"))
                .when(deferredHandler).handle(deferredEvent);
        stubSingleClaim(deferredRepository, deferredEvent);
        when(deferredRepository.defer(eq(deferredEvent.publicId()), eq(deferredEvent.lockedBy()),
                eq("AI_MASTER_DISABLED"), any(Instant.class))).thenReturn(true);
        AiOutboxWorker deferred = worker(deferredRepository, List.of(deferredHandler), false, true);
        try {
            assertEquals(1, deferred.processAvailable(1));
            verify(deferredRepository, never()).markFailure(any(), any(), any(), any(), any(), anyInt());
        } finally {
            deferred.close();
        }

        JdbcAiOutboxRepository rejectedRepository = mock(JdbcAiOutboxRepository.class);
        AiOutboxEventHandler rejectedHandler = handler(TYPE);
        JdbcAiOutboxRepository.ClaimedOutboxEvent rejectedEvent = event(TYPE, "worker-reject");
        AiApiException disabled = new AiApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "AI_CAPABILITY_DISABLED", "disabled", true, 0, null, List.of(), java.util.Map.of());
        doThrow(disabled).when(rejectedHandler).handle(rejectedEvent);
        stubSingleClaim(rejectedRepository, rejectedEvent);
        when(rejectedRepository.defer(eq(rejectedEvent.publicId()), eq(rejectedEvent.lockedBy()),
                eq("AI_CAPABILITY_DISABLED"), any(Instant.class))).thenReturn(false);
        AiOutboxWorker rejected = worker(rejectedRepository, List.of(rejectedHandler), false, true);
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> rejected.processAvailable(1));
            assertTrue(failure.getMessage().contains("defer 状态 CAS"));
        } finally {
            rejected.close();
        }

        JdbcAiOutboxRepository ordinaryRepository = mock(JdbcAiOutboxRepository.class);
        AiOutboxEventHandler ordinaryHandler = handler(TYPE);
        JdbcAiOutboxRepository.ClaimedOutboxEvent ordinaryEvent = event(TYPE, "worker-ordinary");
        doThrow(AiApiException.unavailable("AI_PROVIDER_TEMPORARY", "temporary"))
                .when(ordinaryHandler).handle(ordinaryEvent);
        stubSingleClaim(ordinaryRepository, ordinaryEvent);
        when(ordinaryRepository.markFailure(any(String.class), any(String.class), any(String.class),
                any(Instant.class), any(Duration.class), anyInt()))
                .thenReturn(new JdbcAiOutboxRepository.FailureResult(false, 1, Instant.now()));
        AiOutboxWorker ordinary = worker(ordinaryRepository, List.of(ordinaryHandler), false, true);
        try {
            assertEquals(1, ordinary.processAvailable(1));
            verify(ordinaryRepository).markFailure(eq(ordinaryEvent.publicId()), eq(ordinaryEvent.lockedBy()),
                    eq("OUTBOX_HANDLER_FAILED"), any(Instant.class), any(Duration.class), anyInt());
        } finally {
            ordinary.close();
        }
    }

    @Test
    void unknownClaimsAndSuccessCasLossFailClosed() {
        JdbcAiOutboxRepository unknownRepository = mock(JdbcAiOutboxRepository.class);
        JdbcAiOutboxRepository.ClaimedOutboxEvent unknown = event("Unknown.v1", "unknown-worker");
        stubSingleClaim(unknownRepository, unknown);
        AiOutboxWorker unknownWorker = worker(unknownRepository, List.of(handler(TYPE)), false, true);
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> unknownWorker.processAvailable(1));
            assertTrue(failure.getMessage().contains("未知 outbox"));
        } finally {
            unknownWorker.close();
        }

        JdbcAiOutboxRepository casRepository = mock(JdbcAiOutboxRepository.class);
        AiOutboxEventHandler casHandler = handler(TYPE);
        JdbcAiOutboxRepository.ClaimedOutboxEvent claimed = event(TYPE, "cas-worker");
        stubSingleClaim(casRepository, claimed);
        when(casRepository.markSucceeded(eq(claimed.publicId()), eq(claimed.lockedBy()), any(Instant.class)))
                .thenReturn(false);
        AiOutboxWorker casWorker = worker(casRepository, List.of(casHandler), false, true);
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> casWorker.processAvailable(1));
            assertTrue(failure.getMessage().contains("成功状态 CAS"));
        } finally {
            casWorker.close();
        }
    }

    @Test
    void heartbeatRenewalFalseOrExceptionInterruptsHandlerAndBlocksTerminalWrites() {
        assertHeartbeatLoss(false);
        assertHeartbeatLoss(true);
    }

    private void assertHeartbeatLoss(boolean renewalThrows) {
        JdbcAiOutboxRepository repository = mock(JdbcAiOutboxRepository.class);
        AiOutboxEventHandler handler = handler(TYPE);
        JdbcAiOutboxRepository.ClaimedOutboxEvent claimed = event(TYPE, "heartbeat-worker");
        stubSingleClaim(repository, claimed);
        doAnswer(invocation -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException expected) {
                return null;
            }
            return null;
        }).when(handler).handle(claimed);
        if (renewalThrows) {
            when(repository.renewLease(eq(claimed.publicId()), eq(claimed.lockedBy()), any(Instant.class)))
                    .thenThrow(new IllegalStateException("renewal failed"));
        } else {
            when(repository.renewLease(eq(claimed.publicId()), eq(claimed.lockedBy()), any(Instant.class)))
                    .thenReturn(false);
        }
        AiOutboxWorker worker = new AiOutboxWorker(repository, List.of(handler),
                Duration.ofMillis(100), Duration.ofMillis(10), Duration.ofSeconds(1),
                5, false, true);
        try {
            IllegalStateException lost = assertThrows(IllegalStateException.class,
                    () -> worker.processAvailable(1));
            assertTrue(lost.getMessage().contains("lease heartbeat"));
            assertFalse(Thread.currentThread().isInterrupted());
            verify(repository, never()).markSucceeded(any(), any(), any());
            verify(repository, never()).markFailure(any(), any(), any(), any(), any(), anyInt());
        } finally {
            worker.close();
            Thread.interrupted();
        }
    }

    private AiOutboxWorker worker(
            JdbcAiOutboxRepository repository,
            List<AiOutboxEventHandler> handlers,
            boolean autoProcess,
            boolean aiEnabled) {
        return new AiOutboxWorker(repository, handlers,
                Duration.ofSeconds(2), Duration.ofMillis(500), Duration.ofSeconds(1),
                5, autoProcess, aiEnabled);
    }

    private AiOutboxEventHandler handler(String eventType) {
        AiOutboxEventHandler handler = mock(AiOutboxEventHandler.class);
        when(handler.eventTypes()).thenReturn(Set.of(eventType));
        return handler;
    }

    private JdbcAiOutboxRepository.ClaimedOutboxEvent event(String eventType, String worker) {
        return new JdbcAiOutboxRepository.ClaimedOutboxEvent(
                UUID.randomUUID().toString(), "RISK_SCAN", UUID.randomUUID().toString(),
                eventType, "{}", worker, 0, "SERVICE", "worker-test", 7L, 7L);
    }

    private void stubSingleClaim(
            JdbcAiOutboxRepository repository,
            JdbcAiOutboxRepository.ClaimedOutboxEvent event) {
        when(repository.recoverExpiredLeases(any(Instant.class), any(Duration.class), anyInt(), anySet()))
                .thenReturn(new JdbcAiOutboxRepository.LeaseRecoveryResult(0, List.of()));
        when(repository.claimNext(any(String.class), any(Instant.class), anySet()))
                .thenReturn(Optional.of(event), Optional.empty());
    }
}
