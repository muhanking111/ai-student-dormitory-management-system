package com.example.dormitory.ai.config;

import com.example.dormitory.ai.domain.model.AiCapability;
import com.example.dormitory.ai.security.PiiClassificationService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * MySQL 事实源上的紧急关闭层。每次判定读取共享事实源，因此重启和多实例不会静默丢失关闭状态。
 * 运行时覆盖只能收窄静态配置，不能把部署时关闭的能力动态打开。
 */
@Component
public class AiRuntimeControlService {

    private final AiProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final PiiClassificationService classification;

    public AiRuntimeControlService(
            AiProperties properties,
            JdbcTemplate jdbcTemplate,
            PiiClassificationService classification) {
        this.properties = java.util.Objects.requireNonNull(properties);
        this.jdbcTemplate = java.util.Objects.requireNonNull(jdbcTemplate);
        this.classification = java.util.Objects.requireNonNull(classification);
    }

    public boolean masterEnabled() {
        return properties.isEnabled() && !isDisabled(Scope.MASTER, "*");
    }

    public boolean capabilityEnabled(AiCapability capability) {
        return masterEnabled() && staticallyEnabled(capability)
                && !isDisabled(Scope.CAPABILITY, capability.name());
    }

    public boolean providerEnabled(String providerCode) {
        String normalized = canonicalKey(Scope.PROVIDER, providerCode);
        return masterEnabled() && !"none".equals(properties.getProvider().getActive())
                && properties.getProvider().getActive().equals(normalized)
                && !isDisabled(Scope.PROVIDER, normalized);
    }

    public boolean writeExecutionEnabled() {
        return masterEnabled() && properties.getWriteExecution().isEnabled()
                && !isDisabled(Scope.WRITE, "*");
    }

    public boolean sourceEnabled(String sourcePublicId) {
        return masterEnabled() && !isDisabled(Scope.SOURCE, canonicalKey(Scope.SOURCE, sourcePublicId));
    }

    @Transactional
    public SwitchRecord disable(Scope scope, String key, String reason) {
        return disable(scope, key, reason, null);
    }

    @Transactional
    public SwitchRecord disable(Scope scope, String key, String reason, Long updatedByUserId) {
        SwitchKey normalized = new SwitchKey(scope, canonicalKey(scope, key));
        String safeReason = normalizeReason(reason);
        int updated = jdbcTemplate.update("UPDATE ai_runtime_switch SET disabled=TRUE, reason_redacted=?, "
                        + "version=version+1, updated_by_user_id=?, updated_at=CURRENT_TIMESTAMP "
                        + "WHERE scope_type=? AND scope_key=?",
                safeReason, updatedByUserId, scope.name(), normalized.key());
        if (updated == 0) {
            try {
                jdbcTemplate.update("INSERT INTO ai_runtime_switch "
                                + "(scope_type,scope_key,resource_public_id,disabled,reason_redacted,version,"
                                + "updated_by_user_id,created_at,updated_at) "
                                + "VALUES(?,?,?,TRUE,?,1,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                        scope.name(), normalized.key(), resourcePublicId(scope, normalized.key()), safeReason,
                        updatedByUserId);
            } catch (DuplicateKeyException concurrentInsert) {
                jdbcTemplate.update("UPDATE ai_runtime_switch SET disabled=TRUE, reason_redacted=?, "
                                + "version=version+1, updated_by_user_id=?, updated_at=CURRENT_TIMESTAMP "
                                + "WHERE scope_type=? AND scope_key=?",
                        safeReason, updatedByUserId, scope.name(), normalized.key());
            }
        }
        return require(scope, normalized.key());
    }

    @Transactional
    public SwitchRecord clear(
            Scope scope,
            String key,
            String reason,
            long expectedVersion,
            Long updatedByUserId) {
        SwitchKey normalized = new SwitchKey(scope, canonicalKey(scope, key));
        if (expectedVersion < 1) throw new IllegalArgumentException("Kill Switch 版本不合法");
        normalizeReason(reason);
        int deleted = jdbcTemplate.update("DELETE FROM ai_runtime_switch WHERE scope_type=? AND scope_key=? "
                        + "AND disabled=TRUE AND version=?",
                scope.name(), normalized.key(), expectedVersion);
        if (deleted != 1) throw new RuntimeSwitchConflictException();
        return new SwitchRecord(scope, normalized.key(), resourcePublicId(scope, normalized.key()), false,
                "已清除运行时关闭覆盖",
                expectedVersion + 1, Instant.now());
    }

    public List<SwitchRecord> disabledSwitches() {
        return jdbcTemplate.query("SELECT scope_type,scope_key,resource_public_id,disabled,reason_redacted,"
                        + "version,updated_at FROM ai_runtime_switch WHERE disabled=TRUE "
                        + "ORDER BY scope_type,scope_key",
                (resultSet, rowNum) -> new SwitchRecord(
                        Scope.valueOf(resultSet.getString("scope_type")), resultSet.getString("scope_key"),
                        resultSet.getString("resource_public_id"), resultSet.getBoolean("disabled"),
                        resultSet.getString("reason_redacted"), resultSet.getLong("version"),
                        resultSet.getTimestamp("updated_at").toInstant()));
    }

    private boolean isDisabled(Scope scope, String key) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_runtime_switch "
                        + "WHERE scope_type=? AND scope_key=? AND disabled=TRUE", Integer.class,
                scope.name(), canonicalKey(scope, key));
        return count != null && count > 0;
    }

    private SwitchRecord require(Scope scope, String key) {
        SwitchRecord record = find(scope, key);
        if (record == null) throw new IllegalStateException("Kill Switch 状态写入失败");
        return record;
    }

    private SwitchRecord find(Scope scope, String key) {
        List<SwitchRecord> records = jdbcTemplate.query("SELECT scope_type,scope_key,resource_public_id,disabled,"
                        + "reason_redacted,version,updated_at FROM ai_runtime_switch "
                        + "WHERE scope_type=? AND scope_key=?",
                (resultSet, rowNum) -> new SwitchRecord(
                        Scope.valueOf(resultSet.getString("scope_type")), resultSet.getString("scope_key"),
                        resultSet.getString("resource_public_id"), resultSet.getBoolean("disabled"),
                        resultSet.getString("reason_redacted"), resultSet.getLong("version"),
                        resultSet.getTimestamp("updated_at").toInstant()), scope.name(), key);
        return records.isEmpty() ? null : records.getFirst();
    }

    private boolean staticallyEnabled(AiCapability capability) {
        return switch (capability) {
            case ASSISTANT -> properties.getCapabilities().isAssistant();
            case KNOWLEDGE -> properties.getCapabilities().isKnowledge();
            case DASHBOARD -> properties.getCapabilities().isDashboard();
            case REPAIR -> properties.getCapabilities().isRepair();
            case NOTICE -> properties.getCapabilities().isNotice();
            case RISK -> properties.getCapabilities().isRisk();
            case EVALUATION -> properties.getCapabilities().isEvaluation();
        };
    }

    public static String canonicalKey(Scope scope, String key) {
        String normalized = key == null ? "" : key.trim();
        return switch (scope) {
            case MASTER, WRITE -> {
                if (!"*".equals(normalized)) throw new IllegalArgumentException("总开关和写执行开关 key 必须为 *");
                yield normalized;
            }
            case CAPABILITY -> {
                String value = normalized.toUpperCase(Locale.ROOT);
                try {
                    AiCapability.valueOf(value);
                } catch (RuntimeException exception) {
                    throw new IllegalArgumentException("未知 AI capability", exception);
                }
                yield value;
            }
            case PROVIDER -> {
                String value = normalized.toLowerCase(Locale.ROOT);
                if (!value.matches("[a-z0-9][a-z0-9-]{1,31}")) {
                    throw new IllegalArgumentException("provider code 不合法");
                }
                yield value;
            }
            case SOURCE -> {
                if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9_-]{1,127}")) {
                    throw new IllegalArgumentException("知识源 ID 不合法");
                }
                yield normalized;
            }
        };
    }

    private String normalizeReason(String reason) {
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.length() < 6 || normalized.length() > 500
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Kill Switch 理由需为 6-500 字纯文本");
        }
        String redacted = classification.redact(normalized, "kill-switch-reason").redactedText();
        if (redacted.length() > 500) {
            throw new IllegalArgumentException("Kill Switch 理由脱敏后超过 500 字");
        }
        return redacted;
    }

    public static String resourcePublicId(Scope scope, String key) {
        return java.util.UUID.nameUUIDFromBytes(("ai-kill-switch|" + scope.name() + "|" + key)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    public enum Scope { MASTER, CAPABILITY, PROVIDER, SOURCE, WRITE }

    private record SwitchKey(Scope scope, String key) { }

    public record SwitchRecord(
            Scope scope,
            String key,
            String resourcePublicId,
            boolean disabled,
            String reason,
            long version,
            Instant updatedAt) { }
}
