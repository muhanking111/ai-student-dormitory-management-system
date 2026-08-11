package com.example.dormitory.ai.infrastructure.persistence;

import com.example.dormitory.ai.application.control.IdempotencyPayloadMismatchException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.Optional;

@Repository
public class JdbcAiIdempotencyRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcAiIdempotencyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Reservation> inspect(Scope scope, String requestHash) {
        validateHash(requestHash, "requestHash");
        Optional<Reservation> existing = jdbcTemplate.query(
                        "SELECT id, request_hash, state FROM ai_idempotency_record "
                                + "WHERE actor_user_id = ? AND route_code = ? "
                                + "AND aggregate_public_id = ? AND idempotency_key = ?",
                        (resultSet, rowNum) -> new Reservation(
                                resultSet.getLong("id"),
                                ReservationStatus.REPLAY,
                                resultSet.getString("request_hash"),
                                resultSet.getString("state")),
                        scope.actorUserId(),
                        scope.routeCode(),
                        scope.aggregatePublicId(),
                        scope.idempotencyKey())
                .stream()
                .findFirst();
        existing.ifPresent(reservation -> requireMatchingRequestHash(requestHash, reservation));
        return existing;
    }

    public Reservation reserve(Scope scope, String requestHash, Instant expiresAt) {
        validateHash(requestHash, "requestHash");
        if (expiresAt == null) throw new IllegalArgumentException("幂等记录过期时间不能为空");
        try {
            jdbcTemplate.update(
                    "INSERT INTO ai_idempotency_record "
                            + "(actor_user_id, route_code, aggregate_public_id, idempotency_key, "
                            + "request_hash, state, expires_at, version, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, 'PENDING', ?, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    scope.actorUserId(),
                    scope.routeCode(),
                    scope.aggregatePublicId(),
                    scope.idempotencyKey(),
                    requestHash,
                    Timestamp.from(expiresAt));
            Long recordId = jdbcTemplate.queryForObject(
                    "SELECT id FROM ai_idempotency_record WHERE actor_user_id = ? AND route_code = ? "
                            + "AND aggregate_public_id = ? AND idempotency_key = ?",
                    Long.class,
                    scope.actorUserId(),
                    scope.routeCode(),
                    scope.aggregatePublicId(),
                    scope.idempotencyKey());
            if (recordId == null) throw new IllegalStateException("无法取得幂等记录 ID");
            return new Reservation(recordId, ReservationStatus.CREATED, requestHash, "PENDING");
        } catch (DuplicateKeyException duplicate) {
            return inspect(scope, requestHash)
                    .orElseThrow(() -> new IllegalStateException("幂等记录冲突后不可见", duplicate));
        }
    }

    public void complete(long recordId, int responseStatus, String resourcePublicId) {
        if (recordId < 1 || responseStatus < 200 || responseStatus > 299 || resourcePublicId == null) {
            throw new IllegalArgumentException("幂等完成参数不合法");
        }
        int updated = jdbcTemplate.update("UPDATE ai_idempotency_record SET state='COMPLETED',"
                        + "response_status=?,response_resource_public_id=?,version=version+1,"
                        + "updated_at=CURRENT_TIMESTAMP WHERE id=? AND state='PENDING'",
                responseStatus, resourcePublicId, recordId);
        if (updated != 1) throw new IllegalStateException("幂等记录无法完成");
    }

    public Optional<CompletedResponse> completedResponse(long recordId) {
        if (recordId < 1) return Optional.empty();
        return jdbcTemplate.query("SELECT response_status,response_resource_public_id FROM ai_idempotency_record "
                        + "WHERE id=? AND state='COMPLETED'",
                (rs, row) -> new CompletedResponse(rs.getInt(1), rs.getString(2)), recordId)
                .stream().findFirst();
    }

    public void releasePending(long recordId) {
        if (recordId > 0) jdbcTemplate.update("DELETE FROM ai_idempotency_record WHERE id=? AND state='PENDING'", recordId);
    }

    private static void validateHash(String value, String field) {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException(field + " 必须是 64 位十六进制哈希");
        }
    }

    private static void requireMatchingRequestHash(String requestHash, Reservation existing) {
        if (!Objects.equals(requestHash, existing.requestHash())) {
            throw new IdempotencyPayloadMismatchException();
        }
    }

    public record Scope(
            long actorUserId,
            String routeCode,
            String aggregatePublicId,
            String idempotencyKey) {

        public Scope {
            if (actorUserId < 1 || routeCode == null || !routeCode.matches("[A-Z0-9_]{1,64}")
                    || idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
                throw new IllegalArgumentException("幂等范围不合法");
            }
            try {
                UUID.fromString(aggregatePublicId);
            } catch (Exception exception) {
                throw new IllegalArgumentException("aggregatePublicId 必须是 UUID", exception);
            }
        }
    }

    public record Reservation(
            long recordId,
            ReservationStatus status,
            String requestHash,
            String state) {
    }

    public enum ReservationStatus {
        CREATED,
        REPLAY
    }

    public record CompletedResponse(int status, String resourcePublicId) { }
}
