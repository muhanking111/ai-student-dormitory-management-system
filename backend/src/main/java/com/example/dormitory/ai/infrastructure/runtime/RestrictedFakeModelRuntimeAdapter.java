package com.example.dormitory.ai.infrastructure.runtime;

import com.example.dormitory.ai.application.run.AiModelRuntimePort;
import com.example.dormitory.ai.domain.model.AiStreamEvent;
import com.example.dormitory.ai.domain.model.ModelRequest;
import com.example.dormitory.ai.domain.model.ModelUsage;
import com.example.dormitory.ai.infrastructure.fake.DeterministicFakeModelGateway;
import com.example.dormitory.ai.port.AiEventStream;
import com.example.dormitory.ai.port.AiModelAttemptAccountingPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Set;
import java.util.function.Consumer;

@Component
public class RestrictedFakeModelRuntimeAdapter implements AiModelRuntimePort {

    private static final Set<String> ASSISTANT_READ_TOOLS = Set.of(
            "knowledge.search.v1", "dashboard.query_metric.v1", "repair.get_context.v1",
            "dormitory.get_capacity_summary.v1", "notice.list_published.v1");

    private final DeterministicFakeModelGateway gateway = new DeterministicFakeModelGateway();
    private final AtomicLong invocationCount = new AtomicLong();
    private final AiModelAttemptAccountingPort accounting;

    @Autowired
    public RestrictedFakeModelRuntimeAdapter(AiModelAttemptAccountingPort accounting) {
        this.accounting = java.util.Objects.requireNonNull(accounting);
    }

    /** 仅供不启动 Spring 容器的窄单元测试。 */
    RestrictedFakeModelRuntimeAdapter() {
        this.accounting = null;
    }

    @Override
    public String providerCode() {
        return "fake";
    }

    @Override
    public String modelAlias() {
        return "deterministic-fake-v1";
    }

    @Override
    public AiEventStream stream(ModelRequest request) {
        if (request == null || !ASSISTANT_READ_TOOLS.containsAll(request.allowedToolIds())) {
            throw new SecurityException("Fake runtime 拒绝动态、未知或提案工具");
        }
        if (accounting != null && request.billingTrace() == null) {
            throw new IllegalArgumentException("Fake 模型调用缺少服务端 billing trace");
        }
        AiModelAttemptAccountingPort.AttemptHandle handle = accounting == null ? null : accounting.begin(
                request.billingTrace(), request.capability(), providerCode(), modelAlias(),
                "MODEL_STREAM", 1, Instant.now());
        invocationCount.incrementAndGet();
        AiEventStream stream = gateway.stream(request);
        return handle == null ? stream : new AccountedFakeStream(stream, request, accounting, handle);
    }

    @Override
    public long invocationCount() {
        return invocationCount.get();
    }

    private static final class AccountedFakeStream implements AiEventStream {
        private final AiEventStream delegate;
        private final ModelRequest request;
        private final AiModelAttemptAccountingPort accounting;
        private final AiModelAttemptAccountingPort.AttemptHandle handle;
        private final long startedNanos = System.nanoTime();
        private final AtomicReference<ModelUsage> observedUsage = new AtomicReference<>();
        private final AtomicLong outputCharacters = new AtomicLong();
        private final AtomicBoolean finished = new AtomicBoolean();

        private AccountedFakeStream(
                AiEventStream delegate,
                ModelRequest request,
                AiModelAttemptAccountingPort accounting,
                AiModelAttemptAccountingPort.AttemptHandle handle) {
            this.delegate = delegate;
            this.request = request;
            this.accounting = accounting;
            this.handle = handle;
        }

        @Override
        public void consume(Consumer<AiStreamEvent> consumer) {
            try {
                delegate.consume(event -> {
                    if (event.type() == AiStreamEvent.Type.USAGE) observedUsage.set(event.usage());
                    if (event.type() == AiStreamEvent.Type.TEXT_DELTA && event.textDelta() != null) {
                        outputCharacters.addAndGet(event.textDelta().codePointCount(0, event.textDelta().length()));
                    }
                    consumer.accept(event);
                });
                finish(delegate.isCancelled()
                        ? AiModelAttemptAccountingPort.AttemptOutcome.CANCELLED
                        : AiModelAttemptAccountingPort.AttemptOutcome.SUCCEEDED,
                        delegate.isCancelled() ? "UPSTREAM_CANCELLED" : null);
            } catch (RuntimeException failure) {
                finish(AiModelAttemptAccountingPort.AttemptOutcome.FAILED_FATAL, "UPSTREAM_FATAL");
                throw failure;
            }
        }

        @Override
        public boolean cancel() {
            boolean cancelled = delegate.cancel();
            if (cancelled) finish(AiModelAttemptAccountingPort.AttemptOutcome.CANCELLED, "UPSTREAM_CANCELLED");
            return cancelled;
        }

        @Override
        public boolean isCancelled() {
            return delegate.isCancelled();
        }

        private void finish(AiModelAttemptAccountingPort.AttemptOutcome outcome, String failureCode) {
            if (!finished.compareAndSet(false, true)) return;
            ModelUsage usage = observedUsage.get();
            if (usage == null) {
                usage = new ModelUsage(
                        request.prompt().codePointCount(0, request.prompt().length()),
                        outputCharacters.get(), ModelUsage.Source.ESTIMATED);
            }
            accounting.finish(handle, usage, outcome,
                    Duration.ofNanos(Math.max(0L, System.nanoTime() - startedNanos)), failureCode);
        }
    }
}
