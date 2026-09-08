package com.example.dormitory.ai.tool;

import com.example.dormitory.ai.domain.model.BusinessActorScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 只执行当前三个固定上下文工具。这里实现的是项目自己的有限 shape 合同，
 * 不是通用 JSON Schema 或模型可扩展工具引擎。
 */
public final class FixedToolExecutor {

    private static final int MAXIMUM_REQUEST_BYTES = 65_536;

    private final ToolCatalog catalog;
    private final ObjectMapper json;
    private final LongSupplier nanoTime;

    public FixedToolExecutor(ToolCatalog catalog, ObjectMapper json) {
        this(catalog, json, System::nanoTime);
    }

    FixedToolExecutor(ToolCatalog catalog, ObjectMapper json, LongSupplier nanoTime) {
        this.catalog = java.util.Objects.requireNonNull(catalog);
        this.json = java.util.Objects.requireNonNull(json);
        this.nanoTime = java.util.Objects.requireNonNull(nanoTime);
    }

    public Session open(BusinessActorScope scope) {
        if (scope == null) throw new IllegalArgumentException("固定工具执行必须携带 actor scope");
        return new Session(scope);
    }

    public final class Session {
        private final BusinessActorScope scope;
        private final Map<String, Integer> calls = new HashMap<>();

        private Session(BusinessActorScope scope) {
            this.scope = scope;
        }

        public ToolDefinition preflight(String id) {
            return catalog.requireRuntimeExecutable(id, scope);
        }

        public String execute(String id, String requestJson, Supplier<String> handler) {
            ToolDefinition definition = preflight(id);
            JsonNode request = parse(requestJson, true);
            validateInput(id, request);
            int nextCall = calls.merge(id, 1, Math::addExact);
            if (nextCall > definition.maxCallsPerRun()) {
                throw new ToolExecutionException(
                        "AI_TOOL_CALL_LIMIT_EXCEEDED", "固定工具超过每次运行允许的调用次数");
            }
            if (handler == null) {
                throw new ToolExecutionException("AI_TOOL_EXECUTION_FAILED", "固定工具 handler 不存在");
            }

            long startedNanos = nanoTime.getAsLong();
            String response = handler.get();
            long elapsedNanos = nanoTime.getAsLong() - startedNanos;
            // Deliberately keep the explicit USER/transaction thread. This is a result-admission deadline:
            // late output is rejected and audited, but a blocking handler is not asynchronously hard-cancelled.
            if (elapsedNanos < 0 || elapsedNanos > definition.timeout().toNanos()) {
                throw new ToolExecutionException(
                        "AI_TOOL_DEADLINE_EXCEEDED", "固定工具执行超过截止时间，结果已拒绝");
            }
            if (response == null) {
                throw new ToolExecutionException("AI_TOOL_OUTPUT_SCHEMA_INVALID", "固定工具响应不能为空");
            }
            if (response.getBytes(StandardCharsets.UTF_8).length > definition.maxResponseBytes()) {
                throw new ToolExecutionException("AI_TOOL_RESPONSE_TOO_LARGE", "固定工具响应超过字节上限");
            }
            JsonNode output = parse(response, false);
            validateOutput(id, output);
            return response;
        }
    }

    private JsonNode parse(String value, boolean input) {
        String errorCode = input ? "AI_TOOL_INPUT_SCHEMA_INVALID" : "AI_TOOL_OUTPUT_SCHEMA_INVALID";
        if (value == null || value.isBlank()
                || (input && value.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_REQUEST_BYTES)) {
            throw new ToolExecutionException(errorCode, "固定工具 JSON 不合法");
        }
        try {
            JsonNode node = json.readTree(value);
            if (node == null || !node.isObject()) {
                throw new ToolExecutionException(errorCode, "固定工具 JSON 必须是对象");
            }
            return node;
        } catch (ToolExecutionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ToolExecutionException(errorCode, "固定工具 JSON 无法解析", exception);
        }
    }

    private void validateInput(String id, JsonNode node) {
        boolean valid = switch (id) {
            case "knowledge.search.v1" -> exact(node, "query", "sourceScope", "topK")
                    && text(node.get("query"), 1, 2_000)
                    && stringArray(node.get("sourceScope"), 20, 128)
                    && integer(node.get("topK"), 1, 20);
            case "dashboard.query_metric.v1" -> fixedBusinessRequest(node, "dashboard.context.v1", false);
            case "repair.get_context.v1" -> fixedBusinessRequest(node, "repair.context.v1", true);
            default -> false;
        };
        if (!valid) {
            throw new ToolExecutionException("AI_TOOL_INPUT_SCHEMA_INVALID", "固定工具输入不符合关闭 shape");
        }
    }

    private void validateOutput(String id, JsonNode node) {
        boolean valid = switch (id) {
            case "knowledge.search.v1" -> exact(node, "grounded", "safetyState", "citationsAsData")
                    && node.path("grounded").isBoolean()
                    && text(node.get("safetyState"), 1, 64)
                    && text(node.get("citationsAsData"), 0, 30_000);
            case "dashboard.query_metric.v1" -> dashboardOutput(node);
            case "repair.get_context.v1" -> repairOutput(node);
            default -> false;
        };
        if (!valid) {
            throw new ToolExecutionException("AI_TOOL_OUTPUT_SCHEMA_INVALID", "固定工具输出不符合关闭 shape");
        }
    }

    private boolean fixedBusinessRequest(JsonNode node, String queryId, boolean repair) {
        if (!exact(node, "queryId", "parameters") || !node.path("queryId").isTextual()
                || !queryId.equals(node.path("queryId").asText()) || !node.path("parameters").isObject()) {
            return false;
        }
        JsonNode parameters = node.path("parameters");
        if (!repair) return parameters.isEmpty();
        return exact(parameters, "repairOrderId")
                && parameters.path("repairOrderId").isTextual()
                && parameters.path("repairOrderId").asText().matches("[1-9][0-9]{0,18}");
    }

    private boolean dashboardOutput(JsonNode node) {
        if (!exact(node, "cards") || !node.path("cards").isArray() || node.path("cards").size() > 20) {
            return false;
        }
        for (JsonNode card : node.path("cards")) {
            if (!card.isObject() || !exact(card, "title", "value", "unit")
                    || !text(card.get("title"), 1, 100) || !card.path("value").isIntegralNumber()
                    || !text(card.get("unit"), 0, 32)) {
                return false;
            }
        }
        return true;
    }

    private boolean repairOutput(JsonNode node) {
        if (!exact(node, "repairOrderId", "code", "type", "status", "description", "assigneeUserId", "asOf")
                || !integer(node.get("repairOrderId"), 1, Long.MAX_VALUE)
                || !nullableText(node.get("code"), 100)
                || !nullableText(node.get("type"), 100)
                || !nullableText(node.get("status"), 64)
                || !nullableText(node.get("description"), 4_000)
                || !nullableText(node.get("asOf"), 64)) {
            return false;
        }
        JsonNode assignee = node.get("assigneeUserId");
        return assignee != null && (assignee.isNull() || integer(assignee, 1, Long.MAX_VALUE));
    }

    private boolean exact(JsonNode node, String... fields) {
        if (node == null || !node.isObject()) return false;
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        return actual.equals(Set.of(fields));
    }

    private boolean stringArray(JsonNode node, int maximumItems, int maximumLength) {
        if (node == null || !node.isArray() || node.size() > maximumItems) return false;
        for (JsonNode item : node) if (!text(item, 1, maximumLength)) return false;
        return true;
    }

    private boolean nullableText(JsonNode node, int maximumLength) {
        return node != null && (node.isNull() || text(node, 0, maximumLength));
    }

    private boolean text(JsonNode node, int minimumLength, int maximumLength) {
        return node != null && node.isTextual()
                && node.textValue().length() >= minimumLength && node.textValue().length() <= maximumLength;
    }

    private boolean integer(JsonNode node, long minimum, long maximum) {
        return node != null && node.isIntegralNumber()
                && node.canConvertToLong() && node.longValue() >= minimum && node.longValue() <= maximum;
    }
}
