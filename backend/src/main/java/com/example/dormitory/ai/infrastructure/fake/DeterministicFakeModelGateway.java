package com.example.dormitory.ai.infrastructure.fake;

import com.example.dormitory.ai.domain.model.AiStreamEvent;
import com.example.dormitory.ai.domain.model.ModelCapability;
import com.example.dormitory.ai.domain.model.ModelRequest;
import com.example.dormitory.ai.domain.model.ModelResult;
import com.example.dormitory.ai.domain.model.ModelUsage;
import com.example.dormitory.ai.port.AiEventStream;
import com.example.dormitory.ai.port.ModelGateway;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class DeterministicFakeModelGateway implements ModelGateway {

    private static final Set<ModelCapability> CAPABILITIES = Set.of(
            ModelCapability.COMPLETE,
            ModelCapability.STREAMING,
            ModelCapability.USAGE,
            ModelCapability.CANCELLATION);

    @Override
    public String adapterCode() {
        return "fake";
    }

    @Override
    public ModelResult complete(ModelRequest request) {
        String content = responseContent(request);
        return new ModelResult(content, usage(request.prompt(), content), "STOP");
    }

    @Override
    public AiEventStream stream(ModelRequest request) {
        ModelResult result = complete(request);
        return new DeterministicEventStream(result);
    }

    @Override
    public Set<ModelCapability> supportedCapabilities() {
        return CAPABILITIES;
    }

    private String responseContent(ModelRequest request) {
        return "FAKE[" + request.capability().name() + "]:" + request.prompt();
    }

    private ModelUsage usage(String input, String output) {
        return new ModelUsage(
                input.codePointCount(0, input.length()),
                output.codePointCount(0, output.length()),
                ModelUsage.Source.ESTIMATED);
    }

    private static final class DeterministicEventStream implements AiEventStream {

        private final ModelResult result;
        private final AtomicBoolean consumed = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private DeterministicEventStream(ModelResult result) {
            this.result = result;
        }

        @Override
        public void consume(Consumer<AiStreamEvent> consumer) {
            if (!consumed.compareAndSet(false, true)) {
                throw new IllegalStateException("事件流只能消费一次");
            }
            consumer.accept(AiStreamEvent.started());
            if (emitCancellationIfNeeded(consumer)) return;
            for (String delta : result.content().codePoints()
                    .mapToObj(codePoint -> new String(Character.toChars(codePoint)))
                    .toList()) {
                consumer.accept(AiStreamEvent.textDelta(delta));
                if (emitCancellationIfNeeded(consumer)) return;
            }
            consumer.accept(AiStreamEvent.usage(result.usage()));
            consumer.accept(AiStreamEvent.completed());
        }

        @Override
        public boolean cancel() {
            return cancelled.compareAndSet(false, true);
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        private boolean emitCancellationIfNeeded(Consumer<AiStreamEvent> consumer) {
            if (!cancelled.get()) return false;
            consumer.accept(AiStreamEvent.cancelled());
            return true;
        }
    }
}
