package com.example.dormitory.ai.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EvaluationDatasetRegistryTamperTest {

    private static final String DATASET_PATH = "ai/eval/dashboard-intent-v1.jsonl";
    private static final String MANIFEST_PATH = "ai/eval/manifest-v1.json";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void injectedReaderStillLoadsAValidPinnedDatasetThroughThePublicBoundary() {
        String dataset = line(validCase());

        EvaluationDatasetRegistry.LoadedDataset loaded = registry(dataset, validManifest(bytes(dataset)))
                .load("dashboard-intent", "dashboard-intent-v1");

        assertEquals(1, loaded.cases().size());
        assertEquals("case-001", loaded.cases().getFirst().caseKey());
    }

    @Test
    void manifestSchemaGovernanceAndPinnedFileFactsFailClosed() {
        assertManifestRejected(root -> root.put("schemaVersion", "v2"));
        assertManifestRejected(root -> root.put("reviewStatus", "UNREVIEWED"));
        assertManifestRejected(root -> root.put("reviewedBy", "x"));
        assertManifestRejected(root -> root.set("datasets", objectMapper.createObjectNode()));
        assertManifestRejected(root -> ((ArrayNode) root.path("datasets"))
                .add(root.path("datasets").get(0).deepCopy()));
        assertManifestRejected(root -> ((ArrayNode) root.path("datasets")).removeAll());
        assertManifestRejected(root -> entry(root).put("fileName", "other.jsonl"));
        assertManifestRejected(root -> entry(root).put("sha256", "0".repeat(64)));
        assertManifestRejected(root -> entry(root).put("caseCount", 0));
        assertManifestRejected(root -> entry(root).put("caseCount", 10_001));
        assertManifestRejected(root -> entry(root).put("classification", "CONTAINS_PII"));
        assertManifestRejected(root -> entry(root).put("reviewedBy", "different-reviewer"));
        assertManifestRejected(root -> entry(root).put("createdBy", "x"));
        assertManifestRejected(root -> entry(root).put("source", "x"));
        assertManifestRejected(root -> entry(root).put("license", "x"));
        assertManifestRejected(root -> entry(root).put("license", "MIT"));
        assertManifestRejected(root -> entry(root).put("license", "Apache 2.0"));
        assertManifestRejected(root -> entry(root).put("changeSummary", "x"));

        assertThrows(IllegalStateException.class,
                () -> registryRaw(line(validCase()), "[]").load(
                        "dashboard-intent", "dashboard-intent-v1"));
        assertThrows(IllegalStateException.class,
                () -> registryRaw(line(validCase()), "null").load(
                        "dashboard-intent", "dashboard-intent-v1"));
    }

    @Test
    void caseIdentityAndBaseContractTamperingFailClosed() {
        assertCaseRejected(payload -> payload.put("caseKey", ""));
        assertCaseRejected(payload -> payload.put("caseKey", "UPPER CASE"));
        assertCaseRejected(payload -> payload.put("capability", "KNOWLEDGE"));
        assertCaseRejected(payload -> payload.put("input", "not-object"));
        assertCaseRejected(payload -> payload.put("fixtures", "not-object"));
        assertCaseRejected(payload -> payload.put("expected", "not-object"));
        assertCaseRejected(payload -> payload.put("datasetVersion", "v2"));

        String duplicated = line(validCase()) + line(validCase());
        ObjectNode duplicateManifest = validManifest(bytes(duplicated));
        entry(duplicateManifest).put("caseCount", 2);
        assertThrows(IllegalStateException.class,
                () -> registry(duplicated, duplicateManifest).load(
                        "dashboard-intent", "dashboard-intent-v1"));
        assertThrows(IllegalStateException.class,
                () -> registryRaw("not-json\n", objectMapperString(validManifest(bytes("not-json\n"))))
                        .load("dashboard-intent", "dashboard-intent-v1"));
        assertThrows(IllegalStateException.class,
                () -> registry("", validManifest(bytes(""))).load(
                        "dashboard-intent", "dashboard-intent-v1"));
    }

    @Test
    void expectedToolCitationAndForbiddenPatternContractsFailClosed() {
        assertCaseRejected(payload -> expected(payload).put("allowedTools", "not-array"));
        assertCaseRejected(payload -> expected(payload).put("requiredCitationKeys", "not-array"));
        assertCaseRejected(payload -> expected(payload).put("forbiddenPatterns", "not-array"));
        assertCaseRejected(payload -> expected(payload).put("securityOutcome", "UNKNOWN"));
        assertCaseRejected(payload -> expected(payload).withArray("allowedTools").add(1));
        assertCaseRejected(payload -> expected(payload).withArray("allowedTools").add("sql.execute.v1"));

        assertCaseRejected(payload -> {
            ArrayNode values = expected(payload).withArray("requiredCitationKeys");
            for (int index = 0; index < 65; index++) values.add("citation-" + index);
        });
        assertCaseRejected(payload -> expected(payload).withArray("requiredCitationKeys").add(""));
        assertCaseRejected(payload -> expected(payload).withArray("requiredCitationKeys").add("x".repeat(513)));
        assertCaseRejected(payload -> expected(payload).withArray("forbiddenPatterns").add(1));
        assertCaseRejected(payload -> expected(payload).withArray("forbiddenPatterns").add(" "));
    }

    @Test
    void caseTagsMustContainLocaleAndExactlyOneControlledTier() {
        assertCaseRejected(payload -> payload.put("tags", "not-array"));
        assertCaseRejected(payload -> payload.set("tags", objectMapper.createArrayNode()));
        assertCaseRejected(payload -> payload.withArray("tags").add(1));
        assertCaseRejected(payload -> payload.withArray("tags").add("bad tag"));
        assertCaseRejected(payload -> payload.set("tags", objectMapper.createArrayNode().add("golden")));
        assertCaseRejected(payload -> payload.set("tags", objectMapper.createArrayNode().add("zh-CN")));
        assertCaseRejected(payload -> payload.set("tags", objectMapper.createArrayNode()
                .add("zh-CN").add("golden").add("challenge")));
    }

    @Test
    void missingResourcesAndCaseCountMismatchAreRejected() {
        String dataset = line(validCase());
        Map<String, byte[]> missingManifest = Map.of(DATASET_PATH, bytes(dataset));
        EvaluationDatasetRegistry registry = new EvaluationDatasetRegistry(objectMapper, path -> {
            byte[] resource = missingManifest.get(path);
            if (resource == null) throw new IllegalStateException("missing " + path);
            return resource;
        });
        assertThrows(IllegalStateException.class,
                () -> registry.load("dashboard-intent", "dashboard-intent-v1"));

        ObjectNode manifest = validManifest(bytes(dataset));
        entry(manifest).put("caseCount", 2);
        assertThrows(IllegalStateException.class,
                () -> registry(dataset, manifest).load("dashboard-intent", "dashboard-intent-v1"));
    }

    private void assertManifestRejected(Consumer<ObjectNode> mutation) {
        String dataset = line(validCase());
        ObjectNode manifest = validManifest(bytes(dataset));
        mutation.accept(manifest);
        assertThrows(IllegalStateException.class,
                () -> registry(dataset, manifest).load("dashboard-intent", "dashboard-intent-v1"));
    }

    private void assertCaseRejected(Consumer<ObjectNode> mutation) {
        ObjectNode payload = validCase();
        mutation.accept(payload);
        String dataset = line(payload);
        assertThrows(IllegalStateException.class,
                () -> registry(dataset, validManifest(bytes(dataset))).load(
                        "dashboard-intent", "dashboard-intent-v1"));
    }

    private EvaluationDatasetRegistry registry(String dataset, ObjectNode manifest) {
        return registryRaw(dataset, objectMapperString(manifest));
    }

    private EvaluationDatasetRegistry registryRaw(String dataset, String manifest) {
        Map<String, byte[]> resources = new HashMap<>();
        resources.put(DATASET_PATH, bytes(dataset));
        resources.put(MANIFEST_PATH, bytes(manifest));
        return new EvaluationDatasetRegistry(objectMapper, path -> {
            byte[] resource = resources.get(path);
            if (resource == null) throw new IllegalStateException("missing " + path);
            return resource;
        });
    }

    private ObjectNode validCase() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("caseKey", "case-001");
        payload.put("capability", "DASHBOARD");
        payload.set("input", objectMapper.createObjectNode().put("question", "待处理维修数量"));
        payload.set("fixtures", objectMapper.createObjectNode());
        ObjectNode expected = objectMapper.createObjectNode();
        expected.set("allowedTools", objectMapper.createArrayNode().add("dashboard.query_metric.v1"));
        expected.set("requiredCitationKeys", objectMapper.createArrayNode());
        expected.set("forbiddenPatterns", objectMapper.createArrayNode());
        expected.put("securityOutcome", "ALLOW");
        payload.set("expected", expected);
        payload.put("datasetVersion", "v1");
        payload.set("tags", objectMapper.createArrayNode().add("zh-CN").add("golden"));
        return payload;
    }

    private ObjectNode validManifest(byte[] datasetBytes) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", "eval-dataset-manifest.v1");
        root.put("reviewStatus", "APPROVED");
        root.put("reviewedBy", "security-reviewer");
        ObjectNode dataset = objectMapper.createObjectNode();
        dataset.put("datasetVersion", "dashboard-intent-v1");
        dataset.put("fileName", "dashboard-intent-v1.jsonl");
        dataset.put("sha256", sha256(datasetBytes));
        dataset.put("caseCount", 1);
        dataset.put("classification", "SYNTHETIC_NON_PII");
        dataset.put("createdBy", "evaluation-owner");
        dataset.put("reviewedBy", "security-reviewer");
        dataset.put("source", "synthetic-fixture");
        dataset.put("license", "Apache-2.0");
        dataset.put("changeSummary", "Initial controlled evaluation fixture");
        root.set("datasets", objectMapper.createArrayNode().add(dataset));
        return root;
    }

    private static ObjectNode entry(ObjectNode manifest) {
        return (ObjectNode) manifest.path("datasets").get(0);
    }

    private static ObjectNode expected(ObjectNode payload) {
        return (ObjectNode) payload.path("expected");
    }

    private String line(ObjectNode payload) {
        return objectMapperString(payload) + "\n";
    }

    private String objectMapperString(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
