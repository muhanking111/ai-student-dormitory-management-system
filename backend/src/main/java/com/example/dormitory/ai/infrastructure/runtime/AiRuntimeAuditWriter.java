package com.example.dormitory.ai.infrastructure.runtime;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.application.run.AiActorContext;
import com.example.dormitory.ai.config.AiProperties;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiAuditChainRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

@Component
public class AiRuntimeAuditWriter {

    private static final int MAX_PERSISTED_KEY_VERSIONS = 64;
    private static final String PERSISTED_KEY_VERSIONS_SQL =
            "SELECT integrity_key_version FROM ("
                    + "SELECT integrity_key_version FROM ai_audit_event "
                    + "UNION SELECT integrity_key_version FROM ai_audit_chain_head"
                    + ") persisted_key_versions ORDER BY integrity_key_version LIMIT ?";
    private static final String INCONSISTENT_CHAIN_HEAD_SQL = """
            SELECT 1
            FROM ai_audit_chain_head h
            LEFT JOIN (
              SELECT chain_scope, aggregate_type, aggregate_public_id,
                     COUNT(*) AS event_count, MIN(sequence_no) AS min_sequence_no,
                     MAX(sequence_no) AS max_sequence_no
              FROM ai_audit_event
              GROUP BY chain_scope, aggregate_type, aggregate_public_id
            ) s ON s.chain_scope = h.chain_scope
               AND s.aggregate_type = h.aggregate_type
               AND s.aggregate_public_id = h.aggregate_public_id
            LEFT JOIN ai_audit_event t ON t.chain_scope = h.chain_scope
               AND t.aggregate_type = h.aggregate_type
               AND t.aggregate_public_id = h.aggregate_public_id
               AND t.sequence_no = h.last_sequence_no
            WHERE h.last_sequence_no < 1
               OR h.last_event_hash IS NULL
               OR s.event_count IS NULL
               OR s.min_sequence_no <> 1
               OR s.max_sequence_no <> h.last_sequence_no
               OR s.event_count <> h.last_sequence_no
               OR t.id IS NULL
               OR t.event_hash <> h.last_event_hash
               OR t.integrity_key_version <> h.integrity_key_version
            LIMIT 1
            """;
    private static final String ORPHAN_AUDIT_EVENT_SQL = """
            SELECT 1
            FROM ai_audit_event e
            LEFT JOIN ai_audit_chain_head h ON h.chain_scope = e.chain_scope
               AND h.aggregate_type = e.aggregate_type
               AND h.aggregate_public_id = e.aggregate_public_id
            WHERE h.id IS NULL
            LIMIT 1
            """;
    private static final String BROKEN_AUDIT_LINK_SQL = """
            SELECT 1
            FROM ai_audit_event e
            LEFT JOIN ai_audit_event p ON p.chain_scope = e.chain_scope
               AND p.aggregate_type = e.aggregate_type
               AND p.aggregate_public_id = e.aggregate_public_id
               AND p.sequence_no = e.sequence_no - 1
            WHERE e.sequence_no < 1
               OR (e.sequence_no = 1 AND e.previous_event_hash IS NOT NULL)
               OR (e.sequence_no > 1 AND (
                    p.id IS NULL
                    OR e.previous_event_hash IS NULL
                    OR e.previous_event_hash <> p.event_hash
               ))
            LIMIT 1
            """;

    private final JdbcTemplate jdbcTemplate;
    private final AiProperties properties;
    private final JdbcAiAuditChainRepository repository;

    public AiRuntimeAuditWriter(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            AiProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.repository = new JdbcAiAuditChainRepository(
                jdbcTemplate,
                transactionManager,
                this::resolveAuditKey);
    }

    public boolean writable() {
        if (!hasStrongAuditKey(properties.getAudit().getActiveKeyVersion())) return false;
        try {
            List<Integer> persistedVersions = jdbcTemplate.queryForList(
                    PERSISTED_KEY_VERSIONS_SQL,
                    Integer.class,
                    MAX_PERSISTED_KEY_VERSIONS + 1);
            return persistedVersions.size() <= MAX_PERSISTED_KEY_VERSIONS
                    && persistedVersions.stream().allMatch(this::hasStrongAuditKey);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /** 启动时验证持久链结构和每个事件的 canonical HMAC。 */
    public boolean startupReady() {
        if (!writable()) return false;
        try {
            return !hasRows(INCONSISTENT_CHAIN_HEAD_SQL)
                    && !hasRows(ORPHAN_AUDIT_EVENT_SQL)
                    && !hasRows(BROKEN_AUDIT_LINK_SQL)
                    && repository.verifyAll()
                    && toolFactsConsistent(null);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public void requireWritable() {
        if (!writable()) {
            throw AiApiException.unavailable("AI_AUDIT_UNAVAILABLE", "AI 审计控制面不可用");
        }
    }

    public void requireValidChain(String chainScope, String aggregateType, String aggregatePublicId) {
        requireWritable();
        final boolean valid;
        try {
            valid = repository.verify(chainScope, aggregateType, aggregatePublicId);
        } catch (RuntimeException exception) {
            // 密钥轮换或配置异常在此边界统一视为完整性失败，不能向调用方泄露 key version、JDBC 或加密实现细节。
            throw AiApiException.unavailable("AI_AUDIT_INTEGRITY_FAILURE", "AI 审计链完整性校验失败");
        }
        if (!valid || ("RUN".equals(chainScope) && "RUN".equals(aggregateType)
                && !toolFactsConsistent(aggregatePublicId))) {
            throw AiApiException.unavailable("AI_AUDIT_INTEGRITY_FAILURE", "AI 审计链完整性校验失败");
        }
    }

    private boolean toolFactsConsistent(String runPublicId) {
        String toolSql = "SELECT t.public_id,t.sequence_no,t.tool_name,t.tool_version,"
                + "t.request_redacted,t.response_redacted,t.required_permissions_text,"
                + "t.authorization_decision,t.state,t.error_code,t.created_operator_user_id,"
                + "t.updated_operator_user_id,t.started_at,t.finished_at,r.public_id AS run_public_id "
                + "FROM ai_tool_call t JOIN ai_run r ON r.id=t.run_id"
                + (runPublicId == null ? "" : " WHERE r.public_id=?");
        List<ToolIntegrityRow> tools = runPublicId == null
                ? jdbcTemplate.query(toolSql, (resultSet, rowNum) -> mapTool(resultSet, rowNum))
                : jdbcTemplate.query(toolSql, (resultSet, rowNum) -> mapTool(resultSet, rowNum), runPublicId);
        for (ToolIntegrityRow tool : tools) {
            if (tool.createdOperatorUserId() == null || tool.updatedOperatorUserId() == null
                    || tool.startedAt() == null || tool.finishedAt() == null) return false;
            List<String> signedPayloads = jdbcTemplate.queryForList(
                    "SELECT payload_redacted_hash FROM ai_audit_event "
                            + "WHERE chain_scope='RUN' AND aggregate_type='RUN' "
                            + "AND aggregate_public_id=? AND event_type='TOOL_CALL_RECORDED' "
                            + "AND correlation_id=?",
                    String.class, tool.runPublicId(), tool.publicId());
            if (signedPayloads.size() != 1) return false;
            String expected = AiToolCallIntegrity.payloadHash(new AiToolCallIntegrity.ToolFact(
                    tool.publicId(), tool.runPublicId(), tool.sequenceNo(), tool.toolName(), tool.toolVersion(),
                    tool.requestRedacted(), tool.responseRedacted(), tool.requiredPermissionsJson(),
                    tool.authorizationDecision(), tool.state(), tool.errorCode(),
                    tool.createdOperatorUserId(), tool.updatedOperatorUserId(),
                    tool.startedAt(), tool.finishedAt()));
            if (!AiToolCallIntegrity.constantTimeEquals(expected, signedPayloads.getFirst())) return false;
        }

        StringBuilder orphanSql = new StringBuilder("SELECT COUNT(*) FROM ai_audit_event e "
                + "LEFT JOIN ai_tool_call t ON t.public_id=e.correlation_id "
                + "LEFT JOIN ai_run r ON r.id=t.run_id "
                + "WHERE e.event_type='TOOL_CALL_RECORDED'");
        List<Object> orphanArguments = new java.util.ArrayList<>();
        if (runPublicId != null) {
            orphanSql.append(" AND (r.public_id=? OR e.aggregate_public_id=?)");
            orphanArguments.add(runPublicId);
            orphanArguments.add(runPublicId);
        }
        orphanSql.append(" AND (e.chain_scope<>'RUN' OR e.aggregate_type<>'RUN' "
                + "OR t.id IS NULL OR r.public_id<>e.aggregate_public_id)");
        Integer orphanCount = jdbcTemplate.queryForObject(
                orphanSql.toString(), Integer.class, orphanArguments.toArray());
        return orphanCount != null && orphanCount == 0;
    }

    private ToolIntegrityRow mapTool(java.sql.ResultSet resultSet, int rowNum) throws java.sql.SQLException {
        long created = resultSet.getLong("created_operator_user_id");
        Long createdUser = resultSet.wasNull() ? null : created;
        long updated = resultSet.getLong("updated_operator_user_id");
        Long updatedUser = resultSet.wasNull() ? null : updated;
        java.sql.Timestamp started = resultSet.getTimestamp("started_at");
        java.sql.Timestamp finished = resultSet.getTimestamp("finished_at");
        return new ToolIntegrityRow(
                resultSet.getString("public_id"), resultSet.getString("run_public_id"),
                resultSet.getLong("sequence_no"), resultSet.getString("tool_name"),
                resultSet.getString("tool_version"), resultSet.getString("request_redacted"),
                resultSet.getString("response_redacted"), resultSet.getString("required_permissions_text"),
                resultSet.getString("authorization_decision"), resultSet.getString("state"),
                resultSet.getString("error_code"), createdUser, updatedUser,
                started == null ? null : started.toInstant(), finished == null ? null : finished.toInstant());
    }

    public void append(
            String chainScope,
            String aggregateType,
            String aggregatePublicId,
            String eventType,
            AiActorContext actor,
            String payloadHash,
            String correlationId) {
        append(chainScope, aggregateType, aggregatePublicId, eventType,
                actor.actor(), actor.sessionFingerprintHash(), actor.sessionFingerprintKeyVersion(),
                actor.permissionDigest(), payloadHash, correlationId);
    }

    public void append(
            String chainScope,
            String aggregateType,
            String aggregatePublicId,
            String eventType,
            ActorDescriptor actor,
            String sessionFingerprintHash,
            int sessionFingerprintKeyVersion,
            String permissionDigest,
            String payloadHash,
            String correlationId) {
        requireWritable();
        repository.append(new JdbcAiAuditChainRepository.AuditAppendCommand(
                chainScope,
                aggregateType,
                aggregatePublicId,
                eventType,
                actor,
                sessionFingerprintHash,
                sessionFingerprintKeyVersion,
                permissionDigest,
                payloadHash,
                correlationId,
                Instant.now(),
                properties.getAudit().getActiveKeyVersion()));
    }

    private byte[] resolveAuditKey(int version) {
        AiProperties.Audit audit = properties.getAudit();
        String retained = audit.getHmacKeyring().get(version);
        if (retained != null && !retained.isBlank()) {
            return retained.getBytes(StandardCharsets.UTF_8);
        }
        if (version == audit.getActiveKeyVersion()) {
            return audit.getHmacKey().getBytes(StandardCharsets.UTF_8);
        }
        if (version == audit.getPreviousKeyVersion() && version > 0
                && !audit.getPreviousHmacKey().isBlank()) {
            return audit.getPreviousHmacKey().getBytes(StandardCharsets.UTF_8);
        }
        return null;
    }

    private boolean hasStrongAuditKey(Integer version) {
        if (version == null || version < 1) return false;
        byte[] key = resolveAuditKey(version);
        return key != null && key.length >= 32;
    }

    private boolean hasRows(String sql) {
        return !jdbcTemplate.queryForList(sql, Integer.class).isEmpty();
    }

    private record ToolIntegrityRow(
            String publicId,
            String runPublicId,
            long sequenceNo,
            String toolName,
            String toolVersion,
            String requestRedacted,
            String responseRedacted,
            String requiredPermissionsJson,
            String authorizationDecision,
            String state,
            String errorCode,
            Long createdOperatorUserId,
            Long updatedOperatorUserId,
            Instant startedAt,
            Instant finishedAt) {
    }
}
