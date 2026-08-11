package com.example.dormitory.ai.risk;

import java.time.Instant;
import java.util.Map;

/** 经 RiskScanScope 授权后捕获的去标识业务快照；真实资源 ID 不进入该模型输入层。 */
public record RiskBusinessSnapshot(
        String subjectType,
        String subjectToken,
        Instant capturedAt,
        Map<String, Object> facts) {
    public RiskBusinessSnapshot {
        if (subjectType == null || subjectType.isBlank() || subjectToken == null || subjectToken.isBlank()
                || capturedAt == null || facts == null || facts.isEmpty()) {
            throw new IllegalArgumentException("风险业务快照不完整");
        }
        facts = Map.copyOf(facts);
    }

    public static RiskBusinessSnapshot from(RiskSignal signal) {
        return new RiskBusinessSnapshot(signal.subjectType(), signal.subjectToken(), signal.observedAt(),
                signal.evidence());
    }
}
