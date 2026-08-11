package com.example.dormitory.ai.tool;

import com.example.dormitory.ai.domain.model.BusinessActorScope;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class ToolCatalog {

    private static final String VERSION = "v1";

    private final Map<String, ToolDefinition> definitions;

    private ToolCatalog(Map<String, ToolDefinition> definitions) {
        this.definitions = Collections.unmodifiableMap(new LinkedHashMap<>(definitions));
    }

    public static ToolCatalog standard() {
        Map<String, ToolDefinition> tools = new LinkedHashMap<>();
        register(tools, read(
                "knowledge.search.v1",
                "按已授权知识源检索脱敏片段和引用候选",
                KNOWLEDGE_SEARCH_INPUT_SCHEMA,
                KNOWLEDGE_SEARCH_OUTPUT_SCHEMA,
                Set.of("ai:knowledge:read"),
                ToolDefinition.DataClassification.L2));
        register(tools, read(
                "dashboard.query_metric.v1",
                "通过版本化指标目录执行固定聚合查询",
                DASHBOARD_QUERY_INPUT_SCHEMA,
                DASHBOARD_QUERY_OUTPUT_SCHEMA,
                Set.of("ai:dashboard:query"),
                ToolDefinition.DataClassification.L1));
        register(tools, read(
                "repair.get_context.v1",
                "读取权限裁剪后的脱敏维修上下文",
                REPAIR_CONTEXT_INPUT_SCHEMA,
                REPAIR_CONTEXT_OUTPUT_SCHEMA,
                Set.of("ai:repair:triage", "repair:read"),
                ToolDefinition.DataClassification.L2));
        register(tools, read("dormitory.get_capacity_summary.v1",
                "读取楼栋或宿舍的聚合容量，不返回学生明细",
                CAPACITY_SUMMARY_INPUT_SCHEMA,
                CAPACITY_SUMMARY_OUTPUT_SCHEMA,
                Set.of("ai:assistant:use", "dormitory:read"),
                ToolDefinition.DataClassification.L1));
        register(tools, read(
                "notice.list_published.v1",
                "检索已发布公告的纯文本摘要",
                NOTICE_LIST_INPUT_SCHEMA,
                NOTICE_LIST_OUTPUT_SCHEMA,
                Set.of("ai:assistant:use", "notice:read"),
                ToolDefinition.DataClassification.L1));
        register(tools, proposal(
                "repair.propose_assignment.v1",
                "创建维修改派建议，绝不直接执行业务写入",
                REPAIR_PROPOSAL_INPUT_SCHEMA,
                PROPOSAL_OUTPUT_SCHEMA,
                Set.of("ai:repair:triage", "repair:read"),
                ToolDefinition.DataClassification.L2));
        register(tools, proposal(
                "notice.propose_draft.v1",
                "创建公告草稿建议，绝不直接发布公告",
                NOTICE_PROPOSAL_INPUT_SCHEMA,
                PROPOSAL_OUTPUT_SCHEMA,
                Set.of("ai:notice:draft", "notice:read"),
                ToolDefinition.DataClassification.L1));
        return new ToolCatalog(tools);
    }

    public String version() {
        return VERSION;
    }

    public Map<String, ToolDefinition> definitions() {
        return definitions;
    }

    public ToolDefinition requireAuthorized(String requestedId, BusinessActorScope scope) {
        ToolDefinition definition = definitions.get(requestedId);
        if (definition == null) {
            throw new ToolDeniedException("未知或动态工具请求已拒绝");
        }
        if (scope == null || !scope.hasAllPermissions(definition.requiredPermissions())) {
            throw new ToolDeniedException("工具权限校验未通过，请求已拒绝");
        }
        return definition;
    }

    private static ToolDefinition read(
            String id,
            String description,
            String inputSchema,
            String outputSchema,
            Set<String> permissions,
            ToolDefinition.DataClassification classification) {
        return new ToolDefinition(
                id,
                "v1",
                description,
                ToolDefinition.Kind.READ,
                inputSchema,
                outputSchema,
                permissions,
                32_768,
                Duration.ofSeconds(3),
                5,
                classification,
                false);
    }

    private static ToolDefinition proposal(
            String id,
            String description,
            String inputSchema,
            String outputSchema,
            Set<String> permissions,
            ToolDefinition.DataClassification classification) {
        return new ToolDefinition(
                id,
                "v1",
                description,
                ToolDefinition.Kind.PROPOSAL,
                inputSchema,
                outputSchema,
                permissions,
                4_096,
                Duration.ofSeconds(3),
                1,
                classification,
                true);
    }

    private static void register(Map<String, ToolDefinition> tools, ToolDefinition definition) {
        if (tools.putIfAbsent(definition.id(), definition) != null) {
            throw new IllegalStateException("重复工具 ID: " + definition.id());
        }
    }

    private static final String KNOWLEDGE_SEARCH_INPUT_SCHEMA = """
            {"type":"object","properties":{"query":{"type":"string","minLength":1,"maxLength":2000},"sourceScope":{"type":"array","items":{"type":"string"},"maxItems":20},"topK":{"type":"integer","minimum":1,"maximum":20}},"required":["query","sourceScope","topK"],"additionalProperties":false}
            """;

    private static final String KNOWLEDGE_SEARCH_OUTPUT_SCHEMA = """
            {"type":"object","properties":{"citations":{"type":"array","maxItems":20,"items":{"type":"object","properties":{"citationId":{"type":"string"},"text":{"type":"string","maxLength":4000}},"required":["citationId","text"],"additionalProperties":false}}},"required":["citations"],"additionalProperties":false}
            """;

    private static final String DASHBOARD_QUERY_INPUT_SCHEMA = """
            {"type":"object","properties":{"metricIds":{"type":"array","items":{"type":"string"},"minItems":1,"maxItems":20},"dateFrom":{"type":"string","format":"date"},"dateTo":{"type":"string","format":"date"},"dimensions":{"type":"array","items":{"type":"string"},"maxItems":5},"filters":{"type":"object","additionalProperties":{"type":"string"}}},"required":["metricIds","dateFrom","dateTo","dimensions","filters"],"additionalProperties":false}
            """;

    private static final String DASHBOARD_QUERY_OUTPUT_SCHEMA = """
            {"type":"object","properties":{"schemaVersion":{"type":"string"},"asOf":{"type":"string","format":"date-time"},"results":{"type":"array","items":{"type":"object"}}},"required":["schemaVersion","asOf","results"],"additionalProperties":false}
            """;

    private static final String REPAIR_CONTEXT_INPUT_SCHEMA = """
            {"type":"object","properties":{"repairId":{"type":"integer","minimum":1}},"required":["repairId"],"additionalProperties":false}
            """;

    private static final String REPAIR_CONTEXT_OUTPUT_SCHEMA = """
            {"type":"object","properties":{"repairId":{"type":"integer"},"status":{"type":"string"},"category":{"type":"string"},"descriptionRedacted":{"type":"string","maxLength":4000},"locationToken":{"type":"string"},"snapshotHash":{"type":"string"}},"required":["repairId","status","category","descriptionRedacted","locationToken","snapshotHash"],"additionalProperties":false}
            """;

    private static final String CAPACITY_SUMMARY_INPUT_SCHEMA = """
            {"type":"object","properties":{"buildingId":{"type":"integer","minimum":1},"dormitoryId":{"type":"integer","minimum":1}},"anyOf":[{"required":["buildingId"]},{"required":["dormitoryId"]}],"additionalProperties":false}
            """;

    private static final String CAPACITY_SUMMARY_OUTPUT_SCHEMA = """
            {"type":"object","properties":{"capacity":{"type":"integer","minimum":0},"occupied":{"type":"integer","minimum":0},"available":{"type":"integer","minimum":0},"asOf":{"type":"string","format":"date-time"}},"required":["capacity","occupied","available","asOf"],"additionalProperties":false}
            """;

    private static final String NOTICE_LIST_INPUT_SCHEMA = """
            {"type":"object","properties":{"keyword":{"type":"string","maxLength":200},"limit":{"type":"integer","minimum":1,"maximum":20}},"required":["keyword","limit"],"additionalProperties":false}
            """;

    private static final String NOTICE_LIST_OUTPUT_SCHEMA = """
            {"type":"object","properties":{"notices":{"type":"array","maxItems":20,"items":{"type":"object","properties":{"noticeId":{"type":"integer"},"title":{"type":"string"},"summary":{"type":"string","maxLength":1000}},"required":["noticeId","title","summary"],"additionalProperties":false}}},"required":["notices"],"additionalProperties":false}
            """;

    private static final String REPAIR_PROPOSAL_INPUT_SCHEMA = """
            {"type":"object","properties":{"repairId":{"type":"integer","minimum":1},"candidateUserId":{"type":"integer","minimum":1},"triageVersion":{"type":"string"},"snapshotHash":{"type":"string"}},"required":["repairId","candidateUserId","triageVersion","snapshotHash"],"additionalProperties":false}
            """;

    private static final String NOTICE_PROPOSAL_INPUT_SCHEMA = """
            {"type":"object","properties":{"title":{"type":"string","minLength":1,"maxLength":200},"content":{"type":"string","minLength":1,"maxLength":10000},"sourceRunId":{"type":"string"}},"required":["title","content","sourceRunId"],"additionalProperties":false}
            """;

    private static final String PROPOSAL_OUTPUT_SCHEMA = """
            {"type":"object","properties":{"proposalId":{"type":"string"},"status":{"type":"string","const":"PENDING_REVIEW"}},"required":["proposalId","status"],"additionalProperties":false}
            """;
}
