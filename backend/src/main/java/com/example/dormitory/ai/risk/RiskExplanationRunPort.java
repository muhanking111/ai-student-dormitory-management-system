package com.example.dormitory.ai.risk;

import java.util.List;

/**
 * 风险解释专用受控 run 端口。业务层不依赖模型 SDK；真实 adapter 必须在返回前完成
 * RISK run 的预算、usage、审计和成功落库。
 */
public interface RiskExplanationRunPort {

    RunOutcome run(RunRequest request);

    default long invocationCount() {
        return 0L;
    }

    record RunRequest(
            String riskCasePublicId,
            long initiatedByUserId,
            RiskSignalEvidence signal,
            RiskBusinessSnapshot businessSnapshot) {
        public RunRequest {
            if (riskCasePublicId == null || !riskCasePublicId.matches("[0-9a-fA-F-]{36}")
                    || initiatedByUserId < 1 || signal == null || businessSnapshot == null) {
                throw new IllegalArgumentException("风险解释 run 请求不合法");
            }
        }
    }

    record RunReceipt(
            long runDatabaseId,
            String runPublicId,
            String capability,
            String state,
            String explanation) {
        public RunReceipt {
            if (runDatabaseId < 1 || runPublicId == null
                    || !runPublicId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                    || !"RISK".equals(capability) || !"SUCCEEDED".equals(state)
                    || explanation == null || explanation.isBlank() || explanation.length() > 4_000) {
                throw new IllegalArgumentException("风险解释 receipt 必须绑定成功的 RISK run");
            }
        }
    }

    enum OutcomeStatus {
        SUCCEEDED,
        DISABLED,
        FAILED
    }

    record RunOutcome(OutcomeStatus status, RunReceipt receipt, String errorCode) {
        private static final List<String> SAFE_ERROR_CODES = List.of(
                "RISK_EXPLANATION_DISABLED", "RISK_EXPLANATION_PROVIDER_FAILED");

        public RunOutcome {
            if (status == null
                    || (status == OutcomeStatus.SUCCEEDED && (receipt == null || errorCode != null))
                    || (status != OutcomeStatus.SUCCEEDED
                            && (receipt != null || !SAFE_ERROR_CODES.contains(errorCode)))) {
                throw new IllegalArgumentException("风险解释 run 结果不合法");
            }
        }

        public static RunOutcome succeeded(RunReceipt receipt) {
            return new RunOutcome(OutcomeStatus.SUCCEEDED, receipt, null);
        }

        public static RunOutcome disabled() {
            return new RunOutcome(OutcomeStatus.DISABLED, null, "RISK_EXPLANATION_DISABLED");
        }

        public static RunOutcome failed() {
            return new RunOutcome(OutcomeStatus.FAILED, null, "RISK_EXPLANATION_PROVIDER_FAILED");
        }
    }
}
