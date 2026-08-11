package com.example.dormitory.ai.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationDatasetRegistryTest {

    private static final List<DatasetRef> DATASETS = List.of(
            new DatasetRef("dashboard-intent", "dashboard-intent-v1", "DASHBOARD"),
            new DatasetRef("knowledge-grounding", "knowledge-grounding-v1", "KNOWLEDGE"),
            new DatasetRef("notice-draft", "notice-draft-v1", "NOTICE"),
            new DatasetRef("repair-triage", "repair-triage-v1", "REPAIR"),
            new DatasetRef("risk-explanation", "risk-explanation-v1", "RISK"),
            new DatasetRef("security-redteam", "security-redteam-v1", "SECURITY"));

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EvaluationDatasetRegistry registry = new EvaluationDatasetRegistry(objectMapper);

    @Test
    void everyCaseImplementsTheVersionedOfflineEvaluationContract() {
        for (DatasetRef ref : DATASETS) {
            EvaluationDatasetRegistry.LoadedDataset loaded = registry.load(ref.suite(), ref.version());
            assertEquals(ref.capability(), loaded.capability());
            assertTrue(loaded.manifest().sha256().matches("[0-9a-f]{64}"));
            assertEquals("SYNTHETIC_NON_PII", loaded.manifest().classification());
            for (EvaluationDatasetRegistry.EvaluationCase evaluationCase : loaded.cases()) {
                JsonNode payload = evaluationCase.payload();
                assertEquals(ref.capability(), payload.path("capability").asText(), evaluationCase.caseKey());
                assertTrue(payload.path("input").isObject(), evaluationCase.caseKey());
                assertTrue(payload.path("fixtures").isObject(), evaluationCase.caseKey());
                JsonNode expected = payload.path("expected");
                assertTrue(expected.isObject(), evaluationCase.caseKey());
                assertTrue(expected.path("allowedTools").isArray(), evaluationCase.caseKey());
                assertTrue(expected.path("requiredCitationKeys").isArray(), evaluationCase.caseKey());
                assertTrue(expected.path("forbiddenPatterns").isArray(), evaluationCase.caseKey());
                assertTrue(expected.path("securityOutcome").isTextual(), evaluationCase.caseKey());
                assertEquals("v1", payload.path("datasetVersion").asText(), evaluationCase.caseKey());
                Set<String> tags = objectMapper.convertValue(payload.path("tags"),
                        objectMapper.getTypeFactory().constructCollectionType(Set.class, String.class));
                assertTrue(tags.contains("zh-CN"), evaluationCase.caseKey());
                assertEquals(1, tags.stream().filter(
                        Set.of("golden", "challenge", "security-redteam")::contains).count(),
                        evaluationCase.caseKey());
            }
        }
    }

    @Test
    void manifestPinsHashAndGovernanceMetadataForEveryDataset() throws Exception {
        JsonNode manifest;
        try (InputStream input = getClass().getResourceAsStream("/ai/eval/manifest-v1.json")) {
            assertNotNull(input, "缺少离线评测数据集 manifest");
            manifest = objectMapper.readTree(input);
        }
        assertEquals("eval-dataset-manifest.v1", manifest.path("schemaVersion").asText());
        assertEquals(DATASETS.size(), manifest.path("datasets").size());
        for (DatasetRef ref : DATASETS) {
            JsonNode entry = findEntry(manifest.path("datasets"), ref.version());
            assertTrue(entry.path("sha256").asText().matches("[0-9a-f]{64}"), ref.version());
            assertTrue(entry.path("createdBy").asText().length() >= 3, ref.version());
            assertTrue(entry.path("reviewedBy").asText().length() >= 3, ref.version());
            assertTrue(entry.path("source").asText().length() >= 3, ref.version());
            assertEquals("Apache-2.0", entry.path("license").asText(), ref.version());
            assertEquals("SYNTHETIC_NON_PII", entry.path("classification").asText(), ref.version());
            assertTrue(entry.path("changeSummary").asText().length() >= 3, ref.version());
            byte[] bytes;
            try (InputStream data = getClass().getResourceAsStream(
                    "/ai/eval/" + ref.version() + ".jsonl")) {
                assertNotNull(data);
                bytes = data.readAllBytes();
            }
            assertEquals(entry.path("sha256").asText(), sha256(bytes), ref.version());
        }
    }

    @Test
    void loadNormalizesRegisteredNamesAndRejectsEveryUnregisteredCombination() {
        EvaluationDatasetRegistry.LoadedDataset loaded = registry.load(
                "  DASHBOARD-INTENT  ", "  DASHBOARD-INTENT-V1  ");
        assertEquals("dashboard-intent", loaded.suiteName());
        assertEquals("dashboard-intent-v1", loaded.datasetVersion());

        assertThrows(com.example.dormitory.ai.api.AiApiException.class,
                () -> registry.load(null, "dashboard-intent-v1"));
        assertThrows(com.example.dormitory.ai.api.AiApiException.class,
                () -> registry.load("dashboard-intent", null));
        assertThrows(com.example.dormitory.ai.api.AiApiException.class,
                () -> registry.load("unknown-suite", "dashboard-intent-v1"));
        assertThrows(com.example.dormitory.ai.api.AiApiException.class,
                () -> registry.load("dashboard-intent", "dashboard-intent-v2"));
    }

    @Test
    void loadedDatasetCollectionsAreImmutableSnapshots() {
        EvaluationDatasetRegistry.LoadedDataset loaded = registry.load(
                "knowledge-grounding", "knowledge-grounding-v1");

        assertThrows(UnsupportedOperationException.class,
                () -> loaded.requiredPermissions().add("ai:knowledge:publish-public"));
        assertThrows(UnsupportedOperationException.class,
                () -> loaded.cases().clear());
    }

    private static JsonNode findEntry(JsonNode entries, String version) {
        for (JsonNode entry : entries) {
            if (version.equals(entry.path("datasetVersion").asText())) return entry;
        }
        throw new AssertionError("manifest 缺少数据集 " + version);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record DatasetRef(String suite, String version, String capability) {
    }
}
