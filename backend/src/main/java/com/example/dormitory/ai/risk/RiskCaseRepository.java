package com.example.dormitory.ai.risk;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface RiskCaseRepository {

    void create(StoredRiskCase riskCase);

    /**
     * 以数据库中的版本作为 CAS 条件保存快照和新增事件。刷新同版本信号时 expectedVersion
     * 等于当前版本；状态迁移时实体版本应为 expectedVersion + 1。
     */
    void save(StoredRiskCase riskCase, int expectedVersion, Long idempotencyRecordId);

    TransitionReservation reserveTransition(
            long actorUserId,
            String publicId,
            RiskCaseState target,
            String idempotencyKey,
            String requestHash);

    void releaseTransition(long recordId);

    Optional<StoredRiskCase> findByPublicId(String publicId);

    Optional<StoredRiskCase> findLatestByDedupKey(String dedupKey);

    List<StoredRiskCase> findByState(RiskCaseState state, int offset, int limit);

    List<StoredRiskCase> findByCriteria(RiskCaseState state, java.util.Set<String> riskTypes, int offset, int limit);

    long countByState(RiskCaseState state);

    long countByCriteria(RiskCaseState state, java.util.Set<String> riskTypes);

    record TransitionReservation(long recordId, boolean replay) {
        public TransitionReservation {
            if (recordId < 0) throw new IllegalArgumentException("风险幂等记录 ID 不合法");
        }
    }

    final class StoredRiskCase {
        private final RiskCase riskCase;
        private final String dedupKey;
        private final String subjectType;
        private final Long subjectResourceId;
        private volatile RiskSignalEvidence signalEvidence;
        private volatile RiskBusinessSnapshot businessSnapshot;
        private volatile RiskExplanationEvidence explanationEvidence;
        private volatile Long assigneeUserId;
        private volatile Instant dueAt;

        public StoredRiskCase(RiskCase riskCase, RiskSignal signal) {
            this.riskCase = java.util.Objects.requireNonNull(riskCase);
            this.dedupKey = signal.dedupKey();
            this.subjectType = signal.subjectType();
            this.subjectResourceId = signal.subjectResourceId();
            refreshSignal(signal);
            this.explanationEvidence = new RiskExplanationEvidence(
                    "确定性规则证据已命中；模型解释当前不可用，请人工核验业务事实后再记录处置结论。",
                    RiskExplanationBasis.DETERMINISTIC_DEGRADED,
                    "risk-explanation-deterministic.v1", null, null, null, true);
        }

        public static StoredRiskCase restore(
                RiskCase riskCase,
                String dedupKey,
                String subjectType,
                Long subjectResourceId,
                RiskSignalEvidence signalEvidence,
                RiskBusinessSnapshot businessSnapshot,
                RiskExplanationEvidence explanationEvidence,
                Long assigneeUserId,
                Instant dueAt) {
            if (riskCase == null || dedupKey == null || !dedupKey.matches("[0-9a-f]{64}")
                    || subjectType == null || subjectResourceId == null || subjectResourceId < 1
                    || signalEvidence == null || businessSnapshot == null || explanationEvidence == null
                    || (assigneeUserId != null && assigneeUserId < 1)) {
                throw new IllegalArgumentException("持久化风险快照不合法");
            }
            return new StoredRiskCase(riskCase, dedupKey, subjectType, subjectResourceId, signalEvidence,
                    businessSnapshot, explanationEvidence, assigneeUserId, dueAt);
        }

        private StoredRiskCase(
                RiskCase riskCase,
                String dedupKey,
                String subjectType,
                Long subjectResourceId,
                RiskSignalEvidence signalEvidence,
                RiskBusinessSnapshot businessSnapshot,
                RiskExplanationEvidence explanationEvidence,
                Long assigneeUserId,
                Instant dueAt) {
            this.riskCase = riskCase;
            this.dedupKey = dedupKey;
            this.subjectType = subjectType;
            this.subjectResourceId = subjectResourceId;
            this.signalEvidence = signalEvidence;
            this.businessSnapshot = businessSnapshot;
            this.explanationEvidence = explanationEvidence;
            this.assigneeUserId = assigneeUserId;
            this.dueAt = dueAt;
        }

        public RiskCase riskCase() { return riskCase; }
        public String dedupKey() { return dedupKey; }
        public String subjectType() { return subjectType; }
        public Long subjectResourceId() { return subjectResourceId; }
        public String severity() { return signalEvidence.severity(); }
        public Map<String, Object> evidence() { return signalEvidence.facts(); }
        public Instant asOf() { return signalEvidence.observedAt(); }
        public String explanation() { return explanationEvidence.text(); }
        public boolean explanationDegraded() { return explanationEvidence.degraded(); }
        public RiskSignalEvidence signalEvidence() { return signalEvidence; }
        public RiskBusinessSnapshot businessSnapshot() { return businessSnapshot; }
        public RiskExplanationEvidence explanationEvidence() { return explanationEvidence; }
        public Long assigneeUserId() { return assigneeUserId; }
        public Instant dueAt() { return dueAt; }

        public synchronized void refreshSignal(RiskSignal signal) {
            if (!dedupKey.equals(signal.dedupKey())) throw new IllegalArgumentException("风险去重键不可变");
            signalEvidence = RiskSignalEvidence.from(signal);
            businessSnapshot = RiskBusinessSnapshot.from(signal);
        }

        public synchronized void explanation(RiskExplanationEvidence value) {
            explanationEvidence = java.util.Objects.requireNonNull(value);
        }

        public synchronized void assign(long userId, Instant requestedDueAt) {
            if (userId < 1) throw new IllegalArgumentException("风险责任人不合法");
            if (requestedDueAt != null
                    && requestedDueAt.isAfter(asOf().plus(java.time.Duration.ofDays(365)))) {
                throw new IllegalArgumentException("风险到期时间不合法");
            }
            assigneeUserId = userId;
            if (requestedDueAt != null) dueAt = requestedDueAt;
        }
    }
}
