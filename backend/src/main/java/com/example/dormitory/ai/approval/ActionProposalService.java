package com.example.dormitory.ai.approval;

import com.example.dormitory.ai.domain.model.BusinessExecutionActor;
import com.example.dormitory.ai.port.AiAuditPort;
import com.example.dormitory.ai.port.ApprovedBusinessActionPort;
import com.example.dormitory.ai.security.FreshAuthorizationDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** 统一的“建议 - 预览 - 人工审批 - 现有 Service 执行”编排。 */
public final class ActionProposalService {

    private final ActionProposalRepository repository;
    private final ApprovedBusinessActionPort actionPort;
    private final BusinessSnapshotProvider snapshotProvider;
    private final AiAuditPort auditPort;
    private final BooleanSupplier writeExecutionEnabled;
    private final TransactionRunner transactions;
    private final FreshAuthorization freshAuthorization;

    public ActionProposalService(
            ActionProposalRepository repository,
            ApprovedBusinessActionPort actionPort,
            BusinessSnapshotProvider snapshotProvider,
            AiAuditPort auditPort,
            BooleanSupplier writeExecutionEnabled) {
        this(repository, actionPort, snapshotProvider, auditPort, writeExecutionEnabled, TransactionRunner.direct());
    }

    public ActionProposalService(
            ActionProposalRepository repository,
            ApprovedBusinessActionPort actionPort,
            BusinessSnapshotProvider snapshotProvider,
            AiAuditPort auditPort,
            BooleanSupplier writeExecutionEnabled,
            TransactionRunner transactions) {
        this(repository, actionPort, snapshotProvider, auditPort, writeExecutionEnabled, transactions,
                (actor, proposal, supplied) -> supplied);
    }

    public ActionProposalService(
            ActionProposalRepository repository,
            ApprovedBusinessActionPort actionPort,
            BusinessSnapshotProvider snapshotProvider,
            AiAuditPort auditPort,
            BooleanSupplier writeExecutionEnabled,
            TransactionRunner transactions,
            FreshAuthorization freshAuthorization) {
        this.repository = java.util.Objects.requireNonNull(repository);
        this.actionPort = java.util.Objects.requireNonNull(actionPort);
        this.snapshotProvider = java.util.Objects.requireNonNull(snapshotProvider);
        this.auditPort = java.util.Objects.requireNonNull(auditPort);
        this.writeExecutionEnabled = java.util.Objects.requireNonNull(writeExecutionEnabled);
        this.transactions = java.util.Objects.requireNonNull(transactions);
        this.freshAuthorization = java.util.Objects.requireNonNull(freshAuthorization);
    }

    public ProposalView create(
            CreateProposalCommand command,
            String idempotencyKey,
            String requestHash,
            BusinessExecutionActor actor) {
        requireAudit();
        validateCreateIdentity(command, actor, idempotencyKey, requestHash);
        ActionProposalRepository.CreateRequest createRequest = new ActionProposalRepository.CreateRequest(
                actor.userId(), idempotencyKey, requestHash, command.expiresAt().plusSeconds(86400));
        java.util.Optional<ActionProposalRepository.StoredProposal> replay =
                repository.findCreateReplay(command.origin(), createRequest);
        if (replay.isPresent()) return view(replay.get());
        validateCommand(command, actor, idempotencyKey, requestHash);
        return transactions.required(() -> {
            String canonicalPayload = CanonicalJsonHasher.canonicalize(command.payloadJson());
            String payloadHash = CanonicalJsonHasher.sha256(canonicalPayload);
            String snapshotHash = snapshotProvider.currentSnapshotHash(command.actionType(), canonicalPayload);
            if (snapshotHash == null || snapshotHash.isBlank()) {
                throw new IllegalStateException("无法生成业务快照 hash");
            }
            String publicId = UUID.randomUUID().toString();
            ActionProposal proposal = ActionProposal.pending(publicId, command.actionType(), canonicalPayload,
                    payloadHash, snapshotHash, command.requiredBusinessPermission(),
                    command.requiredApprovalCount(), command.expiresAt());
            ActionProposalRepository.StoredProposal stored = new ActionProposalRepository.StoredProposal(
                    proposal, command.origin().runPublicId(), command.targetType(), command.targetResourceId(), command.preview(),
                    command.riskLevel(), command.proposerUserId(), Instant.now());
            ActionProposalRepository.CreateResult result = repository.createOrReplay(stored, command.origin(),
                    createRequest);
            if (!result.replayed()) audit("PROPOSAL_CREATED", result.proposal(), actor, payloadHash);
            return view(result.proposal());
        });
    }

    public ProposalView approve(
            String proposalPublicId,
            int expectedVersion,
            String submittedPayloadHash,
            String submittedSnapshotHash,
            BusinessExecutionActor actor,
            Set<String> currentPermissions,
            String idempotencyKey,
            String requestHash,
            String commentRedacted) {
        requireAudit();
        DecisionTransaction decision = freshAuthorizationTransaction(() -> {
            ActionProposalRepository.StoredProposal stored = requireStored(proposalPublicId);
            ActionProposal proposal = stored.proposal();
            Set<String> freshPermissions = currentPermissions(actor, currentPermissions, stored);
            String safeIdempotencyKey = required(idempotencyKey, "Idempotency-Key");
            String safeRequestHash = requiredHash(requestHash, "requestHash");
            if (proposal.approvalReplay(actor, "APPROVE", safeIdempotencyKey, safeRequestHash).isPresent()) {
                return new DecisionTransaction(view(stored), null, null, freshPermissions);
            }
            if (!stored.preview().confirmable()) {
                throw new ProposalConflictException("AI_PROPOSAL_EVIDENCE_UNVERIFIED",
                        "提案缺少可验证来源、数据时间或置信事实，禁止批准");
            }
            if (proposal.duplicateReviewerApproval(expectedVersion, submittedPayloadHash,
                    submittedSnapshotHash, actor, freshPermissions).isPresent()) {
                return new DecisionTransaction(view(stored), null, null, freshPermissions);
            }
            int versionBefore = proposal.version();
            String currentSnapshotHash = snapshotProvider.currentSnapshotHash(
                        proposal.actionType(), proposal.canonicalPayload());
            try {
                proposal.approve(expectedVersion, submittedPayloadHash, submittedSnapshotHash, currentSnapshotHash,
                        actor, freshPermissions, safeIdempotencyKey,
                        safeRequestHash, normalizeComment(commentRedacted));
            } catch (ProposalConflictException conflict) {
                if (proposal.version() != versionBefore
                        && (proposal.state() == ProposalState.EXPIRED || proposal.state() == ProposalState.STALE)) {
                    repository.update(stored);
                    audit("PROPOSAL_" + proposal.state().name(), stored, actor, proposal.payloadHash());
                    return new DecisionTransaction(view(stored), null, conflict, freshPermissions);
                }
                throw conflict;
            }
            if (proposal.version() == versionBefore) {
                return new DecisionTransaction(view(stored), null, null, freshPermissions);
            }
            audit("PROPOSAL_APPROVED", stored, actor, proposal.payloadHash());
            ActionProposal.ExecutionLease lease = null;
            if (proposal.state() == ProposalState.APPROVED && writeExecutionEnabled.getAsBoolean()) {
                lease = proposal.acquireExecutionLease(actor);
                if (lease != null) audit("EXECUTION_LEASE_ACQUIRED", stored, actor, lease.leaseTokenHash());
            }
            repository.update(stored);
            return new DecisionTransaction(view(stored), lease, null, freshPermissions);
        });
        if (decision.conflict() != null) throw decision.conflict();
        if (decision.lease() != null) {
            executeLease(proposalPublicId, actor, decision.lease(), decision.permissions());
            return get(proposalPublicId);
        }
        return decision.view();
    }

    public ProposalView approve(
            String proposalPublicId,
            int expectedVersion,
            String submittedPayloadHash,
            String submittedSnapshotHash,
            BusinessExecutionActor actor,
            Set<String> currentPermissions,
            String idempotencyKey,
            String requestHash) {
        return approve(proposalPublicId, expectedVersion, submittedPayloadHash, submittedSnapshotHash,
                actor, currentPermissions, idempotencyKey, requestHash, null);
    }

    public ProposalView reject(
            String proposalPublicId,
            int expectedVersion,
            BusinessExecutionActor actor,
            Set<String> currentPermissions,
            String idempotencyKey,
            String requestHash,
            String commentRedacted) {
        requireAudit();
        return freshAuthorizationTransaction(() -> {
            ActionProposalRepository.StoredProposal stored = requireStored(proposalPublicId);
            Set<String> freshPermissions = currentPermissions(actor, currentPermissions, stored);
            int versionBefore = stored.proposal().version();
            stored.proposal().reject(expectedVersion, actor, freshPermissions,
                    required(idempotencyKey, "Idempotency-Key"), requiredHash(requestHash, "requestHash"),
                    normalizeComment(commentRedacted));
            if (stored.proposal().version() == versionBefore) return view(stored);
            repository.update(stored);
            audit("PROPOSAL_REJECTED", stored, actor, stored.proposal().payloadHash());
            return view(stored);
        });
    }

    public ProposalView reject(
            String proposalPublicId,
            int expectedVersion,
            BusinessExecutionActor actor,
            Set<String> currentPermissions,
            String idempotencyKey,
            String requestHash) {
        return reject(proposalPublicId, expectedVersion, actor, currentPermissions,
                idempotencyKey, requestHash, null);
    }

    public ProposalView reconfirm(
            String proposalPublicId, int expectedVersion,
            String submittedPayloadHash, String submittedSnapshotHash,
            ReconfirmResolution resolution, BusinessExecutionActor actor,
            Set<String> currentPermissions, String idempotencyKey, String requestHash,
            String commentRedacted) {
        requireAudit();
        required(idempotencyKey, "Idempotency-Key");
        requiredHash(requestHash, "requestHash");
        normalizeComment(commentRedacted);
        ReconfirmTransaction decision = freshAuthorizationTransaction(() -> {
            ActionProposalRepository.StoredProposal stored = requireStored(proposalPublicId);
            ActionProposal proposal = stored.proposal();
            Set<String> freshPermissions = currentPermissions(actor, currentPermissions, stored);
            if (!proposal.payloadHash().equals(submittedPayloadHash)
                    || !proposal.businessSnapshotHash().equals(submittedSnapshotHash)) {
                throw new ProposalConflictException("AI_PROPOSAL_HASH_CONFLICT", "提案 hash 不匹配");
            }
            if (resolution == null) throw new IllegalArgumentException("对账结论不能为空");
            proposal.validateReconfirmation(expectedVersion, actor, freshPermissions);
            if (resolution == ReconfirmResolution.UNKNOWN) {
                audit("EXECUTION_RECONFIRM_UNCERTAIN", stored, actor, requestHash);
                return new ReconfirmTransaction(view(stored), null, freshPermissions);
            }
            if (resolution == ReconfirmResolution.RESULT_CONFIRMED) {
                if (stored.result() == null) {
                    audit("EXECUTION_RECONFIRM_UNCERTAIN", stored, actor, requestHash);
                    return new ReconfirmTransaction(view(stored), null, freshPermissions);
                }
                proposal.confirmRecoveredResult(expectedVersion, actor, freshPermissions);
                stored.clearExecutionFailure();
                repository.update(stored);
                audit("EXECUTION_RECONFIRMED_SUCCEEDED", stored, actor, stored.result().resultHash());
                return new ReconfirmTransaction(view(stored), null, freshPermissions);
            }
            if (resolution == ReconfirmResolution.CONFLICT) {
                proposal.failAfterReconfirmation(expectedVersion, actor, freshPermissions);
                stored.executionFailure("AI_EXECUTION_RECONFIRMED_CONFLICT",
                        "人工对账确认业务状态冲突，需要创建新提案");
                repository.update(stored);
                audit("EXECUTION_RECONFIRMED_FAILED", stored, actor, requestHash);
                return new ReconfirmTransaction(view(stored), null, freshPermissions);
            }
            String currentSnapshotHash = snapshotProvider.currentSnapshotHash(
                    proposal.actionType(), proposal.canonicalPayload());
            // 公告创建没有稳定业务键，固定 schema hash 不能证明原事务未执行，因此绝不重放。
            if (proposal.actionType() != ActionType.REPAIR_ASSIGN
                    || !proposal.businessSnapshotHash().equals(currentSnapshotHash)) {
                audit("EXECUTION_RECONFIRM_UNCERTAIN", stored, actor, requestHash);
                return new ReconfirmTransaction(view(stored), null, freshPermissions);
            }
            ActionProposal.ExecutionLease lease = proposal.resumeAfterReconfirmation(
                    expectedVersion, actor, freshPermissions);
            stored.clearExecutionFailure();
            repository.update(stored);
            audit("EXECUTION_RECONFIRMED_NOT_EXECUTED", stored, actor, lease.leaseTokenHash());
            return new ReconfirmTransaction(view(stored), lease, freshPermissions);
        });
        if (decision.lease() != null) {
            executeLease(proposalPublicId, actor, decision.lease(), decision.permissions());
            return get(proposalPublicId);
        }
        return decision.view();
    }

    public ProposalView reconfirm(
            String proposalPublicId, int expectedVersion,
            String submittedPayloadHash, String submittedSnapshotHash,
            ReconfirmResolution resolution, BusinessExecutionActor actor,
            Set<String> currentPermissions, String idempotencyKey, String requestHash) {
        return reconfirm(proposalPublicId, expectedVersion, submittedPayloadHash, submittedSnapshotHash,
                resolution, actor, currentPermissions, idempotencyKey, requestHash, null);
    }

    public ProposalView get(String proposalPublicId) {
        return view(requireStored(proposalPublicId));
    }

    public ProposalView getByExecutionId(String executionPublicId) {
        return view(repository.findByExecutionPublicId(requiredUuid(executionPublicId, "executionPublicId"))
                .orElseThrow(com.example.dormitory.ai.api.AiApiException::notFound));
    }

    /**
     * 供一次性 step-up proof 的 HTTP 编排在消费 proof 前识别已完成的同 actor 幂等重放。
     * 不同 request hash 会在领域对象内 fail closed，且调用方仍须先完成当前会话和对象级授权。
     */
    public boolean isApprovalReplay(
            String proposalPublicId,
            BusinessExecutionActor actor,
            String idempotencyKey,
            String requestHash) {
        ActionProposal proposal = requireStored(proposalPublicId).proposal();
        return proposal.approvalReplay(actor, "APPROVE",
                required(idempotencyKey, "Idempotency-Key"),
                requiredHash(requestHash, "requestHash")).isPresent();
    }

    public PageResult list(ProposalState state, int page, int pageSize) {
        return list(state, null, page, pageSize);
    }

    public PageResult list(ProposalState state, ActionType actionType, int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("分页参数不合法");
        requireAllowlisted(actionType);
        var stored = actionType == null
                ? repository.findByState(state, (page - 1) * pageSize, pageSize)
                : repository.findByStateAndActionType(state, actionType, (page - 1) * pageSize, pageSize);
        var records = stored
                .stream().map(this::view).toList();
        long total = actionType == null
                ? repository.countByState(state)
                : repository.countByStateAndActionType(state, actionType);
        return new PageResult(records, total, page, pageSize);
    }

    /** Authorization must run before pagination so page contents and total describe the same visible set. */
    public PageResult listAuthorized(
            ProposalState state, int page, int pageSize, Predicate<ProposalView> canAccess) {
        return listAuthorized(state, null, page, pageSize, canAccess);
    }

    /** Repository criteria runs first; object authorization still runs before visible pagination and total. */
    public PageResult listAuthorized(
            ProposalState state, ActionType actionType, int page, int pageSize,
            Predicate<ProposalView> canAccess) {
        if (page < 1 || pageSize < 1 || pageSize > 100 || canAccess == null) {
            throw new IllegalArgumentException("分页或授权过滤参数不合法");
        }
        requireAllowlisted(actionType);
        long candidateTotal = actionType == null
                ? repository.countByState(state)
                : repository.countByStateAndActionType(state, actionType);
        java.util.List<ProposalView> visible = new java.util.ArrayList<>();
        int offset = 0;
        while (offset < candidateTotal) {
            int batchSize = (int) Math.min(100, candidateTotal - offset);
            java.util.List<ActionProposalRepository.StoredProposal> batch = actionType == null
                    ? repository.findByState(state, offset, batchSize)
                    : repository.findByStateAndActionType(state, actionType, offset, batchSize);
            if (batch.isEmpty()) break;
            for (ActionProposalRepository.StoredProposal stored : batch) {
                ProposalView value = view(stored);
                if (canAccess.test(value)) visible.add(value);
            }
            offset += batch.size();
        }
        int from = Math.min((page - 1) * pageSize, visible.size());
        int to = Math.min(from + pageSize, visible.size());
        return new PageResult(visible.subList(from, to), visible.size(), page, pageSize);
    }

    private void requireAllowlisted(ActionType actionType) {
        if (actionType != null && !ActionType.allowlisted().contains(actionType)) {
            throw new IllegalArgumentException("提案动作类型不在白名单");
        }
    }

    private void executeLease(
            String proposalPublicId,
            BusinessExecutionActor actor,
            ActionProposal.ExecutionLease lease,
            Set<String> previouslyAuthorizedPermissions) {
        try {
            freshAuthorizationTransaction(() -> {
                ActionProposalRepository.StoredProposal stored = requireStored(proposalPublicId);
                ActionProposal proposal = stored.proposal();
                ActionProposal.ExecutionLease persistedLease = proposal.executionLease();
                if (persistedLease == null
                        || !persistedLease.leaseTokenHash().equals(lease.leaseTokenHash())
                        || proposal.state() != ProposalState.EXECUTING) {
                    throw new ProposalConflictException("AI_EXECUTION_LEASE_CONFLICT",
                            "执行租约已变化");
                }
                Set<String> executionPermissions = currentPermissions(
                        actor, previouslyAuthorizedPermissions, stored);
                requireExecutionPermissions(proposal, executionPermissions);
                ApprovedBusinessActionPort.BusinessActionResult result = actionPort.execute(actor,
                        new ApprovedBusinessActionPort.ApprovedBusinessAction(
                                proposal.actionType().name(), proposal.canonicalPayload(),
                                proposal.payloadHash(), proposal.businessSnapshotHash()));
                stored.result(result);
                proposal.markSucceeded(persistedLease);
                repository.update(stored);
                audit("EXECUTION_SUCCEEDED", stored, actor, result.resultHash());
                return null;
            });
        } catch (RuntimeException failure) {
            persistExecutionFailure(proposalPublicId, actor, lease, failure);
            throw failure;
        }
    }

    private void persistExecutionFailure(
            String proposalPublicId,
            BusinessExecutionActor actor,
            ActionProposal.ExecutionLease failedLease,
            RuntimeException failure) {
        transactions.requiresNew(() -> {
            ActionProposalRepository.StoredProposal stored = requireStored(proposalPublicId);
            ActionProposal proposal = stored.proposal();
            ActionProposal.ExecutionLease persistedLease = proposal.executionLease();
            if (proposal.state() != ProposalState.EXECUTING || persistedLease == null
                    || !persistedLease.leaseTokenHash().equals(failedLease.leaseTokenHash())) {
                return null;
            }
            String failureClassHash = CanonicalJsonHasher.sha256(failure.getClass().getName());
            if (outcomeUnknown(failure)) {
                proposal.markNeedsReview(persistedLease);
                stored.executionFailure("AI_EXECUTION_OUTCOME_UNKNOWN",
                        "业务事务完成结果不确定，禁止自动重放，等待人工对账");
                repository.update(stored);
                audit("EXECUTION_NEEDS_REVIEW", stored, actor, failureClassHash);
            } else {
                proposal.markFailed(persistedLease);
                stored.executionFailure("AI_BUSINESS_EXECUTION_FAILED",
                        "业务执行失败且事务未提交，需要创建新提案");
                repository.update(stored);
                audit("EXECUTION_FAILED", stored, actor, failureClassHash);
            }
            return null;
        });
    }

    private boolean outcomeUnknown(RuntimeException failure) {
        return failure instanceof org.springframework.transaction.TransactionSystemException
                || failure instanceof org.springframework.transaction.HeuristicCompletionException;
    }

    private <T> T freshAuthorizationTransaction(Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "fresh-RBAC 事务边界禁止运行在调用方已开启的事务中");
        }
        FreshTransactionOutcome<T> outcome = transactions.required(() -> {
            try {
                return FreshTransactionOutcome.success(work.get());
            } catch (FreshAuthorizationDeniedException denial) {
                return FreshTransactionOutcome.denied(denial);
            }
        });
        if (outcome.denial() != null) throw outcome.denial().hiddenFailure();
        return outcome.value();
    }

    private Set<String> currentPermissions(
            BusinessExecutionActor actor,
            Set<String> suppliedPermissions,
            ActionProposalRepository.StoredProposal stored) {
        Set<String> permissions = freshAuthorization.currentPermissions(
                actor, view(stored), suppliedPermissions == null ? Set.of() : Set.copyOf(suppliedPermissions));
        return permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    private void requireExecutionPermissions(ActionProposal proposal, Set<String> permissions) {
        if (!permissions.contains("ai:approval:review")
                || !permissions.contains(proposal.requiredBusinessPermission())) {
            throw new SecurityException("执行前 fresh RBAC 已拒绝 AI 审批或目标业务权限");
        }
    }

    private void validateCommand(
            CreateProposalCommand command,
            BusinessExecutionActor actor,
            String idempotencyKey,
            String requestHash) {
        if (command == null || actor == null || command.actionType() == null
                || !ActionType.allowlisted().contains(command.actionType())
                || command.targetType() == null || command.targetType().isBlank()
                || command.payloadJson() == null || command.payloadJson().isBlank()
                || command.preview() == null || command.origin() == null
                || command.riskLevel() == null || !Set.of("LOW", "MEDIUM", "HIGH").contains(command.riskLevel())
                || command.proposerUserId() != actor.userId()
                || command.expiresAt() == null || !command.expiresAt().isAfter(Instant.now())) {
            throw new IllegalArgumentException("提案创建合同不合法");
        }
        command.origin().requireMatches(command.actionType());
        required(idempotencyKey, "Idempotency-Key");
        requiredHash(requestHash, "requestHash");
        if (command.actionType() == ActionType.NOTICE_CREATE_DRAFT) {
            if (!"NOTICE".equals(command.targetType()) || command.targetResourceId() != null
                    || !"notice:write".equals(command.requiredBusinessPermission())
                    || !command.payloadJson().contains("\"status\":\"草稿\"")) {
                throw new IllegalArgumentException("公告 AI 只能创建草稿提案");
            }
        } else if (!"REPAIR_ORDER".equals(command.targetType())
                || command.targetResourceId() == null || command.targetResourceId() < 1
                || !"repair:write".equals(command.requiredBusinessPermission())) {
            throw new IllegalArgumentException("维修指派提案合同不合法");
        }
    }

    private void validateCreateIdentity(
            CreateProposalCommand command,
            BusinessExecutionActor actor,
            String idempotencyKey,
            String requestHash) {
        if (command == null || actor == null || command.actionType() == null || command.origin() == null
                || command.expiresAt() == null || command.proposerUserId() != actor.userId()) {
            throw new IllegalArgumentException("提案创建身份合同不合法");
        }
        command.origin().requireMatches(command.actionType());
        required(idempotencyKey, "Idempotency-Key");
        requiredHash(requestHash, "requestHash");
    }

    private ActionProposalRepository.StoredProposal requireStored(String publicId) {
        return repository.findByPublicId(required(publicId, "proposalPublicId"))
                .orElseThrow(com.example.dormitory.ai.api.AiApiException::notFound);
    }

    private void requireAudit() {
        if (!auditPort.writable()) throw new IllegalStateException("AI 审计不可写，已阻止提案操作");
    }

    private void audit(
            String eventType,
            ActionProposalRepository.StoredProposal stored,
            BusinessExecutionActor actor,
            String payloadHash) {
        auditPort.append(new AiAuditPort.AiAuditEvent(
                "ACTION_PROPOSAL", stored.proposal().publicId(), eventType, actor.descriptor(),
                CanonicalJsonHasher.sha256(payloadHash), Instant.now()));
    }

    private ProposalView view(ActionProposalRepository.StoredProposal stored) {
        ActionProposal proposal = stored.proposal();
        return new ProposalView(proposal.publicId(), proposal.actionType(), stored.targetType(),
                stored.targetResourceId(), stored.preview(), proposal.payloadHash(),
                proposal.businessSnapshotHash(), proposal.requiredBusinessPermission(),
                proposal.requiredApprovalCount(), proposal.approvals().size(), stored.riskLevel(),
                proposal.state(), proposal.version(), proposal.expiresAt(), stored.result(),
                stored.executionPublicId(), stored.runId());
    }

    private String required(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(name + " 不合法");
        }
        return value;
    }

    private String requiredHash(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " 必须是 64 位小写十六进制 hash");
        }
        return value;
    }

    private String requiredUuid(String value, String name) {
        try {
            return UUID.fromString(value == null ? "" : value.trim()).toString();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(name + " 必须是 UUID", exception);
        }
    }

    private String normalizeComment(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > 500 || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("审批说明不合法");
        }
        return normalized;
    }

    @FunctionalInterface
    public interface BusinessSnapshotProvider {
        String currentSnapshotHash(ActionType actionType, String canonicalPayload);
    }

    public record CreateProposalCommand(
            ActionType actionType,
            String targetType,
            Long targetResourceId,
            String payloadJson,
            ProposalPreview preview,
            String requiredBusinessPermission,
            int requiredApprovalCount,
            String riskLevel,
            long proposerUserId,
            Instant expiresAt,
            ProposalOrigin origin) {
    }

    public record ProposalView(
            String publicId,
            ActionType actionType,
            String targetType,
            Long targetResourceId,
            ProposalPreview preview,
            String payloadHash,
            String businessSnapshotHash,
            String requiredBusinessPermission,
            int requiredApprovalCount,
            int approvedCount,
            String riskLevel,
            ProposalState state,
            int version,
            Instant expiresAt,
            ApprovedBusinessActionPort.BusinessActionResult result,
            String executionPublicId,
            String runId) {
        public ProposalView(
                String publicId,
                ActionType actionType,
                String targetType,
                Long targetResourceId,
                ProposalPreview preview,
                String payloadHash,
                String businessSnapshotHash,
                String requiredBusinessPermission,
                int requiredApprovalCount,
                int approvedCount,
                String riskLevel,
                ProposalState state,
                int version,
                Instant expiresAt,
                ApprovedBusinessActionPort.BusinessActionResult result) {
            this(publicId, actionType, targetType, targetResourceId, preview, payloadHash,
                    businessSnapshotHash, requiredBusinessPermission, requiredApprovalCount,
                    approvedCount, riskLevel, state, version, expiresAt, result, null, null);
        }

        public ProposalView(
                String publicId,
                ActionType actionType,
                String targetType,
                Long targetResourceId,
                ProposalPreview preview,
                String payloadHash,
                String businessSnapshotHash,
                String requiredBusinessPermission,
                int requiredApprovalCount,
                int approvedCount,
                String riskLevel,
                ProposalState state,
                int version,
                Instant expiresAt,
                ApprovedBusinessActionPort.BusinessActionResult result,
                String executionPublicId) {
            this(publicId, actionType, targetType, targetResourceId, preview, payloadHash,
                    businessSnapshotHash, requiredBusinessPermission, requiredApprovalCount,
                    approvedCount, riskLevel, state, version, expiresAt, result, executionPublicId, null);
        }
    }

    public record PageResult(java.util.List<ProposalView> records, long total, int page, int pageSize) {
        public PageResult {
            records = java.util.List.copyOf(records);
        }
    }

    public enum ReconfirmResolution { RESULT_CONFIRMED, PROVEN_NOT_EXECUTED, CONFLICT, UNKNOWN }

    public interface TransactionRunner {
        <T> T required(Supplier<T> work);
        <T> T requiresNew(Supplier<T> work);

        static TransactionRunner direct() {
            return new TransactionRunner() {
                @Override
                public <T> T required(Supplier<T> work) { return work.get(); }

                @Override
                public <T> T requiresNew(Supplier<T> work) { return work.get(); }
            };
        }
    }

    private record FreshTransactionOutcome<T>(T value, FreshAuthorizationDeniedException denial) {
        private static <T> FreshTransactionOutcome<T> success(T value) {
            return new FreshTransactionOutcome<>(value, null);
        }

        private static <T> FreshTransactionOutcome<T> denied(FreshAuthorizationDeniedException denial) {
            return new FreshTransactionOutcome<>(null, java.util.Objects.requireNonNull(denial));
        }
    }

    @FunctionalInterface
    public interface FreshAuthorization {
        Set<String> currentPermissions(
                BusinessExecutionActor actor,
                ProposalView proposal,
                Set<String> suppliedPermissions);
    }

    private record DecisionTransaction(
            ProposalView view,
            ActionProposal.ExecutionLease lease,
            ProposalConflictException conflict,
            Set<String> permissions) { }

    private record ReconfirmTransaction(
            ProposalView view,
            ActionProposal.ExecutionLease lease,
            Set<String> permissions) { }
}
