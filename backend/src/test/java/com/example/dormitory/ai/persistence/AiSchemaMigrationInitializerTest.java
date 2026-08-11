package com.example.dormitory.ai.persistence;

import com.example.dormitory.ai.infrastructure.persistence.AiSchemaMigrationInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.AbstractResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@SpringBootTest
class AiSchemaMigrationInitializerTest {

    private static final Set<String> AI_TABLES = Set.of(
            "ai_action_approval", "ai_action_execution", "ai_action_proposal",
            "ai_audit_anchor", "ai_audit_chain_head", "ai_audit_event",
            "ai_budget_bucket", "ai_budget_reservation", "ai_citation", "ai_conversation",
            "ai_document", "ai_document_chunk", "ai_document_version",
            "ai_erasure_job", "ai_erasure_target", "ai_eval_result", "ai_eval_run", "ai_feedback",
            "ai_idempotency_record", "ai_ingestion_job", "ai_knowledge_public_approval",
            "ai_knowledge_source", "ai_knowledge_source_permission", "ai_message",
            "ai_model_alias", "ai_model_deployment", "ai_outbox_event", "ai_pricing_version",
            "ai_prompt_version", "ai_provider_attempt", "ai_quota_policy", "ai_retrieval_trace", "ai_risk_case",
            "ai_risk_case_event", "ai_risk_scan", "ai_run", "ai_run_event", "ai_step_up_grant",
            "ai_step_up_failure_window",
            "ai_runtime_switch", "ai_tool_call", "ai_tool_catalog_version", "ai_upload_session", "ai_usage_ledger");

    private static final Set<String> BUSINESS_TABLES = Set.of(
            "sys_user", "sys_role", "sys_permission", "sys_user_role", "sys_role_permission",
            "building", "dormitory", "dormitory_building", "student", "bed",
            "check_in_application", "check_in_application_detail", "check_in_record",
            "repair_order", "repair_record", "payment", "payment_record", "hygiene_check", "notice");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AiSchemaMigrationInitializer initializer;

    @Test
    void createsExactlyFortyFourAiTablesWithoutOwningBusinessSchema() throws Exception {
        assertEquals(44, AI_TABLES.size());
        assertEquals(AI_TABLES, tableNames("ai_"));
        assertTrue(tableNames("").containsAll(BUSINESS_TABLES));
        assertFalse(AI_TABLES.contains("notice"));

        String schema = new ClassPathResource("ai-schema.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile(
                        "(?i)CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+([a-z0-9_]+)")
                .matcher(schema);
        Set<String> declared = new java.util.LinkedHashSet<>();
        while (matcher.find()) declared.add(matcher.group(1).toLowerCase(Locale.ROOT));
        assertEquals(AI_TABLES, declared);
    }

    @Test
    void fractionalTimestampDefaultsUseMatchingMysqlPrecision() throws Exception {
        String schema = new ClassPathResource("ai-schema.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertFalse(Pattern.compile(
                        "(?i)TIMESTAMP\\(6\\)\\s+NOT\\s+NULL\\s+DEFAULT\\s+CURRENT_TIMESTAMP(?!\\(6\\))")
                .matcher(schema).find());
    }

    @Test
    void migrationIsAdditiveAndIdempotent() {
        assertDoesNotThrow(initializer::migrate);
        assertDoesNotThrow(initializer::migrate);
        assertEquals(AI_TABLES, tableNames("ai_"));
        assertEquals(7, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_prompt_version", Integer.class));
    }

    @Test
    void schemaReadinessBecomesVisibleOnlyAfterSuccessfulMigrationAndValidation() {
        DataSource isolated = new DriverManagerDataSource(
                "jdbc:h2:mem:ai-schema-ready-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        AiSchemaMigrationInitializer migration = new AiSchemaMigrationInitializer(
                new JdbcTemplate(isolated), new ClassPathResource("ai-schema.sql"), true, List.of());

        assertFalse(migration.isSchemaReady());
        assertDoesNotThrow(migration::migrate);
        assertTrue(migration.isSchemaReady());

        AiSchemaMigrationInitializer broken = new AiSchemaMigrationInitializer(
                new JdbcTemplate(isolated),
                new ByteArrayResource("CREATE TABL definitely_broken".getBytes(StandardCharsets.UTF_8)),
                false,
                List.of());
        assertDoesNotThrow(broken::migrate);
        assertFalse(broken.isSchemaReady());
    }

    @Test
    void migratesRiskEvidenceAndHumanDispositionColumnsIntoAnExistingTable() {
        DataSource isolated = new DriverManagerDataSource(
                "jdbc:h2:mem:ai-risk-column-migration-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(isolated);
        jdbc.execute("CREATE TABLE ai_risk_case (id BIGINT PRIMARY KEY AUTO_INCREMENT)");
        AiSchemaMigrationInitializer migration = new AiSchemaMigrationInitializer(
                jdbc, new ClassPathResource("ai-schema.sql"), false, List.of());

        assertDoesNotThrow(migration::migrate);
        Set<String> migrated = jdbc.queryForList(
                        "SELECT column_name FROM information_schema.columns "
                                + "WHERE table_schema='PUBLIC' AND LOWER(table_name)='ai_risk_case'",
                        String.class).stream().map(value -> value.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        assertTrue(migrated.containsAll(Set.of(
                "business_snapshot_redacted", "explanation_text_redacted", "explanation_basis",
                "explanation_policy_version", "explanation_run_id", "assignee_user_id", "due_at")));
        assertDoesNotThrow(migration::migrate);
    }

    @Test
    void criticalColumnsUniqueConstraintsAndIndexesMatchTheContract() {
        Map<String, Set<String>> requiredColumns = Map.ofEntries(
                Map.entry("ai_prompt_version", Set.of(
                        "prompt_key", "version", "content", "content_hash", "response_schema_version",
                        "status", "active_slot_key")),
                Map.entry("ai_idempotency_record", Set.of(
                        "actor_user_id", "route_code", "aggregate_public_id", "idempotency_key",
                        "request_hash", "state", "response_status", "version")),
                Map.entry("ai_budget_bucket", Set.of(
                        "scope_type", "scope_key", "capability", "provider_code", "period_start",
                        "token_limit", "cost_limit", "reserved_tokens", "committed_tokens",
                        "reserved_cost", "committed_cost", "version")),
                Map.entry("ai_budget_reservation", Set.of(
                        "billing_subject_kind", "billing_subject_public_id", "budget_bucket_id",
                        "reserved_tokens", "reserved_cost", "committed_tokens", "committed_cost", "state")),
                Map.entry("ai_usage_ledger", Set.of(
                        "billing_subject_kind", "billing_subject_public_id", "request_sequence_no",
                        "attempt_no", "request_kind", "actor_kind", "provider_code", "model_name",
                        "input_tokens", "output_tokens", "cost_amount", "usage_source",
                        "effective_subject_user_id", "estimation_policy_version", "occurred_at")),
                Map.entry("ai_provider_attempt", Set.of(
                        "public_id", "billing_subject_kind", "billing_subject_public_id",
                        "request_sequence_no", "attempt_no", "request_kind", "actor_kind",
                        "effective_subject_user_id", "capability", "provider_code", "model_name", "pricing_version_id",
                        "currency", "input_cost_per_million", "output_cost_per_million",
                        "state", "owner_instance_id", "lease_expires_at", "attempt_outcome",
                        "started_at", "finished_at",
                        "reconciliation_marked_at", "version")),
                Map.entry("ai_retrieval_trace", Set.of(
                        "public_id", "run_id", "query_hash", "retrieval_policy_version", "retrieval_mode",
                        "index_code", "index_version", "embedding_model_version", "filter_redacted", "top_k",
                        "acl_pre_filter_count", "acl_post_filter_count", "returned_count", "latency_ms", "state")),
                Map.entry("ai_audit_chain_head", Set.of(
                        "chain_scope", "aggregate_type", "aggregate_public_id", "last_sequence_no",
                        "last_event_hash", "integrity_key_version", "version")),
                Map.entry("ai_audit_event", Set.of(
                        "public_id", "chain_scope", "aggregate_type", "aggregate_public_id", "sequence_no",
                        "previous_event_hash", "event_hash", "integrity_alg", "integrity_key_version",
                        "canonicalization_version", "correlation_id", "occurred_at")),
                Map.entry("ai_outbox_event", Set.of(
                        "public_id", "aggregate_type", "aggregate_public_id", "event_type", "state",
                        "version", "attempts", "available_at", "locked_by", "locked_at", "published_at")),
                Map.entry("ai_risk_scan", Set.of(
                        "public_id", "requested_by_user_id", "actor_kind", "service_principal_code",
                        "initiated_by_user_id", "effective_subject_user_id", "requested_role_codes_text",
                        "requested_permission_codes_text", "requested_scope_text", "permission_digest",
                        "state", "version")),
                Map.entry("ai_risk_case", Set.of(
                        "signal_snapshot_redacted", "business_snapshot_redacted",
                        "explanation_text_redacted", "explanation_basis", "explanation_policy_version",
                        "explanation_run_id", "assignee_user_id", "due_at", "version")),
                Map.entry("ai_erasure_target", Set.of(
                        "erasure_job_id", "target_kind", "target_ref_hash", "provider_code", "state",
                        "attempts", "proof_ref_hash", "last_checked_at", "last_error_code")));
        requiredColumns.forEach((table, expected) ->
                assertTrue(columns(table).containsAll(expected), () -> table + " 缺少关键列"));

        for (String constraint : List.of(
                "uq_ai_prompt_key_version", "uq_ai_prompt_active_slot",
                "uq_ai_message_client_request", "uq_ai_idempotency_scope",
                "uq_ai_budget_bucket_scope_period", "uq_ai_budget_reservation_subject_bucket",
                "uq_ai_usage_attempt", "uq_ai_provider_attempt_identity",
                "uq_ai_audit_head_scope", "uq_ai_audit_event_sequence",
                "uq_ai_outbox_public_id", "uq_ai_erasure_target_key")) {
            assertTrue(constraintExists(constraint), () -> "缺少唯一约束 " + constraint);
        }
        assertTrue(checkConstraintExists("ck_ai_tool_call_authorization_decision"));

        for (String index : List.of(
                "idx_ai_prompt_key_status_active", "idx_ai_ingestion_worker",
                "idx_ai_run_actor_state_created", "idx_ai_run_conversation_state",
                "idx_ai_idempotency_state_expires",
                "idx_ai_budget_period_end", "idx_ai_outbox_worker",
                "idx_ai_provider_attempt_state_started", "idx_ai_provider_attempt_state_lease",
                "idx_ai_audit_event_correlation", "idx_ai_erasure_target_state_checked")) {
            assertTrue(indexExists(index), () -> "缺少索引 " + index);
        }
    }

    @Test
    void bootstrapPromptsArePlainTextDraftsWithVerifiedHashes() throws Exception {
        List<Map<String, Object>> prompts = jdbcTemplate.queryForList(
                "SELECT prompt_key, version, content, content_hash, status, active_slot_key "
                        + "FROM ai_prompt_version ORDER BY prompt_key");
        assertEquals(7, prompts.size());
        for (Map<String, Object> prompt : prompts) {
            assertEquals("v1", prompt.get("VERSION"));
            assertEquals("DRAFT", prompt.get("STATUS"));
            assertEquals(null, prompt.get("ACTIVE_SLOT_KEY"));
            String content = (String) prompt.get("CONTENT");
            assertFalse(content.isBlank());
            assertFalse(content.contains("<script"));
            assertFalse(content.contains("<html"));
            assertEquals(AiSchemaMigrationInitializer.sha256Hex(content), prompt.get("CONTENT_HASH"));
        }
    }

    @Test
    void enabledAiFailsFastWhenRequiredSchemaIsMissingButDisabledAiContainsMigrationFailure() {
        jdbcTemplate.execute("DROP INDEX idx_ai_outbox_worker");
        try {
            assertThrows(IllegalStateException.class, () -> initializer.validateRequiredSchema(true));
        } finally {
            jdbcTemplate.execute("CREATE INDEX idx_ai_outbox_worker "
                    + "ON ai_outbox_event (state, available_at, id)");
        }
        assertDoesNotThrow(() -> initializer.validateRequiredSchema(false));

        DataSource isolated = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("ai-disabled-broken-schema-" + System.nanoTime())
                .build();
        AiSchemaMigrationInitializer broken = new AiSchemaMigrationInitializer(
                new JdbcTemplate(isolated),
                new ByteArrayResource("CREATE TABL definitely_broken".getBytes(StandardCharsets.UTF_8)),
                false,
                List.of());
        assertDoesNotThrow(broken::migrate);
    }

    @Test
    void enabledAiTreatsPersistentRuntimeSwitchAsRequiredControlPlaneFact() {
        jdbcTemplate.execute("DROP TABLE ai_runtime_switch");
        try {
            assertThrows(IllegalStateException.class, () -> initializer.validateRequiredSchema(true));
        } finally {
            initializer.migrate();
        }
        assertDoesNotThrow(() -> initializer.validateRequiredSchema(true));
    }

    @Test
    void budgetFactsRejectNegativeAmountsAtTheDatabaseBoundary() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO ai_budget_bucket "
                        + "(quota_policy_id, scope_type, scope_key, capability, provider_code, period_type, "
                        + "period_start, period_end, token_limit, cost_limit, reserved_tokens, committed_tokens, "
                        + "reserved_cost, committed_cost, currency, version, created_at, updated_at) "
                        + "VALUES (999, 'USER', 'negative-test', 'ASSISTANT', 'fake', 'DAILY', "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, -1, 1, 0, 0, 0, 0, 'CNY', 0, "
                + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"));
    }

    @Test
    void migratesLegacyAllowDecisionAndRejectsUnknownAuthorizationValues() {
        DataSource isolated = new DriverManagerDataSource(
                "jdbc:h2:mem:ai-tool-decision-migration-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(isolated);
        jdbc.execute("CREATE TABLE ai_tool_call (id BIGINT PRIMARY KEY AUTO_INCREMENT, "
                + "authorization_decision VARCHAR(32) NOT NULL)");
        jdbc.update("INSERT INTO ai_tool_call(authorization_decision) VALUES ('ALLOW')");
        AiSchemaMigrationInitializer migration = new AiSchemaMigrationInitializer(
                jdbc, new ClassPathResource("ai-schema.sql"), false, List.of());

        assertDoesNotThrow(migration::migrate);
        assertEquals("ALLOWED", jdbc.queryForObject(
                "SELECT authorization_decision FROM ai_tool_call", String.class));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO ai_tool_call(authorization_decision) VALUES ('ALLOW')"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO ai_tool_call(authorization_decision) VALUES ('UNKNOWN')"));
    }

    @Test
    void missingDatasourceAndEnabledFailuresRespectTheConfiguredFailClosedMode() {
        JdbcTemplate noDataSource = mock(JdbcTemplate.class);
        AiSchemaMigrationInitializer disabled = new AiSchemaMigrationInitializer(
                noDataSource, new ByteArrayResource(new byte[0]), false, List.of());
        AiSchemaMigrationInitializer enabled = new AiSchemaMigrationInitializer(
                noDataSource, new ByteArrayResource(new byte[0]), true, List.of());

        assertDoesNotThrow(disabled::migrate);
        IllegalStateException failure = assertThrows(IllegalStateException.class, enabled::migrate);
        assertTrue(failure.getMessage().contains("AI 已启用"));

        JdbcTemplate noMessage = mock(JdbcTemplate.class);
        when(noMessage.getDataSource()).thenThrow(new RuntimeException());
        assertDoesNotThrow(() -> new AiSchemaMigrationInitializer(
                noMessage, new ByteArrayResource(new byte[0]), false, List.of()).migrate());
    }

    @Test
    void validationReportsMissingColumnsAndTreatsNullMetadataCountsAsMissing() {
        DataSource isolated = new DriverManagerDataSource(
                "jdbc:h2:mem:ai-missing-columns-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate partial = new JdbcTemplate(isolated);
        partial.execute("CREATE TABLE ai_risk_case (id BIGINT PRIMARY KEY AUTO_INCREMENT)");
        AiSchemaMigrationInitializer partialInitializer = new AiSchemaMigrationInitializer(
                partial, new ClassPathResource("ai-schema.sql"), false, List.of());

        assertDoesNotThrow(() -> partialInitializer.validateRequiredSchema(false));
        assertThrows(IllegalStateException.class, () -> partialInitializer.validateRequiredSchema(true));

        JdbcTemplate nullMetadata = mock(JdbcTemplate.class);
        AiSchemaMigrationInitializer nullCounts = new AiSchemaMigrationInitializer(
                nullMetadata, new ByteArrayResource(new byte[0]), false, List.of());
        assertDoesNotThrow(() -> nullCounts.validateRequiredSchema(false));
        assertThrows(IllegalStateException.class, () -> nullCounts.validateRequiredSchema(true));
    }

    @Test
    void migrateRecreatesDroppedCriticalIndexInsteadOfOnlyDetectingIt() {
        jdbcTemplate.execute("DROP INDEX idx_ai_outbox_worker");
        try {
            assertDoesNotThrow(initializer::migrate);
            assertTrue(indexExists("idx_ai_outbox_worker"));
        } finally {
            if (!indexExists("idx_ai_outbox_worker")) {
                jdbcTemplate.execute("CREATE INDEX idx_ai_outbox_worker "
                        + "ON ai_outbox_event (state, available_at, id)");
            }
        }
    }

    @Test
    void invalidEmptyAndUnreadablePromptResourcesFailClosedWhenAiIsEnabled() {
        ByteArrayResource invalidPath = new ByteArrayResource("prompt".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getDescription() {
                return "memory prompt without version path";
            }
        };
        ByteArrayResource empty = new ByteArrayResource(new byte[0]) {
            @Override
            public String getDescription() {
                return "class path resource [ai/prompts/assistant/v9.md]";
            }
        };
        AbstractResource unreadable = new AbstractResource() {
            @Override
            public String getDescription() {
                return "class path resource [ai/prompts/assistant/v10.md]";
            }

            @Override
            public InputStream getInputStream() throws IOException {
                throw new IOException("unreadable prompt");
            }
        };

        assertPromptMigrationFails(invalidPath);
        assertPromptMigrationFails(empty);
        assertPromptMigrationFails(unreadable);
    }

    private void assertPromptMigrationFails(org.springframework.core.io.Resource prompt) {
        DataSource isolated = new DriverManagerDataSource(
                "jdbc:h2:mem:ai-broken-prompt-" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        AiSchemaMigrationInitializer migration = new AiSchemaMigrationInitializer(
                new JdbcTemplate(isolated), new ClassPathResource("ai-schema.sql"), true, List.of(prompt));
        IllegalStateException failure = assertThrows(IllegalStateException.class, migration::migrate);
        assertTrue(failure.getMessage().contains("AI 已启用"));
    }

    private Set<String> tableNames(String prefix) {
        return jdbcTemplate.queryForList(
                        "SELECT table_name FROM information_schema.tables WHERE table_schema = 'PUBLIC'",
                        String.class)
                .stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .filter(value -> value.startsWith(prefix))
                .collect(Collectors.toSet());
    }

    private Set<String> columns(String table) {
        return jdbcTemplate.queryForList(
                        "SELECT column_name FROM information_schema.columns WHERE LOWER(table_name) = ?",
                        String.class,
                        table.toLowerCase(Locale.ROOT))
                .stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    private boolean constraintExists(String constraint) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE LOWER(constraint_name) = ? AND constraint_type = 'UNIQUE'",
                Integer.class,
                constraint.toLowerCase(Locale.ROOT));
        return count != null && count > 0;
    }

    private boolean checkConstraintExists(String constraint) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE LOWER(constraint_name) = ? AND constraint_type = 'CHECK'",
                Integer.class, constraint.toLowerCase(Locale.ROOT));
        return count != null && count > 0;
    }

    private boolean indexExists(String index) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.indexes WHERE LOWER(index_name) = ?",
                Integer.class,
                index.toLowerCase(Locale.ROOT));
        return count != null && count > 0;
    }
}
