package com.example.dormitory.ai.observability;

import java.util.ArrayList;
import java.util.List;

/** 确定性告警判断；只产生稳定告警决定，不自动改配置或调用外部告警系统。 */
public final class AiAlertRuleEngine {

    private final AlertPolicy policy;

    public AiAlertRuleEngine(AlertPolicy policy) {
        this.policy = java.util.Objects.requireNonNull(policy, "告警策略不能为空");
    }

    public List<Alert> evaluate(OperationalSnapshot snapshot) {
        java.util.Objects.requireNonNull(snapshot, "运行快照不能为空");
        List<Alert> alerts = new ArrayList<>();
        long zeroTolerance = snapshot.unauthorizedBusinessWrites()
                + snapshot.crossAclCitations()
                + snapshot.l3SecretEgresses()
                + snapshot.dynamicToolSuccesses()
                + snapshot.staleProposalExecutions()
                + snapshot.dashboardValueMismatches()
                + snapshot.duplicateBusinessWrites();
        if (zeroTolerance > 0) {
            alerts.add(critical("AI_SECURITY_ZERO_TOLERANCE", zeroTolerance, 0));
        }
        if (!snapshot.auditWritable()) {
            alerts.add(critical("AI_AUDIT_UNAVAILABLE", 1, 0));
        }
        if (snapshot.providerAttempts() >= policy.minimumProviderSamples()) {
            double rate = ratio(snapshot.providerFailures(), snapshot.providerAttempts());
            if (rate >= policy.providerErrorRate()) {
                alerts.add(warning("AI_PROVIDER_ERROR_RATE", rate, policy.providerErrorRate()));
            }
        }
        addRelativeRegression(alerts, "AI_LATENCY_P95_REGRESSION",
                snapshot.baselineLatencyP95Millis(), snapshot.currentLatencyP95Millis(),
                policy.latencyIncreaseFraction());
        addRelativeRegression(alerts, "AI_COST_P95_REGRESSION",
                snapshot.baselineCostP95Micros(), snapshot.currentCostP95Micros(),
                policy.costIncreaseFraction());
        if (known(snapshot.baselineGroundedness(), snapshot.currentGroundedness())
                && snapshot.baselineGroundedness() - snapshot.currentGroundedness()
                >= policy.groundednessDrop()) {
            alerts.add(warning("AI_GROUNDEDNESS_REGRESSION", snapshot.currentGroundedness(),
                    snapshot.baselineGroundedness() - policy.groundednessDrop()));
        }
        if (known(snapshot.baselinePiiBlockRate(), snapshot.currentPiiBlockRate())
                && snapshot.currentPiiBlockRate() > 0
                && snapshot.currentPiiBlockRate() >= snapshot.baselinePiiBlockRate()
                * policy.piiBlockRateMultiplier()) {
            alerts.add(warning("AI_PII_BLOCK_RATE_REGRESSION", snapshot.currentPiiBlockRate(),
                    snapshot.baselinePiiBlockRate() * policy.piiBlockRateMultiplier()));
        }
        return List.copyOf(alerts);
    }

    private void addRelativeRegression(
            List<Alert> alerts,
            String code,
            Double baseline,
            Double current,
            double increaseFraction) {
        if (known(baseline, current) && baseline > 0 && current >= baseline * (1 + increaseFraction)) {
            alerts.add(warning(code, current, baseline * (1 + increaseFraction)));
        }
    }

    private Alert critical(String code, double observed, double threshold) {
        return new Alert(code, Severity.CRITICAL, true, policy.policyVersion(), observed, threshold,
                policy.calibrated());
    }

    private Alert warning(String code, double observed, double threshold) {
        return new Alert(code, Severity.WARNING, false, policy.policyVersion(), observed, threshold,
                policy.calibrated());
    }

    private static boolean known(Double first, Double second) {
        return first != null && second != null && Double.isFinite(first) && Double.isFinite(second);
    }

    private static double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }

    public enum Severity { WARNING, CRITICAL }

    public record Alert(
            String code,
            Severity severity,
            boolean killSwitchRecommended,
            String policyVersion,
            double observedValue,
            double thresholdValue,
            boolean calibrated) {
    }

    public record AlertPolicy(
            String policyVersion,
            boolean calibrated,
            int minimumProviderSamples,
            double providerErrorRate,
            double latencyIncreaseFraction,
            double costIncreaseFraction,
            double groundednessDrop,
            double piiBlockRateMultiplier) {

        public AlertPolicy {
            if (policyVersion == null || !policyVersion.matches("[A-Za-z0-9][A-Za-z0-9._-]{2,63}")
                    || minimumProviderSamples < 1
                    || !unitRate(providerErrorRate)
                    || !unitRate(latencyIncreaseFraction)
                    || !unitRate(costIncreaseFraction)
                    || !unitRate(groundednessDrop)
                    || !Double.isFinite(piiBlockRateMultiplier) || piiBlockRateMultiplier < 1) {
                throw new IllegalArgumentException("告警策略不合法");
            }
        }

        public static AlertPolicy initialDefaults() {
            return new AlertPolicy("ai-alert-initial-v1", false, 20, 0.50, 0.30, 0.30, 0.05, 2.0);
        }

        private static boolean unitRate(double value) {
            return Double.isFinite(value) && value >= 0 && value <= 1;
        }
    }

    public record OperationalSnapshot(
            boolean auditWritable,
            long unauthorizedBusinessWrites,
            long crossAclCitations,
            long l3SecretEgresses,
            long dynamicToolSuccesses,
            long staleProposalExecutions,
            long dashboardValueMismatches,
            long duplicateBusinessWrites,
            long providerAttempts,
            long providerFailures,
            Double baselineLatencyP95Millis,
            Double currentLatencyP95Millis,
            Double baselineCostP95Micros,
            Double currentCostP95Micros,
            Double baselineGroundedness,
            Double currentGroundedness,
            Double baselinePiiBlockRate,
            Double currentPiiBlockRate) {

        public OperationalSnapshot {
            if (List.of(unauthorizedBusinessWrites, crossAclCitations, l3SecretEgresses,
                    dynamicToolSuccesses, staleProposalExecutions, dashboardValueMismatches,
                    duplicateBusinessWrites, providerAttempts, providerFailures).stream().anyMatch(value -> value < 0)
                    || providerFailures > providerAttempts) {
                throw new IllegalArgumentException("运行快照计数不合法");
            }
            validateNonNegative(baselineLatencyP95Millis, currentLatencyP95Millis,
                    baselineCostP95Micros, currentCostP95Micros);
            validateRate(baselineGroundedness, currentGroundedness,
                    baselinePiiBlockRate, currentPiiBlockRate);
        }

        public static Builder builder() { return new Builder(); }

        private static void validateNonNegative(Double... values) {
            for (Double value : values) {
                if (value != null && (!Double.isFinite(value) || value < 0)) {
                    throw new IllegalArgumentException("运行快照数值不合法");
                }
            }
        }

        private static void validateRate(Double... values) {
            for (Double value : values) {
                if (value != null && (!Double.isFinite(value) || value < 0 || value > 1)) {
                    throw new IllegalArgumentException("运行快照比例不合法");
                }
            }
        }

        public static final class Builder {
            private boolean auditWritable = true;
            private long unauthorizedBusinessWrites;
            private long crossAclCitations;
            private long l3SecretEgresses;
            private long dynamicToolSuccesses;
            private long staleProposalExecutions;
            private long dashboardValueMismatches;
            private long duplicateBusinessWrites;
            private long providerAttempts;
            private long providerFailures;
            private Double baselineLatencyP95Millis;
            private Double currentLatencyP95Millis;
            private Double baselineCostP95Micros;
            private Double currentCostP95Micros;
            private Double baselineGroundedness;
            private Double currentGroundedness;
            private Double baselinePiiBlockRate;
            private Double currentPiiBlockRate;

            public Builder auditWritable(boolean value) { auditWritable = value; return this; }
            public Builder unauthorizedBusinessWrites(long value) { unauthorizedBusinessWrites = value; return this; }
            public Builder crossAclCitations(long value) { crossAclCitations = value; return this; }
            public Builder l3SecretEgresses(long value) { l3SecretEgresses = value; return this; }
            public Builder dynamicToolSuccesses(long value) { dynamicToolSuccesses = value; return this; }
            public Builder staleProposalExecutions(long value) { staleProposalExecutions = value; return this; }
            public Builder dashboardValueMismatches(long value) { dashboardValueMismatches = value; return this; }
            public Builder duplicateBusinessWrites(long value) { duplicateBusinessWrites = value; return this; }
            public Builder providerAttempts(long value) { providerAttempts = value; return this; }
            public Builder providerFailures(long value) { providerFailures = value; return this; }
            public Builder baselineLatencyP95Millis(double value) { baselineLatencyP95Millis = value; return this; }
            public Builder currentLatencyP95Millis(double value) { currentLatencyP95Millis = value; return this; }
            public Builder baselineCostP95Micros(double value) { baselineCostP95Micros = value; return this; }
            public Builder currentCostP95Micros(double value) { currentCostP95Micros = value; return this; }
            public Builder baselineGroundedness(double value) { baselineGroundedness = value; return this; }
            public Builder currentGroundedness(double value) { currentGroundedness = value; return this; }
            public Builder baselinePiiBlockRate(double value) { baselinePiiBlockRate = value; return this; }
            public Builder currentPiiBlockRate(double value) { currentPiiBlockRate = value; return this; }

            public OperationalSnapshot build() {
                return new OperationalSnapshot(auditWritable, unauthorizedBusinessWrites, crossAclCitations,
                        l3SecretEgresses, dynamicToolSuccesses, staleProposalExecutions,
                        dashboardValueMismatches, duplicateBusinessWrites, providerAttempts, providerFailures,
                        baselineLatencyP95Millis, currentLatencyP95Millis,
                        baselineCostP95Micros, currentCostP95Micros,
                        baselineGroundedness, currentGroundedness,
                        baselinePiiBlockRate, currentPiiBlockRate);
            }
        }
    }
}
