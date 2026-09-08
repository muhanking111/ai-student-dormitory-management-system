package com.example.dormitory.ai.governance;

import com.example.dormitory.ai.tool.ToolCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class StandardToolCatalogManifestTest {

    @Test
    void manifestPublishesHonestExecutionModesAndNoProviderCallableTools() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode manifest = mapper.readTree(
                new StandardToolCatalogManifest(ToolCatalog.standard(), mapper).manifest());

        assertEquals("tool-catalog.v2", manifest.path("schemaVersion").asText());
        assertEquals("v2", manifest.path("version").asText());
        Set<String> runtime = Set.of(
                "knowledge.search.v1", "dashboard.query_metric.v1", "repair.get_context.v1");
        Set<String> internal = Set.of("repair.propose_assignment.v1", "notice.propose_draft.v1");
        Set<String> reserved = Set.of("dormitory.get_capacity_summary.v1", "notice.list_published.v1");
        assertEquals(runtime, values(manifest.path("runtimeExecutableToolIds")));
        assertEquals(internal, values(manifest.path("internalProposalToolIds")));
        assertEquals(reserved, values(manifest.path("reservedToolIds")));
        assertEquals(Set.of(), values(manifest.path("providerCallableToolIds")));
        for (JsonNode tool : manifest.path("tools")) {
            assertFalse(tool.path("providerCallable").asBoolean(true));
            String id = tool.path("id").asText();
            String expected = runtime.contains(id) ? "RUNTIME_CONTEXT"
                    : internal.contains(id) ? "INTERNAL_PROPOSAL" : "RESERVED";
            assertEquals(expected, tool.path("executionMode").asText());
            assertEquals(runtime.contains(id) ? "FIXED_EXECUTOR"
                            : internal.contains(id) ? "SERVER_GENERATED" : "NONE_RESERVED",
                    tool.path("contractEnforcement").asText());
            assertEquals(runtime.contains(id) ? "RESULT_ADMISSION_NON_PREEMPTIVE" : "NOT_APPLICABLE",
                    tool.path("deadlineEnforcement").asText());
        }
    }

    private Set<String> values(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false)
                .map(JsonNode::asText).collect(Collectors.toSet());
    }
}
