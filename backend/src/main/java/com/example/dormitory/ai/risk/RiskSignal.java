package com.example.dormitory.ai.risk;

import com.example.dormitory.ai.approval.CanonicalJsonHasher;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

public record RiskSignal(
        String riskType,
        String subjectType,
        Long subjectResourceId,
        String subjectToken,
        String severity,
        String policyVersion,
        Map<String, Object> evidence,
        Instant observedAt) {

    private static final Set<String> ALLOWED_RISK_TYPES = Set.of(
            "repair-backlog", "repeat-repair", "resource-checkin-inconsistency", "failed-hygiene-check",
            "long-pending-operation", "overdue-payment");
    private static final Set<String> ALLOWED_SUBJECT_TYPES = Set.of(
            "REPAIR_ORDER", "DORMITORY", "CHECK_IN_APPLICATION", "PAYMENT", "OPERATION_TASK");
    private static final Set<String> ALLOWED_SEVERITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final Set<String> ALLOWED_EVIDENCE_KEYS = Set.of(
            "ageHours", "count", "status", "windowDays", "firstSeenAt", "lastSeenAt",
            "ruleThreshold", "relatedCount", "sourceState", "asOf", "configuredBeds", "actualBeds",
            "configuredOccupied", "occupiedBeds", "activeCheckIns", "configuredVacant",
            "score", "result", "inspectedOn", "deadline", "ageDays", "amountDue", "amountPaid");

    public RiskSignal {
        if (!ALLOWED_RISK_TYPES.contains(riskType)
                || !ALLOWED_SUBJECT_TYPES.contains(subjectType)
                || subjectResourceId == null || subjectResourceId < 1
                || subjectToken == null || !subjectToken.matches("[A-Za-z][A-Za-z0-9_-]{7,127}")
                || subjectToken.matches("1[3-9]\\d{9}")
                || !ALLOWED_SEVERITIES.contains(severity)
                || policyVersion == null || !policyVersion.matches("[A-Za-z0-9._-]{2,64}")
                || evidence == null || evidence.isEmpty() || !ALLOWED_EVIDENCE_KEYS.containsAll(evidence.keySet())
                || observedAt == null) {
            throw new IllegalArgumentException("风险信号合同不合法");
        }
        for (Object value : evidence.values()) {
            if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
                throw new IllegalArgumentException("风险证据只能包含受控标量");
            }
        }
        evidence = Map.copyOf(evidence);
    }

    public String dedupKey() {
        return CanonicalJsonHasher.sha256(riskType + "|" + subjectType + "|" + subjectToken);
    }
}
