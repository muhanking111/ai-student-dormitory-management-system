package com.example.dormitory.ai.risk;

import java.util.Map;
import java.util.Set;

/** 无模型或模型失败时的安全解释；只解释受控规则类型，不生成学生画像或自动处分。 */
public final class RiskExplanationPolicy {

    private static final Set<String> TYPES = Set.of(
            "repair-backlog", "repeat-repair", "resource-checkin-inconsistency", "failed-hygiene-check",
            "long-pending-operation", "overdue-payment");

    public String safeFallback(String riskType, Map<String, Object> evidence) {
        if (!TYPES.contains(riskType) || evidence == null || evidence.isEmpty()) {
            throw new IllegalArgumentException("风险解释输入不合法");
        }
        return "确定性规则证据已命中；模型解释当前不可用，请人工核验业务事实后再记录处置结论。";
    }
}
