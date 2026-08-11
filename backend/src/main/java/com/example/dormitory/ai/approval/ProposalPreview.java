package com.example.dormitory.ai.approval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Persisted, action-specific preview. Evidence is intentionally optional: absence must stay visible so
 * clients can fail closed instead of inventing confidence or grounding.
 */
public record ProposalPreview(
        String currentValue,
        String proposedValue,
        String impact,
        Instant asOf,
        EvidenceBasis evidenceBasis,
        Double confidence,
        List<Citation> citations) {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final Set<String> CITATION_TYPES = Set.of("BUSINESS_SNAPSHOT", "USER_COMMAND");

    public ProposalPreview {
        currentValue = required(currentValue, "currentValue", 2_000);
        proposedValue = required(proposedValue, "proposedValue", 10_000);
        impact = required(impact, "impact", 2_000);
        evidenceBasis = evidenceBasis == null ? EvidenceBasis.UNVERIFIED : evidenceBasis;
        if (confidence != null && (!Double.isFinite(confidence) || confidence < 0 || confidence > 1)) {
            throw new IllegalArgumentException("preview confidence 不合法");
        }
        citations = citations == null ? List.of() : List.copyOf(citations);
        if (citations.size() > 20) throw new IllegalArgumentException("preview citation 过多");
    }

    public boolean grounded() {
        return asOf != null && !citations.isEmpty();
    }

    public boolean confirmable() {
        if (!grounded()) return false;
        return evidenceBasis == EvidenceBasis.DETERMINISTIC
                || evidenceBasis == EvidenceBasis.MODEL && confidence != null && confidence >= 0.7;
    }

    public String toStoredJson() {
        try {
            return CanonicalJsonHasher.canonicalize(MAPPER.writeValueAsString(this));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("提案预览序列化失败", exception);
        }
    }

    public static ProposalPreview fromStored(String value) {
        if (value == null || value.isBlank()) return legacy("旧提案缺少可验证预览");
        try {
            JsonNode root = MAPPER.readTree(value);
            if (root == null || !root.isObject() || !root.hasNonNull("currentValue")
                    || !root.hasNonNull("proposedValue") || !root.hasNonNull("impact")) {
                return legacy(value);
            }
            return MAPPER.treeToValue(root, ProposalPreview.class);
        } catch (RuntimeException | JsonProcessingException exception) {
            return legacy(value);
        }
    }

    public static ProposalPreview legacy(String text) {
        String safe = text == null || text.isBlank() ? "旧提案缺少可验证预览" : text.trim();
        return new ProposalPreview("旧格式未保存当前值", safe,
                "旧格式缺少来源、数据时间或置信事实，必须重新创建提案",
                null, EvidenceBasis.UNVERIFIED, null, List.of());
    }

    private static String required(String value, String field, int max) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > max
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("preview " + field + " 不合法");
        }
        return normalized;
    }

    public record Citation(String type, String sourceRef, String label, String contentHash) {
        public Citation {
            if (!CITATION_TYPES.contains(type)
                    || sourceRef == null || sourceRef.isBlank() || sourceRef.length() > 256
                    || label == null || label.isBlank() || label.length() > 500
                    || sourceRef.codePoints().anyMatch(Character::isISOControl)
                    || label.codePoints().anyMatch(Character::isISOControl)
                    || contentHash == null || !contentHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("preview citation 不合法");
            }
        }
    }

    public enum EvidenceBasis { DETERMINISTIC, MODEL, UNVERIFIED }
}
