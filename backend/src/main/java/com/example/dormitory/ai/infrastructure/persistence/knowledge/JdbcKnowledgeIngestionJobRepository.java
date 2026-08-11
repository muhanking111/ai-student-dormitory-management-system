package com.example.dormitory.ai.infrastructure.persistence.knowledge;

import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiOutboxRepository;
import com.example.dormitory.ai.knowledge.KnowledgeIngestionJobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class JdbcKnowledgeIngestionJobRepository implements KnowledgeIngestionJobRepository {

    private final JdbcTemplate jdbcTemplate;
    private final JdbcAiOutboxRepository outbox;

    @Autowired
    public JdbcKnowledgeIngestionJobRepository(
            JdbcTemplate jdbcTemplate, JdbcAiOutboxRepository outbox) {
        this.jdbcTemplate = jdbcTemplate;
        this.outbox = outbox;
    }

    /** 仅供不启动 Spring 的 repository 合同测试。 */
    public JdbcKnowledgeIngestionJobRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new JdbcAiOutboxRepository(jdbcTemplate));
    }

    @Override
    @Transactional
    public IngestionJob enqueue(long documentVersionId, long initiatedByUserId, Instant availableAt) {
        if (documentVersionId < 1 || initiatedByUserId < 1 || availableAt == null) {
            throw new IllegalArgumentException("知识摄取任务参数不合法");
        }
        String publicId = UUID.randomUUID().toString();
        try {
            jdbcTemplate.update("INSERT INTO ai_ingestion_job "
                            + "(public_id, document_version_id, state, attempt, actor_kind, "
                            + "service_principal_code, initiated_by_user_id, effective_subject_user_id, version, "
                            + "available_at, created_operator_user_id, updated_operator_user_id, created_at, "
                            + "updated_at) VALUES (?, ?, 'QUEUED', 1, 'SERVICE', 'knowledge-ingestion', ?, ?, 0, "
                            + "?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    publicId, documentVersionId, initiatedByUserId, initiatedByUserId,
                    Timestamp.from(availableAt), initiatedByUserId, initiatedByUserId);
        } catch (DuplicateKeyException duplicate) {
            List<IngestionJob> existing = jdbcTemplate.query(
                    "SELECT * FROM ai_ingestion_job WHERE document_version_id = ? AND attempt = 1",
                    this::mapJob, documentVersionId);
            if (!existing.isEmpty()) return ensureOutbox(existing.getFirst());
            throw duplicate;
        }
        return ensureOutbox(findByPublicId(publicId).orElseThrow());
    }

    @Override
    @Transactional
    public Optional<IngestionJob> claimNext(String workerId, Instant now) {
        if (workerId == null || workerId.isBlank() || workerId.length() > 128 || now == null) {
            throw new IllegalArgumentException("知识摄取 worker 参数不合法");
        }
        for (int retry = 0; retry < 5; retry++) {
            List<IngestionJob> candidates = jdbcTemplate.query(
                    "SELECT * FROM ai_ingestion_job WHERE state = 'QUEUED' AND available_at <= ? "
                            + "ORDER BY available_at, id LIMIT 1",
                    this::mapJob, Timestamp.from(now));
            if (candidates.isEmpty()) return Optional.empty();
            IngestionJob candidate = candidates.getFirst();
            int updated = jdbcTemplate.update("UPDATE ai_ingestion_job SET state = 'RUNNING', worker_id = ?, "
                            + "started_at = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP "
                            + "WHERE id = ? AND state = 'QUEUED' AND version = ?",
                    workerId, Timestamp.from(now), candidate.id(), candidate.version());
            if (updated == 1) return findByPublicId(candidate.publicId());
        }
        return Optional.empty();
    }

    @Override
    @Transactional
    public Optional<IngestionJob> claim(String publicId, String workerId, Instant now) {
        if (publicId == null || publicId.isBlank() || workerId == null || workerId.isBlank()
                || workerId.length() > 128 || now == null) {
            throw new IllegalArgumentException("知识摄取定向 claim 参数不合法");
        }
        IngestionJob candidate = findByPublicId(publicId).orElse(null);
        if (candidate == null) return Optional.empty();
        if ("SUCCEEDED".equals(candidate.state()) || "FAILED".equals(candidate.state())) {
            return Optional.of(candidate);
        }
        int updated = jdbcTemplate.update("UPDATE ai_ingestion_job SET state='RUNNING',worker_id=?,"
                        + "started_at=COALESCE(started_at,?),version=version+1,updated_at=CURRENT_TIMESTAMP "
                        + "WHERE public_id=? AND state IN ('QUEUED','RUNNING') AND version=?",
                workerId, Timestamp.from(now), publicId, candidate.version());
        if (updated != 1) throw new IllegalStateException("知识摄取定向 claim CAS 冲突");
        return findByPublicId(publicId);
    }

    @Override
    public void succeed(String publicId, String workerId, Instant finishedAt) {
        finish(publicId, workerId, "SUCCEEDED", null, null, finishedAt);
    }

    @Override
    public void fail(
            String publicId, String workerId, String errorCode, String safeSummary, Instant finishedAt) {
        if (errorCode == null || !errorCode.matches("[A-Z0-9_]{2,64}")
                || safeSummary == null || safeSummary.isBlank() || safeSummary.length() > 500) {
            throw new IllegalArgumentException("知识摄取失败信息不合法");
        }
        finish(publicId, workerId, "FAILED", errorCode, safeSummary, finishedAt);
    }

    @Override
    public void requeue(String publicId, String workerId, Instant availableAt) {
        if (publicId == null || publicId.isBlank() || workerId == null || workerId.isBlank() || availableAt == null) {
            throw new IllegalArgumentException("知识摄取重排参数不合法");
        }
        int updated = jdbcTemplate.update("UPDATE ai_ingestion_job SET state='QUEUED',worker_id=NULL,"
                        + "started_at=NULL,available_at=?,version=version+1,updated_at=CURRENT_TIMESTAMP "
                        + "WHERE public_id=? AND state='RUNNING' AND worker_id=?",
                Timestamp.from(availableAt), publicId, workerId);
        if (updated != 1) throw new IllegalStateException("知识摄取任务无法重新排队");
    }

    @Override
    public void dead(String publicId, String errorCode, Instant finishedAt) {
        if (publicId == null || publicId.isBlank() || errorCode == null
                || !errorCode.matches("[A-Z0-9_]{1,64}") || finishedAt == null) {
            throw new IllegalArgumentException("知识摄取 DEAD 参数不合法");
        }
        int updated = jdbcTemplate.update("UPDATE ai_ingestion_job SET state='DEAD',error_code=?,"
                        + "error_summary='Outbox 重试耗尽，需人工复核',finished_at=?,version=version+1,"
                        + "updated_at=CURRENT_TIMESTAMP WHERE public_id=? "
                        + "AND state IN ('QUEUED','RUNNING')",
                errorCode, Timestamp.from(finishedAt), publicId);
        if (updated == 0 && findByPublicId(publicId).filter(job -> Set.of(
                "SUCCEEDED", "FAILED", "DEAD").contains(job.state())).isEmpty()) {
            throw new IllegalStateException("知识摄取 DEAD 状态 CAS 未命中");
        }
    }

    @Override
    public Optional<IngestionJob> findByPublicId(String publicId) {
        if (publicId == null || publicId.isBlank()) return Optional.empty();
        return jdbcTemplate.query("SELECT * FROM ai_ingestion_job WHERE public_id = ?", this::mapJob, publicId)
                .stream().findFirst();
    }

    @Override
    public Optional<IngestionJob> findByDocumentVersionId(long documentVersionId) {
        if (documentVersionId < 1) return Optional.empty();
        return jdbcTemplate.query("SELECT * FROM ai_ingestion_job WHERE document_version_id = ? "
                        + "ORDER BY attempt DESC LIMIT 1", this::mapJob, documentVersionId)
                .stream().findFirst();
    }

    private void finish(
            String publicId,
            String workerId,
            String state,
            String errorCode,
            String safeSummary,
            Instant finishedAt) {
        if (publicId == null || publicId.isBlank() || workerId == null || workerId.isBlank()
                || finishedAt == null) {
            throw new IllegalArgumentException("知识摄取完成参数不合法");
        }
        int updated = jdbcTemplate.update("UPDATE ai_ingestion_job SET state = ?, finished_at = ?, "
                        + "error_code = ?, error_summary = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE public_id = ? AND state = 'RUNNING' AND worker_id = ?",
                state, Timestamp.from(finishedAt), errorCode, safeSummary, publicId, workerId);
        if (updated != 1) throw new IllegalStateException("知识摄取任务状态冲突");
    }

    private IngestionJob mapJob(ResultSet resultSet, int rowNumber) throws SQLException {
        return new IngestionJob(resultSet.getLong("id"), resultSet.getString("public_id"),
                resultSet.getLong("document_version_id"), resultSet.getString("state"),
                resultSet.getInt("attempt"), resultSet.getString("worker_id"),
                resultSet.getLong("initiated_by_user_id"), resultSet.getLong("version"),
                instant(resultSet, "available_at"), instant(resultSet, "started_at"),
                instant(resultSet, "finished_at"), resultSet.getString("error_code"));
    }

    private IngestionJob ensureOutbox(IngestionJob job) {
        String versionPublicId = jdbcTemplate.queryForObject(
                "SELECT public_id FROM ai_document_version WHERE id=?", String.class, job.documentVersionId());
        if (versionPublicId == null) throw new IllegalStateException("摄取任务关联版本不存在");
        outbox.enqueueOnce("knowledge-version-registered|" + versionPublicId,
                new JdbcAiOutboxRepository.OutboxDraft(
                        "KNOWLEDGE_VERSION", versionPublicId, "KnowledgeVersionRegistered.v1",
                        "{\"schemaVersion\":\"knowledge-version-registered.v1\",\"jobId\":\""
                                + job.publicId() + "\"}",
                        ActorDescriptor.service("knowledge-ingestion", job.initiatedByUserId(),
                                job.initiatedByUserId()), job.availableAt()));
        return job;
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
