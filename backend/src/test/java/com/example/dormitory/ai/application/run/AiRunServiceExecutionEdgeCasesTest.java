package com.example.dormitory.ai.application.run;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.application.run.AiRunRecords.Conversation;
import com.example.dormitory.ai.application.run.AiRunRecords.ConversationDetail;
import com.example.dormitory.ai.application.run.AiRunRecords.CitationCandidate;
import com.example.dormitory.ai.application.run.AiRunRecords.RetrievalTrace;
import com.example.dormitory.ai.application.run.AiRunRecords.Run;
import com.example.dormitory.ai.application.run.AiRunRecords.RunCreation;
import com.example.dormitory.ai.application.run.AiRunRecords.RunMutation;
import com.example.dormitory.ai.application.run.AiRunRecords.TracedCommandResult;
import com.example.dormitory.ai.config.AiProperties;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.AiCapability;
import com.example.dormitory.ai.domain.model.AiStreamEvent;
import com.example.dormitory.ai.infrastructure.runtime.AiRuntimeAuditWriter;
import com.example.dormitory.ai.infrastructure.runtime.AiRuntimeCrypto;
import com.example.dormitory.ai.observability.AiObservability;
import com.example.dormitory.ai.port.AiEventStream;
import com.example.dormitory.ai.resilience.AiResiliencePolicy;
import com.example.dormitory.ai.security.AiAuditContentAuthorizationService;
import com.example.dormitory.ai.security.PiiClassificationService;
import com.example.dormitory.ai.security.PiiRedactionService;
import com.example.dormitory.ai.security.RecentAuthenticationPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiRunServiceExecutionEdgeCasesTest {

    @AfterEach
    void clearInterruptFromCancellationRaceTests() {
        Thread.interrupted();
    }

    @Test
    void explicitCommandContextPersistsTracedResultBeforeCompleting() {
        Fixture fixture = new Fixture();
        fixture.prepareCommand("REPAIR", 99L);
        RetrievalTrace trace = trace();

        fixture.service.createCommand(AiCapability.DASHBOARD, "ai:dashboard:query", "DASHBOARD",
                " repair ", 99L, "request-1", "{}",
                (actor, runId) -> new TracedCommandResult(Map.of("ok", true), trace));
        fixture.executor.runCaptured();

        verify(fixture.store).createCommandRun(eq(fixture.actor), eq(AiCapability.DASHBOARD),
                eq("DASHBOARD"), eq("REPAIR"), eq(99L), eq("request-1"),
                anyString(), anyString(), anyString(), eq("deterministic"));
        verify(fixture.store).recordRetrievalTrace(fixture.runId, fixture.actor, trace);
        verify(fixture.store).completeCommandRun(fixture.runId, Map.of("ok", true));
    }

    @Test
    void tracedCommandCarriesKnowledgeCitationsIntoTheCompletionStore() {
        Fixture fixture = new Fixture();
        fixture.prepareCommand(AiCapability.KNOWLEDGE, "GLOBAL", "KNOWLEDGE", null);
        RetrievalTrace trace = trace();
        CitationCandidate citation = new CitationCandidate(
                "source-1", "version-1", "chunk-1", "制度", "第 1 段",
                "安全回答依据", "a".repeat(64), 1, null);

        fixture.service.createCommand(AiCapability.KNOWLEDGE, "ai:knowledge:read", "GLOBAL",
                "KNOWLEDGE", null, "request-1", "{}",
                (actor, runId) -> new TracedCommandResult(
                        Map.of("answerText", "安全回答"), trace, List.of(citation)));
        fixture.executor.runCaptured();

        verify(fixture.store).completeCommandRun(
                fixture.runId, Map.of("answerText", "安全回答"), List.of(citation),
                Set.copyOf(fixture.actor.permissionCodes()));
    }

    @Test
    void commandStopsBeforeWorkWhenSessionWasRevoked() {
        Fixture fixture = new Fixture();
        fixture.prepareCommand("COMMAND", null);
        when(fixture.actors.stillValid(fixture.actor, "ai:dashboard:query")).thenReturn(false);
        AtomicInteger workCalls = new AtomicInteger();

        fixture.createCommand((actor, runId) -> {
            workCalls.incrementAndGet();
            return Map.of("ok", true);
        });
        fixture.executor.runCaptured();

        assertEquals(0, workCalls.get());
        verify(fixture.store).failRun(fixture.runId, "AI_SESSION_REVOKED", false);
        verify(fixture.store, never()).markStarted(anyString(), anyString());
    }

    @Test
    void unchangedCommandStartStopsBeforeWork() {
        Fixture fixture = new Fixture();
        fixture.prepareCommand("COMMAND", null);
        when(fixture.store.markStarted(fixture.runId, "deterministic-rules-v1"))
                .thenReturn(RunMutation.unchanged());
        AtomicInteger workCalls = new AtomicInteger();

        fixture.createCommand((actor, runId) -> {
            workCalls.incrementAndGet();
            return Map.of("ok", true);
        });
        fixture.executor.runCaptured();

        assertEquals(0, workCalls.get());
        verify(fixture.store, never()).completeCommandRun(anyString(), any());
    }

    @Test
    void commandGateClosingAfterStartFailsBeforeWork() {
        Fixture fixture = new Fixture();
        fixture.prepareCommand("COMMAND", null);
        AtomicInteger checks = new AtomicInteger();
        doAnswer(ignored -> {
            if (checks.incrementAndGet() == 3) throw disabled("AI_DISABLED");
            return null;
        }).when(fixture.gate).requireStreaming(AiCapability.DASHBOARD, fixture.actor.userId());
        AtomicInteger workCalls = new AtomicInteger();

        fixture.createCommand((actor, runId) -> {
            workCalls.incrementAndGet();
            return Map.of("ok", true);
        });
        fixture.executor.runCaptured();

        assertEquals(0, workCalls.get());
        verify(fixture.store).failRun(fixture.runId, "AI_DISABLED", true);
    }

    @Test
    void commandGateClosingAfterWorkPreventsCompletion() {
        Fixture fixture = new Fixture();
        fixture.prepareCommand("COMMAND", null);
        AtomicInteger checks = new AtomicInteger();
        doAnswer(ignored -> {
            if (checks.incrementAndGet() == 4) throw disabled("AI_CAPABILITY_DISABLED");
            return null;
        }).when(fixture.gate).requireStreaming(AiCapability.DASHBOARD, fixture.actor.userId());

        fixture.createCommand((actor, runId) -> Map.of("ok", true));
        fixture.executor.runCaptured();

        verify(fixture.store).failRun(fixture.runId, "AI_CAPABILITY_DISABLED", true);
        verify(fixture.store, never()).completeCommandRun(anyString(), any());
    }

    @Test
    void commandRevalidatesSessionAfterWork() {
        Fixture fixture = new Fixture();
        fixture.prepareCommand("COMMAND", null);
        when(fixture.actors.stillValid(fixture.actor, "ai:dashboard:query"))
                .thenReturn(true, false);

        fixture.createCommand((actor, runId) -> Map.of("ok", true));
        fixture.executor.runCaptured();

        verify(fixture.store).failRun(fixture.runId, "AI_SESSION_REVOKED", false);
        verify(fixture.store, never()).completeCommandRun(anyString(), any());
    }

    @Test
    void commandCancellationInsideWorkStopsCompletion() {
        Fixture fixture = new Fixture();
        fixture.prepareCommand("COMMAND", null);

        fixture.createCommand((actor, runId) -> {
            fixture.service.cancel(runId);
            return Map.of("ok", true);
        });
        fixture.executor.runCaptured();

        verify(fixture.store).cancelRun(fixture.actor, fixture.runId);
        verify(fixture.store, never()).completeCommandRun(anyString(), any());
        verify(fixture.store, never()).failRun(eq(fixture.runId),
                eq("AI_DETERMINISTIC_COMMAND_FAILED"), anyBoolean());
    }

    @Test
    void cancelledCommandFailureDoesNotOverwriteCancelledState() {
        Fixture fixture = new Fixture();
        fixture.prepareCommand("COMMAND", null);

        fixture.createCommand((actor, runId) -> {
            fixture.service.cancel(runId);
            throw new IllegalStateException("work failed after cancellation");
        });
        fixture.executor.runCaptured();

        verify(fixture.store).cancelRun(fixture.actor, fixture.runId);
        verify(fixture.store, never()).failRun(eq(fixture.runId),
                eq("AI_DETERMINISTIC_COMMAND_FAILED"), anyBoolean());
    }

    @Test
    void retryRecordsFreshRetrievalTraceBeforeSchedulingChildRun() {
        Fixture fixture = new Fixture();
        fixture.prepareAssistant(null, new TestStream(ignored -> { }));
        Run parent = fixture.run(fixture.parentRunId, AiCapability.ASSISTANT, "FAILED",
                fixture.conversationId);
        Conversation conversation = fixture.conversation();
        when(fixture.store.ownedRun(fixture.actor.userId(), fixture.parentRunId)).thenReturn(parent);
        when(fixture.store.retrySource(fixture.actor.userId(), fixture.parentRunId))
                .thenReturn(new AiRunRecords.RetrySource(parent, conversation,
                        "retry question", "L1", "b".repeat(64)));
        RetrievalTrace trace = trace();
        ContextResolverRegistry.Resolution preflight = fixture.resolution(null, null);
        ContextResolverRegistry.Resolution resolved = fixture.resolution(null, trace);
        when(fixture.contexts.resolve(eq(fixture.actor), eq("GLOBAL"), eq("NONE"), isNull(),
                isNull())).thenReturn(preflight);
        when(fixture.contexts.resolveForRun(eq(fixture.runId), eq(fixture.actor), eq("GLOBAL"),
                eq("NONE"), isNull(), eq("retry question"))).thenReturn(resolved);
        Run child = fixture.run(fixture.runId, AiCapability.ASSISTANT, "ACCEPTED",
                fixture.conversationId);
        when(fixture.store.createRetryRun(fixture.actor, fixture.parentRunId, "retry-1", "fake"))
                .thenReturn(new RunCreation(child, false));

        fixture.service.retry(fixture.parentRunId, "retry-1");

        verify(fixture.store).recordRetrievalTrace(fixture.runId, fixture.actor, trace);
        verify(fixture.store).markQueued(fixture.runId);
    }

    @Test
    void missingContextRegistryFailsClosedThroughLegacyConstructor() {
        Fixture fixture = new Fixture();
        AiRunService legacy = new AiRunService(
                fixture.gate, fixture.actors, fixture.store, fixture.runtime,
                fixture.publisher, fixture.crypto, fixture.executor, new ObjectMapper(),
                fixture.recent, fixture.audit, fixture.observability);

        AiApiException failure = assertThrows(AiApiException.class,
                () -> legacy.createConversation("GLOBAL", "NONE", null));

        assertEquals("AI_CONTEXT_RESOLVER_UNAVAILABLE", failure.errorCode());
    }

    @Test
    void assistantStopsBeforeStartWhenSessionWasRevoked() {
        Fixture fixture = new Fixture();
        fixture.prepareAssistant(null, new TestStream(ignored -> { }));
        when(fixture.actors.stillValid(fixture.actor, "ai:assistant:use")).thenReturn(false);

        fixture.createMessage();
        fixture.executor.runCaptured();

        verify(fixture.store).failRun(fixture.runId, "AI_SESSION_REVOKED", false);
        verify(fixture.store, never()).markStarted(anyString(), anyString());
        verify(fixture.runtime, never()).stream(any());
    }

    @Test
    void assistantStopsBeforeStartWhenCurrentObjectScopeWasRevokedAfterAcceptance() {
        Fixture fixture = new Fixture();
        fixture.prepareAssistant(null, new TestStream(ignored -> { }));

        fixture.createMessage();
        doThrow(new SecurityException("维修对象范围已撤销")).when(fixture.contexts)
                .authorizeCurrentAccess(fixture.actor, "GLOBAL", "NONE", null);
        fixture.executor.runCaptured();

        verify(fixture.store).failRun(fixture.runId, "AI_CONTEXT_ACCESS_REVOKED", false);
        verify(fixture.store, never()).markStarted(anyString(), anyString());
        verify(fixture.runtime, never()).stream(any());
    }

    @Test
    void assistantPreservesResolverUnavailableCodeDuringExecutionRecheck() {
        Fixture fixture = new Fixture();
        fixture.prepareAssistant(null, new TestStream(ignored -> { }));
        fixture.createMessage();
        doThrow(AiApiException.unavailable("AI_CONTEXT_RESOLVER_UNAVAILABLE", "上下文解析器暂不可用"))
                .when(fixture.contexts)
                .authorizeCurrentAccess(fixture.actor, "GLOBAL", "NONE", null);

        fixture.executor.runCaptured();

        verify(fixture.store).failRun(fixture.runId, "AI_CONTEXT_RESOLVER_UNAVAILABLE", true);
        verify(fixture.store, never()).markStarted(anyString(), anyString());
        verify(fixture.runtime, never()).stream(any());
    }

    @Test
    void assistantCancelsStreamBeforePersistingDeltaWhenCurrentObjectScopeIsRevoked() {
        Fixture fixture = new Fixture();
        TestStream stream = new TestStream(consumer ->
                consumer.accept(AiStreamEvent.textDelta("不得在撤权后继续输出")));
        fixture.prepareAssistant(null, stream);
        AtomicInteger accessChecks = new AtomicInteger();
        doAnswer(ignored -> {
            if (accessChecks.incrementAndGet() >= 4) {
                throw new SecurityException("维修对象范围已撤销");
            }
            return null;
        }).when(fixture.contexts)
                .authorizeCurrentAccess(fixture.actor, "GLOBAL", "NONE", null);

        fixture.createMessage();
        fixture.executor.runCaptured();

        assertEquals(1, stream.cancelCalls.get());
        verify(fixture.store).failRun(fixture.runId, "AI_CONTEXT_ACCESS_REVOKED", false);
        verify(fixture.store, never()).appendDelta(eq(fixture.runId), anyString());
        verify(fixture.store, never()).completeRun(eq(fixture.runId), any(), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), any());
    }

    @Test
    void directResponseRevalidatesSessionBeforeCompletion() {
        Fixture fixture = new Fixture();
        fixture.prepareAssistant("暂无可靠来源，无法确认", new TestStream(ignored -> { }));
        when(fixture.actors.stillValid(fixture.actor, "ai:assistant:use"))
                .thenReturn(true, false);

        fixture.createMessage();
        fixture.executor.runCaptured();

        verify(fixture.store).appendDelta(eq(fixture.runId), anyString());
        verify(fixture.store).failRun(fixture.runId, "AI_SESSION_REVOKED", false);
        verify(fixture.store, never()).completeRun(eq(fixture.runId), any(), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), any());
    }

    @Test
    void directResponseGateClosingAfterDeltaPreventsCompletion() {
        Fixture fixture = new Fixture();
        fixture.prepareAssistant("暂无可靠来源，无法确认", new TestStream(ignored -> { }));
        AtomicInteger checks = new AtomicInteger();
        doAnswer(ignored -> {
            if (checks.incrementAndGet() == 4) throw disabled("AI_PROVIDER_DISABLED");
            return null;
        }).when(fixture.gate).requireStreamingProvider(AiCapability.ASSISTANT, fixture.actor.userId());

        fixture.createMessage();
        fixture.executor.runCaptured();

        verify(fixture.store).failRun(fixture.runId, "AI_PROVIDER_DISABLED", true);
        verify(fixture.store, never()).completeRun(eq(fixture.runId), any(), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), any());
    }

    @Test
    void groundedResponseRechecksSessionAfterFinalContextResolution() {
        Fixture fixture = new Fixture();
        CitationCandidate citation = citation("source-1", "version-1", "chunk-1", "受控知识正文");
        fixture.prepareAssistant("受控知识正文", new TestStream(ignored -> { }), List.of(citation));
        fixture.createMessage();
        AtomicInteger checks = new AtomicInteger();
        when(fixture.actors.stillValid(any(), anyString())).thenAnswer(ignored -> checks.incrementAndGet() == 1);
        fixture.executor.runCaptured();
        verify(fixture.store, never()).completeRunWithFinalDelta(any(), any(), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), any());
        verify(fixture.store).failRun(fixture.runId, "AI_SESSION_REVOKED", false);
    }

    @Test
    void groundedDirectResponseCompletesAndPublishesDeltaInOneAtomicStoreCall() {
        Fixture fixture = new Fixture();
        CitationCandidate citation = citation("source-1", "version-1", "chunk-1", "受控知识正文");
        fixture.prepareAssistant("受控知识正文", new TestStream(ignored -> { }), List.of(citation));
        when(fixture.store.completeRunWithFinalDelta(
                eq(fixture.runId), eq("受控知识正文"), any(),
                eq("deterministic"), eq("knowledge-grounding-policy-v1"), eq(false),
                eq(true), eq(List.of(citation)), eq(Set.copyOf(fixture.actor.permissionCodes()))))
                .thenReturn(Fixture.changed());

        fixture.createMessage();
        fixture.executor.runCaptured();

        verify(fixture.store).completeRunWithFinalDelta(
                eq(fixture.runId), eq("受控知识正文"), any(),
                eq("deterministic"), eq("knowledge-grounding-policy-v1"), eq(false),
                eq(true), eq(List.of(citation)), eq(Set.copyOf(fixture.actor.permissionCodes())));
        verify(fixture.store, never()).appendDelta(eq(fixture.runId), anyString());
        verify(fixture.store, never()).completeRun(eq(fixture.runId), any(), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), any());
    }

    @Test
    void revokedCitationAtAtomicCompletionBoundaryDoesNotPersistOrPublishDelta() {
        Fixture fixture = new Fixture();
        CitationCandidate citation = citation("source-1", "version-1", "chunk-1", "撤权后不得输出");
        fixture.prepareAssistant("撤权后不得输出", new TestStream(ignored -> { }), List.of(citation));
        when(fixture.store.completeRunWithFinalDelta(
                eq(fixture.runId), eq("撤权后不得输出"), any(),
                eq("deterministic"), eq("knowledge-grounding-policy-v1"), eq(false),
                eq(true), eq(List.of(citation)), eq(Set.copyOf(fixture.actor.permissionCodes()))))
                .thenThrow(new AiApiException(org.springframework.http.HttpStatus.NOT_FOUND,
                        "AI_CONTEXT_ACCESS_REVOKED", "当前知识授权已撤销", false));

        fixture.createMessage();
        fixture.executor.runCaptured();

        verify(fixture.store).failRun(fixture.runId, "AI_CONTEXT_ACCESS_REVOKED", false);
        verify(fixture.store, never()).appendDelta(eq(fixture.runId), anyString());
        verify(fixture.store, never()).completeRun(eq(fixture.runId), any(), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), any());
        verify(fixture.publisher, never()).publish(org.mockito.ArgumentMatchers.argThat(event ->
                "message.delta".equals(event.type())
                        && String.valueOf(event.payload()).contains("撤权后不得输出")));
    }

    @Test
    void cancellationBetweenProviderReturnAndStreamRegistrationCancelsTheStream() {
        Fixture fixture = new Fixture();
        TestStream stream = new TestStream(ignored -> { });
        fixture.prepareAssistant(null, stream);
        when(fixture.runtime.stream(any())).thenAnswer(ignored -> {
            fixture.service.cancel(fixture.runId);
            return stream;
        });

        fixture.createMessage();
        fixture.executor.runCaptured();

        assertEquals(1, stream.cancelCalls.get());
        verify(fixture.store).cancelRun(fixture.actor, fixture.runId);
        verify(fixture.store, never()).completeRun(eq(fixture.runId), any(), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), any());
    }

    @Test
    void providerCancelledEventFailsRunWithoutCompletingIt() {
        Fixture fixture = new Fixture();
        TestStream stream = new TestStream(consumer -> consumer.accept(AiStreamEvent.cancelled()));
        stream.providerCancelled.set(true);
        fixture.prepareAssistant(null, stream);

        fixture.createMessage();
        fixture.executor.runCaptured();

        verify(fixture.store).failRun(fixture.runId, "AI_PROVIDER_CANCELLED", true);
        verify(fixture.store, never()).completeRun(eq(fixture.runId), any(), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), any());
    }

    @Test
    void oversizedProviderOutputMapsToUnavailableWithoutPersistingDelta() {
        Fixture fixture = new Fixture();
        fixture.prepareAssistant(null, new TestStream(
                consumer -> consumer.accept(AiStreamEvent.textDelta("x".repeat(16_385)))));

        fixture.createMessage();
        fixture.executor.runCaptured();

        verify(fixture.store).failRun(fixture.runId, "AI_PROVIDER_UNAVAILABLE", true);
        verify(fixture.store, never()).appendDelta(eq(fixture.runId), anyString());
    }

    @Test
    void upstreamAndNestedJdkTimeoutsMapToProviderTimeout() {
        for (RuntimeException timeout : List.of(
                new AiResiliencePolicy.UpstreamTimeoutException(),
                new IllegalStateException("wrapped", new TimeoutException("late")))) {
            Fixture fixture = new Fixture();
            fixture.prepareAssistant(null, new TestStream(ignored -> { throw timeout; }));

            fixture.createMessage();
            fixture.executor.runCaptured();

            verify(fixture.store).failRun(fixture.runId, "AI_PROVIDER_TIMEOUT", true);
        }
    }

    @Test
    void aiApiFailureAfterStreamRegistrationCancelsStreamAndPreservesCode() {
        Fixture fixture = new Fixture();
        TestStream stream = new TestStream(ignored -> {
            throw disabled("AI_PROVIDER_DISABLED");
        });
        fixture.prepareAssistant(null, stream);

        fixture.createMessage();
        fixture.executor.runCaptured();

        assertEquals(1, stream.cancelCalls.get());
        verify(fixture.store).failRun(fixture.runId, "AI_PROVIDER_DISABLED", true);
    }

    @Test
    void aiApiFailureAfterCancellationDoesNotOverwriteCancelledState() {
        Fixture fixture = new Fixture();
        TestStream stream = new TestStream(ignored -> {
            fixture.service.cancel(fixture.runId);
            throw disabled("AI_PROVIDER_DISABLED");
        });
        fixture.prepareAssistant(null, stream);

        fixture.createMessage();
        fixture.executor.runCaptured();

        assertTrue(stream.cancelCalls.get() >= 1);
        verify(fixture.store).cancelRun(fixture.actor, fixture.runId);
        verify(fixture.store, never()).failRun(eq(fixture.runId), eq("AI_PROVIDER_DISABLED"), anyBoolean());
    }

    @Test
    void sessionRevocationDuringDeltaCancelsRegisteredStream() {
        Fixture fixture = new Fixture();
        TestStream stream = new TestStream(consumer ->
                consumer.accept(AiStreamEvent.textDelta("普通内容".repeat(30))));
        fixture.prepareAssistant(null, stream);
        when(fixture.actors.stillValid(fixture.actor, "ai:assistant:use"))
                .thenReturn(true, false);

        fixture.createMessage();
        fixture.executor.runCaptured();

        assertTrue(stream.cancelCalls.get() >= 1);
        verify(fixture.store).failRun(fixture.runId, "AI_PROVIDER_UNAVAILABLE", true);
        verify(fixture.store, never()).completeRun(eq(fixture.runId), any(), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), any());
    }

    private static AiApiException disabled(String code) {
        return AiApiException.unavailable(code, "runtime gate disabled");
    }

    private static RetrievalTrace trace() {
        return new RetrievalTrace("a".repeat(64), "retrieval-v1", "HYBRID", "knowledge-index",
                "index-v1", "embedding-v1", "{}", 5, 1, 1, 1, 10, "SUCCEEDED");
    }

    private static final class Fixture {
        private final AiActorContext actor = new AiActorContext(
                7L, "session", "fingerprint", 1, "digest", List.of("ADMIN"),
                List.of("ai:assistant:use", "ai:knowledge:read", "ai:dashboard:query", "dashboard:read"),
                ActorDescriptor.user(7L));
        private final String conversationId = UUID.randomUUID().toString();
        private final String runId = UUID.randomUUID().toString();
        private final String parentRunId = UUID.randomUUID().toString();
        private final AiRuntimeGate gate = mock(AiRuntimeGate.class);
        private final AiActorResolver actors = mock(AiActorResolver.class);
        private final AiConversationRunStore store = mock(AiConversationRunStore.class);
        private final AiModelRuntimePort runtime = mock(AiModelRuntimePort.class);
        private final AiRunEventPublisher publisher = mock(AiRunEventPublisher.class);
        private final AiRuntimeCrypto crypto = crypto();
        private final CapturingTaskExecutor executor = new CapturingTaskExecutor();
        private final RecentAuthenticationPolicy recent = mock(RecentAuthenticationPolicy.class);
        private final AiRuntimeAuditWriter audit = mock(AiRuntimeAuditWriter.class);
        private final AiObservability observability = mock(AiObservability.class);
        private final ContextResolverRegistry contexts = mock(ContextResolverRegistry.class);
        private final AiRunService service = new AiRunService(
                gate, actors, store, runtime, publisher, crypto, executor, new ObjectMapper(),
                recent, audit, observability, contexts, mock(AiAuditContentAuthorizationService.class));

        private Fixture() {
            when(actors.current(nullable(String.class))).thenReturn(actor);
            when(actors.stillValid(eq(actor), anyString())).thenReturn(true);
            when(runtime.providerCode()).thenReturn("fake");
            when(runtime.modelAlias()).thenReturn("fake-model");
            when(store.markQueued(anyString())).thenReturn(changed());
            when(store.appendDelta(anyString(), anyString())).thenReturn(RunMutation.unchanged());
            when(store.completeRun(anyString(), anyString(), any(), anyString(), anyString(),
                    anyBoolean(), anyBoolean(), any(), any())).thenReturn(RunMutation.unchanged());
            when(store.completeCommandRun(anyString(), any())).thenReturn(RunMutation.unchanged());
            when(store.completeCommandRun(anyString(), any(), any(), any()))
                    .thenReturn(RunMutation.unchanged());
            when(store.failRun(anyString(), anyString(), anyBoolean())).thenReturn(RunMutation.unchanged());
            when(store.cancelRun(any(), anyString())).thenReturn(RunMutation.unchanged());
        }

        private void prepareCommand(String normalizedContext, Long contextId) {
            prepareCommand(AiCapability.DASHBOARD, "DASHBOARD", normalizedContext, contextId);
        }

        private void prepareCommand(
                AiCapability capability, String surface, String normalizedContext, Long contextId) {
            Conversation conversation = new Conversation(conversationId, surface, normalizedContext,
                    contextId, "ACTIVE", null, Instant.now(), Instant.now());
            Run run = run(runId, capability, "ACCEPTED", conversationId);
            when(store.createCommandRun(eq(actor), eq(capability), eq(surface),
                    eq(normalizedContext), eq(contextId), eq("request-1"), anyString(), anyString(),
                    anyString(), eq("deterministic"))).thenReturn(new RunCreation(run, false));
            when(store.markStarted(runId, "deterministic-rules-v1")).thenReturn(changed());
            when(store.ownedRun(actor.userId(), runId)).thenReturn(run);
            when(store.ownedConversation(actor.userId(), conversationId)).thenReturn(conversation);
        }

        private void prepareAssistant(String directResponse, AiEventStream stream) {
            prepareAssistant(directResponse, stream, List.of());
        }

        private void prepareAssistant(
                String directResponse, AiEventStream stream, List<CitationCandidate> citations) {
            Conversation conversation = conversation();
            Run run = run(runId, AiCapability.ASSISTANT, "ACCEPTED", conversationId);
            when(store.conversationDetail(actor.userId(), conversationId))
                    .thenReturn(new ConversationDetail(conversation, List.of()));
            when(store.ownedConversation(actor.userId(), conversationId)).thenReturn(conversation);
            ContextResolverRegistry.Resolution resolution = resolution(
                    directResponse, !citations.isEmpty(), citations, null);
            when(contexts.resolve(eq(actor), eq("GLOBAL"), eq("NONE"), isNull(), isNull()))
                    .thenReturn(resolution);
            when(contexts.resolveForRun(eq(runId), eq(actor), eq("GLOBAL"), eq("NONE"),
                    isNull(), anyString())).thenReturn(resolution);
            when(store.createRun(eq(actor), eq(conversationId), eq("request-1"),
                    anyString(), anyString(), anyString(), eq("fake")))
                    .thenReturn(new RunCreation(run, false));
            when(store.markStarted(runId, directResponse == null
                    ? "fake-model" : "knowledge-grounding-policy-v1")).thenReturn(changed());
            when(store.pinnedSystemPrompt(runId)).thenReturn("只遵循服务端固定规则。");
            when(store.ownedRun(actor.userId(), runId)).thenReturn(run);
            when(runtime.stream(any())).thenReturn(stream);
        }

        private RunCreation createCommand(AiRunService.CommandWork work) {
            return service.createCommand(AiCapability.DASHBOARD, "ai:dashboard:query", "DASHBOARD",
                    "request-1", "{}", work);
        }

        private RunCreation createMessage() {
            return service.createMessage(conversationId, "请给出维修建议", "request-1");
        }

        private Conversation conversation() {
            return new Conversation(conversationId, "GLOBAL", "NONE", null,
                    "ACTIVE", null, Instant.now(), Instant.now());
        }

        private ContextResolverRegistry.Resolution resolution(
                String directResponse, RetrievalTrace retrievalTrace) {
            return resolution(directResponse, false, List.of(), retrievalTrace);
        }

        private ContextResolverRegistry.Resolution resolution(
                String directResponse,
                boolean grounded,
                List<CitationCandidate> citations,
                RetrievalTrace retrievalTrace) {
            return new ContextResolverRegistry.Resolution(
                    "GLOBAL", "NONE", null, "{}", Set.of(), directResponse,
                    grounded, citations, retrievalTrace);
        }

        private Run run(String id, AiCapability capability, String state, String conversation) {
            return new Run(id, conversation, actor.userId(), capability.name(), state,
                    actor.permissionDigest(), actor.sessionFingerprintHash(), "v1",
                    Instant.now(), null, null);
        }

        private static RunMutation changed() {
            return new RunMutation(true, List.of());
        }
    }

    private static CitationCandidate citation(
            String sourceId, String versionId, String chunkId, String quote) {
        return new CitationCandidate(sourceId, versionId, chunkId, "制度", "第 1 段", quote,
                "a".repeat(64), 1, null);
    }

    private static final class CapturingTaskExecutor implements TaskExecutor {
        private Runnable task;

        @Override
        public void execute(Runnable task) {
            this.task = task;
        }

        private void runCaptured() {
            if (task == null) throw new IllegalStateException("no task captured");
            task.run();
        }
    }

    private static final class TestStream implements AiEventStream {
        private final Consumer<Consumer<AiStreamEvent>> script;
        private final AtomicBoolean providerCancelled = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicInteger cancelCalls = new AtomicInteger();

        private TestStream(Consumer<Consumer<AiStreamEvent>> script) {
            this.script = script;
        }

        @Override
        public void consume(Consumer<AiStreamEvent> consumer) {
            script.accept(consumer);
        }

        @Override
        public boolean cancel() {
            cancelCalls.incrementAndGet();
            return cancelled.compareAndSet(false, true);
        }

        @Override
        public boolean isCancelled() {
            return providerCancelled.get();
        }
    }

    private static AiRuntimeCrypto crypto() {
        AiProperties properties = new AiProperties();
        String key = "run-service-execution-edge-key-32-bytes-minimum";
        properties.getTokenization().setHmacKey(key);
        properties.getTokenization().setActiveKeyVersion(1);
        PiiRedactionService redaction = new PiiRedactionService(
                key.getBytes(StandardCharsets.UTF_8), "test-v1");
        return new AiRuntimeCrypto(properties, new PiiClassificationService(redaction, List::of));
    }
}
