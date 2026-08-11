package com.example.dormitory.ai.approval;

import com.example.dormitory.ai.domain.model.BusinessExecutionActor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class ActionProposal {

    private static final Pattern FORBIDDEN_PAYLOAD_KEY = Pattern.compile(
            "(?i)\"(?:sql|url|endpoint|class|method|tool|bean|script|command)\"\\s*:");

    private final String publicId;
    private final ActionType actionType;
    private final String canonicalPayload;
    private final String payloadHash;
    private final String businessSnapshotHash;
    private final String requiredBusinessPermission;
    private final int requiredApprovalCount;
    private final Instant expiresAt;
    private ProposalState state = ProposalState.PENDING_APPROVAL;
    private int version;
    private final List<ApprovalEvent> approvals = new ArrayList<>();
    private final Map<String, IdempotencyEntry> idempotency = new LinkedHashMap<>();
    private ExecutionLease executionLease;

    private ActionProposal(
            String publicId,
            ActionType actionType,
            String payloadText,
            String payloadHash,
            String businessSnapshotHash,
            String requiredBusinessPermission,
            int requiredApprovalCount,
            Instant expiresAt) {
        if (blank(publicId) || actionType == null || blank(payloadText) || blank(payloadHash)
                || blank(businessSnapshotHash) || blank(requiredBusinessPermission)
                || requiredApprovalCount < 1 || expiresAt == null) {
            throw new IllegalArgumentException("提案合同不完整");
        }
        if (!ActionType.allowlisted().contains(actionType)) throw new IllegalArgumentException("动作不在白名单");
        this.canonicalPayload = CanonicalJsonHasher.canonicalize(payloadText);
        if (FORBIDDEN_PAYLOAD_KEY.matcher(canonicalPayload).find()) {
            throw new IllegalArgumentException("提案 payload 含动态执行字段");
        }
        this.publicId = publicId;
        this.actionType = actionType;
        this.payloadHash = payloadHash;
        this.businessSnapshotHash = businessSnapshotHash;
        this.requiredBusinessPermission = requiredBusinessPermission;
        this.requiredApprovalCount = requiredApprovalCount;
        this.expiresAt = expiresAt;
    }

    public static ActionProposal pending(
            String publicId,
            ActionType actionType,
            String payloadText,
            String payloadHash,
            String businessSnapshotHash,
            String requiredBusinessPermission,
            int requiredApprovalCount,
            Instant expiresAt) {
        return new ActionProposal(publicId, actionType, payloadText, payloadHash, businessSnapshotHash,
                requiredBusinessPermission, requiredApprovalCount, expiresAt);
    }

    public synchronized ApprovalDecision approve(
            int expectedVersion,
            String submittedPayloadHash,
            String submittedSnapshotHash,
            String currentBusinessSnapshotHash,
            BusinessExecutionActor actor,
            Set<String> currentPermissions,
            String idempotencyKey,
            String requestHash,
            String commentRedacted) {
        requireActor(actor);
        String scope = actor.userId() + "|APPROVE|" + publicId + "|" + idempotencyKey;
        IdempotencyEntry replay = idempotency.get(scope);
        if (replay != null) {
            if (!replay.requestHash().equals(requestHash)) {
                throw new IdempotencyConflictException("相同幂等键对应不同审批 payload");
            }
            return replay.decision();
        }
        requirePermission(currentPermissions);
        if (state != ProposalState.PENDING_APPROVAL) {
            throw new ProposalConflictException("AI_PROPOSAL_STATE_CONFLICT", "提案不在待审批状态");
        }
        if (Instant.now().isAfter(expiresAt)) {
            state = ProposalState.EXPIRED;
            version++;
            throw new ProposalConflictException("AI_PROPOSAL_EXPIRED", "提案已过期");
        }
        if (version != expectedVersion || !payloadHash.equals(submittedPayloadHash)
                || !businessSnapshotHash.equals(submittedSnapshotHash)) {
            throw new ProposalConflictException("AI_PROPOSAL_HASH_CONFLICT", "提案版本或 hash 不匹配");
        }
        if (!businessSnapshotHash.equals(currentBusinessSnapshotHash)) {
            state = ProposalState.STALE;
            version++;
            throw new ProposalConflictException("AI_PROPOSAL_STALE", "业务快照已变化");
        }
        if (hasApproved(actor)) {
            ApprovalDecision decision = currentApprovalDecision();
            idempotency.put(scope, new IdempotencyEntry(requestHash, decision));
            return decision;
        }
        approvals.add(new ApprovalEvent(actor.userId(), "APPROVE", payloadHash, businessSnapshotHash,
                idempotencyKey, requestHash, commentRedacted, Instant.now()));
        if (approvals.size() >= requiredApprovalCount) state = ProposalState.APPROVED;
        version++;
        ApprovalDecision decision = currentApprovalDecision();
        idempotency.put(scope, new IdempotencyEntry(requestHash, decision));
        return decision;
    }

    /** 同一审批人更换幂等键仍是无副作用重放，但当前授权与提案 CAS/hash 必须继续通过。 */
    public synchronized Optional<ApprovalDecision> duplicateReviewerApproval(
            int expectedVersion,
            String submittedPayloadHash,
            String submittedSnapshotHash,
            BusinessExecutionActor actor,
            Set<String> currentPermissions) {
        requireActor(actor);
        requirePermission(currentPermissions);
        if (state != ProposalState.PENDING_APPROVAL) {
            throw new ProposalConflictException("AI_PROPOSAL_STATE_CONFLICT", "提案不在待审批状态");
        }
        if (Instant.now().isAfter(expiresAt)) {
            return Optional.empty();
        }
        if (version != expectedVersion || !payloadHash.equals(submittedPayloadHash)
                || !businessSnapshotHash.equals(submittedSnapshotHash)) {
            throw new ProposalConflictException("AI_PROPOSAL_HASH_CONFLICT", "提案版本或 hash 不匹配");
        }
        return hasApproved(actor) ? Optional.of(currentApprovalDecision()) : Optional.empty();
    }

    public synchronized Optional<ApprovalDecision> approvalReplay(
            BusinessExecutionActor actor,
            String decision,
            String idempotencyKey,
            String requestHash) {
        requireActor(actor);
        String scope = actor.userId() + "|" + decision + "|" + publicId + "|" + idempotencyKey;
        IdempotencyEntry replay = idempotency.get(scope);
        if (replay == null) return Optional.empty();
        if (!replay.requestHash().equals(requestHash)) {
            throw new IdempotencyConflictException("相同幂等键对应不同审批 payload");
        }
        return Optional.of(replay.decision());
    }

    /** 定时生命周期只允许把尚未开始业务执行的到期提案转为 EXPIRED。 */
    public synchronized boolean expireIfDue(Instant now) {
        if (now == null) throw new IllegalArgumentException("提案过期检查时间不能为空");
        if (now.isBefore(expiresAt)
                || (state != ProposalState.PENDING_APPROVAL && state != ProposalState.APPROVED)) {
            return false;
        }
        state = ProposalState.EXPIRED;
        version++;
        return true;
    }

    public ApprovalDecision approve(
            int expectedVersion,
            String submittedPayloadHash,
            String submittedSnapshotHash,
            String currentBusinessSnapshotHash,
            BusinessExecutionActor actor,
            Set<String> currentPermissions,
            String idempotencyKey,
            String requestHash) {
        return approve(expectedVersion, submittedPayloadHash, submittedSnapshotHash,
                currentBusinessSnapshotHash, actor, currentPermissions, idempotencyKey, requestHash, null);
    }

    public synchronized ApprovalDecision reject(
            int expectedVersion,
            BusinessExecutionActor actor,
            Set<String> permissions,
            String idempotencyKey,
            String requestHash,
            String commentRedacted) {
        requireActor(actor);
        String scope = actor.userId() + "|REJECT|" + publicId + "|" + idempotencyKey;
        IdempotencyEntry replay = idempotency.get(scope);
        if (replay != null) {
            if (!replay.requestHash().equals(requestHash)) throw new IdempotencyConflictException("幂等 payload 冲突");
            return replay.decision();
        }
        requirePermission(permissions);
        if (state != ProposalState.PENDING_APPROVAL || version != expectedVersion) {
            throw new ProposalConflictException("AI_PROPOSAL_STATE_CONFLICT", "提案状态冲突");
        }
        approvals.add(new ApprovalEvent(actor.userId(), "REJECT", payloadHash, businessSnapshotHash,
                idempotencyKey, requestHash, commentRedacted, Instant.now()));
        state = ProposalState.REJECTED;
        version++;
        ApprovalDecision decision = new ApprovalDecision(state, version, approvals.size());
        idempotency.put(scope, new IdempotencyEntry(requestHash, decision));
        return decision;
    }

    public ApprovalDecision reject(
            int expectedVersion,
            BusinessExecutionActor actor,
            Set<String> permissions,
            String idempotencyKey,
            String requestHash) {
        return reject(expectedVersion, actor, permissions, idempotencyKey, requestHash, null);
    }

    public synchronized ExecutionLease acquireExecutionLease(BusinessExecutionActor actor) {
        requireActor(actor);
        if (executionLease != null) return null;
        if (state != ProposalState.APPROVED) {
            throw new ProposalConflictException("AI_EXECUTION_NOT_APPROVED", "提案尚未批准");
        }
        String tokenHash = CanonicalJsonHasher.sha256(UUID.randomUUID().toString());
        executionLease = new ExecutionLease(publicId, tokenHash, actor.userId(), 1, Instant.now());
        state = ProposalState.EXECUTING;
        version++;
        return executionLease;
    }

    public synchronized void markSucceeded(ExecutionLease lease) {
        requireLease(lease);
        if (state != ProposalState.EXECUTING) throw new IllegalStateException("执行前态不合法");
        state = ProposalState.SUCCEEDED;
        version++;
    }

    public synchronized void markFailed(ExecutionLease lease) {
        requireLease(lease);
        if (state != ProposalState.EXECUTING) throw new IllegalStateException("执行前态不合法");
        state = ProposalState.FAILED;
        version++;
    }

    public synchronized void markNeedsReview(ExecutionLease lease) {
        requireLease(lease);
        if (state != ProposalState.EXECUTING) throw new IllegalStateException("执行前态不合法");
        state = ProposalState.NEEDS_REVIEW;
        version++;
    }

    public synchronized ExecutionLease resumeAfterReconfirmation(
            int expectedVersion, BusinessExecutionActor actor, Set<String> permissions) {
        requireActor(actor);
        requirePermission(permissions);
        requireNeedsReviewVersion(expectedVersion);
        if (executionLease == null) throw new IllegalStateException("缺少原 execution lease");
        executionLease = new ExecutionLease(publicId,
                CanonicalJsonHasher.sha256(UUID.randomUUID().toString()), actor.userId(),
                executionLease.leaseVersion() + 1, Instant.now());
        state = ProposalState.EXECUTING;
        version++;
        return executionLease;
    }

    public synchronized void validateReconfirmation(
            int expectedVersion, BusinessExecutionActor actor, Set<String> permissions) {
        requireActor(actor);
        requirePermission(permissions);
        requireNeedsReviewVersion(expectedVersion);
    }

    public synchronized void confirmRecoveredResult(
            int expectedVersion, BusinessExecutionActor actor, Set<String> permissions) {
        requireActor(actor);
        requirePermission(permissions);
        requireNeedsReviewVersion(expectedVersion);
        markReconfirmed(actor);
        state = ProposalState.SUCCEEDED;
        version++;
    }

    public synchronized void failAfterReconfirmation(
            int expectedVersion, BusinessExecutionActor actor, Set<String> permissions) {
        requireActor(actor);
        requirePermission(permissions);
        requireNeedsReviewVersion(expectedVersion);
        markReconfirmed(actor);
        state = ProposalState.FAILED;
        version++;
    }

    private void markReconfirmed(BusinessExecutionActor actor) {
        if (executionLease == null) throw new IllegalStateException("缺少原 execution lease");
        executionLease = new ExecutionLease(publicId, executionLease.leaseTokenHash(), actor.userId(),
                executionLease.leaseVersion() + 1, executionLease.acquiredAt());
    }

    private void requireNeedsReviewVersion(int expectedVersion) {
        if (state != ProposalState.NEEDS_REVIEW || version != expectedVersion) {
            throw new ProposalConflictException("AI_EXECUTION_RECONFIRM_CONFLICT", "执行对账状态或版本冲突");
        }
    }

    private void requirePermission(Set<String> currentPermissions) {
        if (currentPermissions == null || !currentPermissions.contains("ai:approval:review")
                || !currentPermissions.contains(requiredBusinessPermission)) {
            throw new SecurityException("缺少 AI 审批或目标业务权限");
        }
    }

    private void requireActor(BusinessExecutionActor actor) {
        if (actor == null) throw new IllegalArgumentException("审批/执行人必须是真实 USER actor");
    }

    private void requireLease(ExecutionLease lease) {
        if (lease == null || executionLease == null || !executionLease.leaseTokenHash().equals(lease.leaseTokenHash())) {
            throw new SecurityException("execution lease 不匹配");
        }
    }

    private boolean hasApproved(BusinessExecutionActor actor) {
        return approvals.stream().anyMatch(event -> event.reviewerUserId() == actor.userId()
                && "APPROVE".equals(event.decision()));
    }

    private ApprovalDecision currentApprovalDecision() {
        return new ApprovalDecision(state, version, approvals.size());
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public synchronized ProposalState state() {
        return state;
    }

    public synchronized int version() {
        return version;
    }

    public synchronized List<ApprovalEvent> approvals() {
        return List.copyOf(approvals);
    }

    public synchronized ExecutionLease executionLease() {
        return executionLease;
    }

    public String publicId() {
        return publicId;
    }

    public String requiredBusinessPermission() {
        return requiredBusinessPermission;
    }

    public int requiredApprovalCount() {
        return requiredApprovalCount;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public String payloadHash() {
        return payloadHash;
    }

    public String businessSnapshotHash() {
        return businessSnapshotHash;
    }

    public String canonicalPayload() {
        return canonicalPayload;
    }

    public ActionType actionType() {
        return actionType;
    }

    public static ActionProposal restore(
            String publicId,
            ActionType actionType,
            String canonicalPayload,
            String payloadHash,
            String businessSnapshotHash,
            String requiredBusinessPermission,
            int requiredApprovalCount,
            Instant expiresAt,
            ProposalState state,
            int version,
            List<ApprovalEvent> approvals,
            List<RestoredIdempotency> idempotencyEntries,
            ExecutionLease executionLease) {
        ActionProposal proposal = new ActionProposal(publicId, actionType, canonicalPayload, payloadHash,
                businessSnapshotHash, requiredBusinessPermission, requiredApprovalCount, expiresAt);
        proposal.state = java.util.Objects.requireNonNull(state);
        proposal.version = version;
        proposal.approvals.addAll(approvals == null ? List.of() : approvals);
        if (idempotencyEntries != null) {
            for (RestoredIdempotency entry : idempotencyEntries) {
                String scope = entry.actorUserId() + "|" + entry.decision() + "|" + publicId + "|"
                        + entry.idempotencyKey();
                proposal.idempotency.put(scope, new IdempotencyEntry(entry.requestHash(), entry.response()));
            }
        }
        proposal.executionLease = executionLease;
        return proposal;
    }

    public record ApprovalEvent(
            long reviewerUserId,
            String decision,
            String payloadHash,
            String snapshotHash,
            String idempotencyKey,
            String requestHash,
            String commentRedacted,
            Instant occurredAt) {
    }

    public record RestoredIdempotency(
            long actorUserId,
            String decision,
            String idempotencyKey,
            String requestHash,
            ApprovalDecision response) {
    }

    public record ApprovalDecision(ProposalState state, int proposalVersion, int approvalCount) {
    }

    public record ExecutionLease(
            String proposalPublicId,
            String leaseTokenHash,
            long executedByUserId,
            int leaseVersion,
            Instant acquiredAt) {
    }

    private record IdempotencyEntry(String requestHash, ApprovalDecision decision) {
    }
}
