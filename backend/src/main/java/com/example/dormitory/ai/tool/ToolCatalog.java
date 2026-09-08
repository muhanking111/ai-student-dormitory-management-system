package com.example.dormitory.ai.tool;

import com.example.dormitory.ai.domain.model.BusinessActorScope;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class ToolCatalog {

    /** v2 adds enforceable runtime modes and closed context shapes; v1 rows remain immutable history. */
    private static final String VERSION = "v2";
    private static final Set<String> RUNTIME_EXECUTABLE_IDS = Set.of(
            "knowledge.search.v1", "dashboard.query_metric.v1", "repair.get_context.v1");
    private static final Set<String> INTERNAL_PROPOSAL_IDS = Set.of(
            "repair.propose_assignment.v1", "notice.propose_draft.v1");
    private static final Set<String> RESERVED_IDS = Set.of(
            "dormitory.get_capacity_summary.v1", "notice.list_published.v1");

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
                "兼容 ID：读取当前授权 Dashboard 的固定统计卡上下文，不接受动态指标",
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
                "目录兼容预留：当前助手运行时不执行容量查询",
                CAPACITY_SUMMARY_INPUT_SCHEMA,
                CAPACITY_SUMMARY_OUTPUT_SCHEMA,
                Set.of("ai:assistant:use", "dormitory:read"),
                ToolDefinition.DataClassification.L1));
        register(tools, read(
                "notice.list_published.v1",
                "目录兼容预留：当前助手运行时不执行公告列表查询",
                NOTICE_LIST_INPUT_SCHEMA,
                NOTICE_LIST_OUTPUT_SCHEMA,
                Set.of("ai:assistant:use", "notice:read"),
                ToolDefinition.DataClassification.L1));
        register(tools, proposal(
                "repair.propose_assignment.v1",
                "服务端命令内部记录维修改派提案，绝不向 provider 开放",
                REPAIR_PROPOSAL_INPUT_SCHEMA,
                PROPOSAL_OUTPUT_SCHEMA,
                Set.of("ai:repair:triage", "repair:read"),
                ToolDefinition.DataClassification.L2));
        register(tools, proposal(
                "notice.propose_draft.v1",
                "服务端命令内部记录公告草稿提案，绝不向 provider 开放",
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

    /** 当前仅由服务端固定上下文处理器同步执行；不等于向模型供应商开放 tool calling。 */
    public Set<String> runtimeExecutableIds() {
        return RUNTIME_EXECUTABLE_IDS;
    }

    /** 由确定性命令服务创建 proposal，不进入助手运行时工具循环。 */
    public Set<String> internalProposalIds() {
        return INTERNAL_PROPOSAL_IDS;
    }

    /** 为 v1 目录兼容保留，但当前没有用户路径或 handler，不得记录成执行成功。 */
    public Set<String> reservedIds() {
        return RESERVED_IDS;
    }

    /** 当前 provider adapter 不注册任何可回调工具。 */
    public Set<String> providerCallableIds() {
        return Set.of();
    }

    public ExecutionMode executionMode(String id) {
        if (!definitions.containsKey(id)) throw new ToolDeniedException("未知或动态工具请求已拒绝");
        if (RUNTIME_EXECUTABLE_IDS.contains(id)) return ExecutionMode.RUNTIME_CONTEXT;
        if (INTERNAL_PROPOSAL_IDS.contains(id)) return ExecutionMode.INTERNAL_PROPOSAL;
        if (RESERVED_IDS.contains(id)) return ExecutionMode.RESERVED;
        throw new IllegalStateException("标准工具未声明执行模式: " + id);
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

    public ToolDefinition requireRuntimeExecutable(String requestedId, BusinessActorScope scope) {
        if (executionMode(requestedId) != ExecutionMode.RUNTIME_CONTEXT) {
            throw new ToolDeniedException("AI_TOOL_NOT_RUNTIME_EXECUTABLE", "工具未向助手运行时开放");
        }
        return requireAuthorized(requestedId, scope);
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
            {"type":"object","properties":{"grounded":{"type":"boolean"},"safetyState":{"type":"string","minLength":1,"maxLength":64},"citationsAsData":{"type":"string","maxLength":30000}},"required":["grounded","safetyState","citationsAsData"],"additionalProperties":false}
            """;

    private static final String DASHBOARD_QUERY_INPUT_SCHEMA = """
            {"type":"object","properties":{"queryId":{"type":"string","const":"dashboard.context.v1"},"parameters":{"type":"object","maxProperties":0,"additionalProperties":false}},"required":["queryId","parameters"],"additionalProperties":false}
            """;

    private static final String DASHBOARD_QUERY_OUTPUT_SCHEMA = """
            {"type":"object","properties":{"cards":{"type":"array","maxItems":20,"items":{"type":"object","properties":{"title":{"type":"string","minLength":1,"maxLength":100},"value":{"type":"integer"},"unit":{"type":"string","maxLength":32}},"required":["title","value","unit"],"additionalProperties":false}}},"required":["cards"],"additionalProperties":false}
            """;

    private static final String REPAIR_CONTEXT_INPUT_SCHEMA = """
            {"type":"object","properties":{"queryId":{"type":"string","const":"repair.context.v1"},"parameters":{"type":"object","properties":{"repairOrderId":{"type":"string","pattern":"^[1-9][0-9]{0,18}$"}},"required":["repairOrderId"],"additionalProperties":false}},"required":["queryId","parameters"],"additionalProperties":false}
            """;

    private static final String REPAIR_CONTEXT_OUTPUT_SCHEMA = """
            {"type":"object","properties":{"repairOrderId":{"type":"integer","minimum":1},"code":{"type":["string","null"],"maxLength":100},"type":{"type":["string","null"],"maxLength":100},"status":{"type":["string","null"],"maxLength":64},"description":{"type":["string","null"],"maxLength":4000},"assigneeUserId":{"type":["integer","null"],"minimum":1},"asOf":{"type":["string","null"],"format":"date-time"}},"required":["repairOrderId","code","type","status","description","assigneeUserId","asOf"],"additionalProperties":false}
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
            {"type":"object","properties":{"payloadHash":{"type":"string","pattern":"^[0-9a-f]{64}$"},"targetType":{"type":"string","const":"REPAIR_ORDER"}},"required":["payloadHash","targetType"],"additionalProperties":false}
            """;

    private static final String NOTICE_PROPOSAL_INPUT_SCHEMA = """
            {"type":"object","properties":{"payloadHash":{"type":"string","pattern":"^[0-9a-f]{64}$"},"targetType":{"type":"string","const":"NOTICE"}},"required":["payloadHash","targetType"],"additionalProperties":false}
            """;

    private static final String PROPOSAL_OUTPUT_SCHEMA = """
            {"type":"object","properties":{"proposalId":{"type":"string","format":"uuid"},"state":{"type":"string","const":"PENDING_APPROVAL"}},"required":["proposalId","state"],"additionalProperties":false}
            """;

    public enum ExecutionMode {
        RUNTIME_CONTEXT,
        INTERNAL_PROPOSAL,
        RESERVED
    }
}
