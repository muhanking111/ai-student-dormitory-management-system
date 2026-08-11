package com.example.dormitory.ai.resilience;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;

import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

/** 单层重试、超时、熔断与权限隔离缓存；框架 adapter 必须关闭自身重试以避免成本倍增。 */
public final class AiResiliencePolicy implements AutoCloseable {

    private final ExecutorService executor;
    private final LongSupplier nowMillis;
    private final DelayStrategy delayStrategy;
    private final DoubleSupplier jitterSource;
    private final Map<String, Circuit> circuits = new ConcurrentHashMap<>();
    private final Map<RequestKey, CacheEntry> cache = new ConcurrentHashMap<>();

    public AiResiliencePolicy(ExecutorService executor, LongSupplier nowMillis) {
        this(executor, nowMillis, AiResiliencePolicy::sleep, Math::random);
    }

    AiResiliencePolicy(
            ExecutorService executor,
            LongSupplier nowMillis,
            DelayStrategy delayStrategy,
            DoubleSupplier jitterSource) {
        this.executor = java.util.Objects.requireNonNull(executor);
        this.nowMillis = java.util.Objects.requireNonNull(nowMillis);
        this.delayStrategy = java.util.Objects.requireNonNull(delayStrategy);
        this.jitterSource = java.util.Objects.requireNonNull(jitterSource);
    }

    @SuppressWarnings("unchecked")
    public <T> T execute(Policy policy, RequestKey key, Supplier<T> operation) {
        validate(policy, key, operation);
        CacheEntry cached = cache.get(key);
        long now = nowMillis.getAsLong();
        if (cached != null && cached.expiresAtMillis() > now) return (T) cached.value();
        if (cached != null) cache.remove(key, cached);

        String circuitKey = key.capability() + "|" + key.providerCode();
        Circuit circuit = circuits.computeIfAbsent(circuitKey, ignored -> new Circuit());
        synchronized (circuit) {
            if (circuit.openUntilMillis > now) throw new CircuitOpenException();
            if (circuit.openUntilMillis > 0 && circuit.openUntilMillis <= now) circuit.halfOpen = true;
        }

        RuntimeException last = null;
        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            try {
                T value = timed(policy.timeout(), operation);
                synchronized (circuit) {
                    circuit.failures = 0;
                    circuit.openUntilMillis = 0;
                    circuit.halfOpen = false;
                }
                if (!policy.cacheTtl().isZero()) {
                    cache.put(key, new CacheEntry(value, nowMillis.getAsLong() + policy.cacheTtl().toMillis()));
                }
                return value;
            } catch (RetryableFailure retryable) {
                last = retryable;
                if (attempt == policy.maxAttempts()) break;
                delayStrategy.pause(backoff(policy, attempt));
            }
        }
        synchronized (circuit) {
            circuit.failures++;
            if (circuit.halfOpen || circuit.failures >= policy.failureThreshold()) {
                circuit.openUntilMillis = nowMillis.getAsLong() + policy.openDuration().toMillis();
                circuit.halfOpen = false;
            }
        }
        throw last == null ? new IllegalStateException("AI resilience operation failed") : last;
    }

    /**
     * 流式调用的单层超时/重试/熔断。只有在尚未向调用方发出任何增量时才允许重试，
     * 避免重复输出和重复计费被静默合并。
     */
    public <T> Flux<T> executeStreaming(Policy policy, RequestKey key, Supplier<Flux<T>> operation) {
        return executeStreaming(policy, key, operation, new StreamingAttemptObserver<>() { });
    }

    /**
     * 与逻辑流分离地暴露每一次物理订阅。observer 的 onStart 在调用 provider supplier 之前执行，
     * 因而价格版本等前置治理失败时不会产生真实上游调用；每个已开始的 attempt 只会收到一个终态。
     */
    public <T> Flux<T> executeStreaming(
            Policy policy,
            RequestKey key,
            Supplier<Flux<T>> operation,
            StreamingAttemptObserver<T> observer) {
        validate(policy, key, operation);
        java.util.Objects.requireNonNull(observer, "streaming attempt observer");
        return Flux.defer(() -> {
            long now = nowMillis.getAsLong();
            String circuitKey = key.capability() + "|" + key.providerCode();
            Circuit circuit = circuits.computeIfAbsent(circuitKey, ignored -> new Circuit());
            synchronized (circuit) {
                if (circuit.openUntilMillis > now) return Flux.error(new CircuitOpenException());
                if (circuit.openUntilMillis > 0 && circuit.openUntilMillis <= now) circuit.halfOpen = true;
            }

            AtomicBoolean emitted = new AtomicBoolean();
            java.util.concurrent.atomic.AtomicInteger attemptCounter =
                    new java.util.concurrent.atomic.AtomicInteger();
            Flux<T> attempt = Flux.defer(() -> {
                int attemptNo = attemptCounter.incrementAndGet();
                observer.onStart(attemptNo);
                AtomicBoolean ended = new AtomicBoolean();
                Flux<T> physical = Flux.defer(operation)
                        .doOnNext(value -> {
                            emitted.set(true);
                            observer.onNext(attemptNo, value);
                        })
                        .timeout(policy.timeout())
                        .onErrorMap(TimeoutException.class, ignored -> new UpstreamTimeoutException());
                return physical
                        .doOnComplete(() -> endAttempt(observer, ended, attemptNo,
                                StreamingAttemptOutcome.SUCCEEDED, null))
                        .doOnError(failure -> endAttempt(observer, ended, attemptNo,
                                streamingOutcome(failure), failure))
                        .doOnCancel(() -> endAttempt(observer, ended, attemptNo,
                                StreamingAttemptOutcome.CANCELLED, null));
            });
            if (policy.maxAttempts() > 1) {
                attempt = attempt.retryWhen(Retry.backoff(policy.maxAttempts() - 1L, policy.retryBaseDelay())
                        .maxBackoff(maxBackoff(policy))
                        .jitter(policy.retryJitterRatio())
                        .filter(failure -> !emitted.get() && failure instanceof RetryableFailure));
            }
            return attempt
                    .doOnComplete(() -> recordSuccess(circuit))
                    .doOnError(ignored -> recordFailure(circuit, policy));
        });
    }

    private static <T> void endAttempt(
            StreamingAttemptObserver<T> observer,
            AtomicBoolean ended,
            int attemptNo,
            StreamingAttemptOutcome outcome,
            Throwable failure) {
        if (ended.compareAndSet(false, true)) observer.onEnd(attemptNo, outcome, failure);
    }

    private static StreamingAttemptOutcome streamingOutcome(Throwable failure) {
        if (failure instanceof UpstreamTimeoutException) return StreamingAttemptOutcome.TIMED_OUT;
        if (failure instanceof RetryableFailure) return StreamingAttemptOutcome.FAILED_RETRYABLE;
        return StreamingAttemptOutcome.FAILED_FATAL;
    }

    private void recordSuccess(Circuit circuit) {
        synchronized (circuit) {
            circuit.failures = 0;
            circuit.openUntilMillis = 0;
            circuit.halfOpen = false;
        }
    }

    private void recordFailure(Circuit circuit, Policy policy) {
        synchronized (circuit) {
            circuit.failures++;
            if (circuit.halfOpen || circuit.failures >= policy.failureThreshold()) {
                circuit.openUntilMillis = nowMillis.getAsLong() + policy.openDuration().toMillis();
                circuit.halfOpen = false;
            }
        }
    }

    private <T> T timed(Duration timeout, Supplier<T> operation) {
        Future<T> future = executor.submit(operation::get);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new UpstreamTimeoutException();
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI 调用被中断", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("AI 上游调用失败", cause);
        }
    }

    private void validate(Policy policy, RequestKey key, Supplier<?> operation) {
        if (policy == null || key == null || operation == null || policy.timeout().isZero()
                || policy.timeout().isNegative() || policy.maxAttempts() < 1 || policy.maxAttempts() > 3
                || policy.failureThreshold() < 1 || policy.openDuration().isNegative()
                || policy.cacheTtl().isNegative() || policy.retryBaseDelay() == null
                || policy.retryBaseDelay().isNegative() || policy.retryBaseDelay().isZero()
                || !Double.isFinite(policy.retryJitterRatio())
                || policy.retryJitterRatio() < 0 || policy.retryJitterRatio() > 1) {
            throw new IllegalArgumentException("AI resilience policy 不合法");
        }
    }

    private Duration backoff(Policy policy, int completedAttempt) {
        long multiplier = 1L << Math.min(20, Math.max(0, completedAttempt - 1));
        long baseMillis = Math.multiplyExact(policy.retryBaseDelay().toMillis(), multiplier);
        double jitter = (Math.max(0d, Math.min(1d, jitterSource.getAsDouble())) * 2d - 1d)
                * policy.retryJitterRatio();
        long delayedMillis = Math.max(1L, Math.round(baseMillis * (1d + jitter)));
        return Duration.ofMillis(delayedMillis);
    }

    private Duration maxBackoff(Policy policy) {
        long multiplier = 1L << Math.min(20, Math.max(0, policy.maxAttempts() - 2));
        return Duration.ofMillis(Math.max(1L,
                Math.multiplyExact(policy.retryBaseDelay().toMillis(), multiplier)));
    }

    private static void sleep(Duration delay) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI 重试退避被中断", interrupted);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
        cache.clear();
        circuits.clear();
    }

    public record Policy(
            Duration timeout,
            int maxAttempts,
            int failureThreshold,
            Duration openDuration,
            Duration cacheTtl,
            Duration retryBaseDelay,
            double retryJitterRatio) {
        public Policy(
                Duration timeout,
                int maxAttempts,
                int failureThreshold,
                Duration openDuration,
                Duration cacheTtl) {
            this(timeout, maxAttempts, failureThreshold, openDuration, cacheTtl,
                    Duration.ofMillis(200), 0.2d);
        }
    }

    public record RequestKey(
            String capability,
            String providerCode,
            String modelVersion,
            String promptVersion,
            String toolCatalogVersion,
            String retrievalPolicyVersion,
            String redactionPolicyVersion,
            String permissionDigest,
            String requestHash) {
        public RequestKey {
            for (String value : new String[]{capability, providerCode, modelVersion, promptVersion,
                    toolCatalogVersion, retrievalPolicyVersion, redactionPolicyVersion, permissionDigest, requestHash}) {
                if (value == null || value.isBlank()) throw new IllegalArgumentException("AI cache key 不完整");
            }
        }
    }

    public static class RetryableFailure extends RuntimeException {
        public RetryableFailure(String message) { super(message); }
        public RetryableFailure(String message, Throwable cause) { super(message, cause); }
    }

    public static final class CircuitOpenException extends RuntimeException {
        public CircuitOpenException() { super("AI circuit is open"); }
    }

    public static final class UpstreamTimeoutException extends RetryableFailure {
        public UpstreamTimeoutException() { super("AI upstream timed out"); }
    }

    public enum StreamingAttemptOutcome {
        SUCCEEDED,
        FAILED_RETRYABLE,
        FAILED_FATAL,
        TIMED_OUT,
        CANCELLED
    }

    /** observer 不得把 request/user/run 标识作为指标标签，也不得把内部 trace 外发给 provider。 */
    public interface StreamingAttemptObserver<T> {
        default void onStart(int attemptNo) { }

        default void onNext(int attemptNo, T value) { }

        default void onEnd(int attemptNo, StreamingAttemptOutcome outcome, Throwable failure) { }
    }

    private static final class Circuit {
        private int failures;
        private long openUntilMillis;
        private boolean halfOpen;
    }

    private record CacheEntry(Object value, long expiresAtMillis) { }

    @FunctionalInterface
    interface DelayStrategy {
        void pause(Duration delay);
    }
}
