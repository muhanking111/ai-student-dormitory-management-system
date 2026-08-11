package com.example.dormitory.ai.evaluation;

import com.example.dormitory.ai.api.AiApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class EvaluationDatasetRegistry {

    private static final String MANIFEST_RESOURCE = "ai/eval/manifest-v1.json";
    private static final String DATASET_LICENSE = "Apache-2.0";
    private static final Set<String> CASE_TIERS = Set.of("golden", "challenge", "security-redteam");
    private static final Set<String> SECURITY_OUTCOMES = Set.of("ALLOW", "REFUSE", "DEGRADE");
    private static final Set<String> ALLOWED_TOOL_IDS = Set.of(
            "knowledge.search.v1", "dashboard.query_metric.v1", "repair.get_context.v1",
            "dormitory.get_capacity_summary.v1", "notice.list_published.v1",
            "repair.propose_assignment.v1", "notice.propose_draft.v1");

    private static final Map<String, DatasetDefinition> DATASETS = Map.of(
            "dashboard-intent", new DatasetDefinition(
                    "dashboard-intent-v1", "DASHBOARD", Set.of("ai:dashboard:query")),
            "knowledge-grounding", new DatasetDefinition(
                    "knowledge-grounding-v1", "KNOWLEDGE", Set.of("ai:knowledge:read")),
            "notice-draft", new DatasetDefinition(
                    "notice-draft-v1", "NOTICE", Set.of("ai:notice:draft", "notice:read")),
            "repair-triage", new DatasetDefinition(
                    "repair-triage-v1", "REPAIR", Set.of("ai:repair:triage", "repair:read")),
            "risk-explanation", new DatasetDefinition(
                    "risk-explanation-v1", "RISK", Set.of("ai:risk:read")),
            "security-redteam", new DatasetDefinition(
                    "security-redteam-v1", "SECURITY", Set.of("ai:audit:read")));

    private final ObjectMapper objectMapper;
    private final ResourceReader resourceReader;

    @Autowired
    public EvaluationDatasetRegistry(ObjectMapper objectMapper) {
        this(objectMapper, EvaluationDatasetRegistry::readClasspathResource);
    }

    EvaluationDatasetRegistry(ObjectMapper objectMapper, ResourceReader resourceReader) {
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper);
        this.resourceReader = java.util.Objects.requireNonNull(resourceReader);
    }

    public LoadedDataset load(String suiteName, String datasetVersion) {
        String suite = suiteName == null ? "" : suiteName.trim().toLowerCase(java.util.Locale.ROOT);
        String version = datasetVersion == null ? "" : datasetVersion.trim().toLowerCase(java.util.Locale.ROOT);
        DatasetDefinition definition = DATASETS.get(suite);
        if (definition == null || !definition.version().equals(version)) {
            throw new AiApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AI_EVAL_DATASET_NOT_ALLOWLISTED",
                    "评测套件或数据集版本未登记", false);
        }
        String fileName = definition.version() + ".jsonl";
        byte[] datasetBytes = readResource("ai/eval/" + fileName);
        DatasetManifest manifest = requireManifest(definition.version(), fileName, datasetBytes);
        List<EvaluationCase> cases = new ArrayList<>();
        Set<String> uniqueKeys = new LinkedHashSet<>();
        try {
            for (String line : new String(datasetBytes, StandardCharsets.UTF_8).lines().toList()) {
                if (line.isBlank()) continue;
                JsonNode payload = objectMapper.readTree(line);
                if (payload == null || !payload.isObject()) {
                    throw new IllegalStateException("评测数据集 case 必须是 JSON object");
                }
                String caseKey = payload.path("caseKey").asText("");
                if (!caseKey.matches("[a-z0-9][a-z0-9._-]{2,127}") || !uniqueKeys.add(caseKey)) {
                    throw new IllegalStateException("评测数据集 caseKey 缺失、重复或不合法");
                }
                validateCase(payload, caseKey, definition.capability());
                cases.add(new EvaluationCase(caseKey, payload));
            }
        } catch (AiApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("无法读取受控评测数据集", exception);
        }
        if (cases.isEmpty() || cases.size() > 10_000) {
            throw new IllegalStateException("评测数据集为空或超过安全上限");
        }
        if (cases.size() != manifest.caseCount()) {
            throw new IllegalStateException("评测数据集 case 数量与 manifest 不一致");
        }
        return new LoadedDataset(suite, definition.version(), definition.capability(),
                "urn:dormitory-ai:eval-dataset:" + definition.version() + ":sha256:" + manifest.sha256(),
                definition.requiredPermissions(), manifest, cases);
    }

    private DatasetManifest requireManifest(String version, String fileName, byte[] datasetBytes) {
        try {
            JsonNode root = objectMapper.readTree(readResource(MANIFEST_RESOURCE));
            if (root == null || !root.isObject()
                    || !"eval-dataset-manifest.v1".equals(root.path("schemaVersion").asText())
                    || !root.path("datasets").isArray()) {
                throw new IllegalStateException("评测数据集 manifest schema 不合法");
            }
            String reviewStatus = requireText(root, "reviewStatus", "[A-Z_]{3,64}");
            if (!Set.of("PENDING_INDEPENDENT_SECURITY_REVIEW", "APPROVED").contains(reviewStatus)) {
                throw new IllegalStateException("评测数据集 manifest reviewStatus 不合法");
            }
            String manifestReviewer = requireText(root, "reviewedBy", "[A-Za-z0-9._:-]{3,128}");
            JsonNode match = null;
            for (JsonNode entry : root.path("datasets")) {
                if (version.equals(entry.path("datasetVersion").asText())) {
                    if (match != null) throw new IllegalStateException("manifest 数据集版本重复");
                    match = entry;
                }
            }
            if (match == null || !match.isObject()) {
                throw new IllegalStateException("manifest 未登记评测数据集版本");
            }
            String hash = requireText(match, "sha256", "[0-9a-f]{64}");
            if (!fileName.equals(requireText(match, "fileName", "[a-z0-9][a-z0-9.-]{2,127}"))
                    || !hash.equals(sha256(datasetBytes))) {
                throw new IllegalStateException("评测数据集文件名或 hash 与 manifest 不一致");
            }
            int caseCount = match.path("caseCount").asInt(0);
            if (caseCount < 1 || caseCount > 10_000) {
                throw new IllegalStateException("manifest caseCount 不合法");
            }
            String classification = requireText(match, "classification", "[A-Z0-9_]{3,64}");
            if (!"SYNTHETIC_NON_PII".equals(classification)) {
                throw new IllegalStateException("首期评测数据必须标记为合成非 PII");
            }
            String reviewedBy = requireText(match, "reviewedBy", "[A-Za-z0-9._:-]{3,128}");
            if (!manifestReviewer.equals(reviewedBy)) {
                throw new IllegalStateException("数据集审核人与 manifest 审核人不一致");
            }
            String license = requireText(match, "license", "[A-Za-z0-9][A-Za-z0-9.+-]{2,63}");
            if (!DATASET_LICENSE.equals(license)) {
                throw new IllegalStateException("评测数据集许可证必须与项目许可证一致");
            }
            return new DatasetManifest(version, fileName, hash, caseCount, reviewStatus,
                    requireText(match, "createdBy", "[A-Za-z0-9._:-]{3,128}"),
                    reviewedBy,
                    requireText(match, "source", "[A-Za-z0-9._:-]{3,128}"),
                    license, classification,
                    requireText(match, "changeSummary", ".{3,512}"));
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("无法校验评测数据集 manifest", exception);
        }
    }

    private static void validateCase(JsonNode payload, String caseKey, String capability) {
        if (!capability.equals(payload.path("capability").asText())
                || !payload.path("input").isObject()
                || !payload.path("fixtures").isObject()
                || !payload.path("expected").isObject()
                || !"v1".equals(payload.path("datasetVersion").asText())) {
            throw new IllegalStateException("评测 case 基础合同不合法: " + caseKey);
        }
        JsonNode expected = payload.path("expected");
        if (!expected.path("allowedTools").isArray()
                || !expected.path("requiredCitationKeys").isArray()
                || !expected.path("forbiddenPatterns").isArray()
                || !SECURITY_OUTCOMES.contains(expected.path("securityOutcome").asText())) {
            throw new IllegalStateException("评测 case expected 合同不合法: " + caseKey);
        }
        for (JsonNode tool : expected.path("allowedTools")) {
            if (!tool.isTextual() || !ALLOWED_TOOL_IDS.contains(tool.asText())) {
                throw new IllegalStateException("评测 case 引用了非白名单工具: " + caseKey);
            }
        }
        validateBoundedTextArray(expected.path("requiredCitationKeys"), caseKey);
        validateBoundedTextArray(expected.path("forbiddenPatterns"), caseKey);
        JsonNode tagNode = payload.path("tags");
        if (!tagNode.isArray() || tagNode.isEmpty()) {
            throw new IllegalStateException("评测 case tags 缺失: " + caseKey);
        }
        Set<String> tags = new LinkedHashSet<>();
        for (JsonNode tag : tagNode) {
            if (!tag.isTextual() || !tag.asText().matches("[A-Za-z0-9][A-Za-z0-9._-]{1,63}")) {
                throw new IllegalStateException("评测 case tag 不合法: " + caseKey);
            }
            tags.add(tag.asText());
        }
        if (!tags.contains("zh-CN") || tags.stream().filter(CASE_TIERS::contains).count() != 1) {
            throw new IllegalStateException("评测 case 必须且只能属于一个数据层级: " + caseKey);
        }
    }

    private static void validateBoundedTextArray(JsonNode values, String caseKey) {
        if (values.size() > 64) throw new IllegalStateException("评测 case 数组超过安全上限: " + caseKey);
        for (JsonNode value : values) {
            if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 512) {
                throw new IllegalStateException("评测 case 数组字段不合法: " + caseKey);
            }
        }
    }

    private byte[] readResource(String path) {
        return resourceReader.read(path);
    }

    private static byte[] readClasspathResource(String path) {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return input.readAllBytes();
        } catch (Exception exception) {
            throw new IllegalStateException("无法读取受控评测资源", exception);
        }
    }

    private static String requireText(JsonNode object, String field, String pattern) {
        String value = object.path(field).asText("");
        if (!value.matches(pattern)) throw new IllegalStateException("manifest 字段不合法: " + field);
        return value;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算评测数据集 hash", exception);
        }
    }

    public record LoadedDataset(
            String suiteName,
            String datasetVersion,
            String capability,
            String artifactPath,
            Set<String> requiredPermissions,
            DatasetManifest manifest,
            List<EvaluationCase> cases) {
        public LoadedDataset {
            requiredPermissions = Set.copyOf(requiredPermissions);
            cases = List.copyOf(cases);
        }
    }

    public record EvaluationCase(String caseKey, JsonNode payload) {
    }

    public record DatasetManifest(
            String datasetVersion,
            String fileName,
            String sha256,
            int caseCount,
            String reviewStatus,
            String createdBy,
            String reviewedBy,
            String source,
            String license,
            String classification,
            String changeSummary) {
    }

    private record DatasetDefinition(String version, String capability, Set<String> requiredPermissions) {
        private DatasetDefinition {
            requiredPermissions = Set.copyOf(requiredPermissions);
        }
    }

    @FunctionalInterface
    interface ResourceReader {
        byte[] read(String path);
    }
}
