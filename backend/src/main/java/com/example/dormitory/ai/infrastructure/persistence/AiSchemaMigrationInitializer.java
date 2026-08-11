package com.example.dormitory.ai.infrastructure.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Order(10)
public class AiSchemaMigrationInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AiSchemaMigrationInitializer.class);
    /** MySQL TIMESTAMP 会按连接时区换算，Instant.EPOCH 在 UTC+ 时区可能越过其最小边界。 */
    private static final java.time.Instant FAKE_PRICING_EFFECTIVE_FROM =
            java.time.Instant.parse("2000-01-01T00:00:00Z");

    private static final Set<String> REQUIRED_TABLES = Set.of(
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
            "ai_runtime_switch", "ai_tool_call", "ai_tool_catalog_version", "ai_upload_session",
            "ai_usage_ledger");

    private static final List<ColumnMigration> CRITICAL_COLUMN_DDL = List.of(
            new ColumnMigration("ai_retrieval_trace", "retrieval_policy_version",
                    "ALTER TABLE ai_retrieval_trace ADD COLUMN retrieval_policy_version VARCHAR(64) "
                            + "NOT NULL DEFAULT 'knowledge-retrieval-policy-v1'"),
            new ColumnMigration("ai_retrieval_trace", "retrieval_mode",
                    "ALTER TABLE ai_retrieval_trace ADD COLUMN retrieval_mode VARCHAR(32) "
                            + "NOT NULL DEFAULT 'keyword-fallback-v1'"),
            new ColumnMigration("ai_retrieval_trace", "embedding_model_version",
                    "ALTER TABLE ai_retrieval_trace ADD COLUMN embedding_model_version VARCHAR(64) "
                            + "NOT NULL DEFAULT 'none'"),
            new ColumnMigration("ai_retrieval_trace", "acl_pre_filter_count",
                    "ALTER TABLE ai_retrieval_trace ADD COLUMN acl_pre_filter_count INT NOT NULL DEFAULT 0"),
            new ColumnMigration("ai_retrieval_trace", "acl_post_filter_count",
                    "ALTER TABLE ai_retrieval_trace ADD COLUMN acl_post_filter_count INT NOT NULL DEFAULT 0"),
            new ColumnMigration("ai_risk_scan", "actor_kind",
                    "ALTER TABLE ai_risk_scan ADD COLUMN actor_kind VARCHAR(16) DEFAULT 'SERVICE'"),
            new ColumnMigration("ai_risk_scan", "service_principal_code",
                    "ALTER TABLE ai_risk_scan ADD COLUMN service_principal_code VARCHAR(64) DEFAULT 'risk-scan'"),
            new ColumnMigration("ai_risk_scan", "initiated_by_user_id",
                    "ALTER TABLE ai_risk_scan ADD COLUMN initiated_by_user_id BIGINT"),
            new ColumnMigration("ai_risk_scan", "effective_subject_user_id",
                    "ALTER TABLE ai_risk_scan ADD COLUMN effective_subject_user_id BIGINT"),
            new ColumnMigration("ai_risk_scan", "requested_role_codes_text",
                    "ALTER TABLE ai_risk_scan ADD COLUMN requested_role_codes_text LONGTEXT"),
            new ColumnMigration("ai_risk_scan", "requested_permission_codes_text",
                    "ALTER TABLE ai_risk_scan ADD COLUMN requested_permission_codes_text LONGTEXT"),
            new ColumnMigration("ai_risk_scan", "requested_scope_text",
                    "ALTER TABLE ai_risk_scan ADD COLUMN requested_scope_text LONGTEXT"),
            new ColumnMigration("ai_risk_scan", "permission_digest",
                    "ALTER TABLE ai_risk_scan ADD COLUMN permission_digest CHAR(64)"),
            new ColumnMigration("ai_risk_case", "business_snapshot_redacted",
                    "ALTER TABLE ai_risk_case ADD COLUMN business_snapshot_redacted LONGTEXT"),
            new ColumnMigration("ai_risk_case", "explanation_text_redacted",
                    "ALTER TABLE ai_risk_case ADD COLUMN explanation_text_redacted LONGTEXT"),
            new ColumnMigration("ai_risk_case", "explanation_basis",
                    "ALTER TABLE ai_risk_case ADD COLUMN explanation_basis VARCHAR(32)"),
            new ColumnMigration("ai_risk_case", "explanation_policy_version",
                    "ALTER TABLE ai_risk_case ADD COLUMN explanation_policy_version VARCHAR(64)"),
            new ColumnMigration("ai_risk_case", "explanation_run_id",
                    "ALTER TABLE ai_risk_case ADD COLUMN explanation_run_id BIGINT"),
            new ColumnMigration("ai_risk_case", "assignee_user_id",
                    "ALTER TABLE ai_risk_case ADD COLUMN assignee_user_id BIGINT"),
            new ColumnMigration("ai_risk_case", "due_at",
                    "ALTER TABLE ai_risk_case ADD COLUMN due_at TIMESTAMP(6)"),
            new ColumnMigration("ai_usage_ledger", "attempt_outcome",
                    "ALTER TABLE ai_usage_ledger ADD COLUMN attempt_outcome VARCHAR(32) "
                            + "NOT NULL DEFAULT 'SUCCEEDED'"),
            new ColumnMigration("ai_usage_ledger", "duration_ms",
                    "ALTER TABLE ai_usage_ledger ADD COLUMN duration_ms BIGINT NOT NULL DEFAULT 0"),
            new ColumnMigration("ai_usage_ledger", "failure_code",
                    "ALTER TABLE ai_usage_ledger ADD COLUMN failure_code VARCHAR(64)"),
            new ColumnMigration("ai_usage_ledger", "estimation_policy_version",
                    "ALTER TABLE ai_usage_ledger ADD COLUMN estimation_policy_version VARCHAR(64)"),
            new ColumnMigration("ai_usage_ledger", "effective_subject_user_id",
                    "ALTER TABLE ai_usage_ledger ADD COLUMN effective_subject_user_id BIGINT"),
            new ColumnMigration("ai_provider_attempt", "owner_instance_id",
                    "ALTER TABLE ai_provider_attempt ADD COLUMN owner_instance_id VARCHAR(64) "
                            + "NOT NULL DEFAULT 'legacy-unowned'"),
            new ColumnMigration("ai_provider_attempt", "lease_expires_at",
                    "ALTER TABLE ai_provider_attempt ADD COLUMN lease_expires_at TIMESTAMP(6)"));

    private static final Map<String, String> CRITICAL_INDEX_DDL = Map.ofEntries(
            Map.entry("idx_ai_prompt_key_status_active",
                    "CREATE INDEX idx_ai_prompt_key_status_active "
                            + "ON ai_prompt_version (prompt_key, status, activated_at)"),
            Map.entry("idx_ai_ingestion_worker",
                    "CREATE INDEX idx_ai_ingestion_worker ON ai_ingestion_job (state, available_at, id)"),
            Map.entry("idx_ai_run_actor_state_created",
                    "CREATE INDEX idx_ai_run_actor_state_created ON ai_run (actor_user_id, state, created_at)"),
            Map.entry("idx_ai_run_conversation_state",
                    "CREATE INDEX idx_ai_run_conversation_state ON ai_run (conversation_id, state)"),
            Map.entry("idx_ai_idempotency_state_expires",
                    "CREATE INDEX idx_ai_idempotency_state_expires ON ai_idempotency_record (state, expires_at)"),
            Map.entry("idx_ai_budget_period_end",
                    "CREATE INDEX idx_ai_budget_period_end ON ai_budget_bucket (period_end)"),
            Map.entry("idx_ai_provider_attempt_state_started",
                    "CREATE INDEX idx_ai_provider_attempt_state_started "
                            + "ON ai_provider_attempt (state, started_at, id)"),
            Map.entry("idx_ai_provider_attempt_state_lease",
                    "CREATE INDEX idx_ai_provider_attempt_state_lease "
                            + "ON ai_provider_attempt (state, lease_expires_at, id)"),
            Map.entry("idx_ai_outbox_worker",
                    "CREATE INDEX idx_ai_outbox_worker ON ai_outbox_event (state, available_at, id)"),
            Map.entry("idx_ai_audit_event_correlation",
                    "CREATE INDEX idx_ai_audit_event_correlation ON ai_audit_event (correlation_id)"),
            Map.entry("idx_ai_erasure_target_state_checked",
                    "CREATE INDEX idx_ai_erasure_target_state_checked "
                            + "ON ai_erasure_target (state, last_checked_at)"));

    private static final Map<String, String> CRITICAL_CONSTRAINT_DDL = Map.of(
            "ck_ai_tool_call_authorization_decision",
            "ALTER TABLE ai_tool_call ADD CONSTRAINT ck_ai_tool_call_authorization_decision "
                    + "CHECK (authorization_decision IN ('ALLOWED','DENIED'))");

    private static final Pattern PROMPT_PATH = Pattern.compile(
            "(?i)[\\\\/]ai[\\\\/]prompts[\\\\/]([^\\\\/]+)[\\\\/]([^\\\\/]+)\\.md");

    private final JdbcTemplate jdbcTemplate;
    private final Resource schemaResource;
    private final boolean aiEnabled;
    private final List<Resource> promptResources;
    private volatile boolean schemaReady;

    @Autowired
    public AiSchemaMigrationInitializer(JdbcTemplate jdbcTemplate, Environment environment) {
        this(
                jdbcTemplate,
                new ClassPathResource("ai-schema.sql"),
                environment.getProperty("dormitory.ai.enabled", Boolean.class, false),
                discoverPromptResources());
    }

    public AiSchemaMigrationInitializer(
            JdbcTemplate jdbcTemplate,
            Resource schemaResource,
            boolean aiEnabled,
            List<Resource> promptResources) {
        this.jdbcTemplate = jdbcTemplate;
        this.schemaResource = schemaResource;
        this.aiEnabled = aiEnabled;
        this.promptResources = List.copyOf(promptResources);
    }

    @Override
    public void run(ApplicationArguments args) {
        migrate();
    }

    public void migrate() {
        schemaReady = false;
        try {
            if (jdbcTemplate.getDataSource() == null) {
                throw new IllegalStateException("AI schema 初始化缺少 DataSource");
            }
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator(schemaResource);
            populator.setContinueOnError(false);
            populator.execute(jdbcTemplate.getDataSource());
            ensureCriticalColumns();
            migrateLegacyAuthorizationDecisions();
            ensureCriticalConstraints();
            ensureCriticalIndexes();
            bootstrapFakePricing();
            bootstrapPrompts();
            validateRequiredSchema(aiEnabled);
            schemaReady = true;
        } catch (RuntimeException exception) {
            if (aiEnabled) {
                throw new IllegalStateException("AI 已启用但控制面 schema 初始化失败", exception);
            }
            log.warn("AI 处于关闭状态，控制面 schema 初始化失败但不阻断原业务: {}",
                    safeMessage(exception));
        }
    }

    public boolean isSchemaReady() {
        return schemaReady;
    }

    public void validateRequiredSchema(boolean failFast) {
        boolean mysql = isMySql();
        Set<String> missingTables = new LinkedHashSet<>();
        for (String table : REQUIRED_TABLES) {
            if (!tableExists(table, mysql)) missingTables.add(table);
        }
        Set<String> missingIndexes = new LinkedHashSet<>();
        for (String index : CRITICAL_INDEX_DDL.keySet()) {
            if (!indexExists(index, mysql)) missingIndexes.add(index);
        }
        Set<String> missingConstraints = new LinkedHashSet<>();
        for (String constraint : CRITICAL_CONSTRAINT_DDL.keySet()) {
            if (!constraintExists(constraint, mysql)) missingConstraints.add(constraint);
        }
        Set<String> missingColumns = new LinkedHashSet<>();
        for (ColumnMigration migration : CRITICAL_COLUMN_DDL) {
            if (tableExists(migration.table(), mysql)
                    && !columnExists(migration.table(), migration.column(), mysql)) {
                missingColumns.add(migration.table() + "." + migration.column());
            }
        }
        if (missingTables.isEmpty() && missingIndexes.isEmpty() && missingColumns.isEmpty()
                && missingConstraints.isEmpty()) return;

        String summary = "missingTables=" + missingTables + ", missingIndexes=" + missingIndexes
                + ", missingColumns=" + missingColumns + ", missingConstraints=" + missingConstraints;
        if (failFast) {
            throw new IllegalStateException("AI 控制面 schema 不完整: " + summary);
        }
        log.warn("AI 控制面 schema 尚不完整但 AI 未启用: {}", summary);
    }

    private void ensureCriticalIndexes() {
        boolean mysql = isMySql();
        for (Map.Entry<String, String> entry : CRITICAL_INDEX_DDL.entrySet()) {
            if (!indexExists(entry.getKey(), mysql)) {
                jdbcTemplate.execute(entry.getValue());
            }
        }
    }

    private void ensureCriticalConstraints() {
        boolean mysql = isMySql();
        for (Map.Entry<String, String> entry : CRITICAL_CONSTRAINT_DDL.entrySet()) {
            if (!constraintExists(entry.getKey(), mysql)) {
                jdbcTemplate.execute(entry.getValue());
            }
        }
    }

    private void migrateLegacyAuthorizationDecisions() {
        if (!tableExists("ai_tool_call", isMySql())) return;
        jdbcTemplate.update("UPDATE ai_tool_call SET authorization_decision='ALLOWED' "
                + "WHERE authorization_decision='ALLOW'");
    }

    private void ensureCriticalColumns() {
        boolean mysql = isMySql();
        for (ColumnMigration migration : CRITICAL_COLUMN_DDL) {
            if (!columnExists(migration.table(), migration.column(), mysql)) {
                jdbcTemplate.execute(migration.ddl());
            }
        }
    }

    private void bootstrapPrompts() {
        for (Resource resource : promptResources) {
            PromptResource prompt = readPrompt(resource);
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ai_prompt_version WHERE prompt_key = ? AND version = ?",
                    Integer.class,
                    prompt.promptKey(),
                    prompt.version());
            if (count != null && count > 0) continue;
            try {
                jdbcTemplate.update(
                        "INSERT INTO ai_prompt_version "
                                + "(prompt_key, version, content, content_hash, response_schema_version, "
                                + "status, active_slot_key, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, 'DRAFT', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                        prompt.promptKey(),
                        prompt.version(),
                        prompt.content(),
                        sha256Hex(prompt.content()),
                        "text.v1");
            } catch (DuplicateKeyException ignored) {
                // Another initializer imported the same immutable prompt version first.
            }
        }
    }

    private void bootstrapFakePricing() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_pricing_version WHERE provider_code='fake' "
                        + "AND model_name='deterministic-fake-v1' AND source_reference='test://fake-zero-price-v1'",
                Integer.class);
        if (count != null && count > 0) return;
        try {
            jdbcTemplate.update("INSERT INTO ai_pricing_version "
                            + "(provider_code,model_name,currency,input_cost_per_million,output_cost_per_million,"
                            + "effective_from,effective_to,source_reference,created_at,updated_at) "
                            + "VALUES ('fake','deterministic-fake-v1','CNY',0,0,?,NULL,"
                            + "'test://fake-zero-price-v1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    java.sql.Timestamp.from(FAKE_PRICING_EFFECTIVE_FROM));
        } catch (DuplicateKeyException ignored) {
            // 多实例初始化时，只允许一个显式 fake 零价版本获胜。
        }
    }

    private PromptResource readPrompt(Resource resource) {
        String description = resource.getDescription();
        Matcher matcher = PROMPT_PATH.matcher(description);
        if (!matcher.find()) {
            throw new IllegalStateException("非法 prompt 资源路径: " + description);
        }
        try {
            String content = resource.getContentAsString(StandardCharsets.UTF_8)
                    .replace("\r\n", "\n")
                    .strip() + "\n";
            if (content.isBlank()) throw new IllegalStateException("prompt 内容为空");
            return new PromptResource(
                    matcher.group(1).toLowerCase(Locale.ROOT) + ".system",
                    matcher.group(2).toLowerCase(Locale.ROOT),
                    content);
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取 prompt 资源: " + description, exception);
        }
    }

    public static String sha256Hex(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private boolean tableExists(String table, boolean mysql) {
        String sql = mysql
                ? "SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema = DATABASE() AND LOWER(table_name) = ?"
                : "SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema = 'PUBLIC' AND LOWER(table_name) = ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, table.toLowerCase(Locale.ROOT));
        return count != null && count > 0;
    }

    private boolean indexExists(String index, boolean mysql) {
        String sql = mysql
                ? "SELECT COUNT(*) FROM information_schema.statistics "
                    + "WHERE table_schema = DATABASE() AND LOWER(index_name) = ?"
                : "SELECT COUNT(*) FROM information_schema.indexes "
                    + "WHERE table_schema = 'PUBLIC' AND LOWER(index_name) = ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, index.toLowerCase(Locale.ROOT));
        return count != null && count > 0;
    }

    private boolean constraintExists(String constraint, boolean mysql) {
        String sql = mysql
                ? "SELECT COUNT(*) FROM information_schema.table_constraints "
                    + "WHERE constraint_schema = DATABASE() AND LOWER(constraint_name) = ?"
                : "SELECT COUNT(*) FROM information_schema.table_constraints "
                    + "WHERE table_schema = 'PUBLIC' AND LOWER(constraint_name) = ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, constraint.toLowerCase(Locale.ROOT));
        return count != null && count > 0;
    }

    private boolean columnExists(String table, String column, boolean mysql) {
        String sql = mysql
                ? "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() "
                    + "AND LOWER(table_name)=? AND LOWER(column_name)=?"
                : "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='PUBLIC' "
                    + "AND LOWER(table_name)=? AND LOWER(column_name)=?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class,
                table.toLowerCase(Locale.ROOT), column.toLowerCase(Locale.ROOT));
        return count != null && count > 0;
    }

    private boolean isMySql() {
        return Boolean.TRUE.equals(jdbcTemplate.execute((Connection connection) -> {
            try {
                return connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT)
                        .contains("mysql");
            } catch (SQLException exception) {
                throw new IllegalStateException("无法识别数据库类型", exception);
            }
        }));
    }

    private static List<Resource> discoverPromptResources() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:ai/prompts/*/*.md");
            return java.util.Arrays.stream(resources)
                    .sorted(java.util.Comparator.comparing(Resource::getDescription))
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("无法发现 bootstrap prompt", exception);
        }
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null ? exception.getClass().getSimpleName() : message.replaceAll("[\\r\\n]+", " ");
    }

    private record PromptResource(String promptKey, String version, String content) {
    }

    private record ColumnMigration(String table, String column, String ddl) { }
}
