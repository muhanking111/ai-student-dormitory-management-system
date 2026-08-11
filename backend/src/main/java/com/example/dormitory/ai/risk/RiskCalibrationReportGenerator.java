package com.example.dormitory.ai.risk;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 风险规则的确定性离线校准器。少于批准的最小人工样本时只报告 NOT_CALIBRATED，
 * 不允许把建议阈值伪装为发布通过。
 */
public final class RiskCalibrationReportGenerator {

    public static final int MIN_APPROVED_LABELS = 30;
    public static final String POLICY_VERSION = "risk-calibration.v1";

    public CalibrationReport generate(List<CalibrationSample> samples) {
        if (samples == null || samples.isEmpty()) throw new IllegalArgumentException("风险校准样本不能为空");
        List<CalibrationSample> values = List.copyOf(samples);
        Set<String> ids = new HashSet<>();
        for (CalibrationSample sample : values) {
            if (sample == null || !ids.add(sample.caseId())) {
                throw new IllegalArgumentException("风险校准样本 ID 必须唯一");
            }
        }

        long completeEvidence = values.stream().filter(CalibrationSample::ruleEvidenceComplete).count();
        long duplicates = values.stream().filter(CalibrationSample::duplicateActiveCase).count();
        long approved = values.stream().filter(sample -> sample.humanLabel() != HumanLabel.NOT_REVIEWED).count();
        long confirmed = values.stream().filter(sample -> sample.humanLabel() == HumanLabel.CONFIRMED_RISK).count();
        double completeness = ratio(completeEvidence, values.size());
        double precision = ratio(confirmed, approved);
        double duplicateRate = ratio(duplicates, values.size());

        CalibrationStatus status;
        if (approved < MIN_APPROVED_LABELS) {
            status = CalibrationStatus.NOT_CALIBRATED;
        } else if (completeness == 1.0d && precision >= 0.80d && duplicateRate <= 0.05d) {
            status = CalibrationStatus.PASS;
        } else {
            status = CalibrationStatus.FAIL;
        }
        return new CalibrationReport(POLICY_VERSION, status, values.size(), approved, confirmed,
                completeEvidence, duplicates, completeness, precision, duplicateRate,
                MIN_APPROVED_LABELS, 1.0d, 0.80d, 0.05d);
    }

    private double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0.0d : (double) numerator / denominator;
    }

    public enum HumanLabel {
        CONFIRMED_RISK,
        FALSE_POSITIVE,
        NOT_REVIEWED
    }

    public enum CalibrationStatus {
        NOT_CALIBRATED,
        PASS,
        FAIL
    }

    public record CalibrationSample(
            String caseId,
            boolean ruleEvidenceComplete,
            HumanLabel humanLabel,
            boolean duplicateActiveCase) {
        public CalibrationSample {
            if (caseId == null || caseId.isBlank() || caseId.length() > 128 || humanLabel == null) {
                throw new IllegalArgumentException("风险校准样本不合法");
            }
        }
    }

    public record CalibrationReport(
            String policyVersion,
            CalibrationStatus status,
            int sampleCount,
            long approvedLabelCount,
            long confirmedRiskCount,
            long completeEvidenceCount,
            long duplicateCaseCount,
            double ruleEvidenceCompleteness,
            double humanConfirmedPrecision,
            double duplicateRate,
            int minimumApprovedLabels,
            double requiredEvidenceCompleteness,
            double minimumPrecision,
            double maximumDuplicateRate) { }
}
