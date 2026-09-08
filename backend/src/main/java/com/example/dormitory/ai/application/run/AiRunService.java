package com.example.dormitory.ai.application.run;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.application.run.AiRunRecords.AuditRun;
import com.example.dormitory.ai.application.run.AiRunRecords.AuditRunDetail;
import com.example.dormitory.ai.application.run.AiRunRecords.AuditRunContent;
import com.example.dormitory.ai.application.run.AiRunRecords.Conversation;
import com.example.dormitory.ai.application.run.AiRunRecords.ConversationDetail;
import com.example.dormitory.ai.application.run.AiRunRecords.Event;
import com.example.dormitory.ai.application.run.AiRunRecords.Readiness;
import com.example.dormitory.ai.application.run.AiRunRecords.Run;
import com.example.dormitory.ai.application.run.AiRunRecords.RunCreation;
import com.example.dormitory.ai.application.run.AiRunRecords.RunMutation;
import com.example.dormitory.ai.domain.model.AiCapability;
import com.example.dormitory.ai.domain.model.AiStreamEvent;
import com.example.dormitory.ai.domain.model.ModelRequest;
import com.example.dormitory.ai.domain.model.ModelUsage;
import com.example.dormitory.ai.infrastructure.runtime.AiRuntimeCrypto;
import com.example.dormitory.ai.port.AiEventStream;
import com.example.dormitory.ai.resilience.AiResiliencePolicy;
import com.example.dormitory.ai.security.PiiRedactionService;
import com.example.dormitory.ai.security.PiiStreamingRedactor;
import com.example.dormitory.ai.security.SensitiveDataBlockedException;
import com.example.dormitory.ai.security.AuthenticatedRunContext;
import com.example.dormitory.ai.security.AiAuditContentAuthorizationService;
import com.example.dormitory.ai.security.RecentAuthenticationPolicy;
import com.example.dormitory.ai.infrastructure.runtime.AiRuntimeAuditWriter;
import com.example.dormitory.ai.observability.AiObservability;
import com.example.dormitory.common.BusinessException;
import com.example.dormitory.common.PageResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class AiRunService {

    private static final Set<String> SURFACES = Set.of(
            "GLOBAL", "DASHBOARD", "REPAIR", "NOTICE", "KNOWLEDGE", "RISK", "APPROVAL", "AUDIT");
    private static final Set<String> TERMINAL_STATES = Set.of("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT");
    private static final long CONVERSATION_ACCESS_SCAN_PAGE_SIZE = 100;

    private final AiRuntimeGate gate;
    private final AiActorResolver actorResolver;
    private final AiConversationRunStore store;
    private final AiModelRuntimePort modelRuntime;
    private final AiRunEventPublisher eventPublisher;
    private final AiRuntimeCrypto crypto;
    private final TaskExecutor executor;
    private final ObjectMapper objectMapper;
    private final RecentAuthenticationPolicy recentAuthentication;
    private final AiRuntimeAuditWriter auditWriter;
    private final AiObservability observability;
    private final ContextResolverRegistry contextResolvers;
    private final AiAuditContentAuthorizationService auditContentAuthorization;
    private final AiRunCurrentAccessAuthorizer runAccessAuthorizer;
    private final ConcurrentMap<String, ActiveExecution> activeExecutions = new ConcurrentHashMap<>();

    @Autowired
    public AiRunService(
            AiRuntimeGate gate,
            AiActorResolver actorResolver,
            AiConversationRunStore store,
            AiModelRuntimePort modelRuntime,
            AiRunEventPublisher eventPublisher,
            AiRuntimeCrypto crypto,
            @Qualifier("aiRunTaskExecutor") TaskExecutor executor,
            ObjectMapper objectMapper,
            RecentAuthenticationPolicy recentAuthentication,
            AiRuntimeAuditWriter auditWriter,
            AiObservability observability,
            ContextResolverRegistry contextResolvers,
            AiAuditContentAuthorizationService auditContentAuthorization,
            AiRunCurrentAccessAuthorizer runAccessAuthorizer) {
        this.gate = gate;
        this.actorResolver = actorResolver;
        this.store = store;
        this.modelRuntime = modelRuntime;
        this.eventPublisher = eventPublisher;
        this.crypto = crypto;
        this.executor = executor;
        this.objectMapper = objectMapper;
        this.recentAuthentication = recentAuthentication;
        this.auditWriter = auditWriter;
        this.observability = observability;
        this.contextResolvers = java.util.Objects.requireNonNull(contextResolvers);
        this.auditContentAuthorization = java.util.Objects.requireNonNull(auditContentAuthorization);
        this.runAccessAuthorizer = java.util.Objects.requireNonNull(runAccessAuthorizer);
    }

    public AiRunService(
            AiRuntimeGate gate,
            AiActorResolver actorResolver,
            AiConversationRunStore store,
            AiModelRuntimePort modelRuntime,
            AiRunEventPublisher eventPublisher,
            AiRuntimeCrypto crypto,
            TaskExecutor executor,
            ObjectMapper objectMapper,
            RecentAuthenticationPolicy recentAuthentication,
            AiRuntimeAuditWriter auditWriter,
            AiObservability observability,
            ContextResolverRegistry contextResolvers,
            AiAuditContentAuthorizationService auditContentAuthorization) {
        this(gate, actorResolver, store, modelRuntime, eventPublisher, crypto, executor, objectMapper,
                recentAuthentication, auditWriter, observability, contextResolvers,
                auditContentAuthorization,
                new AiRunCurrentAccessAuthorizer(store, contextResolvers, auditContentAuthorization));
    }

    /** 仅保留给不涉及上下文的窄单元测试；生产注入使用上方显式注册表构造器。 */
    public AiRunService(
            AiRuntimeGate gate,
            AiActorResolver actorResolver,
            AiConversationRunStore store,
            AiModelRuntimePort modelRuntime,
            AiRunEventPublisher eventPublisher,
            AiRuntimeCrypto crypto,
            TaskExecutor executor,
            ObjectMapper objectMapper,
            RecentAuthenticationPolicy recentAuthentication,
            AiRuntimeAuditWriter auditWriter,
            AiObservability observability) {
        this.gate = gate;
        this.actorResolver = actorResolver;
        this.store = store;
        this.modelRuntime = modelRuntime;
        this.eventPublisher = eventPublisher;
        this.crypto = crypto;
        this.executor = executor;
        this.objectMapper = objectMapper;
        this.recentAuthentication = recentAuthentication;
        this.auditWriter = auditWriter;
        this.observability = observability;
        this.contextResolvers = null;
        this.auditContentAuthorization = null;
        this.runAccessAuthorizer = null;
    }

    public Conversation createConversation(String surface, String contextType, Long contextId) {
        gate.requireCapability(AiCapability.ASSISTANT);
        AiActorContext actor = actorResolver.current("ai:assistant:use");
        String normalizedSurface = normalizeSurface(surface);
        ContextResolverRegistry.Resolution resolution = resolveCurrentContext(
                actor, normalizedSurface, contextType, contextId);
        requireCurrentContextAccess(actor, resolution.surface(), resolution.contextType(), resolution.contextId());
        return store.createConversation(actor, resolution.surface(),
                resolution.contextType(), resolution.contextId());
    }

    public PageResponse<Conversation> listConversations(long page, long pageSize) {
        gate.requireCapability(AiCapability.ASSISTANT);
        validatePage(page, pageSize);
        int requestedPageSize = Math.toIntExact(pageSize);
        AiActorContext actor = actorResolver.current("ai:assistant:use");
        long requestedOffset;
        try {
            requestedOffset = Math.multiplyExact(page - 1, pageSize);
        } catch (ArithmeticException overflow) {
            requestedOffset = Long.MAX_VALUE;
        }
        List<Conversation> requestedRecords = new ArrayList<>(requestedPageSize);
        long visibleTotal = 0;
        long scanned = 0;
        long expectedOwnerTotal = -1;
        long sourcePage = 1;
        while (true) {
            PageResponse<Conversation> batch = store.listConversations(
                    actor.userId(), sourcePage, CONVERSATION_ACCESS_SCAN_PAGE_SIZE);
            if (batch == null || batch.records() == null || batch.total() < 0
                    || batch.records().size() > CONVERSATION_ACCESS_SCAN_PAGE_SIZE) {
                throw new IllegalStateException("AI 会话分页结果不合法");
            }
            if (expectedOwnerTotal < 0) expectedOwnerTotal = batch.total();
            else if (expectedOwnerTotal != batch.total()) {
                throw new IllegalStateException("AI 会话分页快照发生变化，请重试");
            }
            if (batch.records().size() > expectedOwnerTotal - scanned) {
                throw new IllegalStateException("AI 会话分页结果超过总数");
            }
            for (Conversation conversation : batch.records()) {
                if (!hasCurrentConversationAccess(actor, conversation)) continue;
                if (visibleTotal >= requestedOffset && requestedRecords.size() < requestedPageSize) {
                    requestedRecords.add(conversation);
                }
                visibleTotal++;
            }
            scanned += batch.records().size();
            if (scanned == expectedOwnerTotal) break;
            if (batch.records().isEmpty()) {
                throw new IllegalStateException("AI 会话分页结果不完整");
            }
            sourcePage++;
        }
        return new PageResponse<>(List.copyOf(requestedRecords), visibleTotal, page, pageSize);
    }

    public ConversationDetail conversation(String conversationId) {
        gate.requireCapability(AiCapability.ASSISTANT);
        requireUuid(conversationId);
        AiActorContext actor = actorResolver.current("ai:assistant:use");
        ConversationDetail detail = store.conversationDetail(actor.userId(), conversationId);
        requireCurrentConversationAccess(actor, detail.conversation());
        return detail;
    }

    public void archiveConversation(String conversationId) {
        gate.requireCapability(AiCapability.ASSISTANT);
        requireUuid(conversationId);
        store.archiveConversation(actorResolver.current("ai:assistant:use"), conversationId);
    }

    public RunCreation createMessage(String conversationId, String text, String clientRequestId) {
        requireUuid(conversationId);
        requireClientRequestId(clientRequestId);
        AiActorContext actor = actorResolver.current("ai:assistant:use");
        gate.requireStreamingProvider(AiCapability.ASSISTANT, actor.userId());
        PiiRedactionService.RedactionResult redaction = crypto.redact(text, "assistant-input");
        Conversation conversation = store.conversationDetail(actor.userId(), conversationId).conversation();
        // 先重验页面上下文和业务对象可见性，但不触发知识检索；撤权请求不得创建 run。
        requireCurrentConversationAccess(actor, conversation);
        String requestHash = crypto.sha256("assistant-message.v1|" + conversationId + "|"
                + redaction.redactedText());
        RunCreation creation = store.createRun(
                actor,
                conversationId,
                clientRequestId,
                requestHash,
                redaction.redactedText(),
                redaction.classification().name(),
                modelRuntime.providerCode());
        if (!creation.replayed()) {
            try {
                ContextResolverRegistry.Resolution resolution = requireContextResolvers().resolveForRun(
                        creation.run().id(), actor, conversation.surface(), conversation.contextType(),
                        conversation.contextId(), redaction.redactedText());
                if (resolution.retrievalTrace() != null) {
                    store.recordRetrievalTrace(creation.run().id(), actor, resolution.retrievalTrace());
                }
                requireQueued(creation.run().id());
                schedule(creation.run(), actor, conversation, redaction.redactedText(), resolution.contextJson(),
                        contextKind(resolution),
                        resolution.allowedToolIds(), resolution.directResponse(),
                        resolution.grounded(), resolution.citations());
            } catch (RuntimeException failure) {
                safeFail(creation.run().id(), "AI_CONTEXT_RESOLUTION_FAILED", false);
                throw failure;
            }
        }
        return creation;
    }

    public RunCreation createCommand(
            AiCapability capability,
            String requiredPermission,
            String surface,
            String clientRequestId,
            String inputJson,
            CommandWork work) {
        return createCommand(capability, requiredPermission, surface, "COMMAND", null,
                clientRequestId, inputJson, work);
    }

    public RunCreation createCommand(
            AiCapability capability,
            String requiredPermission,
            String surface,
            String contextType,
            Long contextId,
            String clientRequestId,
            String inputJson,
            CommandWork work) {
        requireClientRequestId(clientRequestId);
        AiActorContext actor = actorResolver.current(requiredPermission);
        gate.requireStreaming(capability, actor.userId());
        PiiRedactionService.RedactionResult redaction = crypto.redact(inputJson, capability.name().toLowerCase(Locale.ROOT)
                + "-command-input");
        String normalizedContext = contextType == null ? "COMMAND" : contextType.trim().toUpperCase(Locale.ROOT);
        String requestHash = crypto.sha256(capability.name() + "|command.v2|" + normalizedContext + "|"
                + (contextId == null ? "" : contextId) + "|" + redaction.redactedText());
        RunCreation creation = store.createCommandRun(actor, capability, surface, normalizedContext, contextId,
                clientRequestId, requestHash, redaction.redactedText(), redaction.classification().name(),
                "deterministic");
        if (!creation.replayed()) {
            requireQueued(creation.run().id());
            scheduleCommand(creation.run(), actor, capability, requiredPermission, work);
        }
        return creation;
    }

    public RunCreation retry(String parentRunId, String idempotencyKey) {
        requireUuid(parentRunId);
        requireClientRequestId(idempotencyKey);
        AiActorContext candidate = actorResolver.current(null);
        Run parent = store.ownedRun(candidate.userId(), parentRunId);
        AiCapability capability = AiCapability.valueOf(parent.capability());
        if (capability != AiCapability.ASSISTANT) {
            throw new AiApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AI_RUN_RETRY_UNSUPPORTED",
                    "该能力尚不支持通用运行重试", false);
        }
        AiActorContext actor = actorResolver.current(permissionFor(capability));
        gate.requireStreamingProvider(AiCapability.ASSISTANT, actor.userId());
        AiRunRecords.RetrySource source = store.retrySource(actor.userId(), parentRunId);
        requireCurrentConversationAccess(actor, source.conversation());
        RunCreation creation = store.createRetryRun(actor, parentRunId, idempotencyKey,
                modelRuntime.providerCode());
        if (!creation.replayed()) {
            try {
                ContextResolverRegistry.Resolution resolution = requireContextResolvers().resolveForRun(
                        creation.run().id(), actor, source.conversation().surface(),
                        source.conversation().contextType(), source.conversation().contextId(),
                        source.inputRedacted());
                if (resolution.retrievalTrace() != null) {
                    store.recordRetrievalTrace(creation.run().id(), actor, resolution.retrievalTrace());
                }
                requireQueued(creation.run().id());
                schedule(creation.run(), actor, source.conversation(), source.inputRedacted(), resolution.contextJson(),
                        contextKind(resolution),
                        resolution.allowedToolIds(), resolution.directResponse(),
                        resolution.grounded(), resolution.citations());
            } catch (RuntimeException failure) {
                safeFail(creation.run().id(), "AI_RETRY_CONTEXT_RESOLUTION_FAILED", false);
                throw failure;
            }
        }
        return creation;
    }

    public Run run(String runId) {
        requireUuid(runId);
        AiActorContext actor = actorResolver.current(null);
        Run run = store.ownedRun(actor.userId(), runId);
        AiCapability capability = AiCapability.valueOf(run.capability());
        gate.requireCapability(capability);
        actor = actorResolver.current(permissionFor(capability));
        requireRunConversationAccess(actor, run);
        return run;
    }

    public void cancel(String runId) {
        requireUuid(runId);
        AiActorContext actor = actorResolver.current(null);
        Run run = store.ownedRun(actor.userId(), runId);
        AiCapability capability = AiCapability.valueOf(run.capability());
        gate.requireCapability(capability);
        actor = actorResolver.current(permissionFor(capability));
        requireRunConversationAccess(actor, run);
        ActiveExecution active = activeExecutions.remove(runId);
        if (active != null) active.cancel();
        RunMutation cancelled = store.cancelRun(actor, runId);
        publish(cancelled);
    }

    public void feedback(String messageId, int rating, List<String> tags, String comment) {
        gate.requireCapability(AiCapability.ASSISTANT);
        requireUuid(messageId);
        if (rating < -1 || rating > 1 || rating == 0) {
            throw new AiApiException(HttpStatus.BAD_REQUEST, "AI_INVALID_RATING",
                    "反馈 rating 只能是 -1 或 1", false);
        }
        List<String> safeTags = tags == null ? List.of() : tags.stream()
                .map(String::trim)
                .filter(tag -> tag.matches("[a-zA-Z0-9_-]{1,32}"))
                .distinct().limit(10).toList();
        if (tags != null && safeTags.size() != tags.size()) {
            throw new AiApiException(HttpStatus.BAD_REQUEST, "AI_INVALID_FEEDBACK_TAG",
                    "反馈标签不合法", false);
        }
        String safeComment = comment == null || comment.isBlank()
                ? null : crypto.redact(comment, "feedback-comment").redactedText();
        store.upsertFeedback(actorResolver.current("ai:assistant:use"),
                messageId, rating, safeTags, safeComment);
    }

    public PageResponse<AuditRun> auditRuns(long page, long pageSize) {
        return auditRuns(AiRunRecords.AuditRunFilter.none(), page, pageSize);
    }

    public PageResponse<AuditRun> auditRuns(
            AiRunRecords.AuditRunFilter filter, long page, long pageSize) {
        validatePage(page, pageSize);
        actorResolver.current("ai:audit:read");
        return store.auditRuns(java.util.Objects.requireNonNull(filter), page, pageSize);
    }

    public AuditRunDetail auditRun(String runId) {
        requireUuid(runId);
        actorResolver.current("ai:audit:read");
        return store.auditRun(runId);
    }

    public AuditRunContent auditRunContent(String runId, String reason, String proof) {
        requireUuid(runId);
        String normalizedReason = normalizeAuditReason(reason);
        AiActorContext actor = actorResolver.current("ai:audit:content:read");
        if (!actor.permissionCodes().contains("ai:audit:read")) {
            throw new AiApiException(HttpStatus.FORBIDDEN, "AI_AUDIT_READ_PERMISSION_REQUIRED",
                    "读取审计正文还需要审计元数据权限", false);
        }
        AuditRunDetail metadata = store.auditRun(runId);
        AiCapability capability = AiCapability.valueOf(metadata.run().capability());
        String capabilityPermission = permissionFor(capability);
        if (!actor.permissionCodes().contains(capabilityPermission)) throw AiApiException.notFound();
        String businessPermission = auditBusinessPermission(capability);
        if (businessPermission != null && !actor.permissionCodes().contains(businessPermission)) {
            throw AiApiException.notFound();
        }
        if (Set.of(AiCapability.KNOWLEDGE, AiCapability.RISK, AiCapability.EVALUATION).contains(capability)) {
            throw new AiApiException(HttpStatus.FORBIDDEN, "AI_AUDIT_CONTENT_SCOPE_UNAVAILABLE",
                    "该运行尚无可安全重建的底层正文授权范围", false);
        }
        if (auditContentAuthorization != null) {
            auditContentAuthorization.authorize(actor, runId, capability);
        }
        auditWriter.requireValidChain("RUN", "RUN", runId);
        String requestHash = auditContentRequestHash(runId, normalizedReason);
        recentAuthentication.consume(proof, AuthenticatedRunContext.from(actor),
                "AUDIT_CONTENT_READ", runId, requestHash);
        AuditRunContent content = store.auditRunContent(runId);
        auditWriter.append("SECURITY", "AI_RUN", runId, "AUDIT_CONTENT_READ", actor,
                requestHash, UUID.randomUUID().toString());
        return content;
    }

    public static String auditContentRequestHash(String runId, String reason) {
        return com.example.dormitory.ai.approval.CanonicalJsonHasher.sha256(
                "audit-content.v1|" + runId + "|" + normalizeAuditReason(reason));
    }

    public Readiness readiness() {
        AiActorContext actor = actorResolver.current("ai:config:manage");
        return store.readiness(actor.userId(), modelRuntime.providerCode());
    }

    public AiActorContext streamActor(String runId) {
        requireUuid(runId);
        AiActorContext actor = actorResolver.current(null);
        Run run = store.ownedRun(actor.userId(), runId);
        AiCapability capability = AiCapability.valueOf(run.capability());
        gate.requireCapability(capability);
        actor = actorResolver.current(permissionFor(capability));
        requireRunConversationAccess(actor, run);
        return actor;
    }

    public boolean isTerminal(String runId, long ownerUserId) {
        return TERMINAL_STATES.contains(store.ownedRun(ownerUserId, runId).state());
    }

    private void schedule(
            Run run,
            AiActorContext actor,
            Conversation conversation,
            String userPrompt,
            String untrustedContext,
            String untrustedContextKind,
            Set<String> allowedToolIds,
            String directResponse,
            boolean grounded,
            List<AiRunRecords.CitationCandidate> citations) {
        ActiveExecution execution = new ActiveExecution();
        ActiveRun active = new ActiveRun(actor, conversation, userPrompt, untrustedContext, untrustedContextKind,
                allowedToolIds, directResponse,
                grounded, citations, execution);
        activeExecutions.put(run.id(), execution);
        FutureTask<Void> task = new FutureTask<>(() -> {
            execute(run.id(), active);
            return null;
        });
        execution.registerTask(task);
        try {
            executor.execute(task);
        } catch (RejectedExecutionException exception) {
            activeExecutions.remove(run.id(), execution);
            execution.cancel();
            safeFail(run.id(), "AI_EXECUTOR_SATURATED", true);
            throw new AiApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_EXECUTOR_SATURATED",
                    "AI 运行队列已满", true, 10, run.id(), List.of(), java.util.Map.of());
        }
    }

    private void scheduleCommand(
            Run run,
            AiActorContext actor,
            AiCapability capability,
            String requiredPermission,
            CommandWork work) {
        ActiveExecution execution = new ActiveExecution();
        activeExecutions.put(run.id(), execution);
        FutureTask<Void> task = new FutureTask<>(() -> {
            executeCommand(run, actor, capability, requiredPermission, work, execution);
            return null;
        });
        execution.registerTask(task);
        try {
            executor.execute(task);
        } catch (RejectedExecutionException exception) {
            activeExecutions.remove(run.id(), execution);
            execution.cancel();
            safeFail(run.id(), "AI_EXECUTOR_SATURATED", true);
            throw new AiApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_EXECUTOR_SATURATED",
                    "AI 运行队列已满", true, 10, run.id(), List.of(), java.util.Map.of());
        }
    }

    private void executeCommand(
            Run run,
            AiActorContext actor,
            AiCapability capability,
            String requiredPermission,
            CommandWork work,
            ActiveExecution execution) {
        long startedNanos = System.nanoTime();
        String outcome = "FAILED";
        try {
            if (execution.cancelled()) return;
            if (!executionGateAllows(run.id(),
                    () -> gate.requireStreaming(capability, actor.userId()))) return;
            if (!actorResolver.stillValid(actor, requiredPermission)) {
                safeFail(run.id(), "AI_SESSION_REVOKED", false);
                return;
            }
            RunMutation started = store.markStarted(run.id(), "deterministic-rules-v1");
            publish(started);
            if (!started.changed() || execution.cancelled()) return;
            if (!executionGateAllows(run.id(),
                    () -> gate.requireStreaming(capability, actor.userId()))) return;
            Object result = work.execute(actor, run.id());
            if (execution.cancelled()) return;
            if (!executionGateAllows(run.id(),
                    () -> gate.requireStreaming(capability, actor.userId()))) return;
            List<AiRunRecords.CitationCandidate> citations = List.of();
            if (result instanceof AiRunRecords.TracedCommandResult traced) {
                store.recordRetrievalTrace(run.id(), actor, traced.retrievalTrace());
                citations = traced.citations();
                result = traced.responseBody();
            }
            if (!actorResolver.stillValid(actor, requiredPermission)) {
                safeFail(run.id(), "AI_SESSION_REVOKED", false);
                return;
            }
            Map<String, Object> payload = objectMapper.convertValue(
                    result, new TypeReference<Map<String, Object>>() { });
            RunMutation completion = citations.isEmpty()
                    ? store.completeCommandRun(run.id(), payload)
                    : store.completeCommandRun(run.id(), payload, citations,
                            Set.copyOf(actor.permissionCodes()));
            publish(completion);
            outcome = "SUCCEEDED";
        } catch (RuntimeException exception) {
            if (!execution.cancelled()) safeFail(run.id(), "AI_DETERMINISTIC_COMMAND_FAILED", false);
        } finally {
            activeExecutions.remove(run.id(), execution);
            recordObservation(capability.name(), "DETERMINISTIC", outcome, startedNanos);
        }
    }

    private void execute(String runId, ActiveRun active) {
        long startedNanos = System.nanoTime();
        String outcome = "FAILED";
        try {
            if (active.execution().cancelled()) return;
            if (!executionGateAllows(runId, () -> gate.requireStreamingProvider(
                    AiCapability.ASSISTANT, active.actor().userId()))) return;
            if (!actorResolver.stillValid(active.actor(), "ai:assistant:use")) {
                safeFail(runId, "AI_SESSION_REVOKED", false);
                return;
            }
            if (!currentContextAccessAllows(runId, active)) return;
            RunMutation started = store.markStarted(runId, active.directResponse() == null
                    ? modelRuntime.modelAlias() : "knowledge-grounding-policy-v1");
            publish(started);
            if (!started.changed() || active.execution().cancelled()) return;
            if (active.directResponse() != null) {
                if (!executionGateAllows(runId, () -> gate.requireStreamingProvider(
                        AiCapability.ASSISTANT, active.actor().userId()))) return;
                if (!currentContextAccessAllows(runId, active)) return;
                String safeOutput = crypto.redact(active.directResponse(), "knowledge-assistant-output")
                        .redactedText();
                if (!active.citations().isEmpty()) {
                    // 检索/脱敏之后再次检查会话；SSE broker 在网络发送前还会逐事件重验。
                    if (!actorResolver.stillValid(active.actor(), "ai:assistant:use")) {
                        safeFail(runId, "AI_SESSION_REVOKED", false);
                        return;
                    }
                    publish(store.completeRunWithFinalDelta(runId, safeOutput,
                            new ModelUsage(0, 0, ModelUsage.Source.ESTIMATED),
                            "deterministic", "knowledge-grounding-policy-v1", false,
                            active.grounded(), active.citations(),
                            Set.copyOf(active.actor().permissionCodes())));
                    outcome = "SUCCEEDED";
                    return;
                }
                publish(store.appendDelta(runId, safeOutput));
                if (!actorResolver.stillValid(active.actor(), "ai:assistant:use")) {
                    safeFail(runId, "AI_SESSION_REVOKED", false);
                    return;
                }
                if (!executionGateAllows(runId, () -> gate.requireStreamingProvider(
                        AiCapability.ASSISTANT, active.actor().userId()))) return;
                if (!currentContextAccessAllows(runId, active)) return;
                publish(store.completeRun(runId, safeOutput,
                        new ModelUsage(0, 0, ModelUsage.Source.ESTIMATED),
                        "deterministic", "knowledge-grounding-policy-v1", false,
                        active.grounded(), active.citations(), Set.copyOf(active.actor().permissionCodes())));
                outcome = "SUCCEEDED";
                return;
            }
            ModelRequest request = ModelRequest.roleSeparated(
                    AiCapability.ASSISTANT,
                    store.pinnedSystemPrompt(runId),
                    active.userPrompt(),
                    List.of(new ModelRequest.UntrustedContent(
                            active.untrustedContextKind(), active.untrustedContext())),
                    active.allowedToolIds(), null, java.time.Duration.ofSeconds(60), 4_096,
                    new ModelRequest.BillingTrace(runId, 1, active.actor().actor()));
            if (!executionGateAllows(runId, () -> gate.requireStreamingProvider(
                    AiCapability.ASSISTANT, active.actor().userId()))) return;
            if (!currentContextAccessAllows(runId, active)) return;
            AiEventStream stream = modelRuntime.stream(request);
            active.execution().registerStream(stream);
            if (active.execution().cancelled()) {
                outcome = "CANCELLED";
                return;
            }
            StringBuilder output = new StringBuilder();
            int[] outputCodePoints = new int[]{0};
            PiiStreamingRedactor streamingRedactor = crypto.streamingRedactor("assistant-output", 64);
            AtomicReference<ModelUsage> usage = new AtomicReference<>(new ModelUsage(0, 0, ModelUsage.Source.ESTIMATED));
            stream.consume(event -> consumeEvent(
                    runId, active, event, output, outputCodePoints, streamingRedactor, usage));
            if (!executionGateAllows(runId, () -> gate.requireStreamingProvider(
                    AiCapability.ASSISTANT, active.actor().userId()))) {
                stream.cancel();
                return;
            }
            emitSafeDelta(runId, active, output, streamingRedactor.finish());
            if (active.execution().cancelled()) {
                outcome = "CANCELLED";
                return;
            }
            if (stream.isCancelled()) {
                safeFail(runId, "AI_PROVIDER_CANCELLED", true);
                return;
            }
            if (!actorResolver.stillValid(active.actor(), "ai:assistant:use")) {
                safeFail(runId, "AI_SESSION_REVOKED", false);
                return;
            }
            if (!currentContextAccessAllows(runId, active)) return;
            publish(store.completeRun(runId, output.toString(), usage.get(),
                    modelRuntime.providerCode(), modelRuntime.modelAlias(), true,
                    false, List.of(), Set.copyOf(active.actor().permissionCodes())));
            outcome = "SUCCEEDED";
        } catch (SensitiveDataBlockedException exception) {
            AiEventStream stream = active.execution().stream();
            if (stream != null) stream.cancel();
            safeFail(runId, "AI_SENSITIVE_OUTPUT_BLOCKED", false);
        } catch (ContextAccessTerminatedException ignored) {
            // currentContextAccessAllows 已经终止 run；此处只阻止底层流继续传播异常。
        } catch (AiApiException exception) {
            AiEventStream stream = active.execution().stream();
            if (stream != null) stream.cancel();
            if (!active.execution().cancelled()) {
                safeFail(runId, exception.errorCode(), exception.retryable());
            }
        } catch (RuntimeException exception) {
            if (!active.execution().cancelled()) {
                boolean timedOut = isProviderTimeout(exception);
                safeFail(runId, timedOut ? "AI_PROVIDER_TIMEOUT" : "AI_PROVIDER_UNAVAILABLE", true);
            }
        } finally {
            activeExecutions.remove(runId, active.execution());
            recordObservation("ASSISTANT", modelRuntime.providerCode(), outcome, startedNanos);
        }
    }

    private void consumeEvent(
            String runId,
            ActiveRun active,
            AiStreamEvent event,
            StringBuilder output,
            int[] outputCodePoints,
            PiiStreamingRedactor streamingRedactor,
            AtomicReference<ModelUsage> usage) {
        switch (event.type()) {
            case TEXT_DELTA -> {
                outputCodePoints[0] += event.textDelta().codePointCount(0, event.textDelta().length());
                if (outputCodePoints[0] > 16_384) {
                    throw new IllegalStateException("模型输出超过安全上限");
                }
                emitSafeDelta(runId, active, output, streamingRedactor.accept(event.textDelta()));
            }
            case USAGE -> usage.set(event.usage());
            case CANCELLED -> { }
            case STARTED, COMPLETED -> { }
        }
    }

    private void emitSafeDelta(String runId, ActiveRun active, StringBuilder output, String safeDelta) {
        if (safeDelta == null || safeDelta.isEmpty()) return;
        if (active.execution().cancelled()) return;
        if (!actorResolver.stillValid(active.actor(), "ai:assistant:use")) {
            AiEventStream stream = active.execution().stream();
            if (stream != null) stream.cancel();
            throw new IllegalStateException("AI session revoked");
        }
        if (!currentContextAccessAllows(runId, active)) {
            AiEventStream stream = active.execution().stream();
            if (stream != null) stream.cancel();
            throw new ContextAccessTerminatedException();
        }
        output.append(safeDelta);
        publish(store.appendDelta(runId, safeDelta));
    }

    private boolean currentContextAccessAllows(String runId, ActiveRun active) {
        try {
            requireCurrentConversationAccess(active.actor(), active.conversation());
            return true;
        } catch (AiApiException denied) {
            if ("AI_RESOURCE_NOT_FOUND".equals(denied.errorCode())) {
                safeFail(runId, "AI_CONTEXT_ACCESS_REVOKED", false);
            } else {
                safeFail(runId, denied.errorCode(), denied.retryable());
            }
            return false;
        } catch (RuntimeException failure) {
            safeFail(runId, "AI_CONTEXT_RECHECK_FAILED", true);
            return false;
        }
    }

    private void safeFail(String runId, String code, boolean retryable) {
        try {
            publish(store.failRun(runId, code, retryable));
        } catch (RuntimeException failure) {
            store.forceFailWithoutEvent(runId, code);
        }
    }

    private boolean executionGateAllows(String runId, Runnable gateCheck) {
        try {
            gateCheck.run();
            return true;
        } catch (AiApiException denied) {
            safeFail(runId, denied.errorCode(), denied.retryable());
            return false;
        }
    }

    private static boolean isProviderTimeout(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof AiResiliencePolicy.UpstreamTimeoutException
                    || current instanceof java.util.concurrent.TimeoutException) return true;
        }
        return false;
    }

    private void recordObservation(String capability, String provider, String outcome, long startedNanos) {
        try {
            observability.record(capability.toUpperCase(Locale.ROOT), provider.toUpperCase(Locale.ROOT), outcome,
                    java.time.Duration.ofNanos(Math.max(0, System.nanoTime() - startedNanos)));
        } catch (RuntimeException ignored) {
            // 观测系统不可反向影响 AI 终态；告警由 Actuator/MeterRegistry 自身健康检查承担。
        }
    }

    private void publish(RunMutation mutation) {
        if (!mutation.changed()) return;
        mutation.events().forEach(eventPublisher::publish);
    }

    private void requireQueued(String runId) {
        RunMutation queued = store.markQueued(runId);
        if (!queued.changed()) {
            throw new IllegalStateException("新建 AI run 无法进入 QUEUED");
        }
        publish(queued);
    }

    private String normalizeSurface(String surface) {
        String normalized = surface == null ? "" : surface.trim().toUpperCase(Locale.ROOT);
        if (!SURFACES.contains(normalized)) {
            throw new AiApiException(HttpStatus.BAD_REQUEST, "AI_INVALID_SURFACE", "AI surface 不合法", false);
        }
        return normalized;
    }

    private void requireClientRequestId(String clientRequestId) {
        if (clientRequestId == null || clientRequestId.isBlank() || clientRequestId.length() > 128) {
            throw new AiApiException(HttpStatus.BAD_REQUEST, "AI_INVALID_CLIENT_REQUEST_ID",
                    "clientRequestId 不合法", false);
        }
    }

    private void requireUuid(String value) {
        try {
            UUID.fromString(value);
        } catch (Exception exception) {
            throw new AiApiException(HttpStatus.BAD_REQUEST, "AI_INVALID_RESOURCE_ID", "资源 ID 必须是 UUID", false);
        }
    }

    private void validatePage(long page, long pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new AiApiException(HttpStatus.BAD_REQUEST, "AI_INVALID_PAGE", "分页参数不合法", false);
        }
    }

    private void requireCurrentConversationAccess(AiActorContext actor, Conversation conversation) {
        if (!hasCurrentConversationAccess(actor, conversation)) throw AiApiException.notFound();
    }

    private void requireCurrentContextAccess(
            AiActorContext actor,
            String surface,
            String contextType,
            Long contextId) {
        try {
            requireContextResolvers().authorizeCurrentAccess(actor, surface, contextType, contextId);
        } catch (RuntimeException failure) {
            if (isCurrentAccessDenied(failure)) throw AiApiException.notFound();
            throw failure;
        }
    }

    private ContextResolverRegistry.Resolution resolveCurrentContext(
            AiActorContext actor,
            String surface,
            String contextType,
            Long contextId) {
        try {
            return requireContextResolvers().resolve(actor, surface, contextType, contextId, null);
        } catch (RuntimeException failure) {
            if (isCurrentAccessDenied(failure)) throw AiApiException.notFound();
            throw failure;
        }
    }

    private void requireRunConversationAccess(AiActorContext actor, Run run) {
        if (runAccessAuthorizer != null) {
            runAccessAuthorizer.requireCurrentAccess(actor, run);
            return;
        }
        if (!AiCapability.ASSISTANT.name().equals(run.capability())) return;
        if (run.conversationId() == null || run.conversationId().isBlank()) {
            throw AiApiException.notFound();
        }
        Conversation conversation = store.conversationDetail(actor.userId(), run.conversationId()).conversation();
        requireCurrentConversationAccess(actor, conversation);
    }

    private boolean hasCurrentConversationAccess(AiActorContext actor, Conversation conversation) {
        try {
            requireCurrentContextAccess(actor, conversation.surface(),
                    conversation.contextType(), conversation.contextId());
            return true;
        } catch (RuntimeException failure) {
            if (isCurrentAccessDenied(failure)) return false;
            throw failure;
        }
    }

    private static boolean isCurrentAccessDenied(RuntimeException failure) {
        if (failure instanceof SecurityException) return true;
        if (failure instanceof AiApiException aiFailure) {
            return isAccessDeniedStatus(aiFailure.status());
        }
        if (failure instanceof BusinessException businessFailure) {
            return isAccessDeniedStatus(businessFailure.getStatus());
        }
        return false;
    }

    private static boolean isAccessDeniedStatus(HttpStatus status) {
        return status == HttpStatus.UNAUTHORIZED
                || status == HttpStatus.FORBIDDEN
                || status == HttpStatus.NOT_FOUND;
    }

    private String permissionFor(AiCapability capability) {
        return switch (capability) {
            case ASSISTANT -> "ai:assistant:use";
            case KNOWLEDGE -> "ai:knowledge:read";
            case DASHBOARD -> "ai:dashboard:query";
            case REPAIR -> "ai:repair:triage";
            case NOTICE -> "ai:notice:draft";
            case RISK -> "ai:risk:read";
            case EVALUATION -> "ai:eval:run";
        };
    }

    private static String auditBusinessPermission(AiCapability capability) {
        return switch (capability) {
            case DASHBOARD -> "dashboard:read";
            case REPAIR -> "repair:read";
            case NOTICE -> "notice:read";
            default -> null;
        };
    }

    private static String normalizeAuditReason(String reason) {
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.length() < 10 || normalized.length() > 500
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new AiApiException(HttpStatus.BAD_REQUEST, "AI_AUDIT_REASON_REQUIRED",
                    "审计正文读取理由需为 10-500 字的纯文本", false);
        }
        return normalized;
    }

    private ContextResolverRegistry requireContextResolvers() {
        if (contextResolvers == null) {
            throw new AiApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_CONTEXT_RESOLVER_UNAVAILABLE",
                    "AI 上下文解析器不可用", true);
        }
        return contextResolvers;
    }

    private String contextKind(ContextResolverRegistry.Resolution resolution) {
        return resolution.citations().isEmpty() ? "PAGE_CONTEXT_JSON" : "RAG_CONTEXT_JSON";
    }

    @FunctionalInterface
    public interface CommandWork {
        Object execute(AiActorContext actor, String runPublicId);
    }

    private record ActiveRun(
            AiActorContext actor,
            Conversation conversation,
            String userPrompt,
            String untrustedContext,
            String untrustedContextKind,
            Set<String> allowedToolIds,
            String directResponse,
            boolean grounded,
            List<AiRunRecords.CitationCandidate> citations,
            ActiveExecution execution) { }

    private static final class ContextAccessTerminatedException extends RuntimeException {
        private ContextAccessTerminatedException() {
            super(null, null, false, false);
        }
    }

    private static final class ActiveExecution {
        private final AtomicReference<AiEventStream> stream = new AtomicReference<>();
        private final AtomicReference<Future<?>> task = new AtomicReference<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private void registerTask(Future<?> future) {
            task.set(future);
            if (cancelled.get()) future.cancel(true);
        }

        private void registerStream(AiEventStream eventStream) {
            stream.set(eventStream);
            if (cancelled.get()) eventStream.cancel();
        }

        private AiEventStream stream() {
            return stream.get();
        }

        private boolean cancelled() {
            return cancelled.get();
        }

        private void cancel() {
            cancelled.set(true);
            AiEventStream eventStream = stream.get();
            if (eventStream != null) eventStream.cancel();
            Future<?> future = task.get();
            if (future != null) future.cancel(true);
        }
    }
}
