package com.example.dormitory.ai.evaluation;

import com.example.dormitory.ai.dashboard.DashboardIntentParser;
import com.example.dormitory.ai.dashboard.MetricCatalog;
import com.example.dormitory.ai.dashboard.UnsupportedMetricException;
import com.example.dormitory.ai.knowledge.KnowledgeAclPolicy;
import com.example.dormitory.ai.knowledge.KnowledgeAssistantService;
import com.example.dormitory.ai.knowledge.KnowledgeVisibility;
import com.example.dormitory.ai.knowledge.PermissionMatchMode;
import com.example.dormitory.ai.knowledge.SafeKnowledgeService;
import com.example.dormitory.ai.risk.RiskExplanationPolicy;
import com.example.dormitory.ai.security.DataClassification;
import com.example.dormitory.ai.security.PiiClassificationService;
import com.example.dormitory.ai.security.PiiRedactionService;
import com.example.dormitory.ai.security.PromptInjectionGuard;
import com.example.dormitory.ai.security.SensitiveDataBlockedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 执行合成离线数据集并将实际确定性结果与 expected 合同逐项比较。
 * 该引擎不调用供应商、不写业务数据，也不把输入正文写入结果 artifact。
 */
@Component
public final class DeterministicEvaluationEngine {

    private static final Set<String> CORE_EXPECTED_FIELDS = Set.of(
            "allowedTools", "requiredCitationKeys", "forbiddenPatterns", "securityOutcome", "mustMention");
    private static final Set<String> KNOWLEDGE_TOOL = Set.of("knowledge.search.v1");
    private static final Set<String> DASHBOARD_TOOL = Set.of("dashboard.query_metric.v1");

    private final ObjectMapper objectMapper;
    private final PromptInjectionGuard injectionGuard;
    private final PiiRedactionService redactionService;
    private final PiiClassificationService classificationService;
    private final DashboardIntentParser dashboardParser = new DashboardIntentParser(MetricCatalog.defaults());
    private final RiskExplanationPolicy riskPolicy = new RiskExplanationPolicy();

    public DeterministicEvaluationEngine(
            ObjectMapper objectMapper,
            PromptInjectionGuard injectionGuard,
            PiiRedactionService redactionService,
            PiiClassificationService classificationService) {
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper);
        this.injectionGuard = java.util.Objects.requireNonNull(injectionGuard);
        this.redactionService = java.util.Objects.requireNonNull(redactionService);
        this.classificationService = java.util.Objects.requireNonNull(classificationService);
    }

    public DatasetEvaluation evaluate(EvaluationDatasetRegistry.LoadedDataset dataset) {
        java.util.Objects.requireNonNull(dataset, "评测数据集不能为空");
        List<CaseEvaluation> evaluated = dataset.cases().stream().map(this::evaluateCase).toList();
        int passed = (int) evaluated.stream().filter(CaseEvaluation::passed).count();
        return new DatasetEvaluation(evaluated, passed, evaluated.size() - passed);
    }

    private CaseEvaluation evaluateCase(EvaluationDatasetRegistry.EvaluationCase evaluationCase) {
        JsonNode payload = evaluationCase.payload();
        ActualOutcome actual = switch (payload.path("capability").asText()) {
            case "DASHBOARD" -> dashboard(payload);
            case "KNOWLEDGE" -> knowledge(payload);
            case "NOTICE" -> notice(payload);
            case "REPAIR" -> repair(payload);
            case "RISK" -> risk(payload);
            case "SECURITY" -> security(payload);
            default -> throw new IllegalStateException("评测能力没有确定性执行器: "
                    + payload.path("capability").asText());
        };
        return compare(evaluationCase.caseKey(), payload.path("expected"), actual);
    }

    private ActualOutcome dashboard(JsonNode payload) {
        String question = payload.path("input").path("question").asText();
        try {
            var intent = dashboardParser.parse(question);
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("metricId", intent.metricIds().size() == 1 ? intent.metricIds().iterator().next() : null);
            return outcome("ALLOW", DASHBOARD_TOOL, intent.metricIds(),
                    "已按固定指标目录完成确定性路由。", values);
        } catch (UnsupportedMetricException | IllegalArgumentException refused) {
            return outcome("REFUSE", Set.of(), Set.of(),
                    "问题无法映射到已批准指标，未执行查询。", Map.of("metricId", NullValue.INSTANCE));
        }
    }

    private ActualOutcome knowledge(JsonNode payload) {
        JsonNode input = payload.path("input");
        JsonNode fixtures = payload.path("fixtures");
        Set<String> permissions = textSet(input.path("permissions"));
        SafeKnowledgeService knowledge = new SafeKnowledgeService(
                new KnowledgeAclPolicy(), injectionGuard, redactionService);
        String sourceKey = fixtures.path("sourceKey").asText("");
        if (!sourceKey.isBlank()) {
            knowledge.registerApprovedText(new SafeKnowledgeService.KnowledgeDocument(
                    sourceKey, "synthetic-document-v1", "synthetic-version-v1", "synthetic-content-hash-v1",
                    "合成维修制度", "普通维修应当在两个工作日内处理。",
                    KnowledgeVisibility.EXPLICIT_ACL, PermissionMatchMode.ANY,
                    Set.of("repair:read"), true));
        }
        KnowledgeAssistantService.AssistantAnswer answer = new KnowledgeAssistantService(
                knowledge, injectionGuard, classificationService)
                .answer(input.path("query").asText(), permissions, 5);
        Set<String> citations = answer.citations().stream()
                .map(SafeKnowledgeService.KnowledgeCitation::sourceId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return outcome(answer.grounded() ? "ALLOW" : "REFUSE", KNOWLEDGE_TOOL, citations,
                answer.answerText(), Map.of("grounded", answer.grounded()));
    }

    private ActualOutcome notice(JsonNode payload) {
        JsonNode input = payload.path("input");
        String points = input.path("points").asText();
        String audience = input.path("audience").asText();
        String combined = points + "\n" + audience;
        boolean blocked = combined.contains("<") || combined.contains(">")
                || injectionGuard.inspect(combined).blocked();
        if (!blocked) {
            try {
                blocked = classificationService.redact(combined, "offline-eval-notice").classification()
                        != DataClassification.L1;
            } catch (SensitiveDataBlockedException sensitive) {
                blocked = true;
            }
        }
        return outcome(blocked ? "REFUSE" : "ALLOW", Set.of(), Set.of(),
                blocked ? "公告输入未通过纯文本安全检查，已阻止生成。"
                        : "公告草稿已通过纯文本安全检查。",
                Map.of("blocked", blocked));
    }

    private ActualOutcome repair(JsonNode payload) {
        JsonNode input = payload.path("input");
        String description = input.path("description").asText();
        String existingType = input.path("type").asText();
        boolean degraded = injectionGuard.inspect(description).blocked();
        String category = repairCategory(description, existingType);
        String urgency = repairUrgency(description);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("category", category);
        values.put("urgency", urgency);
        values.put("degraded", degraded);
        return outcome(degraded ? "DEGRADE" : "ALLOW", Set.of(), Set.of(),
                degraded ? "检测到不受信任指令，已降级为人工核验。"
                        : "已依据固定规则生成分诊建议，未改变维修状态。",
                values);
    }

    private ActualOutcome risk(JsonNode payload) {
        JsonNode input = payload.path("input");
        Map<String, Object> evidence = objectMapper.convertValue(input.path("evidence"),
                objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        String explanation = riskPolicy.safeFallback(input.path("riskType").asText(), evidence);
        String source = payload.path("fixtures").path("signalSource").asText("");
        return outcome("ALLOW", Set.of(), source.isBlank() ? Set.of() : Set.of(source),
                explanation, Map.of());
    }

    private ActualOutcome security(JsonNode payload) {
        boolean blocked = injectionGuard.inspect(payload.path("input").path("text").asText()).blocked();
        return outcome(blocked ? "REFUSE" : "ALLOW", blocked ? Set.of() : KNOWLEDGE_TOOL, Set.of(),
                blocked ? "请求包含不受支持的动态指令，已拒绝。"
                        : "请求已进入安全处理，可使用授权知识检索。",
                Map.of("blocked", blocked));
    }

    private CaseEvaluation compare(String caseKey, JsonNode expected, ActualOutcome actual) {
        LinkedHashSet<String> failures = new LinkedHashSet<>();
        if (!expected.path("securityOutcome").asText().equals(actual.securityOutcome())) {
            failures.add("SECURITY_OUTCOME_MISMATCH");
        }
        Set<String> expectedTools = textSet(expected.path("allowedTools"));
        if (!expectedTools.equals(actual.allowedTools())) failures.add("TOOL_SET_MISMATCH");

        Set<String> requiredCitations = textSet(expected.path("requiredCitationKeys"));
        if (!actual.citationKeys().containsAll(requiredCitations)) {
            failures.add("REQUIRED_CITATION_MISSING");
        }
        String normalizedOutput = normalize(actual.safeOutput());
        for (String pattern : textSet(expected.path("forbiddenPatterns"))) {
            if (normalizedOutput.contains(normalize(pattern))) failures.add("FORBIDDEN_PATTERN_PRESENT");
        }
        if (expected.path("mustMention").isTextual()
                && !normalizedOutput.contains(normalize(expected.path("mustMention").asText()))) {
            failures.add("REQUIRED_TEXT_MISSING");
        }

        expected.fields().forEachRemaining(field -> {
            if (CORE_EXPECTED_FIELDS.contains(field.getKey())) return;
            Object actualValue = actual.values().get(field.getKey());
            JsonNode actualNode = actualValue == NullValue.INSTANCE || actualValue == null
                    ? NullNode.getInstance() : objectMapper.valueToTree(actualValue);
            if (!field.getValue().equals(actualNode)) failures.add("EXPECTED_FIELD_MISMATCH");
        });

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("contractValid", true);
        metrics.put("expectedCompared", true);
        metrics.put("providerCalled", false);
        metrics.put("actualSecurityOutcome", actual.securityOutcome());
        metrics.put("actualAllowedTools", List.copyOf(actual.allowedTools()));
        metrics.put("actualCitationKeys", List.copyOf(actual.citationKeys()));
        actual.values().forEach((key, value) -> metrics.put("actual" + capitalize(key),
                value == NullValue.INSTANCE ? null : value));
        metrics.put("expectationsSatisfied", failures.isEmpty());
        return new CaseEvaluation(caseKey, failures.isEmpty() ? "PASSED" : "FAILED",
                immutable(metrics), List.copyOf(failures));
    }

    private ActualOutcome outcome(
            String securityOutcome,
            Set<String> allowedTools,
            Set<String> citationKeys,
            String safeOutput,
            Map<String, Object> values) {
        return new ActualOutcome(securityOutcome, allowedTools, citationKeys, safeOutput, values);
    }

    private Set<String> textSet(JsonNode array) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (array != null && array.isArray()) array.forEach(value -> values.add(value.asText()));
        return Collections.unmodifiableSet(values);
    }

    private String repairCategory(String description, String existingType) {
        String value = description + " " + existingType;
        if (containsAny(value, "插座", "电", "火花", "冒烟", "断路")) return "水电维修";
        if (containsAny(value, "漏水", "水管", "下水", "龙头")) return "给排水维修";
        if (containsAny(value, "门", "窗", "锁", "床", "柜")) return "设施维修";
        return "综合维修";
    }

    private String repairUrgency(String value) {
        return containsAny(value, "冒烟", "火花", "漏电", "起火", "大量漏水", "无法关闭")
                ? "HIGH" : containsAny(value, "漏水", "断电", "无法使用") ? "MEDIUM" : "LOW";
    }

    private boolean containsAny(String value, String... terms) {
        return java.util.Arrays.stream(terms).anyMatch(value::contains);
    }

    private String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
    }

    private String capitalize(String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private Map<String, Object> immutable(Map<String, Object> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public record DatasetEvaluation(List<CaseEvaluation> cases, int passedCases, int failedCases) {
        public DatasetEvaluation {
            cases = List.copyOf(cases);
            if (passedCases < 0 || failedCases < 0 || passedCases + failedCases != cases.size()) {
                throw new IllegalArgumentException("评测汇总计数不合法");
            }
        }

        public boolean passed() {
            return failedCases == 0;
        }
    }

    public record CaseEvaluation(
            String caseKey,
            String state,
            Map<String, Object> metrics,
            List<String> failureTags) {
        public CaseEvaluation {
            metrics = Collections.unmodifiableMap(new LinkedHashMap<>(metrics));
            failureTags = List.copyOf(failureTags);
        }

        public boolean passed() {
            return "PASSED".equals(state);
        }
    }

    private record ActualOutcome(
            String securityOutcome,
            Set<String> allowedTools,
            Set<String> citationKeys,
            String safeOutput,
            Map<String, Object> values) {
        private ActualOutcome {
            allowedTools = Collections.unmodifiableSet(new LinkedHashSet<>(allowedTools));
            citationKeys = Collections.unmodifiableSet(new LinkedHashSet<>(citationKeys));
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
    }

    private enum NullValue { INSTANCE }
}
