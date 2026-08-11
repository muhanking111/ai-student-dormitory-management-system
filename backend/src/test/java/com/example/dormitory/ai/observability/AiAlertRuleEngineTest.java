package com.example.dormitory.ai.observability;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiAlertRuleEngineTest {

    private final AiAlertRuleEngine engine = new AiAlertRuleEngine(AiAlertRuleEngine.AlertPolicy.initialDefaults());

    @Test
    void fixedZeroToleranceAndAuditFailuresRecommendImmediateKillSwitch() {
        var snapshot = AiAlertRuleEngine.OperationalSnapshot.builder()
                .unauthorizedBusinessWrites(1)
                .auditWritable(false)
                .build();

        var alerts = engine.evaluate(snapshot);
        Set<String> codes = alerts.stream().map(AiAlertRuleEngine.Alert::code).collect(Collectors.toSet());

        assertTrue(codes.contains("AI_SECURITY_ZERO_TOLERANCE"));
        assertTrue(codes.contains("AI_AUDIT_UNAVAILABLE"));
        assertTrue(alerts.stream().filter(alert -> codes.contains(alert.code()))
                .allMatch(alert -> alert.severity() == AiAlertRuleEngine.Severity.CRITICAL
                        && alert.killSwitchRecommended()));
    }

    @Test
    void suggestedProviderAndDriftRulesRequireEnoughEvidence() {
        var lowSample = AiAlertRuleEngine.OperationalSnapshot.builder()
                .providerAttempts(19).providerFailures(19).build();
        assertFalse(codes(engine.evaluate(lowSample)).contains("AI_PROVIDER_ERROR_RATE"));

        var regressed = AiAlertRuleEngine.OperationalSnapshot.builder()
                .providerAttempts(20).providerFailures(10)
                .baselineLatencyP95Millis(100).currentLatencyP95Millis(131)
                .baselineCostP95Micros(100).currentCostP95Micros(131)
                .baselineGroundedness(0.98).currentGroundedness(0.92)
                .baselinePiiBlockRate(0.01).currentPiiBlockRate(0.021)
                .build();

        Set<String> codes = codes(engine.evaluate(regressed));
        assertTrue(codes.contains("AI_PROVIDER_ERROR_RATE"));
        assertTrue(codes.contains("AI_LATENCY_P95_REGRESSION"));
        assertTrue(codes.contains("AI_COST_P95_REGRESSION"));
        assertTrue(codes.contains("AI_GROUNDEDNESS_REGRESSION"));
        assertTrue(codes.contains("AI_PII_BLOCK_RATE_REGRESSION"));
    }

    @Test
    void healthySnapshotProducesNoAlerts() {
        assertTrue(engine.evaluate(AiAlertRuleEngine.OperationalSnapshot.builder()
                .providerAttempts(20).providerFailures(1)
                .baselineLatencyP95Millis(100).currentLatencyP95Millis(105)
                .baselineCostP95Micros(100).currentCostP95Micros(105)
                .baselineGroundedness(0.98).currentGroundedness(0.97)
                .baselinePiiBlockRate(0.01).currentPiiBlockRate(0.01)
                .build()).isEmpty());
    }

    private static Set<String> codes(java.util.List<AiAlertRuleEngine.Alert> alerts) {
        return alerts.stream().map(AiAlertRuleEngine.Alert::code).collect(Collectors.toSet());
    }
}
