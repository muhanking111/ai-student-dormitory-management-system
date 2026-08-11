package com.example.dormitory.ai.risk;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RiskCalibrationReportGeneratorTest {

    private final RiskCalibrationReportGenerator generator = new RiskCalibrationReportGenerator();

    @Test
    void refusesToClaimCalibrationWithFewerThanThirtyApprovedLabels() {
        RiskCalibrationReportGenerator.CalibrationReport report = generator.generate(samples(29, 29, 0, 0));

        assertEquals(RiskCalibrationReportGenerator.CalibrationStatus.NOT_CALIBRATED, report.status());
        assertEquals(29, report.approvedLabelCount());
        assertEquals(1.0d, report.ruleEvidenceCompleteness());
        assertEquals(1.0d, report.humanConfirmedPrecision());
    }

    @Test
    void calculatesExecutableEvidencePrecisionAndDuplicateGates() {
        RiskCalibrationReportGenerator.CalibrationReport pass = generator.generate(samples(30, 24, 6, 1));
        RiskCalibrationReportGenerator.CalibrationReport fail = generator.generate(samples(30, 23, 7, 2));

        assertEquals(RiskCalibrationReportGenerator.CalibrationStatus.PASS, pass.status());
        assertEquals(0.80d, pass.humanConfirmedPrecision());
        assertEquals(1.0d / 30.0d, pass.duplicateRate());
        assertEquals(RiskCalibrationReportGenerator.CalibrationStatus.FAIL, fail.status());
    }

    private List<RiskCalibrationReportGenerator.CalibrationSample> samples(
            int total,
            int confirmed,
            int falsePositive,
            int duplicates) {
        List<RiskCalibrationReportGenerator.CalibrationSample> values = new ArrayList<>();
        for (int index = 0; index < total; index++) {
            RiskCalibrationReportGenerator.HumanLabel label = index < confirmed
                    ? RiskCalibrationReportGenerator.HumanLabel.CONFIRMED_RISK
                    : index < confirmed + falsePositive
                            ? RiskCalibrationReportGenerator.HumanLabel.FALSE_POSITIVE
                            : RiskCalibrationReportGenerator.HumanLabel.NOT_REVIEWED;
            values.add(new RiskCalibrationReportGenerator.CalibrationSample(
                    "risk-calibration-" + index, true, label, index < duplicates));
        }
        return values;
    }
}
