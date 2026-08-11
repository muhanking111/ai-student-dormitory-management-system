package com.example.dormitory.ai.infrastructure.persistence;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.approval.CanonicalJsonHasher;
import com.example.dormitory.ai.erasure.ErasureJobRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcErasureJobRepository implements ErasureJobRepository {

    private final JdbcTemplate jdbc;

    public JdbcErasureJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Job> findActive(long ownerUserId, String conversationId) {
        return jobs("SELECT * FROM ai_erasure_job WHERE requested_by_user_id=? AND scope_public_id=? "
                + "AND state IN ('PENDING','PROCESSING','RETRYABLE_FAILED','PARTIAL','NEEDS_REVIEW') "
                + "ORDER BY id DESC LIMIT 1", ownerUserId, conversationId).stream().findFirst();
    }

    @Override
    @Transactional
    public Job create(long ownerUserId, String conversationId, boolean hold, Instant now) {
        Long conversationDbId = jdbc.query(
                        "SELECT id FROM ai_conversation WHERE public_id=? AND owner_user_id=? FOR UPDATE",
                        (resultSet, row) -> resultSet.getLong(1), conversationId, ownerUserId)
                .stream().findFirst().orElseThrow(AiApiException::notFound);
        Integer activeRuns = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_run WHERE conversation_id=? "
                        + "AND state IN ('ACCEPTED','QUEUED','RUNNING','STREAMING')",
                Integer.class, conversationDbId);
        if (activeRuns != null && activeRuns > 0) {
            throw new AiApiException(HttpStatus.CONFLICT, "AI_CONVERSATION_HAS_ACTIVE_RUN",
                    "会话仍有运行中的任务，请先取消或等待任务结束", false);
        }
        jdbc.update("UPDATE ai_conversation SET status='ARCHIVED',updated_at=CURRENT_TIMESTAMP WHERE id=?",
                conversationDbId);

        String publicId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ai_erasure_job (public_id,request_type,scope_type,scope_public_id,"
                        + "requested_by_user_id,legal_basis_code,retention_hold,state,version,attempts,available_at,"
                        + "created_operator_user_id,updated_operator_user_id,created_at,updated_at) "
                        + "VALUES (?,'OWNER_DELETE','CONVERSATION',?,?,'OWNER_REQUEST',?,'PENDING',0,0,?,?,?,?,?)",
                publicId, conversationId, ownerUserId, hold, Timestamp.from(now), ownerUserId, ownerUserId,
                Timestamp.from(now), Timestamp.from(now));
        Long id = jdbc.queryForObject("SELECT id FROM ai_erasure_job WHERE public_id=?", Long.class, publicId);
        if (id == null) throw new IllegalStateException("清除任务未持久化");
        for (String kind : List.of("MYSQL_CONTENT", "RAW_OBJECT", "VECTOR", "CACHE", "PROVIDER")) {
            String refHash = CanonicalJsonHasher.sha256("erasure-target.v1|" + kind + "|" + conversationId);
            jdbc.update("INSERT INTO ai_erasure_target (erasure_job_id,target_kind,target_ref_hash,provider_code,"
                            + "state,attempts,created_at,updated_at) VALUES (?,?,?,'fake','PENDING',0,?,?)",
                    id, kind, refHash, Timestamp.from(now), Timestamp.from(now));
        }
        return findOwned(ownerUserId, publicId).orElseThrow();
    }

    @Override
    public Optional<Job> findOwned(long owner, String jobId) {
        return jobs("SELECT * FROM ai_erasure_job WHERE public_id=? AND requested_by_user_id=?", jobId, owner)
                .stream().findFirst();
    }

    @Override
    public Optional<Job> findByPublicId(String jobId) {
        return jobs("SELECT * FROM ai_erasure_job WHERE public_id=?", jobId).stream().findFirst();
    }

    @Override
    @Transactional
    public Optional<Job> claimNext(Instant now) {
        List<Job> rows = jobs("SELECT * FROM ai_erasure_job WHERE state IN ('PENDING','RETRYABLE_FAILED') "
                + "AND available_at<=? ORDER BY id LIMIT 1 FOR UPDATE", Timestamp.from(now));
        if (rows.isEmpty()) return Optional.empty();
        Job job = rows.getFirst();
        int changed = jdbc.update("UPDATE ai_erasure_job SET state='PROCESSING',version=version+1,"
                        + "attempts=attempts+1,updated_at=? WHERE id=? AND version=? "
                        + "AND state IN ('PENDING','RETRYABLE_FAILED')",
                Timestamp.from(now), job.databaseId(), job.version());
        if (changed != 1) return Optional.empty();
        return Optional.of(new Job(job.databaseId(), job.publicId(), job.conversationId(), job.ownerUserId(),
                job.retentionHold(), "PROCESSING", job.version() + 1, job.attempts() + 1));
    }

    @Override
    public List<Target> targets(long jobId) {
        return jdbc.query("SELECT id,target_kind,target_ref_hash,provider_code,state,attempts,last_error_code,"
                        + "proof_ref_hash "
                        + "FROM ai_erasure_target WHERE erasure_job_id=? ORDER BY id",
                (resultSet, row) -> new Target(resultSet.getLong(1), resultSet.getString(2),
                        resultSet.getString(3), resultSet.getString(4), resultSet.getString(5),
                        resultSet.getInt(6), resultSet.getString(7), resultSet.getString(8)), jobId);
    }

    @Override
    public void markTargetVerified(long id, String proof, Instant now) {
        updateTarget(id, "VERIFIED", proof, null, now);
    }

    @Override
    public void markTargetRetained(long id, Instant now) {
        updateTarget(id, "RETAINED", null, "LEGAL_HOLD", now);
    }

    @Override
    public void markTargetFailure(long id, String state, String error, Instant now) {
        updateTarget(id, state, null, error, now);
    }

    private void updateTarget(long id, String state, String proof, String error, Instant now) {
        jdbc.update("UPDATE ai_erasure_target SET state=?,proof_ref_hash=?,last_error_code=?,attempts=attempts+1,"
                        + "last_checked_at=?,updated_at=? WHERE id=? AND state NOT IN ('VERIFIED','RETAINED')",
                state, proof, error, Timestamp.from(now), Timestamp.from(now), id);
    }

    @Override
    public void finish(long id, int version, String state, String error, Instant nextAt, Instant now) {
        int changed = jdbc.update("UPDATE ai_erasure_job SET state=?,error_code=?,available_at=?,finished_at=?,"
                        + "version=version+1,updated_at=? WHERE id=? AND version=? AND state='PROCESSING'",
                state, error, Timestamp.from(nextAt), "SUCCEEDED".equals(state) ? Timestamp.from(now) : null,
                Timestamp.from(now), id, version);
        if (changed != 1) throw new IllegalStateException("清除 job CAS 冲突");
    }

    @Override
    public String redactConversationContent(long owner, String conversationId) {
        Long id = jdbc.query("SELECT id FROM ai_conversation WHERE public_id=? AND owner_user_id=?",
                        (resultSet, row) -> resultSet.getLong(1), conversationId, owner)
                .stream().findFirst().orElseThrow(AiApiException::notFound);
        jdbc.update("UPDATE ai_message SET content_redacted='[ERASED]',raw_object_key=NULL "
                + "WHERE conversation_id=?", id);
        jdbc.update("UPDATE ai_conversation SET title_redacted='[ERASED]',status='ARCHIVED',"
                + "updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
        Integer residualMessages = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_message WHERE conversation_id=? "
                        + "AND (content_redacted<>'[ERASED]' OR raw_object_key IS NOT NULL)",
                Integer.class, id);
        List<ConversationErasureState> conversations = jdbc.query(
                "SELECT title_redacted,status FROM ai_conversation WHERE id=? AND owner_user_id=?",
                (resultSet, row) -> new ConversationErasureState(
                        resultSet.getString("title_redacted"), resultSet.getString("status")),
                id, owner);
        if (residualMessages == null || residualMessages != 0 || conversations.size() != 1
                || !"[ERASED]".equals(conversations.getFirst().title())
                || !"ARCHIVED".equals(conversations.getFirst().status())) {
            return null;
        }
        Integer messageCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_message WHERE conversation_id=?", Integer.class, id);
        return CanonicalJsonHasher.sha256("mysql-erasure-proof.v2|" + conversationId
                + "|message-count=" + (messageCount == null ? 0 : messageCount)
                + "|residual=0|title-erased=true|status=ARCHIVED");
    }

    private List<Job> jobs(String sql, Object... arguments) {
        return jdbc.query(sql, (resultSet, row) -> new Job(
                resultSet.getLong("id"), resultSet.getString("public_id"),
                resultSet.getString("scope_public_id"), resultSet.getLong("requested_by_user_id"),
                resultSet.getBoolean("retention_hold"), resultSet.getString("state"),
                resultSet.getInt("version"), resultSet.getInt("attempts")), arguments);
    }

    private record ConversationErasureState(String title, String status) { }
}
