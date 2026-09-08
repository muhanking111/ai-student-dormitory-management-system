package com.example.dormitory.ai.governance;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.application.run.AiActorContext;
import com.example.dormitory.ai.approval.CanonicalJsonHasher;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiOutboxRepository;
import com.example.dormitory.ai.infrastructure.runtime.AiRuntimeAuditWriter;
import com.example.dormitory.ai.security.DataClassification;
import com.example.dormitory.ai.security.PiiClassificationService;
import com.example.dormitory.ai.security.SensitiveDataBlockedException;
import com.example.dormitory.common.PageResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class AiConfigurationGovernanceService {

    private static final Set<String> PROMPT_KEYS = Set.of(
            "assistant.system", "knowledge.system", "dashboard.system", "repair.system",
            "notice.system", "risk.system", "evaluation.system");
    private static final String CONFIG_SERVICE = "ai-config-control";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final StandardToolCatalogManifest standardCatalog;
    private final JdbcAiOutboxRepository outbox;
    private final AiRuntimeAuditWriter audit;
    private final PiiClassificationService classification;

    public AiConfigurationGovernanceService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            StandardToolCatalogManifest standardCatalog,
            JdbcAiOutboxRepository outbox,
            AiRuntimeAuditWriter audit,
            PiiClassificationService classification) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.standardCatalog = standardCatalog;
        this.outbox = outbox;
        this.audit = audit;
        this.classification = classification;
    }

    public PageResponse<PromptView> listPrompts(
            String promptKey, String status, long page, long pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("prompt 分页参数不合法");
        }
        String key = normalizeOptionalPromptKey(promptKey);
        String normalizedStatus = normalizeOptionalStatus(status);
        String where = " WHERE (? IS NULL OR prompt_key = ?) AND (? IS NULL OR status = ?)";
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_prompt_version" + where,
                Long.class, key, key, normalizedStatus, normalizedStatus);
        List<PromptView> records = jdbcTemplate.query(
                "SELECT id,prompt_key,version,content,content_hash,response_schema_version,status,"
                        + "active_slot_key,created_at,activated_at FROM ai_prompt_version" + where
                        + " ORDER BY prompt_key, created_at DESC, id DESC LIMIT ? OFFSET ?",
                this::mapPrompt, key, key, normalizedStatus, normalizedStatus,
                pageSize, (page - 1) * pageSize);
        return new PageResponse<>(records, total == null ? 0 : total, page, pageSize);
    }

    public PromptView requirePrompt(String publicId) {
        String normalized = requireUuid(publicId);
        return jdbcTemplate.query("SELECT id,prompt_key,version,content,content_hash,response_schema_version,"
                        + "status,active_slot_key,created_at,activated_at FROM ai_prompt_version ORDER BY id",
                        this::mapPrompt).stream()
                .filter(prompt -> prompt.id().equals(normalized))
                .findFirst()
                .orElseThrow(AiApiException::notFound);
    }

    @Transactional
    public PromptView createPromptDraft(
            AiActorContext actor,
            String promptKey,
            String version,
            String content,
            String responseSchemaVersion,
            String requestHash) {
        audit.requireWritable();
        String key = normalizePromptKey(promptKey);
        String normalizedVersion = normalizeVersion(version);
        String normalizedContent = validatePromptContent(content);
        var classified = classification.redact(normalizedContent, "prompt-config-" + key);
        if (classified.classification() != DataClassification.L1
                || !classified.redactedText().equals(normalizedContent)) {
            throw new SensitiveDataBlockedException("提示词配置不得包含个人或受保护数据");
        }
        String schema = normalizeResponseSchema(responseSchemaVersion);
        String contentHash = CanonicalJsonHasher.sha256(normalizedContent);
        try {
            jdbcTemplate.update("INSERT INTO ai_prompt_version "
                            + "(prompt_key,version,content,content_hash,response_schema_version,status,active_slot_key,"
                            + "created_operator_user_id,updated_operator_user_id,created_at,updated_at) "
                            + "VALUES (?,?,?,?,?,'DRAFT',NULL,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    key, normalizedVersion, normalizedContent, contentHash, schema,
                    actor.userId(), actor.userId());
        } catch (DuplicateKeyException duplicate) {
            throw conflict("AI_PROMPT_VERSION_CONFLICT", "提示词版本已存在");
        }
        PromptView created = requirePrompt(promptPublicId(key, normalizedVersion, contentHash));
        audit.append("CONFIG", "PROMPT_VERSION", created.id(), "PROMPT_VERSION_CREATED",
                actor, requestHash, UUID.randomUUID().toString());
        enqueueConfigEvent(created.id(), "AI_PROMPT_VERSION_CREATED", actor,
                "{\"schemaVersion\":\"config-event.v1\",\"promptKey\":\"" + key
                        + "\",\"version\":\"" + normalizedVersion + "\"}");
        return created;
    }

    @Transactional
    public void activatePrompt(
            AiActorContext actor,
            String publicId,
            String expectedActiveId,
            String requestHash) {
        audit.requireWritable();
        PromptView target = requirePrompt(publicId);
        List<PromptLock> locked = jdbcTemplate.query(
                "SELECT id,prompt_key,version,content,content_hash,status,active_slot_key "
                        + "FROM ai_prompt_version WHERE prompt_key=? ORDER BY id FOR UPDATE",
                (rs, row) -> new PromptLock(rs.getLong("id"), rs.getString("prompt_key"),
                        rs.getString("version"), rs.getString("content"), rs.getString("content_hash"),
                        rs.getString("status"), rs.getString("active_slot_key")), target.promptKey());
        PromptLock lockedTarget = locked.stream()
                .filter(row -> promptPublicId(row.promptKey(), row.version(), row.contentHash()).equals(target.id()))
                .findFirst().orElseThrow(AiApiException::notFound);
        String current = locked.stream().filter(row -> target.promptKey().equals(row.activeSlotKey()))
                .map(row -> promptPublicId(row.promptKey(), row.version(), row.contentHash()))
                .findFirst().orElse(null);
        if (!java.util.Objects.equals(normalizeOptionalUuid(expectedActiveId), current)) {
            throw conflict("AI_CONFIG_VERSION_CONFLICT", "提示词 active slot 已变化");
        }
        if (target.id().equals(current) || !Set.of("DRAFT", "INACTIVE").contains(lockedTarget.status())) {
            throw conflict("AI_CONFIG_STATE_CONFLICT", "提示词版本不能从当前状态激活");
        }
        String lockedContent = validatePromptContent(lockedTarget.content());
        String currentContentHash = CanonicalJsonHasher.sha256(lockedContent);
        if (!currentContentHash.equals(lockedTarget.contentHash())) {
            throw conflict("AI_PROMPT_CONTENT_HASH_CONFLICT", "提示词正文与创建时 hash 不一致");
        }
        var classified = classification.redact(
                lockedContent, "prompt-config-" + lockedTarget.promptKey());
        if (classified.classification() != DataClassification.L1
                || !classified.redactedText().equals(lockedContent)) {
            throw new SensitiveDataBlockedException("提示词配置不得包含个人或受保护数据");
        }
        jdbcTemplate.update("UPDATE ai_prompt_version SET status='INACTIVE',active_slot_key=NULL,"
                        + "updated_operator_user_id=?,updated_at=CURRENT_TIMESTAMP "
                        + "WHERE prompt_key=? AND active_slot_key=?",
                actor.userId(), target.promptKey(), target.promptKey());
        int activated = jdbcTemplate.update("UPDATE ai_prompt_version SET status='ACTIVE',active_slot_key=?,"
                        + "approved_by_user_id=?,activated_at=CURRENT_TIMESTAMP,updated_operator_user_id=?,"
                        + "updated_at=CURRENT_TIMESTAMP WHERE id=? AND status IN ('DRAFT','INACTIVE')",
                target.promptKey(), actor.userId(), actor.userId(), lockedTarget.id());
        if (activated != 1) throw conflict("AI_CONFIG_VERSION_CONFLICT", "提示词激活 CAS 未命中");
        audit.append("CONFIG", "PROMPT_VERSION", target.id(), "PROMPT_VERSION_ACTIVATED",
                actor, requestHash, UUID.randomUUID().toString());
        enqueueConfigEvent(target.id(), "AI_PROMPT_VERSION_ACTIVATED", actor,
                "{\"schemaVersion\":\"config-event.v1\",\"promptKey\":\""
                        + target.promptKey() + "\",\"version\":\"" + target.version() + "\"}");
    }

    @Transactional
    public void activateModelAlias(
            AiActorContext actor,
            String alias,
            String deploymentPublicId,
            long expectedVersion,
            String requestHash) {
        audit.requireWritable();
        String aliasCode = normalizeAlias(alias);
        if (expectedVersion < 0) throw new IllegalArgumentException("model alias version 不合法");
        Deployment deployment = requireApprovedDeployment(deploymentPublicId);
        List<AliasLock> aliases = jdbcTemplate.query(
                "SELECT id,active_deployment_id,version FROM ai_model_alias WHERE alias_code=? FOR UPDATE",
                (rs, row) -> new AliasLock(rs.getLong("id"), nullableLong(rs, "active_deployment_id"),
                        rs.getLong("version")), aliasCode);
        long nextVersion;
        if (aliases.isEmpty()) {
            if (expectedVersion != 0) {
                throw conflict("AI_CONFIG_VERSION_CONFLICT", "model alias 版本已变化");
            }
            try {
                jdbcTemplate.update("INSERT INTO ai_model_alias "
                                + "(alias_code,active_deployment_id,version,activated_by_user_id,activated_at,"
                                + "created_operator_user_id,updated_operator_user_id,created_at,updated_at) "
                                + "VALUES (?,?,1,?,CURRENT_TIMESTAMP,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                        aliasCode, deployment.id(), actor.userId(), actor.userId(), actor.userId());
            } catch (DuplicateKeyException duplicate) {
                throw conflict("AI_CONFIG_VERSION_CONFLICT", "model alias 并发创建冲突");
            }
            nextVersion = 1;
        } else {
            AliasLock current = aliases.getFirst();
            if (current.version() != expectedVersion) {
                throw conflict("AI_CONFIG_VERSION_CONFLICT", "model alias 版本已变化");
            }
            if (java.util.Objects.equals(current.activeDeploymentId(), deployment.id())) {
                throw conflict("AI_CONFIG_STATE_CONFLICT", "model deployment 已经激活");
            }
            int updated = jdbcTemplate.update("UPDATE ai_model_alias SET active_deployment_id=?,version=version+1,"
                            + "activated_by_user_id=?,activated_at=CURRENT_TIMESTAMP,updated_operator_user_id=?,"
                            + "updated_at=CURRENT_TIMESTAMP WHERE id=? AND version=?",
                    deployment.id(), actor.userId(), actor.userId(), current.id(), expectedVersion);
            if (updated != 1) throw conflict("AI_CONFIG_VERSION_CONFLICT", "model alias 激活 CAS 未命中");
            nextVersion = expectedVersion + 1;
        }
        String aggregate = aliasPublicId(aliasCode);
        audit.append("CONFIG", "MODEL_ALIAS", aggregate, "MODEL_ALIAS_ACTIVATED",
                actor, requestHash, UUID.randomUUID().toString());
        enqueueConfigEvent(aggregate, "AI_MODEL_ALIAS_ACTIVATED", actor,
                "{\"schemaVersion\":\"config-event.v1\",\"alias\":\"" + aliasCode
                        + "\",\"deploymentId\":\"" + deployment.publicId()
                        + "\",\"version\":" + nextVersion + "}");
    }

    @Transactional(readOnly = true)
    public List<ToolCatalogView> listToolCatalogs() {
        List<ToolCatalogView> persisted = jdbcTemplate.query(
                "SELECT version,manifest_hash,status,active_slot_key,created_at,activated_at "
                        + "FROM ai_tool_catalog_version WHERE manifest_hash=? OR active_slot_key='runtime' ORDER BY id",
                this::mapToolCatalog, standardCatalog.hash());
        if (persisted.stream().anyMatch(row -> standardCatalog.hash().equals(row.manifestHash()))) return persisted;
        var choices = new java.util.ArrayList<>(persisted);
        choices.add(new ToolCatalogView(
                toolCatalogPublicId(standardCatalog.hash()), standardCatalog.version(), standardCatalog.hash(),
                "DRAFT", false, standardCatalog.toolIds(), null, null));
        return List.copyOf(choices);
    }

    @Transactional
    public void activateToolCatalog(
            AiActorContext actor,
            String publicId,
            String version,
            String manifestHash,
            String expectedActiveId,
            String requestHash) {
        audit.requireWritable();
        ensureStandardCatalog();
        if (!standardCatalog.version().equals(version) || !standardCatalog.hash().equals(manifestHash)) {
            throw new AiApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AI_TOOL_CATALOG_NOT_ALLOWLISTED",
                    "仅允许激活标准 7 工具目录", false);
        }
        List<ToolCatalogLock> locked = jdbcTemplate.query(
                "SELECT id,version,manifest_text,manifest_hash,status,active_slot_key "
                        + "FROM ai_tool_catalog_version ORDER BY id FOR UPDATE",
                (rs, row) -> new ToolCatalogLock(rs.getLong("id"), rs.getString("version"),
                        rs.getString("manifest_text"), rs.getString("manifest_hash"),
                        rs.getString("status"), rs.getString("active_slot_key")));
        ToolCatalogLock target = locked.stream().filter(row -> toolCatalogPublicId(row.manifestHash()).equals(publicId))
                .findFirst().orElseThrow(AiApiException::notFound);
        if (!standardCatalog.hash().equals(target.manifestHash())
                || !standardCatalog.manifest().equals(CanonicalJsonHasher.canonicalize(target.manifestText()))) {
            throw new AiApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AI_TOOL_CATALOG_NOT_ALLOWLISTED",
                    "仅允许激活标准 7 工具目录", false);
        }
        String current = locked.stream().filter(row -> "runtime".equals(row.activeSlotKey()))
                .map(row -> toolCatalogPublicId(row.manifestHash())).findFirst().orElse(null);
        if (!java.util.Objects.equals(normalizeOptionalUuid(expectedActiveId), current)) {
            throw conflict("AI_CONFIG_VERSION_CONFLICT", "ToolCatalog active slot 已变化");
        }
        if (publicId.equals(current) || !Set.of("DRAFT", "INACTIVE").contains(target.status())) {
            throw conflict("AI_CONFIG_STATE_CONFLICT", "ToolCatalog 不能从当前状态激活");
        }
        jdbcTemplate.update("UPDATE ai_tool_catalog_version SET status='INACTIVE',active_slot_key=NULL,"
                        + "updated_operator_user_id=?,updated_at=CURRENT_TIMESTAMP WHERE active_slot_key='runtime'",
                actor.userId());
        int updated = jdbcTemplate.update("UPDATE ai_tool_catalog_version SET status='ACTIVE',"
                        + "active_slot_key='runtime',activated_at=CURRENT_TIMESTAMP,updated_operator_user_id=?,"
                        + "updated_at=CURRENT_TIMESTAMP WHERE id=? AND status IN ('DRAFT','INACTIVE')",
                actor.userId(), target.id());
        if (updated != 1) throw conflict("AI_CONFIG_VERSION_CONFLICT", "ToolCatalog 激活 CAS 未命中");
        audit.append("CONFIG", "TOOL_CATALOG", publicId, "TOOL_CATALOG_ACTIVATED",
                actor, requestHash, UUID.randomUUID().toString());
        enqueueConfigEvent(publicId, "AI_TOOL_CATALOG_ACTIVATED", actor,
                "{\"schemaVersion\":\"config-event.v1\",\"version\":\"" + version
                        + "\",\"manifestHash\":\"" + manifestHash + "\"}");
    }

    private PromptView mapPrompt(ResultSet rs, int row) throws SQLException {
        String key = rs.getString("prompt_key");
        String version = rs.getString("version");
        String contentHash = rs.getString("content_hash");
        return new PromptView(promptPublicId(key, version, contentHash), key, version,
                rs.getString("content"), contentHash, rs.getString("response_schema_version"),
                rs.getString("status"), key.equals(rs.getString("active_slot_key")),
                instant(rs, "created_at"), nullableInstant(rs, "activated_at"));
    }

    private ToolCatalogView mapToolCatalog(ResultSet rs, int row) throws SQLException {
        String hash = rs.getString("manifest_hash");
        return new ToolCatalogView(toolCatalogPublicId(hash), rs.getString("version"), hash,
                rs.getString("status"), "runtime".equals(rs.getString("active_slot_key")),
                standardCatalog.hash().equals(hash) ? standardCatalog.toolIds() : List.of(),
                instant(rs, "created_at"), nullableInstant(rs, "activated_at"));
    }

    private void ensureStandardCatalog() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_tool_catalog_version WHERE manifest_hash=?",
                Integer.class, standardCatalog.hash());
        if (count != null && count > 0) return;
        try {
            jdbcTemplate.update("INSERT INTO ai_tool_catalog_version "
                            + "(version,manifest_text,manifest_hash,status,active_slot_key,created_at,updated_at) "
                            + "VALUES (?,?,?,'DRAFT',NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    standardCatalog.version(), standardCatalog.manifest(), standardCatalog.hash());
        } catch (DuplicateKeyException concurrent) {
            Integer existing = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ai_tool_catalog_version WHERE manifest_hash=?",
                    Integer.class, standardCatalog.hash());
            if (existing == null || existing == 0) {
                throw new IllegalStateException("标准 ToolCatalog 无法初始化", concurrent);
            }
        }
    }

    private Deployment requireApprovedDeployment(String publicId) {
        String normalized = requireUuid(publicId);
        List<Deployment> rows = jdbcTemplate.query("SELECT id,public_id,endpoint_alias,capabilities_text,enabled "
                        + "FROM ai_model_deployment WHERE public_id=?",
                (rs, row) -> new Deployment(rs.getLong("id"), rs.getString("public_id"),
                        rs.getString("endpoint_alias"), rs.getString("capabilities_text"),
                        rs.getBoolean("enabled")), normalized);
        if (rows.isEmpty()) throw AiApiException.notFound();
        Deployment deployment = rows.getFirst();
        boolean approved;
        try {
            JsonNode capabilities = objectMapper.readTree(deployment.capabilitiesText());
            approved = capabilities != null && capabilities.isObject()
                    && capabilities.path("contractTestPassed").asBoolean(false)
                    && capabilities.path("dataPolicyApproved").asBoolean(false);
        } catch (Exception exception) {
            approved = false;
        }
        if (!deployment.enabled() || !approved
                || !deployment.endpointAlias().matches("[a-zA-Z0-9][a-zA-Z0-9._-]{1,127}")) {
            throw new AiApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AI_MODEL_DEPLOYMENT_NOT_APPROVED",
                    "模型部署未通过合同或数据政策门", false);
        }
        return deployment;
    }

    private void enqueueConfigEvent(
            String aggregatePublicId,
            String eventType,
            AiActorContext actor,
            String payload) {
        outbox.enqueue(new JdbcAiOutboxRepository.OutboxDraft(
                "AI_CONFIG", aggregatePublicId, eventType, payload,
                ActorDescriptor.service(CONFIG_SERVICE, actor.userId(), actor.userId()), Instant.now()));
    }

    public static String promptPublicId(String key, String version, String contentHash) {
        return resourceId("prompt", key + "|" + version + "|" + contentHash);
    }

    public static String promptKeyAggregateId(String key) {
        return resourceId("prompt-key", normalizePromptKey(key));
    }

    public static String aliasPublicId(String alias) {
        return resourceId("model-alias", normalizeAlias(alias));
    }

    public static String toolCatalogPublicId(String manifestHash) {
        return resourceId("tool-catalog", manifestHash);
    }

    public static String resourceId(String namespace, String value) {
        return UUID.nameUUIDFromBytes(("ai-governance|" + namespace + "|" + value)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String normalizePromptKey(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!PROMPT_KEYS.contains(normalized)) throw new IllegalArgumentException("promptKey 不在首期白名单");
        return normalized;
    }

    private static String normalizeOptionalPromptKey(String value) {
        return value == null || value.isBlank() ? null : normalizePromptKey(value);
    }

    private static String normalizeVersion(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("v[1-9][0-9]{0,8}")) throw new IllegalArgumentException("版本号不合法");
        return normalized;
    }

    private static String normalizeResponseSchema(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z][a-z0-9._-]{1,63}\\.v[1-9][0-9]*")) {
            throw new IllegalArgumentException("response schema 版本不合法");
        }
        return normalized;
    }

    private static String validatePromptContent(String value) {
        String normalized = value == null ? "" : value.replace("\r\n", "\n").strip() + "\n";
        if (normalized.isBlank() || normalized.length() > 100_000
                || normalized.codePoints().anyMatch(code -> Character.isISOControl(code)
                && code != '\n' && code != '\t')
                || normalized.matches("(?is).*(?:sk-[a-z0-9_-]{16,}|bearer\\s+[a-z0-9._-]{20,}).*")) {
            throw new IllegalArgumentException("prompt 内容为空、过长、含控制字符或疑似密钥");
        }
        return normalized;
    }

    private static String normalizeAlias(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z][a-z0-9._-]{1,63}")) throw new IllegalArgumentException("alias 不合法");
        return normalized;
    }

    private static String normalizeOptionalStatus(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("DRAFT", "ACTIVE", "INACTIVE", "RETIRED").contains(normalized)) {
            throw new IllegalArgumentException("prompt 状态筛选不合法");
        }
        return normalized;
    }

    private static String normalizeOptionalUuid(String value) {
        return value == null || value.isBlank() ? null : requireUuid(value);
    }

    private static String requireUuid(String value) {
        try {
            return UUID.fromString(value == null ? "" : value.trim()).toString();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("资源 ID 必须为 UUID", exception);
        }
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static AiApiException conflict(String code, String message) {
        return new AiApiException(HttpStatus.CONFLICT, code, message, false);
    }

    public record PromptView(
            String id,
            String promptKey,
            String version,
            String content,
            String contentHash,
            String responseSchemaVersion,
            String status,
            boolean active,
            Instant createdAt,
            Instant activatedAt) {
    }

    public record ToolCatalogView(
            String id,
            String version,
            String manifestHash,
            String status,
            boolean active,
            List<String> toolIds,
            Instant createdAt,
            Instant activatedAt) {
        public ToolCatalogView {
            toolIds = List.copyOf(toolIds);
        }
    }

    private record PromptLock(
            long id,
            String promptKey,
            String version,
            String content,
            String contentHash,
            String status,
            String activeSlotKey) {
    }

    private record ToolCatalogLock(
            long id, String version, String manifestText, String manifestHash, String status, String activeSlotKey) {
    }

    private record Deployment(long id, String publicId, String endpointAlias, String capabilitiesText, boolean enabled) {
    }

    private record AliasLock(long id, Long activeDeploymentId, long version) {
    }
}
