package com.example.dormitory.ai.application.run;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.application.run.AiRunRecords.Conversation;
import com.example.dormitory.ai.application.run.AiRunRecords.ConversationDetail;
import com.example.dormitory.ai.application.run.AiRunRecords.Run;
import com.example.dormitory.ai.application.run.AiRunRecords.RunCreation;
import com.example.dormitory.ai.application.run.AiRunRecords.RunMutation;
import com.example.dormitory.ai.config.AiProperties;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.AiCapability;
import com.example.dormitory.ai.domain.model.AiStreamEvent;
import com.example.dormitory.ai.domain.model.ModelUsage;
import com.example.dormitory.ai.infrastructure.runtime.AiRuntimeAuditWriter;
import com.example.dormitory.ai.infrastructure.runtime.AiRuntimeCrypto;
import com.example.dormitory.ai.observability.AiObservability;
import com.example.dormitory.ai.port.AiEventStream;
import com.example.dormitory.ai.security.AiAuditContentAuthorizationService;
import com.example.dormitory.ai.security.PiiClassificationService;
import com.example.dormitory.ai.security.PiiRedactionService;
import com.example.dormitory.ai.security.RecentAuthenticationPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.task.TaskExecutor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiRunServiceKillSwitchRecheckTest {

    @ParameterizedTest
    @ValueSource(strings = {"AI_DISABLED", "AI_CAPABILITY_DISABLED", "AI_PROVIDER_DISABLED"})
    void queuedAssistantRunFailsWithTheExactRuntimeGateCodeBeforeProviderInvocation(String errorCode) {
        AssistantFixture fixture = new AssistantFixture(null);

        fixture.service.createMessage(fixture.conversationId, "请给出维修建议", "request-1");
        fixture.streamingGateFailure.set(disabled(errorCode));
        fixture.executor.runCaptured();

        verify(fixture.store, never()).markStarted(fixture.runId, "fake-model");
        verify(fixture.runtime, never()).stream(any());
        verify(fixture.store).failRun(fixture.runId, errorCode, true);
        verify(fixture.store, never()).completeRun(eq(fixture.runId), any(), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"AI_DISABLED", "AI_CAPABILITY_DISABLED"})
    void queuedDeterministicRunFailsWithTheExactRuntimeGateCodeBeforeWork(String errorCode) {
        CommandFixture fixture = new CommandFixture();
        AtomicInteger workInvocations = new AtomicInteger();

        fixture.service.createCommand(AiCapability.DASHBOARD, "ai:dashboard:query", "DASHBOARD",
                "request-1", "{}", (actor, runId) -> {
                    workInvocations.incrementAndGet();
                    return Map.of("ok", true);
                });
        fixture.streamingGateFailure.set(disabled(errorCode));
        fixture.executor.runCaptured();

        assertEquals(0, workInvocations.get());
        verify(fixture.store, never()).markStarted(fixture.runId, "deterministic-rules-v1");
        verify(fixture.runtime, never()).stream(any());
        verify(fixture.store).failRun(fixture.runId, errorCode, true);
        verify(fixture.store, never()).completeCommandRun(eq(fixture.runId), any());
    }

    @Test
    void providerDisabledAfterStartIsRecheckedImmediatelyBeforeModelEgress() {
        AssistantFixture fixture = new AssistantFixture(null);
        when(fixture.store.markStarted(fixture.runId, "fake-model")).thenAnswer(ignored -> {
            fixture.streamingGateFailure.set(disabled("AI_PROVIDER_DISABLED"));
            return changed();
        });

        fixture.service.createMessage(fixture.conversationId, "请给出维修建议", "request-1");
        fixture.executor.runCaptured();

        verify(fixture.runtime, never()).stream(any());
        verify(fixture.store).failRun(fixture.runId, "AI_PROVIDER_DISABLED", true);
    }

    @Test
    void masterDisabledAfterStartAlsoBlocksTheDirectResponsePath() {
        AssistantFixture fixture = new AssistantFixture("暂无可靠来源，无法确认");
        when(fixture.store.markStarted(fixture.runId, "knowledge-grounding-policy-v1")).thenAnswer(ignored -> {
            fixture.streamingGateFailure.set(disabled("AI_DISABLED"));
            return changed();
        });

        fixture.service.createMessage(fixture.conversationId, "请给出维修建议", "request-1");
        fixture.executor.runCaptured();

        verify(fixture.runtime, never()).stream(any());
        verify(fixture.store, never()).appendDelta(eq(fixture.runId), anyString());
        verify(fixture.store, never()).completeRun(eq(fixture.runId), any(), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), any());
        verify(fixture.store).failRun(fixture.runId, "AI_DISABLED", true);
    }

    @Test
    void masterKillSwitchKeepsExistingRunReadPathsClosed() {
        AssistantFixture fixture = new AssistantFixture(null);
        when(fixture.actors.current(null)).thenReturn(fixture.actor);
        when(fixture.store.ownedRun(fixture.actor.userId(), fixture.runId)).thenReturn(fixture.run);
        doThrow(disabled("AI_DISABLED"))
                .when(fixture.gate).requireCapability(AiCapability.ASSISTANT);

        AiApiException runFailure = assertThrows(AiApiException.class,
                () -> fixture.service.run(fixture.runId));
        AiApiException streamFailure = assertThrows(AiApiException.class,
                () -> fixture.service.streamActor(fixture.runId));

        assertEquals("AI_DISABLED", runFailure.errorCode());
        assertEquals("AI_DISABLED", streamFailure.errorCode());
        verify(fixture.runtime, never()).stream(any());
    }

    private static AiApiException disabled(String errorCode) {
        return AiApiException.unavailable(errorCode, "运行时门禁已关闭");
    }

    private static RunMutation changed() {
        return new RunMutation(true, List.of());
    }

    private static AiRuntimeCrypto crypto(String key) {
        AiProperties properties = new AiProperties();
        properties.getTokenization().setHmacKey(key);
        properties.getTokenization().setActiveKeyVersion(1);
        PiiRedactionService redaction = new PiiRedactionService(
                key.getBytes(StandardCharsets.UTF_8), "test-v1");
        return new AiRuntimeCrypto(properties, new PiiClassificationService(redaction, List::of));
    }

    private static AiRunService service(
            AiRuntimeGate gate,
            AiActorResolver actors,
            AiConversationRunStore store,
            AiModelRuntimePort runtime,
            CapturingTaskExecutor executor,
            ContextResolverRegistry contexts) {
        return new AiRunService(gate, actors, store, runtime,
                mock(AiRunEventPublisher.class),
                crypto("kill-switch-recheck-test-key-32-bytes-minimum"),
                executor, new ObjectMapper(), mock(RecentAuthenticationPolicy.class),
                mock(AiRuntimeAuditWriter.class), mock(AiObservability.class), contexts,
                mock(AiAuditContentAuthorizationService.class));
    }

    private static final class AssistantFixture {
        private final AiActorContext actor = new AiActorContext(
                7L, "session", "fingerprint", 1, "digest",
                List.of("ADMIN"), List.of("ai:assistant:use"), ActorDescriptor.user(7L));
        private final String conversationId = UUID.randomUUID().toString();
        private final String runId = UUID.randomUUID().toString();
        private final AiRuntimeGate gate = mock(AiRuntimeGate.class);
        private final AiActorResolver actors = mock(AiActorResolver.class);
        private final AiConversationRunStore store = mock(AiConversationRunStore.class);
        private final AiModelRuntimePort runtime = mock(AiModelRuntimePort.class);
        private final ContextResolverRegistry contexts = mock(ContextResolverRegistry.class);
        private final CapturingTaskExecutor executor = new CapturingTaskExecutor();
        private final AtomicReference<AiApiException> streamingGateFailure = new AtomicReference<>();
        private final Run run;
        private final AiRunService service;

        private AssistantFixture(String directResponse) {
            Conversation conversation = new Conversation(conversationId, "GLOBAL", "NONE", null,
                    "ACTIVE", null, Instant.now(), Instant.now());
            run = new Run(runId, conversationId, actor.userId(), "ASSISTANT", "ACCEPTED",
                    actor.permissionDigest(), actor.sessionFingerprintHash(), "v1", Instant.now(), null, null);
            doAnswer(ignored -> {
                AiApiException failure = streamingGateFailure.get();
                if (failure != null) throw failure;
                return null;
            }).when(gate).requireStreamingProvider(AiCapability.ASSISTANT, actor.userId());
            when(actors.current("ai:assistant:use")).thenReturn(actor);
            when(actors.stillValid(actor, "ai:assistant:use")).thenReturn(true);
            when(store.conversationDetail(actor.userId(), conversationId))
                    .thenReturn(new ConversationDetail(conversation, List.of()));
            ContextResolverRegistry.Resolution resolution = new ContextResolverRegistry.Resolution(
                    "GLOBAL", "NONE", null, "{}", Set.of(), directResponse);
            when(contexts.resolve(eq(actor), eq("GLOBAL"), eq("NONE"), eq(null), eq(null)))
                    .thenReturn(resolution);
            when(contexts.resolveForRun(eq(runId), eq(actor), eq("GLOBAL"), eq("NONE"),
                    eq(null), any())).thenReturn(resolution);
            when(store.createRun(eq(actor), eq(conversationId), eq("request-1"), any(), any(), any(), eq("fake")))
                    .thenReturn(new RunCreation(run, false));
            when(store.markQueued(runId)).thenReturn(changed());
            when(store.markStarted(runId, directResponse == null
                    ? "fake-model" : "knowledge-grounding-policy-v1")).thenReturn(changed());
            when(store.pinnedSystemPrompt(runId)).thenReturn("只遵循服务端固定规则。");
            when(store.appendDelta(eq(runId), anyString())).thenReturn(RunMutation.unchanged());
            when(store.completeRun(eq(runId), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any()))
                    .thenReturn(RunMutation.unchanged());
            when(store.failRun(eq(runId), anyString(), anyBoolean())).thenReturn(RunMutation.unchanged());
            when(runtime.providerCode()).thenReturn("fake");
            when(runtime.modelAlias()).thenReturn("fake-model");
            when(runtime.stream(any())).thenReturn(new CompletedStream());
            service = service(gate, actors, store, runtime, executor, contexts);
        }
    }

    private static final class CommandFixture {
        private final AiActorContext actor = new AiActorContext(
                7L, "session", "fingerprint", 1, "digest",
                List.of("ADMIN"), List.of("ai:dashboard:query"), ActorDescriptor.user(7L));
        private final String runId = UUID.randomUUID().toString();
        private final AiRuntimeGate gate = mock(AiRuntimeGate.class);
        private final AiActorResolver actors = mock(AiActorResolver.class);
        private final AiConversationRunStore store = mock(AiConversationRunStore.class);
        private final AiModelRuntimePort runtime = mock(AiModelRuntimePort.class);
        private final ContextResolverRegistry contexts = mock(ContextResolverRegistry.class);
        private final CapturingTaskExecutor executor = new CapturingTaskExecutor();
        private final AtomicReference<AiApiException> streamingGateFailure = new AtomicReference<>();
        private final AiRunService service;

        private CommandFixture() {
            Run run = new Run(runId, null, actor.userId(), "DASHBOARD", "ACCEPTED",
                    actor.permissionDigest(), actor.sessionFingerprintHash(), "v1", Instant.now(), null, null);
            doAnswer(ignored -> {
                AiApiException failure = streamingGateFailure.get();
                if (failure != null) throw failure;
                return null;
            }).when(gate).requireStreaming(AiCapability.DASHBOARD, actor.userId());
            when(actors.current("ai:dashboard:query")).thenReturn(actor);
            when(actors.stillValid(actor, "ai:dashboard:query")).thenReturn(true);
            when(store.createCommandRun(eq(actor), eq(AiCapability.DASHBOARD), eq("DASHBOARD"), eq("COMMAND"),
                    eq(null), eq("request-1"), any(), any(), any(), eq("deterministic")))
                    .thenReturn(new RunCreation(run, false));
            when(store.markQueued(runId)).thenReturn(changed());
            when(store.markStarted(runId, "deterministic-rules-v1")).thenReturn(changed());
            when(store.completeCommandRun(eq(runId), any())).thenReturn(RunMutation.unchanged());
            when(store.failRun(eq(runId), anyString(), anyBoolean())).thenReturn(RunMutation.unchanged());
            service = service(gate, actors, store, runtime, executor, contexts);
        }
    }

    private static final class CapturingTaskExecutor implements TaskExecutor {
        private Runnable task;

        @Override
        public void execute(Runnable task) {
            this.task = task;
        }

        private void runCaptured() {
            if (task != null) task.run();
        }
    }

    private static final class CompletedStream implements AiEventStream {
        @Override
        public void consume(Consumer<AiStreamEvent> consumer) {
            consumer.accept(AiStreamEvent.usage(new ModelUsage(0, 0, ModelUsage.Source.ESTIMATED)));
            consumer.accept(AiStreamEvent.completed());
        }

        @Override
        public boolean cancel() {
            return true;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }
    }
}
