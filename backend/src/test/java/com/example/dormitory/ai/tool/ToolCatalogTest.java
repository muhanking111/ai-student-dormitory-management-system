package com.example.dormitory.ai.tool;

import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.BusinessActorScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCatalogTest {

    private static final Set<String> PLANNED_TOOL_IDS = Set.of(
            "knowledge.search.v1",
            "dashboard.query_metric.v1",
            "repair.get_context.v1",
            "dormitory.get_capacity_summary.v1",
            "notice.list_published.v1",
            "repair.propose_assignment.v1",
            "notice.propose_draft.v1");

    private final ToolCatalog catalog = ToolCatalog.standard();

    @Test
    void catalogContainsExactlySevenVersionedPlanTools() {
        assertEquals("v1", catalog.version());
        assertEquals(7, catalog.definitions().size());
        assertEquals(PLANNED_TOOL_IDS, catalog.definitions().keySet());
        assertEquals(2, catalog.definitions().values().stream()
                .filter(definition -> definition.kind() == ToolDefinition.Kind.PROPOSAL)
                .count());
    }

    @Test
    void everyToolCarriesFailClosedSchemasAndRuntimeLimits() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();

        for (ToolDefinition definition : catalog.definitions().values()) {
            assertFalse(definition.description().isBlank());
            assertTrue(definition.maxResponseBytes() > 0);
            assertTrue(definition.timeout().isPositive());
            assertNotNull(definition.dataClassification());
            assertEquals(
                    definition.kind() == ToolDefinition.Kind.PROPOSAL,
                    definition.producesProposal());

            for (String schema : List.of(definition.inputSchemaJson(), definition.outputSchemaJson())) {
                JsonNode parsed = objectMapper.readTree(schema);
                assertEquals("object", parsed.path("type").asText());
                assertFalse(parsed.path("additionalProperties").asBoolean(true),
                        () -> definition.id() + " schema 必须拒绝未知字段");
            }
        }
    }

    @Test
    void permissionsAreEvaluatedServerSideForEveryResolution() {
        BusinessActorScope scope = new BusinessActorScope(
                ActorDescriptor.user(42L),
                Set.of("ai:repair:triage", "repair:read"),
                java.util.Map.of("repair", Set.of(101L)));

        ToolDefinition definition = catalog.requireAuthorized("repair.get_context.v1", scope);

        assertEquals(Set.of("ai:repair:triage", "repair:read"), definition.requiredPermissions());
        assertThrows(ToolDeniedException.class,
                () -> catalog.requireAuthorized("notice.propose_draft.v1", scope));
    }

    @Test
    void unknownAndDynamicDangerousToolsAreAlwaysDenied() {
        Set<String> explicitPermissions = catalog.definitions().values().stream()
                .flatMap(definition -> definition.requiredPermissions().stream())
                .collect(Collectors.toUnmodifiableSet());
        BusinessActorScope superScope = new BusinessActorScope(
                ActorDescriptor.user(1L), explicitPermissions, java.util.Map.of());

        for (String requested : List.of(
                "sql.execute.v1",
                "http://127.0.0.1/internal",
                "shell.exec",
                "java.lang.Runtime.exec",
                "com.example.Service#method",
                "${bean.call}",
                "knowledge.search.v2")) {
            ToolDeniedException exception = assertThrows(
                    ToolDeniedException.class,
                    () -> catalog.requireAuthorized(requested, superScope));
            assertEquals("AI_TOOL_DENIED", exception.errorCode());
            assertTrue(exception.getMessage().contains("拒绝"));
        }
    }

    @Test
    void aiScopeDoesNotTreatClientSuppliedWildcardAsExplicitPermissions() {
        BusinessActorScope wildcardScope = new BusinessActorScope(
                ActorDescriptor.user(1L), Set.of("*"), java.util.Map.of());

        assertThrows(ToolDeniedException.class,
                () -> catalog.requireAuthorized("knowledge.search.v1", wildcardScope));
    }

    @Test
    void toolDefinitionRejectsEveryMalformedIdentityAndVersionCombination() {
        for (String id : List.of("", "SQL.execute.v1", "sql-execute.v1", "sql.execute.v0", "sql.execute")) {
            assertThrows(IllegalArgumentException.class, () -> definition(id, "v1"));
        }
        assertThrows(IllegalArgumentException.class, () -> definition(null, "v1"));
        for (String version : List.of("", "1", "v0", "v2")) {
            assertThrows(IllegalArgumentException.class,
                    () -> definition("knowledge.search.v1", version));
        }
        assertThrows(IllegalArgumentException.class,
                () -> definition("knowledge.search.v1", null));
    }

    @Test
    void toolDefinitionFailsClosedForEveryMissingRuntimeAndAuthorizationBound() {
        assertThrows(IllegalArgumentException.class, () -> new ToolDefinition(
                "knowledge.search.v1", "v1", null, ToolDefinition.Kind.READ,
                "{}", "{}", Set.of("ai:knowledge:read"), 1, Duration.ofSeconds(1), 1,
                ToolDefinition.DataClassification.L1, false));
        assertThrows(IllegalArgumentException.class, () -> new ToolDefinition(
                "knowledge.search.v1", "v1", " ", ToolDefinition.Kind.READ,
                "{}", "{}", Set.of("ai:knowledge:read"), 1, Duration.ofSeconds(1), 1,
                ToolDefinition.DataClassification.L1, false));
        assertThrows(IllegalArgumentException.class, () -> new ToolDefinition(
                "knowledge.search.v1", "v1", "x".repeat(201), ToolDefinition.Kind.READ,
                "{}", "{}", Set.of("ai:knowledge:read"), 1, Duration.ofSeconds(1), 1,
                ToolDefinition.DataClassification.L1, false));
        assertThrows(IllegalArgumentException.class, () -> validDefinition(null, "{}", "{}",
                Set.of("ai:knowledge:read"), 1, Duration.ofSeconds(1), 1,
                ToolDefinition.DataClassification.L1, false));
        assertThrows(IllegalArgumentException.class, () -> validDefinition(ToolDefinition.Kind.READ, null, "{}",
                Set.of("ai:knowledge:read"), 1, Duration.ofSeconds(1), 1,
                ToolDefinition.DataClassification.L1, false));
        assertThrows(IllegalArgumentException.class, () -> validDefinition(ToolDefinition.Kind.READ, " ", "{}",
                Set.of("ai:knowledge:read"), 1, Duration.ofSeconds(1), 1,
                ToolDefinition.DataClassification.L1, false));
        assertThrows(IllegalArgumentException.class, () -> validDefinition(ToolDefinition.Kind.READ, "{}", null,
                Set.of("ai:knowledge:read"), 1, Duration.ofSeconds(1), 1,
                ToolDefinition.DataClassification.L1, false));
        assertThrows(IllegalArgumentException.class, () -> validDefinition(ToolDefinition.Kind.READ, "{}", " ",
                Set.of("ai:knowledge:read"), 1, Duration.ofSeconds(1), 1,
                ToolDefinition.DataClassification.L1, false));

        assertThrows(IllegalArgumentException.class, () -> validDefinition(ToolDefinition.Kind.READ, "{}", "{}",
                null, 1, Duration.ofSeconds(1), 1, ToolDefinition.DataClassification.L1, false));
        assertThrows(IllegalArgumentException.class, () -> validDefinition(ToolDefinition.Kind.READ, "{}", "{}",
                Set.of(), 1, Duration.ofSeconds(1), 1, ToolDefinition.DataClassification.L1, false));
        assertThrows(IllegalArgumentException.class, () -> validDefinition(ToolDefinition.Kind.READ, "{}", "{}",
                Set.of(" "), 1, Duration.ofSeconds(1), 1, ToolDefinition.DataClassification.L1, false));
        assertThrows(IllegalArgumentException.class, () -> validDefinition(ToolDefinition.Kind.READ, "{}", "{}",
                Set.of("*"), 1, Duration.ofSeconds(1), 1, ToolDefinition.DataClassification.L1, false));
        Set<String> nullPermission = new HashSet<>();
        nullPermission.add(null);
        assertThrows(IllegalArgumentException.class, () -> validDefinition(ToolDefinition.Kind.READ, "{}", "{}",
                nullPermission, 1, Duration.ofSeconds(1), 1, ToolDefinition.DataClassification.L1, false));

        for (int bytes : List.of(0, 1_048_577)) {
            assertThrows(IllegalArgumentException.class, () -> validDefinition(ToolDefinition.Kind.READ, "{}", "{}",
                    Set.of("ai:knowledge:read"), bytes, Duration.ofSeconds(1), 1,
                    ToolDefinition.DataClassification.L1, false));
        }
        for (Duration timeout : List.of(Duration.ZERO, Duration.ofSeconds(-1))) {
            assertThrows(IllegalArgumentException.class, () -> validDefinition(ToolDefinition.Kind.READ, "{}", "{}",
                    Set.of("ai:knowledge:read"), 1, timeout, 1,
                    ToolDefinition.DataClassification.L1, false));
        }
        assertThrows(IllegalArgumentException.class, () -> validDefinition(ToolDefinition.Kind.READ, "{}", "{}",
                Set.of("ai:knowledge:read"), 1, null, 1, ToolDefinition.DataClassification.L1, false));
        assertThrows(IllegalArgumentException.class, () -> validDefinition(ToolDefinition.Kind.READ, "{}", "{}",
                Set.of("ai:knowledge:read"), 1, Duration.ofSeconds(1), 0,
                ToolDefinition.DataClassification.L1, false));
        assertThrows(IllegalArgumentException.class, () -> validDefinition(ToolDefinition.Kind.READ, "{}", "{}",
                Set.of("ai:knowledge:read"), 1, Duration.ofSeconds(1), 1, null, false));
        assertThrows(IllegalArgumentException.class, () -> validDefinition(ToolDefinition.Kind.READ, "{}", "{}",
                Set.of("ai:knowledge:read"), 1, Duration.ofSeconds(1), 1,
                ToolDefinition.DataClassification.L1, true));
        assertThrows(IllegalArgumentException.class, () -> validDefinition(ToolDefinition.Kind.PROPOSAL, "{}", "{}",
                Set.of("ai:knowledge:read"), 1, Duration.ofSeconds(1), 1,
                ToolDefinition.DataClassification.L1, false));
    }

    private static ToolDefinition definition(String id, String version) {
        return new ToolDefinition(id, version, "受控知识检索", ToolDefinition.Kind.READ,
                "{}", "{}", Set.of("ai:knowledge:read"), 1024, Duration.ofSeconds(2), 1,
                ToolDefinition.DataClassification.L1, false);
    }

    private static ToolDefinition validDefinition(
            ToolDefinition.Kind kind,
            String inputSchema,
            String outputSchema,
            Set<String> permissions,
            int maxBytes,
            Duration timeout,
            int maxCalls,
            ToolDefinition.DataClassification classification,
            boolean producesProposal) {
        return new ToolDefinition("knowledge.search.v1", "v1", "受控知识检索", kind,
                inputSchema, outputSchema, permissions, maxBytes, timeout, maxCalls,
                classification, producesProposal);
    }
}
