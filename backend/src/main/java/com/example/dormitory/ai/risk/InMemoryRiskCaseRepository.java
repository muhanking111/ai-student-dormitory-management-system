package com.example.dormitory.ai.risk;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryRiskCaseRepository implements RiskCaseRepository {

    private final ConcurrentHashMap<String, StoredRiskCase> cases = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, InMemoryReservation> transitionReservations = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong reservationSequence = new java.util.concurrent.atomic.AtomicLong();

    @Override
    public void create(StoredRiskCase riskCase) {
        synchronized (cases) {
            boolean activeDuplicate = cases.values().stream()
                    .anyMatch(existing -> existing.dedupKey().equals(riskCase.dedupKey())
                            && isActive(existing.riskCase().state()));
            if (activeDuplicate) throw new ActiveRiskCaseConflictException("活动风险去重键重复");
            if (cases.putIfAbsent(riskCase.riskCase().publicId(), riskCase) != null) {
                throw new IllegalStateException("风险案例 publicId 重复");
            }
        }
    }

    @Override
    public void save(StoredRiskCase riskCase, int expectedVersion, Long idempotencyRecordId) {
        StoredRiskCase current = cases.get(riskCase.riskCase().publicId());
        if (current == null) throw new IllegalStateException("风险案例不存在");
        // 内存 fake 由同一对象承载变更；测试生产 CAS 语义由 JDBC adapter 覆盖。
        if (riskCase.riskCase().version() < expectedVersion
                || riskCase.riskCase().version() > expectedVersion + 1) {
            throw new IllegalStateException("风险案例版本冲突");
        }
    }

    @Override
    public TransitionReservation reserveTransition(
            long actorUserId,
            String publicId,
            RiskCaseState target,
            String idempotencyKey,
            String requestHash) {
        String scope = actorUserId + "|" + target + "|" + publicId + "|" + idempotencyKey;
        InMemoryReservation created = new InMemoryReservation(reservationSequence.incrementAndGet(), requestHash);
        InMemoryReservation existing = transitionReservations.putIfAbsent(scope, created);
        if (existing == null) return new TransitionReservation(created.id(), false);
        if (!existing.requestHash().equals(requestHash)) {
            throw new com.example.dormitory.ai.approval.IdempotencyConflictException("风险处置幂等 payload 冲突");
        }
        return new TransitionReservation(existing.id(), true);
    }

    @Override
    public void releaseTransition(long recordId) {
        transitionReservations.entrySet().removeIf(entry -> entry.getValue().id() == recordId);
    }

    @Override
    public Optional<StoredRiskCase> findByPublicId(String publicId) {
        return Optional.ofNullable(cases.get(publicId));
    }

    @Override
    public Optional<StoredRiskCase> findLatestByDedupKey(String dedupKey) {
        return cases.values().stream().filter(item -> item.dedupKey().equals(dedupKey))
                .max(Comparator.comparing(RiskCaseRepository.StoredRiskCase::asOf));
    }

    @Override
    public List<StoredRiskCase> findByState(RiskCaseState state, int offset, int limit) {
        return findByCriteria(state, java.util.Set.of(), offset, limit);
    }

    @Override
    public List<StoredRiskCase> findByCriteria(
            RiskCaseState state, java.util.Set<String> riskTypes, int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("分页参数不合法");
        return cases.values().stream()
                .filter(item -> state == null || item.riskCase().state() == state)
                .filter(item -> riskTypes == null || riskTypes.isEmpty()
                        || riskTypes.contains(item.riskCase().riskType()))
                .sorted(Comparator.comparing(StoredRiskCase::asOf).reversed())
                .skip(offset).limit(limit).toList();
    }

    @Override
    public long countByState(RiskCaseState state) {
        return countByCriteria(state, java.util.Set.of());
    }

    @Override
    public long countByCriteria(RiskCaseState state, java.util.Set<String> riskTypes) {
        return cases.values().stream()
                .filter(item -> state == null || item.riskCase().state() == state)
                .filter(item -> riskTypes == null || riskTypes.isEmpty()
                        || riskTypes.contains(item.riskCase().riskType()))
                .count();
    }

    private boolean isActive(RiskCaseState state) {
        return state == RiskCaseState.OPEN || state == RiskCaseState.ACKNOWLEDGED;
    }

    private record InMemoryReservation(long id, String requestHash) { }
}
