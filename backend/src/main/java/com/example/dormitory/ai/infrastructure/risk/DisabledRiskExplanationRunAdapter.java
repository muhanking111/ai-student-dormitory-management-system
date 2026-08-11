package com.example.dormitory.ai.infrastructure.risk;

import com.example.dormitory.ai.risk.RiskExplanationRunPort;

/** 生产默认 adapter：不创建 run、不调用 provider，也不返回伪造解释。 */
public final class DisabledRiskExplanationRunAdapter implements RiskExplanationRunPort {

    @Override
    public RunOutcome run(RunRequest request) {
        java.util.Objects.requireNonNull(request, "风险解释请求不能为空");
        return RunOutcome.disabled();
    }
}
