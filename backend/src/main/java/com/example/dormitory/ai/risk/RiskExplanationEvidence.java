package com.example.dormitory.ai.risk;

/** 模型或确定性降级解释；confidence 首期不伪造，始终为空。 */
public record RiskExplanationEvidence(
        String text,
        RiskExplanationBasis basis,
        String policyVersion,
        Long runDatabaseId,
        String runPublicId,
        Double confidence,
        boolean degraded) {
    public RiskExplanationEvidence {
        if (text == null || text.isBlank() || text.length() > 4_000 || basis == null
                || policyVersion == null || policyVersion.isBlank()) {
            throw new IllegalArgumentException("风险解释证据不完整");
        }
        if (basis == RiskExplanationBasis.MODEL
                && (runDatabaseId == null || runDatabaseId < 1 || runPublicId == null
                || !runPublicId.matches("[0-9a-fA-F-]{36}") || degraded)) {
            throw new IllegalArgumentException("模型风险解释必须绑定受控成功 run");
        }
        if (basis == RiskExplanationBasis.DETERMINISTIC_DEGRADED && !degraded) {
            throw new IllegalArgumentException("确定性风险解释必须明确 degraded");
        }
        if (confidence != null) throw new IllegalArgumentException("首期风险解释不得伪造模型置信度");
    }
}
