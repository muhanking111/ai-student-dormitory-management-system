package com.example.dormitory.ai.infrastructure.runtime;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.port.AiToolCallAuditPort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcAiToolCallAuditAdapter implements AiToolCallAuditPort {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AiRuntimeAuditWriter auditWriter;

    public JdbcAiToolCallAuditAdapter(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            AiRuntimeAuditWriter auditWriter) {
        this.jdbcTemplate = java.util.Objects.requireNonNull(jdbcTemplate);
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper);
        this.auditWriter = java.util.Objects.requireNonNull(auditWriter);
    }

    @Override
    @Transactional
    public void append(ToolCallAudit audit) {
        if (audit == null) throw new IllegalArgumentException("工具审计事实不能为空");
        List<RunOwner> owners = jdbcTemplate.query(
                "SELECT id,actor_user_id FROM ai_run WHERE public_id=? FOR UPDATE",
                (resultSet, rowNum) -> new RunOwner(
                        resultSet.getLong("id"), resultSet.getLong("actor_user_id")),
                audit.runId());
        if (owners.isEmpty()) throw AiApiException.notFound();
        RunOwner owner = owners.getFirst();
        if (owner.actorUserId() != audit.actorUserId()) {
            throw new SecurityException("工具审计 actor 与运行所有者不匹配");
        }
        Long sequence = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(sequence_no),0)+1 FROM ai_tool_call WHERE run_id=?",
                Long.class, owner.runDatabaseId());
        if (sequence == null || sequence < 1) {
            throw new IllegalStateException("工具审计序号分配失败");
        }
        String publicId = UUID.randomUUID().toString();
        String permissions = permissionsJson(audit);
        java.time.Instant startedAt = AiToolCallIntegrity.normalize(audit.startedAt());
        java.time.Instant finishedAt = AiToolCallIntegrity.normalize(audit.finishedAt());
        jdbcTemplate.update("INSERT INTO ai_tool_call "
                        + "(public_id,run_id,sequence_no,tool_name,tool_version,request_redacted,response_redacted,"
                        + "required_permissions_text,authorization_decision,state,version,started_at,finished_at,"
                        + "error_code,created_operator_user_id,updated_operator_user_id,created_at,updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,0,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                publicId, owner.runDatabaseId(), sequence,
                audit.toolName(), audit.toolVersion(), audit.requestRedacted(), audit.responseRedacted(),
                permissions, audit.authorizationDecision().name(), audit.state().name(),
                Timestamp.from(startedAt), Timestamp.from(finishedAt), audit.errorCode(),
                audit.actorUserId(), audit.actorUserId());
        auditWriter.append("RUN", "RUN", audit.runId(), "TOOL_CALL_RECORDED",
                ActorDescriptor.user(audit.actorUserId()), null, 1, null,
                AiToolCallIntegrity.payloadHash(new AiToolCallIntegrity.ToolFact(
                        publicId, audit.runId(), sequence, audit.toolName(), audit.toolVersion(),
                        audit.requestRedacted(), audit.responseRedacted(), permissions,
                        audit.authorizationDecision().name(), audit.state().name(), audit.errorCode(),
                        audit.actorUserId(), audit.actorUserId(), startedAt, finishedAt)), publicId);
    }

    private String permissionsJson(ToolCallAudit audit) {
        try {
            return objectMapper.writeValueAsString(audit.requiredPermissions().stream()
                    .sorted(Comparator.naturalOrder()).toList());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("工具权限审计序列化失败", exception);
        }
    }

    private record RunOwner(long runDatabaseId, long actorUserId) {
    }
}
