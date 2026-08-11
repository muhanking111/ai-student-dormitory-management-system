package com.example.dormitory.ai.application.run;

import com.example.dormitory.common.PageResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class AiRunRecords {

    private AiRunRecords() {
    }

    public record Conversation(
            String id,
            String surface,
            String contextType,
            Long contextId,
            String status,
            String title,
            Instant lastMessageAt,
            Instant createdAt) {
    }

    public record Message(
            String id,
            String role,
            String text,
            String classification,
            Instant createdAt,
            String runId,
            String runState,
            Instant asOf,
            List<String> citationIds) {
        public Message {
            citationIds = citationIds == null ? List.of() : List.copyOf(citationIds);
        }

        public Message(String id, String role, String text, String classification, Instant createdAt) {
            this(id, role, text, classification, createdAt, null, null, null, List.of());
        }
    }

    public record ConversationDetail(Conversation conversation, List<Message> messages) {
        public ConversationDetail { messages = List.copyOf(messages); }
    }

    public record Run(
            String id,
            String parentRunId,
            String conversationId,
            long ownerUserId,
            String capability,
            String state,
            String permissionDigest,
            String sessionFingerprintHash,
            String promptVersion,
            Instant createdAt,
            Instant finishedAt,
            String failureCode) {
        public Run(
                String id, String conversationId, long ownerUserId, String capability, String state,
                String permissionDigest, String sessionFingerprintHash, String promptVersion,
                Instant createdAt, Instant finishedAt, String failureCode) {
            this(id, null, conversationId, ownerUserId, capability, state, permissionDigest,
                    sessionFingerprintHash, promptVersion, createdAt, finishedAt, failureCode);
        }
    }

    public record RunCreation(Run run, boolean replayed) {
    }

    public record RetrySource(
            Run parentRun,
            Conversation conversation,
            String inputRedacted,
            String classification,
            String requestHash) {
        public RetrySource {
            if (parentRun == null || conversation == null || blank(inputRedacted)
                    || blank(classification) || requestHash == null
                    || !requestHash.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("AI retry source 不完整");
            }
        }
    }

    public record Event(
            String eventId,
            String runId,
            long sequence,
            String type,
            Instant timestamp,
            Map<String, Object> payload) {
        public Event { payload = Map.copyOf(payload); }
    }

    public record CitationCandidate(
            String sourcePublicId,
            String documentVersionPublicId,
            String chunkPublicId,
            String label,
            String locator,
            String quoteRedacted,
            String contentHash,
            int rank,
            BigDecimal score) {
        public CitationCandidate {
            if (sourcePublicId == null || sourcePublicId.isBlank()
                    || documentVersionPublicId == null || documentVersionPublicId.isBlank()
                    || label == null || label.isBlank() || locator == null || locator.isBlank()
                    || quoteRedacted == null || quoteRedacted.isBlank()
                    || contentHash == null || !contentHash.matches("[0-9a-fA-F]{64}")
                    || rank < 1 || rank > 20) {
                throw new IllegalArgumentException("AI citation candidate 不合法");
            }
        }
    }

    public record RetrievalTrace(
            String queryHash,
            String retrievalPolicyVersion,
            String retrievalMode,
            String indexCode,
            String indexVersion,
            String embeddingModelVersion,
            String filterRedacted,
            int topK,
            int aclPreFilterCount,
            int aclPostFilterCount,
            int returnedCount,
            long latencyMs,
            String state) {
        public RetrievalTrace {
            if (queryHash == null || !queryHash.matches("[0-9a-fA-F]{64}")
                    || blank(retrievalPolicyVersion) || blank(retrievalMode)
                    || blank(indexCode) || blank(indexVersion) || blank(embeddingModelVersion)
                    || blank(filterRedacted) || topK < 1 || topK > 20
                    || aclPreFilterCount < 0 || aclPostFilterCount < 0 || returnedCount < 0
                    || aclPostFilterCount > aclPreFilterCount || returnedCount > aclPostFilterCount
                    || latencyMs < 0 || blank(state)) {
                throw new IllegalArgumentException("AI retrieval trace 不合法");
            }
            queryHash = queryHash.toLowerCase(java.util.Locale.ROOT);
        }
    }

    public record TracedCommandResult(
            Object responseBody,
            RetrievalTrace retrievalTrace,
            List<CitationCandidate> citations) {
        public TracedCommandResult(Object responseBody, RetrievalTrace retrievalTrace) {
            this(responseBody, retrievalTrace, List.of());
        }

        public TracedCommandResult {
            if (responseBody == null || retrievalTrace == null) {
                throw new IllegalArgumentException("带检索轨迹的 command 结果不完整");
            }
            citations = citations == null ? List.of() : List.copyOf(citations);
        }
    }

    public record RunMutation(boolean changed, List<Event> events) {
        public RunMutation { events = List.copyOf(events); }
        public static RunMutation unchanged() { return new RunMutation(false, List.of()); }
    }

    public record AuditRun(
            String id,
            String parentRunId,
            String capability,
            String state,
            String providerAlias,
            String promptVersion,
            long citationCount,
            String chainHash,
            long inputTokens,
            long outputTokens,
            BigDecimal estimatedCost,
            String failureCode,
            Instant createdAt,
            Instant finishedAt) {
        public AuditRun(
                String id, String parentRunId, String capability, String state, String providerAlias,
                String promptVersion, long inputTokens, long outputTokens, BigDecimal estimatedCost,
                String failureCode, Instant createdAt, Instant finishedAt) {
            this(id, parentRunId, capability, state, providerAlias, promptVersion, 0, null,
                    inputTokens, outputTokens, estimatedCost, failureCode, createdAt, finishedAt);
        }

        public AuditRun(
                String id, String capability, String state, String providerAlias, String promptVersion,
                long inputTokens, long outputTokens, BigDecimal estimatedCost, String failureCode,
                Instant createdAt, Instant finishedAt) {
            this(id, null, capability, state, providerAlias, promptVersion, 0, null,
                    inputTokens, outputTokens, estimatedCost, failureCode, createdAt, finishedAt);
        }
    }

    public record AuditRunFilter(
            Instant from,
            Instant to,
            String capability,
            String state,
            String providerCode) {
        private static final java.util.Set<String> STATES = java.util.Set.of(
                "ACCEPTED", "QUEUED", "RUNNING", "STREAMING", "SUCCEEDED", "DEGRADED",
                "FAILED", "TIMED_OUT", "CANCELLED", "NEEDS_RECONCILIATION");

        public AuditRunFilter {
            if (from != null && to != null && from.isAfter(to)) {
                throw new IllegalArgumentException("审计开始时间不能晚于结束时间");
            }
            if (from != null && to != null
                    && java.time.Duration.between(from, to).compareTo(java.time.Duration.ofDays(366)) > 0) {
                throw new IllegalArgumentException("审计筛选时间跨度不能超过 366 天");
            }
            if (capability != null) {
                capability = capability.trim().toUpperCase(java.util.Locale.ROOT);
                try {
                    com.example.dormitory.ai.domain.model.AiCapability.valueOf(capability);
                } catch (RuntimeException exception) {
                    throw new IllegalArgumentException("审计 capability 不合法", exception);
                }
            }
            if (state != null) {
                state = state.trim().toUpperCase(java.util.Locale.ROOT);
                if (!STATES.contains(state)) throw new IllegalArgumentException("审计状态不合法");
            }
            if (providerCode != null) {
                providerCode = providerCode.trim().toLowerCase(java.util.Locale.ROOT);
                if (!providerCode.matches("[a-z0-9][a-z0-9-]{1,31}")) {
                    throw new IllegalArgumentException("审计 provider 不合法");
                }
            }
        }

        public static AuditRunFilter none() {
            return new AuditRunFilter(null, null, null, null, null);
        }
    }

    public record AuditStep(long sequence, String type, Instant occurredAt) {
    }

    public record AuditRetrievalTrace(
            String id,
            String queryHash,
            String retrievalPolicyVersion,
            String retrievalMode,
            String indexCode,
            String indexVersion,
            String embeddingModelVersion,
            int topK,
            int aclPreFilterCount,
            int aclPostFilterCount,
            int returnedCount,
            long latencyMs,
            String state,
            Instant occurredAt) {
    }

    public record AuditToolCall(
            String id,
            long sequence,
            String toolName,
            String toolVersion,
            String authorizationDecision,
            String state,
            String errorCode,
            Instant startedAt,
            Instant finishedAt) {
    }

    public record AuditCitation(
            String id,
            String citationType,
            String documentVersionId,
            String chunkId,
            String metricId,
            int rank,
            BigDecimal score,
            String contentHash,
            Instant createdAt) {
    }

    public record AuditProposal(
            String id,
            String actionType,
            String targetType,
            String payloadHash,
            String businessSnapshotHash,
            String approvalPolicyVersion,
            int requiredApprovalCount,
            int approvedCount,
            String riskLevel,
            String state,
            long proposerUserId,
            Instant expiresAt,
            Instant createdAt) {
    }

    public record AuditApproval(
            String proposalId,
            long proposalVersion,
            String decision,
            long reviewerUserId,
            String payloadHash,
            String businessSnapshotHash,
            Instant createdAt) {
    }

    public record AuditExecution(
            String id,
            String proposalId,
            String state,
            long version,
            String handlerName,
            long executedByUserId,
            Long reconfirmedByUserId,
            String resultResourceType,
            String errorCode,
            Instant startedAt,
            Instant finishedAt) {
    }

    public record AuditUsage(
            int requestSequence,
            int attempt,
            String requestKind,
            String actorKind,
            Long actorUserId,
            String servicePrincipalCode,
            Long initiatedByUserId,
            String capability,
            String providerCode,
            String modelName,
            long inputTokens,
            long outputTokens,
            BigDecimal costAmount,
            String currency,
            String usageSource,
            Instant occurredAt) {
    }

    public record AuditHashEvent(
            String id,
            String chainScope,
            String aggregateType,
            String aggregatePublicId,
            long sequence,
            String eventType,
            String actorKind,
            Long actorUserId,
            String servicePrincipalCode,
            Long initiatedByUserId,
            Long effectiveSubjectUserId,
            String payloadHash,
            String previousEventHash,
            String eventHash,
            String integrityAlgorithm,
            int integrityKeyVersion,
            String canonicalizationVersion,
            String correlationId,
            Instant occurredAt) {
    }

    public record AuditRunDetail(
            AuditRun run,
            List<AuditStep> steps,
            List<AuditRetrievalTrace> retrievals,
            List<AuditToolCall> tools,
            List<AuditCitation> citations,
            List<AuditProposal> proposals,
            List<AuditApproval> approvals,
            List<AuditExecution> executions,
            List<AuditUsage> usage,
            List<AuditHashEvent> hashChain) {
        public AuditRunDetail {
            steps = List.copyOf(steps);
            retrievals = List.copyOf(retrievals);
            tools = List.copyOf(tools);
            citations = List.copyOf(citations);
            proposals = List.copyOf(proposals);
            approvals = List.copyOf(approvals);
            executions = List.copyOf(executions);
            usage = List.copyOf(usage);
            hashChain = List.copyOf(hashChain);
        }

        public AuditRunDetail(AuditRun run, List<AuditStep> steps, List<AuditRetrievalTrace> retrievals) {
            this(run, steps, retrievals, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }

    public record AuditContentMessage(
            String id,
            String role,
            String content,
            String classification,
            Instant createdAt) {
    }

    public record AuditRunContent(String runId, List<AuditContentMessage> messages) {
        public AuditRunContent { messages = List.copyOf(messages); }
    }

    public record AuditAuthorizationScope(
            long ownerUserId,
            String surface,
            String contextType,
            Long contextId,
            List<String> citationDocumentVersionIds) {
        public AuditAuthorizationScope {
            if (ownerUserId < 1) throw new IllegalArgumentException("ownerUserId must be positive");
            citationDocumentVersionIds = citationDocumentVersionIds == null
                    ? List.of() : List.copyOf(citationDocumentVersionIds);
        }
    }

    public record Readiness(
            boolean auditWritable,
            boolean promptActive,
            boolean toolCatalogActive,
            boolean budgetConfigured) {
    }

    public record ConversationPage(PageResponse<Conversation> page) {
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
