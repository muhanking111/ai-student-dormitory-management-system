package com.example.dormitory.ai.tool;

import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.BusinessActorScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FixedToolExecutorTest {

    private final ToolCatalog catalog = ToolCatalog.standard();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void executesOnlyRuntimeToolsWithClosedActualRequestAndResponseShapes() {
        FixedToolExecutor.Session session = new FixedToolExecutor(catalog, json).open(scope());

        String response = session.execute("repair.get_context.v1",
                "{\"queryId\":\"repair.context.v1\",\"parameters\":{\"repairOrderId\":\"42\"}}",
                () -> "{\"repairOrderId\":42,\"code\":\"R-42\",\"type\":\"水电维修\","
                        + "\"status\":\"待处理\",\"description\":\"已脱敏\",\"assigneeUserId\":null,"
                        + "\"asOf\":\"2026-09-08T14:00:00Z\"}");

        assertEquals(42, read(response, "repairOrderId"));
    }

    @Test
    void rejectsInvalidInputBeforeCallingHandlerAndRejectsInvalidOutput() {
        FixedToolExecutor.Session session = new FixedToolExecutor(catalog, json).open(scope());
        AtomicInteger calls = new AtomicInteger();

        ToolExecutionException input = assertThrows(ToolExecutionException.class, () -> session.execute(
                "repair.get_context.v1",
                "{\"queryId\":\"repair.context.v1\",\"parameters\":{\"repairOrderId\":\"42\"},"
                        + "\"dynamicUrl\":\"https://invalid.example\"}",
                () -> {
                    calls.incrementAndGet();
                    return "{}";
                }));
        assertEquals("AI_TOOL_INPUT_SCHEMA_INVALID", input.errorCode());
        assertEquals(0, calls.get());

        ToolExecutionException output = assertThrows(ToolExecutionException.class, () -> session.execute(
                "dashboard.query_metric.v1",
                "{\"queryId\":\"dashboard.context.v1\",\"parameters\":{}}",
                () -> "{\"cards\":[],\"sql\":\"select 1\"}"));
        assertEquals("AI_TOOL_OUTPUT_SCHEMA_INVALID", output.errorCode());
    }

    @Test
    void enforcesResponseBytesPerRunCallCountAndDeadline() {
        FixedToolExecutor.Session sizeSession = new FixedToolExecutor(catalog, json).open(scope());
        ToolExecutionException size = assertThrows(ToolExecutionException.class, () -> sizeSession.execute(
                "knowledge.search.v1",
                "{\"query\":\"维修\",\"sourceScope\":[],\"topK\":5}",
                () -> "{\"grounded\":false,\"safetyState\":\"FORBIDDEN\",\"citationsAsData\":\""
                        + "x".repeat(33_000) + "\"}"));
        assertEquals("AI_TOOL_RESPONSE_TOO_LARGE", size.errorCode());

        FixedToolExecutor.Session countSession = new FixedToolExecutor(catalog, json).open(scope());
        String request = "{\"queryId\":\"dashboard.context.v1\",\"parameters\":{}}";
        for (int index = 0; index < 5; index++) {
            countSession.execute("dashboard.query_metric.v1", request, () -> "{\"cards\":[]}");
        }
        ToolExecutionException count = assertThrows(ToolExecutionException.class,
                () -> countSession.execute("dashboard.query_metric.v1", request, () -> "{\"cards\":[]}"));
        assertEquals("AI_TOOL_CALL_LIMIT_EXCEEDED", count.errorCode());

        AtomicLong nanos = new AtomicLong();
        FixedToolExecutor.Session deadlineSession = new FixedToolExecutor(
                catalog, json, () -> nanos.getAndAdd(Duration.ofSeconds(4).toNanos())).open(scope());
        ToolExecutionException deadline = assertThrows(ToolExecutionException.class,
                () -> deadlineSession.execute("dashboard.query_metric.v1", request, () -> "{\"cards\":[]}"));
        assertEquals("AI_TOOL_DEADLINE_EXCEEDED", deadline.errorCode());
    }

    @Test
    void reservedAndInternalProposalToolsAreNeverRuntimeExecutable() {
        FixedToolExecutor.Session session = new FixedToolExecutor(catalog, json).open(scope());

        for (String id : Set.of("dormitory.get_capacity_summary.v1", "notice.list_published.v1",
                "repair.propose_assignment.v1", "notice.propose_draft.v1")) {
            ToolDeniedException denied = assertThrows(ToolDeniedException.class,
                    () -> session.execute(id, "{}", () -> "{}"));
            assertEquals("AI_TOOL_NOT_RUNTIME_EXECUTABLE", denied.errorCode());
        }
    }

    private BusinessActorScope scope() {
        return new BusinessActorScope(ActorDescriptor.user(7L), Set.of(
                "ai:assistant:use", "ai:knowledge:read", "ai:dashboard:query",
                "ai:repair:triage", "repair:read", "dormitory:read", "notice:read"), Map.of());
    }

    private int read(String value, String field) {
        try {
            return json.readTree(value).path(field).asInt();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
