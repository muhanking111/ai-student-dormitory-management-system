package com.example.dormitory.ai.risk;

import java.util.List;

/** 只读且确定性的风险信号来源；不得在此调用模型。 */
public interface RiskSignalProvider {

    String id();

    String policyVersion();

    List<RiskSignal> evaluate(RiskScanScope scope);
}
