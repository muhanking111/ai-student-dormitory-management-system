package com.example.dormitory.ai.risk;

import java.time.Instant;
import java.util.Map;

/** 版本化确定性规则事实，不包含模型或人工结论。 */
public record RiskSignalEvidence(
        String riskType,
        String policyVersion,
        String severity,
        Instant observedAt,
        Map<String, Object> facts) {
    public RiskSignalEvidence {
        if (riskType == null || riskType.isBlank() || policyVersion == null || policyVersion.isBlank()
                || severity == null || severity.isBlank() || observedAt == null || facts == null || facts.isEmpty()) {
            throw new IllegalArgumentException("风险信号证据不完整");
        }
        facts = Map.copyOf(facts);
    }

    public static RiskSignalEvidence from(RiskSignal signal) {
        return new RiskSignalEvidence(signal.riskType(), signal.policyVersion(), signal.severity(),
                signal.observedAt(), signal.evidence());
    }
}
