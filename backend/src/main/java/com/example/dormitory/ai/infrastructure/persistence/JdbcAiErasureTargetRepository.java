package com.example.dormitory.ai.infrastructure.persistence;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;

@Repository
public class JdbcAiErasureTargetRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcAiErasureTargetRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long register(long erasureJobId, String targetKind, String targetRefHash, String providerCode) {
        validateTarget(erasureJobId, targetKind, targetRefHash, providerCode);
        try {
            jdbcTemplate.update(
                    "INSERT INTO ai_erasure_target "
                            + "(erasure_job_id, target_kind, target_ref_hash, provider_code, state, "
                            + "attempts, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, 'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    erasureJobId,
                    targetKind,
                    targetRefHash,
                    providerCode);
            Long id = jdbcTemplate.queryForObject(
                    "SELECT id FROM ai_erasure_target WHERE erasure_job_id = ? "
                            + "AND target_kind = ? AND target_ref_hash = ?",
                    Long.class,
                    erasureJobId,
                    targetKind,
                    targetRefHash);
            if (id == null) throw new IllegalStateException("无法取得 erasure target ID");
            return id;
        } catch (DuplicateKeyException duplicate) {
            ExistingTarget existing = jdbcTemplate.queryForObject(
                    "SELECT id, provider_code FROM ai_erasure_target WHERE erasure_job_id = ? "
                            + "AND target_kind = ? AND target_ref_hash = ?",
                    (resultSet, rowNum) -> new ExistingTarget(
                            resultSet.getLong("id"),
                            resultSet.getString("provider_code")),
                    erasureJobId,
                    targetKind,
                    targetRefHash);
            if (existing == null || !Objects.equals(existing.providerCode(), providerCode)) {
                throw new IllegalStateException("相同清除 target hash 对应了不同 provider");
            }
            return existing.id();
        }
    }

    public boolean markVerified(long targetId, String proofRefHash, Instant checkedAt) {
        validateHash(proofRefHash, "proofRefHash");
        if (targetId < 1 || checkedAt == null) throw new IllegalArgumentException("清除证明参数不合法");
        return jdbcTemplate.update(
                "UPDATE ai_erasure_target SET state = 'VERIFIED', proof_ref_hash = ?, "
                        + "last_checked_at = ?, attempts = attempts + 1, last_error_code = NULL, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE id = ? "
                        + "AND state IN ('PENDING', 'PROCESSING', 'RETRYABLE_FAILED')",
                proofRefHash,
                Timestamp.from(checkedAt),
                targetId) == 1;
    }

    private void validateTarget(long jobId, String kind, String targetHash, String providerCode) {
        if (jobId < 1 || kind == null || !kind.matches("[A-Z0-9_]{1,32}")
                || (providerCode != null && !providerCode.matches("[a-z0-9._-]{1,64}"))) {
            throw new IllegalArgumentException("清除 target 不合法");
        }
        validateHash(targetHash, "targetRefHash");
    }

    private void validateHash(String value, String field) {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException(field + " 必须是 64 位十六进制哈希");
        }
    }

    private record ExistingTarget(long id, String providerCode) {
    }
}
