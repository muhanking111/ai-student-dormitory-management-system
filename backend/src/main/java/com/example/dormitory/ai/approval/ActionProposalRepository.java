package com.example.dormitory.ai.approval;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ActionProposalRepository {

    CreateResult createOrReplay(StoredProposal proposal, ProposalOrigin origin, CreateRequest request);

    Optional<StoredProposal> findCreateReplay(ProposalOrigin origin, CreateRequest request);

    void update(StoredProposal proposal);

    Optional<StoredProposal> findByPublicId(String publicId);

    Optional<StoredProposal> findByExecutionPublicId(String executionPublicId);

    List<StoredProposal> findByState(ProposalState state, int offset, int limit);

    List<StoredProposal> findByStateAndActionType(
            ProposalState state, ActionType actionType, int offset, int limit);

    long countByState(ProposalState state);

    long countByStateAndActionType(ProposalState state, ActionType actionType);

    /** 锁定并过期尚未执行的到期 proposal，返回本次实际迁移的记录。 */
    List<StoredProposal> expireDue(Instant now);

    /** 将超时且结果不确定的执行转为人工对账，绝不自动重放业务写。 */
    List<String> markStaleExecutionsNeedsReview(Instant staleBefore);

    record CreateRequest(long actorUserId, String idempotencyKey, String requestHash, Instant expiresAt) {
        public CreateRequest {
            if (actorUserId < 1 || idempotencyKey == null || idempotencyKey.isBlank()
                    || idempotencyKey.length() > 128
                    || requestHash == null || !requestHash.matches("[0-9a-f]{64}")
                    || expiresAt == null) {
                throw new IllegalArgumentException("提案创建幂等合同不合法");
            }
        }
    }

    record CreateResult(StoredProposal proposal, boolean replayed) {
        public CreateResult {
            proposal = java.util.Objects.requireNonNull(proposal);
        }
    }

    final class StoredProposal {
        private final ActionProposal proposal;
        private final String runId;
        private final String targetType;
        private final Long targetResourceId;
        private final ProposalPreview preview;
        private final String riskLevel;
        private final long proposerUserId;
        private final Instant createdAt;
        private volatile com.example.dormitory.ai.port.ApprovedBusinessActionPort.BusinessActionResult result;
        private volatile String executionErrorCode;
        private volatile String executionErrorSummary;
        private volatile String executionPublicId;
        private volatile int persistedVersion;

        public StoredProposal(
                ActionProposal proposal,
                String runId,
                String targetType,
                Long targetResourceId,
                ProposalPreview preview,
                String riskLevel,
                long proposerUserId,
                Instant createdAt) {
            this.proposal = java.util.Objects.requireNonNull(proposal);
            this.runId = runId == null || runId.isBlank() ? null : java.util.UUID.fromString(runId).toString();
            this.targetType = java.util.Objects.requireNonNull(targetType);
            this.targetResourceId = targetResourceId;
            this.preview = java.util.Objects.requireNonNull(preview);
            this.riskLevel = java.util.Objects.requireNonNull(riskLevel);
            this.proposerUserId = proposerUserId;
            this.createdAt = java.util.Objects.requireNonNull(createdAt);
            this.persistedVersion = proposal.version();
        }

        public StoredProposal(
                ActionProposal proposal,
                String targetType,
                Long targetResourceId,
                ProposalPreview preview,
                String riskLevel,
                long proposerUserId,
                Instant createdAt) {
            this(proposal, null, targetType, targetResourceId, preview, riskLevel, proposerUserId, createdAt);
        }

        public StoredProposal(
                ActionProposal proposal,
                String runId,
                String targetType,
                Long targetResourceId,
                String legacyPreviewText,
                String riskLevel,
                long proposerUserId,
                Instant createdAt) {
            this(proposal, runId, targetType, targetResourceId, ProposalPreview.legacy(legacyPreviewText), riskLevel,
                    proposerUserId, createdAt);
        }

        public StoredProposal(
                ActionProposal proposal,
                String targetType,
                Long targetResourceId,
                String legacyPreviewText,
                String riskLevel,
                long proposerUserId,
                Instant createdAt) {
            this(proposal, null, targetType, targetResourceId, legacyPreviewText, riskLevel,
                    proposerUserId, createdAt);
        }

        public ActionProposal proposal() { return proposal; }
        public String runId() { return runId; }
        public String targetType() { return targetType; }
        public Long targetResourceId() { return targetResourceId; }
        public ProposalPreview preview() { return preview; }
        public String previewText() { return preview.toStoredJson(); }
        public String riskLevel() { return riskLevel; }
        public long proposerUserId() { return proposerUserId; }
        public Instant createdAt() { return createdAt; }
        public com.example.dormitory.ai.port.ApprovedBusinessActionPort.BusinessActionResult result() { return result; }
        public void result(com.example.dormitory.ai.port.ApprovedBusinessActionPort.BusinessActionResult value) {
            result = value;
        }
        public int persistedVersion() { return persistedVersion; }
        public void markPersisted() { persistedVersion = proposal.version(); }
        public void restoredResult(com.example.dormitory.ai.port.ApprovedBusinessActionPort.BusinessActionResult value) {
            result = value;
        }
        public String executionErrorCode() { return executionErrorCode; }
        public String executionErrorSummary() { return executionErrorSummary; }
        public void executionFailure(String code, String summary) {
            if (code == null || !code.matches("[A-Z0-9_]{1,64}")
                    || summary == null || summary.isBlank() || summary.length() > 500) {
                throw new IllegalArgumentException("execution failure 合同不合法");
            }
            executionErrorCode = code;
            executionErrorSummary = summary;
        }
        public void restoredExecutionFailure(String code, String summary) {
            executionErrorCode = code;
            executionErrorSummary = summary;
        }
        public void clearExecutionFailure() {
            executionErrorCode = null;
            executionErrorSummary = null;
        }
        public String executionPublicId() { return executionPublicId; }
        public void executionPublicId(String value) {
            String normalized;
            try {
                normalized = java.util.UUID.fromString(value).toString();
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("execution public ID 不合法", exception);
            }
            if (executionPublicId != null && !executionPublicId.equals(normalized)) {
                throw new IllegalStateException("execution public ID 不得替换");
            }
            executionPublicId = normalized;
        }
    }
}
