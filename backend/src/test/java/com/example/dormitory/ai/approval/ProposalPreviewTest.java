package com.example.dormitory.ai.approval;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProposalPreviewTest {

    @Test
    void deterministicGroundedPreviewNeedsNoInventedModelConfidence() {
        ProposalPreview preview = preview(ProposalPreview.EvidenceBasis.DETERMINISTIC, null, true);
        assertTrue(preview.grounded());
        assertTrue(preview.confirmable());
    }

    @Test
    void modelLowConfidenceAndMissingCitationFailClosed() {
        assertFalse(preview(ProposalPreview.EvidenceBasis.MODEL, 0.69, true).confirmable());
        assertTrue(preview(ProposalPreview.EvidenceBasis.MODEL, 0.70, true).confirmable());
        assertFalse(preview(ProposalPreview.EvidenceBasis.DETERMINISTIC, null, false).confirmable());
        assertFalse(ProposalPreview.legacy("旧预览").confirmable());
    }

    private ProposalPreview preview(
            ProposalPreview.EvidenceBasis basis, Double confidence, boolean withCitation) {
        return new ProposalPreview("当前值", "建议值", "有限影响", Instant.now(), basis, confidence,
                withCitation ? List.of(new ProposalPreview.Citation(
                        "BUSINESS_SNAPSHOT", "REPAIR_ORDER:1", "授权快照", "a".repeat(64))) : List.of());
    }
}
