package com.example.dormitory.ai.resilience;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiResiliencePolicyTest {

    private final AtomicLong now = new AtomicLong(1_000);
    private final AiResiliencePolicy policy = new AiResiliencePolicy(
            Executors.newFixedThreadPool(2), now::get);

    @AfterEach
    void close() {
        policy.close();
    }

    @Test
    void retriesOnlyExplicitRetryableFailuresAndOpensThenHalfOpensCircuit() {
        AiResiliencePolicy.Policy config = new AiResiliencePolicy.Policy(
                Duration.ofSeconds(1), 2, 2, Duration.ofSeconds(10), Duration.ZERO);
        AiResiliencePolicy.RequestKey key = key("perm-a", "request-a");
        AtomicInteger attempts = new AtomicInteger();

        String value = policy.execute(config, key, () -> {
            if (attempts.incrementAndGet() == 1) throw new AiResiliencePolicy.RetryableFailure("temporary");
            return "ok";
        });
        assertEquals("ok", value);
        assertEquals(2, attempts.get());

        assertThrows(SecurityException.class, () -> policy.execute(config, key("perm-a", "security"), () -> {
            throw new SecurityException("no retry");
        }));

        for (int index = 0; index < 2; index++) {
            assertThrows(AiResiliencePolicy.RetryableFailure.class,
                    () -> policy.execute(new AiResiliencePolicy.Policy(
                                    Duration.ofSeconds(1), 1, 2, Duration.ofSeconds(10), Duration.ZERO),
                            key("perm-a", "failure-" + now.getAndIncrement()),
                            () -> { throw new AiResiliencePolicy.RetryableFailure("down"); }));
        }
        assertThrows(AiResiliencePolicy.CircuitOpenException.class,
                () -> policy.execute(config, key("perm-a", "blocked"), () -> "should-not-run"));
        now.addAndGet(10_001);
        assertEquals("recovered", policy.execute(config, key("perm-a", "half-open"), () -> "recovered"));
    }

    @Test
    void timeoutCancelsAndCacheIsIsolatedByPermissionAndAllPolicyVersions() {
        AiResiliencePolicy.Policy config = new AiResiliencePolicy.Policy(
                Duration.ofMillis(30), 1, 3, Duration.ofSeconds(1), Duration.ofMinutes(1));
        assertThrows(AiResiliencePolicy.UpstreamTimeoutException.class,
                () -> policy.execute(config, key("perm-a", "slow"), () -> {
                    try { Thread.sleep(200); } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return "late";
                }));

        AtomicInteger calls = new AtomicInteger();
        assertEquals("value-1", policy.execute(config, key("perm-a", "same"),
                () -> "value-" + calls.incrementAndGet()));
        assertEquals("value-1", policy.execute(config, key("perm-a", "same"),
                () -> "value-" + calls.incrementAndGet()));
        assertEquals("value-2", policy.execute(config, key("perm-b", "same"),
                () -> "value-" + calls.incrementAndGet()));
        assertEquals(2, calls.get());
    }

    @Test
    void retryableReadsUseBoundedExponentialBackoffBeforeEachPhysicalRetry() {
        List<Duration> delays = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        try (AiResiliencePolicy backoffPolicy = new AiResiliencePolicy(
                Executors.newFixedThreadPool(1), now::get, delays::add, () -> 0.5d)) {
            AiResiliencePolicy.Policy config = new AiResiliencePolicy.Policy(
                    Duration.ofSeconds(1), 3, 3, Duration.ofSeconds(10), Duration.ZERO,
                    Duration.ofMillis(25), 0.2d);

            assertEquals("ok", backoffPolicy.execute(config, key("perm-a", "backoff"), () -> {
                if (attempts.incrementAndGet() < 3) {
                    throw new AiResiliencePolicy.RetryableFailure("temporary");
                }
                return "ok";
            }));
        }

        assertEquals(3, attempts.get());
        assertEquals(List.of(Duration.ofMillis(25), Duration.ofMillis(50)), delays);
    }

    @Test
    void streamingObserverSeesEveryPhysicalRetryBeforeTheLogicalStreamCompletes() {
        AtomicInteger subscriptions = new AtomicInteger();
        List<String> lifecycle = new ArrayList<>();
        AiResiliencePolicy.StreamingAttemptObserver<String> observer =
                new AiResiliencePolicy.StreamingAttemptObserver<>() {
                    @Override
                    public void onStart(int attemptNo) {
                        lifecycle.add("start-" + attemptNo);
                    }

                    @Override
                    public void onEnd(
                            int attemptNo,
                            AiResiliencePolicy.StreamingAttemptOutcome outcome,
                            Throwable failure) {
                        lifecycle.add("end-" + attemptNo + "-" + outcome.name());
                    }
                };

        String value = policy.executeStreaming(
                        new AiResiliencePolicy.Policy(Duration.ofSeconds(1), 2, 3,
                                Duration.ofSeconds(10), Duration.ZERO),
                        key("perm-a", "physical-attempts"),
                        () -> subscriptions.incrementAndGet() == 1
                                ? Flux.error(new AiResiliencePolicy.RetryableFailure("temporary"))
                                : Flux.just("ok"),
                        observer)
                .blockLast();

        assertEquals("ok", value);
        assertEquals(2, subscriptions.get());
        assertEquals(List.of(
                "start-1", "end-1-FAILED_RETRYABLE",
                "start-2", "end-2-SUCCEEDED"), lifecycle);
    }

    @Test
    void streamingObserverDistinguishesTimeoutFromCancellation() {
        List<AiResiliencePolicy.StreamingAttemptOutcome> outcomes = new ArrayList<>();
        AiResiliencePolicy.StreamingAttemptObserver<String> observer =
                new AiResiliencePolicy.StreamingAttemptObserver<>() {
                    @Override
                    public void onEnd(int attemptNo,
                                      AiResiliencePolicy.StreamingAttemptOutcome outcome,
                                      Throwable failure) {
                        outcomes.add(outcome);
                    }
                };

        assertThrows(RuntimeException.class, () -> policy.executeStreaming(
                        new AiResiliencePolicy.Policy(Duration.ofMillis(20), 1, 3,
                                Duration.ofSeconds(10), Duration.ZERO),
                        key("perm-a", "physical-timeout"), Flux::<String>never, observer)
                .blockLast());

        assertEquals(List.of(AiResiliencePolicy.StreamingAttemptOutcome.TIMED_OUT), outcomes);
    }

    @Test
    void validatesEveryPolicyBoundAndCompleteCacheKeyField() {
        AiResiliencePolicy.Policy valid = config();
        AiResiliencePolicy.RequestKey validKey = key("perm", "request");
        assertThrows(IllegalArgumentException.class, () -> policy.execute(null, validKey, () -> "ok"));
        assertThrows(IllegalArgumentException.class, () -> policy.execute(valid, null, () -> "ok"));
        assertThrows(IllegalArgumentException.class, () -> policy.execute(valid, validKey, null));
        for (AiResiliencePolicy.Policy invalid : List.of(
                policy(Duration.ZERO, 1, 1, Duration.ZERO, Duration.ZERO, Duration.ofMillis(1), 0),
                policy(Duration.ofMillis(-1), 1, 1, Duration.ZERO, Duration.ZERO, Duration.ofMillis(1), 0),
                policy(Duration.ofSeconds(1), 0, 1, Duration.ZERO, Duration.ZERO, Duration.ofMillis(1), 0),
                policy(Duration.ofSeconds(1), 4, 1, Duration.ZERO, Duration.ZERO, Duration.ofMillis(1), 0),
                policy(Duration.ofSeconds(1), 1, 0, Duration.ZERO, Duration.ZERO, Duration.ofMillis(1), 0),
                policy(Duration.ofSeconds(1), 1, 1, Duration.ofMillis(-1), Duration.ZERO,
                        Duration.ofMillis(1), 0),
                policy(Duration.ofSeconds(1), 1, 1, Duration.ZERO, Duration.ofMillis(-1),
                        Duration.ofMillis(1), 0),
                policy(Duration.ofSeconds(1), 1, 1, Duration.ZERO, Duration.ZERO, Duration.ZERO, 0),
                policy(Duration.ofSeconds(1), 1, 1, Duration.ZERO, Duration.ZERO,
                        Duration.ofMillis(-1), 0),
                policy(Duration.ofSeconds(1), 1, 1, Duration.ZERO, Duration.ZERO,
                        Duration.ofMillis(1), Double.NaN),
                policy(Duration.ofSeconds(1), 1, 1, Duration.ZERO, Duration.ZERO,
                        Duration.ofMillis(1), -0.01),
                policy(Duration.ofSeconds(1), 1, 1, Duration.ZERO, Duration.ZERO,
                        Duration.ofMillis(1), 1.01))) {
            assertThrows(IllegalArgumentException.class,
                    () -> policy.execute(invalid, validKey, () -> "never"));
        }
        AiResiliencePolicy.Policy nullDelay = new AiResiliencePolicy.Policy(
                Duration.ofSeconds(1), 1, 1, Duration.ZERO, Duration.ZERO, null, 0);
        assertThrows(IllegalArgumentException.class,
                () -> policy.execute(nullDelay, validKey, () -> "never"));

        String[] values = {"DASHBOARD", "fake", "model-v1", "prompt-v1", "tool-v1",
                "retrieval-v1", "redaction-v1", "permission-v1", "request-v1"};
        for (int index = 0; index < values.length; index++) {
            String previous = values[index];
            values[index] = null;
            assertThrows(IllegalArgumentException.class, () -> requestKey(values));
            values[index] = " ";
            assertThrows(IllegalArgumentException.class, () -> requestKey(values));
            values[index] = previous;
        }
    }

    @Test
    void expiredCacheIsRemovedAndFailedHalfOpenProbeReopensCircuit() {
        AtomicInteger calls = new AtomicInteger();
        AiResiliencePolicy.Policy cached = new AiResiliencePolicy.Policy(
                Duration.ofSeconds(1), 1, 2, Duration.ofSeconds(5), Duration.ofMillis(10));
        AiResiliencePolicy.RequestKey key = key("perm", "expiring-cache");
        assertEquals("value-1", policy.execute(cached, key, () -> "value-" + calls.incrementAndGet()));
        now.addAndGet(11);
        assertEquals("value-2", policy.execute(cached, key, () -> "value-" + calls.incrementAndGet()));

        AiResiliencePolicy.Policy breaker = new AiResiliencePolicy.Policy(
                Duration.ofSeconds(1), 1, 1, Duration.ofMillis(10), Duration.ZERO);
        assertThrows(AiResiliencePolicy.RetryableFailure.class,
                () -> policy.execute(breaker, key("perm", "open-1"), () -> {
                    throw new AiResiliencePolicy.RetryableFailure("down");
                }));
        assertThrows(AiResiliencePolicy.CircuitOpenException.class,
                () -> policy.execute(breaker, key("perm", "blocked-1"), () -> "never"));
        now.addAndGet(11);
        assertThrows(AiResiliencePolicy.RetryableFailure.class,
                () -> policy.execute(breaker, key("perm", "half-open-fails"), () -> {
                    throw new AiResiliencePolicy.RetryableFailure("still down");
                }));
        assertThrows(AiResiliencePolicy.CircuitOpenException.class,
                () -> policy.execute(breaker, key("perm", "blocked-again"), () -> "never"));
    }

    @Test
    void streamingDoesNotRetryAfterEmissionAndRecordsFatalAndCancellationOutcomes() {
        List<AiResiliencePolicy.StreamingAttemptOutcome> outcomes = new ArrayList<>();
        AiResiliencePolicy.StreamingAttemptObserver<String> observer = observer(outcomes);
        AtomicInteger subscriptions = new AtomicInteger();

        assertThrows(AiResiliencePolicy.RetryableFailure.class, () -> policy.executeStreaming(
                        new AiResiliencePolicy.Policy(Duration.ofSeconds(1), 3, 3,
                                Duration.ofSeconds(10), Duration.ZERO),
                        key("perm", "emitted-then-failed"),
                        () -> {
                            subscriptions.incrementAndGet();
                            return Flux.concat(Flux.just("partial"),
                                    Flux.error(new AiResiliencePolicy.RetryableFailure("after output")));
                        }, observer)
                .collectList().block());
        assertEquals(1, subscriptions.get());
        assertEquals(List.of(AiResiliencePolicy.StreamingAttemptOutcome.FAILED_RETRYABLE), outcomes);

        outcomes.clear();
        assertThrows(IllegalStateException.class, () -> policy.executeStreaming(
                        config(), key("perm", "fatal-stream"),
                        () -> Flux.error(new IllegalStateException("fatal")), observer)
                .blockLast());
        assertEquals(List.of(AiResiliencePolicy.StreamingAttemptOutcome.FAILED_FATAL), outcomes);

        outcomes.clear();
        policy.executeStreaming(config(), key("perm", "cancelled-stream"), Flux::<String>never, observer)
                .take(0).blockLast();
        assertTrue(outcomes.isEmpty()
                || outcomes.equals(List.of(AiResiliencePolicy.StreamingAttemptOutcome.CANCELLED)));
        assertThrows(NullPointerException.class, () -> policy.executeStreaming(
                config(), key("perm", "null-observer"), Flux::empty, null));
    }

    private AiResiliencePolicy.StreamingAttemptObserver<String> observer(
            List<AiResiliencePolicy.StreamingAttemptOutcome> outcomes) {
        return new AiResiliencePolicy.StreamingAttemptObserver<>() {
            @Override
            public void onEnd(int attemptNo, AiResiliencePolicy.StreamingAttemptOutcome outcome, Throwable failure) {
                outcomes.add(outcome);
            }
        };
    }

    private AiResiliencePolicy.Policy config() {
        return new AiResiliencePolicy.Policy(
                Duration.ofSeconds(1), 1, 3, Duration.ofSeconds(1), Duration.ZERO);
    }

    private AiResiliencePolicy.Policy policy(
            Duration timeout,
            int attempts,
            int threshold,
            Duration open,
            Duration cache,
            Duration retryDelay,
            double jitter) {
        return new AiResiliencePolicy.Policy(timeout, attempts, threshold, open, cache, retryDelay, jitter);
    }

    private AiResiliencePolicy.RequestKey requestKey(String[] values) {
        return new AiResiliencePolicy.RequestKey(
                values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7], values[8]);
    }

    private AiResiliencePolicy.RequestKey key(String permissionDigest, String requestHash) {
        return new AiResiliencePolicy.RequestKey(
                "DASHBOARD", "fake", "model-v1", "prompt-v1", "tool-v1",
                "retrieval-v1", "redaction-v1", permissionDigest, requestHash);
    }
}
