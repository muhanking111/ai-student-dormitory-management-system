package com.example.dormitory.ai.approval;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryActionProposalRepository implements ActionProposalRepository {

    private final ConcurrentHashMap<String, StoredProposal> proposals = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CreateEntry> creates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> proposalByOrigin = new ConcurrentHashMap<>();

    @Override
    public synchronized CreateResult createOrReplay(
            StoredProposal proposal, ProposalOrigin origin, CreateRequest request) {
        if (proposal.runId() != null && !proposal.runId().equals(origin.runPublicId())) {
            throw new ProposalConflictException("AI_PROPOSAL_ORIGIN_CONFLICT",
                    "提案绑定的 origin run 与请求 origin 不一致");
        }
        String scope = request.actorUserId() + "|" + origin.runPublicId() + "|" + request.idempotencyKey();
        CreateEntry existing = creates.get(scope);
        if (existing != null) {
            if (!existing.requestHash.equals(request.requestHash())) {
                throw new IdempotencyConflictException("相同幂等键对应不同提案请求");
            }
            return new CreateResult(proposals.get(existing.proposalPublicId), true);
        }
        String originKey = origin.runPublicId() + "|" + origin.toolName() + "|" + origin.toolVersion();
        String existingProposalId = proposalByOrigin.get(originKey);
        if (existingProposalId != null) {
            StoredProposal replay = proposals.get(existingProposalId);
            if (!replay.proposal().payloadHash().equals(proposal.proposal().payloadHash())) {
                throw new ProposalConflictException("AI_PROPOSAL_ORIGIN_CONFLICT",
                        "同一 origin tool 不能创建不同提案");
            }
            creates.put(scope, new CreateEntry(request.requestHash(), existingProposalId));
            return new CreateResult(replay, true);
        }
        if (proposals.putIfAbsent(proposal.proposal().publicId(), proposal) != null) {
            throw new IllegalStateException("提案 publicId 重复");
        }
        proposalByOrigin.put(originKey, proposal.proposal().publicId());
        creates.put(scope, new CreateEntry(request.requestHash(), proposal.proposal().publicId()));
        return new CreateResult(proposal, false);
    }

    @Override
    public synchronized Optional<StoredProposal> findCreateReplay(
            ProposalOrigin origin, CreateRequest request) {
        String scope = request.actorUserId() + "|" + origin.runPublicId() + "|" + request.idempotencyKey();
        CreateEntry existing = creates.get(scope);
        if (existing == null) return Optional.empty();
        if (!existing.requestHash.equals(request.requestHash())) {
            throw new IdempotencyConflictException("相同幂等键对应不同提案请求");
        }
        return Optional.ofNullable(proposals.get(existing.proposalPublicId));
    }

    @Override
    public void update(StoredProposal proposal) {
        if (proposals.get(proposal.proposal().publicId()) != proposal) {
            throw new IllegalStateException("提案不存在或实例不匹配");
        }
        if (proposal.proposal().executionLease() != null && proposal.executionPublicId() == null) {
            proposal.executionPublicId(java.util.UUID.randomUUID().toString());
        }
        proposal.markPersisted();
    }

    @Override
    public Optional<StoredProposal> findByPublicId(String publicId) {
        return Optional.ofNullable(proposals.get(publicId));
    }

    @Override
    public Optional<StoredProposal> findByExecutionPublicId(String executionPublicId) {
        return proposals.values().stream()
                .filter(value -> executionPublicId != null
                        && executionPublicId.equals(value.executionPublicId()))
                .findFirst();
    }

    @Override
    public List<StoredProposal> findByState(ProposalState state, int offset, int limit) {
        return findByStateAndActionType(state, null, offset, limit);
    }

    @Override
    public List<StoredProposal> findByStateAndActionType(
            ProposalState state, ActionType actionType, int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("分页参数不合法");
        requireAllowlisted(actionType);
        return proposals.values().stream()
                .filter(record -> state == null || record.proposal().state() == state)
                .filter(record -> actionType == null || record.proposal().actionType() == actionType)
                .sorted(Comparator.comparing(StoredProposal::createdAt).reversed())
                .skip(offset).limit(limit).toList();
    }

    @Override
    public long countByState(ProposalState state) {
        return countByStateAndActionType(state, null);
    }

    @Override
    public long countByStateAndActionType(ProposalState state, ActionType actionType) {
        requireAllowlisted(actionType);
        return proposals.values().stream()
                .filter(record -> state == null || record.proposal().state() == state)
                .filter(record -> actionType == null || record.proposal().actionType() == actionType)
                .count();
    }

    @Override
    public synchronized List<StoredProposal> expireDue(java.time.Instant now) {
        java.util.List<StoredProposal> expired = new java.util.ArrayList<>();
        for (StoredProposal stored : proposals.values()) {
            if (stored.proposal().expireIfDue(now)) {
                stored.markPersisted();
                expired.add(stored);
            }
        }
        return List.copyOf(expired);
    }

    @Override
    public List<String> markStaleExecutionsNeedsReview(java.time.Instant staleBefore) {
        return List.of();
    }

    private void requireAllowlisted(ActionType actionType) {
        if (actionType != null && !ActionType.allowlisted().contains(actionType)) {
            throw new IllegalArgumentException("提案动作类型不在白名单");
        }
    }

    private record CreateEntry(String requestHash, String proposalPublicId) { }
}
