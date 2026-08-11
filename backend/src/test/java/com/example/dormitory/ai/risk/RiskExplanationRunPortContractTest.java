package com.example.dormitory.ai.risk;

import com.example.dormitory.ai.infrastructure.fake.DeterministicFakeRiskExplanationRunAdapter;
import com.example.dormitory.ai.infrastructure.risk.DisabledRiskExplanationRunAdapter;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RiskExplanationRunPortContractTest {

    @Test
    void deterministicFakeReturnsOnlyAValidatedSucceededRiskRunReceipt() {
        RiskExplanationRunPort.RunRequest request = request();
        var fake = DeterministicFakeRiskExplanationRunAdapter.succeeded(
                91L, "00000000-0000-0000-0000-000000000091", "确定性 fake 解释");

        RiskExplanationRunPort.RunOutcome outcome = fake.run(request);

        assertEquals(RiskExplanationRunPort.OutcomeStatus.SUCCEEDED, outcome.status());
        assertEquals("RISK", outcome.receipt().capability());
        assertEquals("SUCCEEDED", outcome.receipt().state());
        assertEquals(1, fake.invocationCount());
        assertEquals(request, fake.requests().getFirst());
        assertThrows(IllegalArgumentException.class, () -> new RiskExplanationRunPort.RunReceipt(
                91L, "00000000-0000-0000-0000-000000000091", "ASSISTANT", "SUCCEEDED", "错误能力"));
        assertThrows(IllegalArgumentException.class, () -> new RiskExplanationRunPort.RunReceipt(
                91L, "00000000-0000-0000-0000-000000000091", "RISK", "RUNNING", "未完成"));
    }

    @Test
    void requestContractContainsNoHumanDispositionOrRawResourceIdAndDefaultAdapterIsDisabled() {
        var componentNames = Arrays.stream(RiskExplanationRunPort.RunRequest.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        assertFalse(componentNames.stream().anyMatch(name -> name.matches(
                "(?i).*?(event|assignee|due|detail|subjectResourceId).*")));

        RiskExplanationRunPort.RunOutcome outcome = new DisabledRiskExplanationRunAdapter().run(request());
        assertEquals(RiskExplanationRunPort.OutcomeStatus.DISABLED, outcome.status());
        assertEquals("RISK_EXPLANATION_DISABLED", outcome.errorCode());
    }

    private RiskExplanationRunPort.RunRequest request() {
        RiskSignal signal = new RiskSignal("repair-backlog", "REPAIR_ORDER", 42L,
                "risk_token_42", "HIGH", "repair-backlog.v1",
                Map.of("ageHours", 96, "ruleThreshold", 72), Instant.parse("2026-07-11T08:00:00Z"));
        return new RiskExplanationRunPort.RunRequest(
                "00000000-0000-0000-0000-000000000042", 7L,
                RiskSignalEvidence.from(signal), RiskBusinessSnapshot.from(signal));
    }
}
