package com.example.dormitory.ai.application.run;

import com.example.dormitory.ai.application.run.AiRunRecords.Conversation;
import com.example.dormitory.ai.application.run.AiRunRecords.ConversationDetail;
import com.example.dormitory.ai.application.run.AiRunRecords.Run;
import com.example.dormitory.ai.application.run.AiRunRecords.RunCreation;
import com.example.dormitory.ai.application.run.AiRunRecords.RunMutation;
import com.example.dormitory.ai.config.AiProperties;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.infrastructure.runtime.AiRuntimeAuditWriter;
import com.example.dormitory.ai.infrastructure.runtime.AiRuntimeCrypto;
import com.example.dormitory.ai.observability.AiObservability;
import com.example.dormitory.ai.security.AiAuditContentAuthorizationService;
import com.example.dormitory.ai.security.PiiClassificationService;
import com.example.dormitory.ai.security.PiiRedactionService;
import com.example.dormitory.ai.security.RecentAuthenticationPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiRunServiceCancellationTest {

    @Test
    void cancellingAQueuedRunCancelsTheExecutorTaskBeforeProviderInvocation() {
        AiActorContext actor = new AiActorContext(7L, "session", "fingerprint", 1, "digest",
                List.of("ADMIN"), List.of("ai:assistant:use"), ActorDescriptor.user(7L));
        String conversationId = UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();
        Conversation conversation = new Conversation(conversationId, "GLOBAL", "NONE", null,
                "ACTIVE", null, Instant.now(), Instant.now());
        Run run = new Run(runId, conversationId, actor.userId(), "ASSISTANT", "ACCEPTED",
                actor.permissionDigest(), actor.sessionFingerprintHash(), "v1", Instant.now(), null, null);

        AiRuntimeGate gate = mock(AiRuntimeGate.class);
        AiActorResolver actors = mock(AiActorResolver.class);
        AiConversationRunStore store = mock(AiConversationRunStore.class);
        AiModelRuntimePort runtime = mock(AiModelRuntimePort.class);
        ContextResolverRegistry contexts = mock(ContextResolverRegistry.class);
        CapturingTaskExecutor executor = new CapturingTaskExecutor();
        when(actors.current("ai:assistant:use")).thenReturn(actor);
        when(actors.current(null)).thenReturn(actor);
        when(actors.stillValid(actor, "ai:assistant:use")).thenReturn(true);
        when(store.conversationDetail(actor.userId(), conversationId))
                .thenReturn(new ConversationDetail(conversation, List.of()));
        when(store.ownedConversation(actor.userId(), conversationId)).thenReturn(conversation);
        ContextResolverRegistry.Resolution resolution = new ContextResolverRegistry.Resolution(
                "GLOBAL", "NONE", null, "{}", Set.of(), null);
        when(contexts.resolve(eq(actor), eq("GLOBAL"), eq("NONE"), eq(null), eq(null)))
                .thenReturn(resolution);
        when(contexts.resolveForRun(eq(runId), eq(actor), eq("GLOBAL"), eq("NONE"), eq(null), any()))
                .thenReturn(resolution);
        when(store.createRun(eq(actor), eq(conversationId), eq("request-1"), any(), any(), any(), eq("fake")))
                .thenReturn(new RunCreation(run, false));
        when(store.markQueued(runId)).thenReturn(new RunMutation(true, List.of()));
        when(store.ownedRun(actor.userId(), runId)).thenReturn(run);
        when(store.cancelRun(actor, runId)).thenReturn(new RunMutation(true, List.of()));
        when(store.markStarted(runId, "fake-model")).thenReturn(RunMutation.unchanged());
        when(store.failRun(eq(runId), any(), any(Boolean.class))).thenReturn(RunMutation.unchanged());
        when(runtime.providerCode()).thenReturn("fake");
        when(runtime.modelAlias()).thenReturn("fake-model");

        AiRunService service = new AiRunService(gate, actors, store, runtime,
                mock(AiRunEventPublisher.class), crypto(), executor, new ObjectMapper(),
                mock(RecentAuthenticationPolicy.class), mock(AiRuntimeAuditWriter.class),
                mock(AiObservability.class), contexts, mock(AiAuditContentAuthorizationService.class));

        service.createMessage(conversationId, "请给出维修建议", "request-1");
        service.cancel(runId);
        executor.runCaptured();

        verify(store, never()).markStarted(runId, "fake-model");
        verify(runtime, never()).stream(any());
        verify(store, never()).completeRun(eq(runId), any(), any(), any(), any(), any(Boolean.class),
                any(Boolean.class), any(), any());
        verify(store, never()).failRun(eq(runId), any(), any(Boolean.class));
    }

    private AiRuntimeCrypto crypto() {
        AiProperties properties = new AiProperties();
        String key = "queued-cancellation-test-key-32-bytes-minimum";
        properties.getTokenization().setHmacKey(key);
        properties.getTokenization().setActiveKeyVersion(1);
        PiiRedactionService redaction = new PiiRedactionService(
                key.getBytes(StandardCharsets.UTF_8), "test-v1");
        return new AiRuntimeCrypto(properties, new PiiClassificationService(redaction, List::of));
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
}
