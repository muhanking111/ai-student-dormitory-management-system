package com.example.dormitory.ai.evaluation;

import com.example.dormitory.ai.security.PiiClassificationService;
import com.example.dormitory.ai.security.PiiRedactionService;
import com.example.dormitory.ai.security.PromptInjectionGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicEvaluationEngineTest {

    private static final List<DatasetRef> DATASETS = List.of(
            new DatasetRef("dashboard-intent", "dashboard-intent-v1"),
            new DatasetRef("knowledge-grounding", "knowledge-grounding-v1"),
            new DatasetRef("notice-draft", "notice-draft-v1"),
            new DatasetRef("repair-triage", "repair-triage-v1"),
            new DatasetRef("risk-explanation", "risk-explanation-v1"),
            new DatasetRef("security-redteam", "security-redteam-v1"));

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EvaluationDatasetRegistry datasets = new EvaluationDatasetRegistry(objectMapper);
    private final PiiRedactionService redaction = new PiiRedactionService(
            "synthetic-eval-redaction-material-v1".getBytes(StandardCharsets.UTF_8),
            "eval-v1");
    private final DeterministicEvaluationEngine engine = new DeterministicEvaluationEngine(
            objectMapper,
            new PromptInjectionGuard(),
            redaction,
            new PiiClassificationService(redaction, List::of));

    @Test
    void executesEveryBundledCaseAgainstItsExpectedContract() {
        int total = 0;
        for (DatasetRef ref : DATASETS) {
            EvaluationDatasetRegistry.LoadedDataset dataset = datasets.load(ref.suite(), ref.version());

            DeterministicEvaluationEngine.DatasetEvaluation result = engine.evaluate(dataset);

            assertTrue(result.passed(), ref.version());
            assertEquals(dataset.cases().size(), result.passedCases(), ref.version());
            assertEquals(0, result.failedCases(), ref.version());
            assertTrue(result.cases().stream().allMatch(caseResult ->
                    Boolean.TRUE.equals(caseResult.metrics().get("expectedCompared"))), ref.version());
            total += result.cases().size();
        }
        assertEquals(21, total);
    }

    @Test
    void reportsAStableFailureWhenExpectedMetricDoesNotMatchActualRouting() {
        EvaluationDatasetRegistry.LoadedDataset original = datasets.load(
                "dashboard-intent", "dashboard-intent-v1");
        EvaluationDatasetRegistry.EvaluationCase source = original.cases().getFirst();
        ObjectNode payload = source.payload().deepCopy();
        ((ObjectNode) payload.path("expected")).put("metricId", "bed.available.count");
        EvaluationDatasetRegistry.LoadedDataset altered = new EvaluationDatasetRegistry.LoadedDataset(
                original.suiteName(), original.datasetVersion(), original.capability(), original.artifactPath(),
                original.requiredPermissions(), original.manifest(),
                List.of(new EvaluationDatasetRegistry.EvaluationCase(source.caseKey(), payload)));

        DeterministicEvaluationEngine.DatasetEvaluation result = engine.evaluate(altered);

        assertFalse(result.passed());
        assertEquals(0, result.passedCases());
        assertEquals(1, result.failedCases());
        assertEquals("FAILED", result.cases().getFirst().state());
        assertTrue(result.cases().getFirst().failureTags().contains("EXPECTED_FIELD_MISMATCH"));
        assertEquals("repair.pending.count",
                result.cases().getFirst().metrics().get("actualMetricId"));
    }

    @Test
    void forbiddenPatternAndAllowedToolChecksAreNotInferredFromExpectedValues() {
        EvaluationDatasetRegistry.LoadedDataset original = datasets.load(
                "security-redteam", "security-redteam-v1");
        EvaluationDatasetRegistry.EvaluationCase source = original.cases().stream()
                .filter(item -> item.caseKey().equals("plain-question"))
                .findFirst().orElseThrow();
        ObjectNode payload = source.payload().deepCopy();
        ((ObjectNode) payload.path("expected")).withArray("allowedTools").removeAll();
        ((ObjectNode) payload.path("expected")).withArray("forbiddenPatterns").add("安全处理");
        EvaluationDatasetRegistry.LoadedDataset altered = new EvaluationDatasetRegistry.LoadedDataset(
                original.suiteName(), original.datasetVersion(), original.capability(), original.artifactPath(),
                original.requiredPermissions(), original.manifest(),
                List.of(new EvaluationDatasetRegistry.EvaluationCase(source.caseKey(), payload)));

        DeterministicEvaluationEngine.CaseEvaluation result = engine.evaluate(altered).cases().getFirst();

        assertEquals("FAILED", result.state());
        assertTrue(result.failureTags().contains("TOOL_SET_MISMATCH"));
        assertTrue(result.failureTags().contains("FORBIDDEN_PATTERN_PRESENT"));
    }

    @Test
    void unsupportedCapabilityAndMissingExpectedFactsFailClosedOrProduceStableFailures() {
        EvaluationDatasetRegistry.LoadedDataset unknownCapability = altered(
                "dashboard-intent", "dashboard-intent-v1",
                payload -> payload.put("capability", "UNKNOWN"));
        assertThrows(IllegalStateException.class, () -> engine.evaluate(unknownCapability));

        DeterministicEvaluationEngine.CaseEvaluation outcomeMismatch = engine.evaluate(altered(
                "security-redteam", "security-redteam-v1",
                payload -> ((ObjectNode) payload.path("expected")).put("securityOutcome", "DEGRADE")))
                .cases().getFirst();
        assertTrue(outcomeMismatch.failureTags().contains("SECURITY_OUTCOME_MISMATCH"));

        DeterministicEvaluationEngine.CaseEvaluation citationMismatch = engine.evaluate(altered(
                "knowledge-grounding", "knowledge-grounding-v1",
                payload -> ((ObjectNode) payload.path("expected"))
                        .withArray("requiredCitationKeys").add("missing-source")))
                .cases().getFirst();
        assertTrue(citationMismatch.failureTags().contains("REQUIRED_CITATION_MISSING"));

        DeterministicEvaluationEngine.CaseEvaluation mentionMismatch = engine.evaluate(altered(
                "risk-explanation", "risk-explanation-v1",
                payload -> ((ObjectNode) payload.path("expected")).put("mustMention", "never-present")))
                .cases().getFirst();
        assertTrue(mentionMismatch.failureTags().contains("REQUIRED_TEXT_MISSING"));
    }

    @Test
    void deterministicExecutorsCoverMultiMetricMarkupBlankSourceAndFacilityBranches() {
        DeterministicEvaluationEngine.CaseEvaluation multiMetric = engine.evaluate(altered(
                "dashboard-intent", "dashboard-intent-v1", payload -> {
                    ((ObjectNode) payload.path("input")).put("question", "空余床位和待处理维修数量");
                    ((ObjectNode) payload.path("expected")).putNull("metricId");
                    ((ObjectNode) payload.path("expected")).put("", "unused");
                })).cases().getFirst();
        assertEquals(null, multiMetric.metrics().get("actualMetricId"));

        DeterministicEvaluationEngine.CaseEvaluation markup = engine.evaluate(altered(
                "notice-draft", "notice-draft-v1",
                payload -> ((ObjectNode) payload.path("input")).put("points", "plain > markup")))
                .cases().getFirst();
        assertEquals("REFUSE", markup.metrics().get("actualSecurityOutcome"));

        DeterministicEvaluationEngine.CaseEvaluation blankSource = engine.evaluate(altered(
                "risk-explanation", "risk-explanation-v1",
                payload -> ((ObjectNode) payload.path("fixtures")).put("signalSource", "")))
                .cases().getFirst();
        assertEquals(List.of(), blankSource.metrics().get("actualCitationKeys"));

        DeterministicEvaluationEngine.CaseEvaluation facility = engine.evaluate(altered(
                "repair-triage", "repair-triage-v1", payload -> {
                    ((ObjectNode) payload.path("input")).put("description", "门锁损坏");
                    ((ObjectNode) payload.path("input")).put("type", "其他");
                    ((ObjectNode) payload.path("expected")).put("category", "设施维修");
                })).cases().getFirst();
        assertEquals("设施维修", facility.metrics().get("actualCategory"));
    }

    @Test
    void comparisonTreatsMissingNonArrayExpectedSetsAsEmptyAndSummaryCountsAreValidated() {
        DeterministicEvaluationEngine.CaseEvaluation result = engine.evaluate(altered(
                "security-redteam", "security-redteam-v1", payload -> {
                    ((ObjectNode) payload.path("input")).put("text", "宿舍安全用电制度是什么");
                    ObjectNode expected = (ObjectNode) payload.path("expected");
                    expected.put("securityOutcome", "ALLOW");
                    expected.remove("allowedTools");
                    expected.put("requiredCitationKeys", "not-array");
                    expected.remove("forbiddenPatterns");
                })).cases().getFirst();
        assertTrue(result.failureTags().contains("TOOL_SET_MISMATCH"));

        List<DeterministicEvaluationEngine.CaseEvaluation> one = List.of(result);
        assertThrows(IllegalArgumentException.class,
                () -> new DeterministicEvaluationEngine.DatasetEvaluation(one, -1, 2));
        assertThrows(IllegalArgumentException.class,
                () -> new DeterministicEvaluationEngine.DatasetEvaluation(one, 1, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new DeterministicEvaluationEngine.DatasetEvaluation(one, 1, 1));
    }

    private EvaluationDatasetRegistry.LoadedDataset altered(
            String suite,
            String version,
            Consumer<ObjectNode> mutation) {
        EvaluationDatasetRegistry.LoadedDataset original = datasets.load(suite, version);
        EvaluationDatasetRegistry.EvaluationCase source = original.cases().getFirst();
        ObjectNode payload = source.payload().deepCopy();
        mutation.accept(payload);
        return new EvaluationDatasetRegistry.LoadedDataset(
                original.suiteName(), original.datasetVersion(), original.capability(), original.artifactPath(),
                original.requiredPermissions(), original.manifest(),
                List.of(new EvaluationDatasetRegistry.EvaluationCase(source.caseKey(), payload)));
    }

    private record DatasetRef(String suite, String version) {
    }
}
