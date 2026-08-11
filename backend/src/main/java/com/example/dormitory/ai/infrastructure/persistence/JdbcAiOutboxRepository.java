package com.example.dormitory.ai.infrastructure.persistence;

import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.ActorKind;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Repository
public class JdbcAiOutboxRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcAiOutboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String enqueue(OutboxDraft draft) {
        String publicId = UUID.randomUUID().toString();
        insert(publicId, draft);
        return publicId;
    }

    /**
     * 以生产事务给出的稳定业务键创建事件。相同键的事务重放返回同一事件，
     * 但若载荷或 actor 发生变化则 fail closed，避免把幂等键误当成覆盖键。
     */
    public String enqueueOnce(String producerKey, OutboxDraft draft) {
        if (producerKey == null || producerKey.isBlank() || producerKey.length() > 512) {
            throw new IllegalArgumentException("outbox producer key 不合法");
        }
        String publicId = UUID.nameUUIDFromBytes(
                ("dormitory-ai-outbox-v1|" + producerKey).getBytes(StandardCharsets.UTF_8)).toString();
        try {
            insert(publicId, draft);
            return publicId;
        } catch (DuplicateKeyException duplicate) {
            List<ExistingEvent> existing = jdbcTemplate.query(
                    "SELECT aggregate_type,aggregate_public_id,event_type,payload_redacted,actor_kind,"
                            + "service_principal_code,initiated_by_user_id,effective_subject_user_id "
                            + "FROM ai_outbox_event WHERE public_id=?",
                    (rs, row) -> new ExistingEvent(rs.getString("aggregate_type"),
                            rs.getString("aggregate_public_id"), rs.getString("event_type"),
                            rs.getString("payload_redacted"), rs.getString("actor_kind"),
                            rs.getString("service_principal_code"), nullableLong(rs, "initiated_by_user_id"),
                            nullableLong(rs, "effective_subject_user_id")), publicId);
            if (existing.size() != 1 || !existing.getFirst().matches(draft)) {
                throw new IllegalStateException("outbox producer key 已绑定不同事件", duplicate);
            }
            return publicId;
        }
    }

    private void insert(String publicId, OutboxDraft draft) {
        jdbcTemplate.update(
                "INSERT INTO ai_outbox_event "
                        + "(public_id, aggregate_type, aggregate_public_id, event_type, payload_redacted, "
                        + "state, version, attempts, available_at, actor_kind, service_principal_code, "
                        + "initiated_by_user_id, effective_subject_user_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'PENDING', 0, 0, ?, ?, ?, ?, ?, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                publicId,
                draft.aggregateType(),
                draft.aggregatePublicId(),
                draft.eventType(),
                draft.payloadRedacted(),
                Timestamp.from(draft.availableAt()),
                draft.actor().kind().name(),
                draft.actor().servicePrincipalCode(),
                draft.actor().initiatedByUserId(),
                draft.actor().effectiveSubjectUserId());
    }

    public Optional<ClaimedOutboxEvent> claimNext(String workerId, Instant now) {
        return claimNextInternal(workerId, now, Set.of());
    }

    /** 只领取当前 worker 明确支持的事件，避免与专用消费者争抢其他事件。 */
    public Optional<ClaimedOutboxEvent> claimNext(String workerId, Instant now, Set<String> supportedEventTypes) {
        if (supportedEventTypes == null || supportedEventTypes.isEmpty()
                || supportedEventTypes.stream().anyMatch(value -> value == null
                || !value.matches("[A-Za-z0-9_.-]{1,64}"))) {
            throw new IllegalArgumentException("outbox 支持事件集合不合法");
        }
        return claimNextInternal(workerId, now, Set.copyOf(supportedEventTypes));
    }

    private Optional<ClaimedOutboxEvent> claimNextInternal(
            String workerId, Instant now, Set<String> supportedEventTypes) {
        if (workerId == null || workerId.isBlank() || workerId.length() > 128 || now == null) {
            throw new IllegalArgumentException("outbox worker 参数不合法");
        }
        List<String> eventTypes = supportedEventTypes.stream().sorted().toList();
        String eventFilter = eventTypes.isEmpty() ? "" : " AND event_type IN ("
                + String.join(",", java.util.Collections.nCopies(eventTypes.size(), "?")) + ")";
        for (int retry = 0; retry < 8; retry++) {
            List<Object> parameters = new ArrayList<>();
            parameters.add(Timestamp.from(now));
            parameters.addAll(eventTypes);
            List<Candidate> candidates = jdbcTemplate.query(
                    "SELECT id, version FROM ai_outbox_event "
                            + "WHERE state IN ('PENDING', 'RETRYABLE_FAILED') AND available_at <= ? "
                            + eventFilter + " ORDER BY available_at, id LIMIT 1",
                    (resultSet, rowNum) -> new Candidate(
                            resultSet.getLong("id"),
                            resultSet.getLong("version")),
                    parameters.toArray());
            if (candidates.isEmpty()) return Optional.empty();
            Candidate candidate = candidates.getFirst();
            int claimed = jdbcTemplate.update(
                    "UPDATE ai_outbox_event SET state = 'PROCESSING', locked_by = ?, locked_at = ?, "
                            + "version = version + 1, updated_at = CURRENT_TIMESTAMP "
                            + "WHERE id = ? AND version = ? "
                            + "AND state IN ('PENDING', 'RETRYABLE_FAILED') AND available_at <= ?",
                    workerId,
                    Timestamp.from(now),
                    candidate.id(),
                    candidate.version(),
                    Timestamp.from(now));
            if (claimed == 1) {
                return Optional.ofNullable(jdbcTemplate.queryForObject(
                        "SELECT public_id, aggregate_type, aggregate_public_id, event_type, "
                                + "payload_redacted, locked_by, attempts, actor_kind, service_principal_code, "
                                + "initiated_by_user_id, effective_subject_user_id "
                                + "FROM ai_outbox_event WHERE id = ?",
                        (resultSet, rowNum) -> new ClaimedOutboxEvent(
                                resultSet.getString("public_id"),
                                resultSet.getString("aggregate_type"),
                                resultSet.getString("aggregate_public_id"),
                                resultSet.getString("event_type"),
                                resultSet.getString("payload_redacted"),
                                resultSet.getString("locked_by"),
                                resultSet.getInt("attempts"),
                                resultSet.getString("actor_kind"),
                                resultSet.getString("service_principal_code"),
                                nullableLong(resultSet, "initiated_by_user_id"),
                                nullableLong(resultSet, "effective_subject_user_id")),
                        candidate.id()));
            }
        }
        return Optional.empty();
    }

    public Instant markRetryableFailure(
            String publicId,
            String workerId,
            String errorCode,
            Instant failedAt,
            Duration baseDelay) {
        FailureResult result = markFailure(publicId, workerId, errorCode, failedAt, baseDelay,
                100);
        return result.retryAt();
    }

    /**
     * 仅允许当前 PROCESSING lease 的持有者刷新租约时间。条件更新同时约束事件、worker 和前态，
     * 因此旧 worker、已恢复的事件及任何终态都不能被续租重新激活。
     */
    public boolean renewLease(String publicId, String workerId, Instant renewedAt) {
        validateTransitionIdentity(publicId, workerId);
        if (renewedAt == null) throw new IllegalArgumentException("outbox lease 续租时间不能为空");
        Instant normalized = renewedAt.truncatedTo(ChronoUnit.MICROS);
        Timestamp heartbeat = Timestamp.from(normalized);
        return jdbcTemplate.update(
                "UPDATE ai_outbox_event SET locked_at = ?, version = version + 1, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE public_id = ? "
                        + "AND state = 'PROCESSING' AND locked_by = ? "
                        + "AND locked_at IS NOT NULL AND locked_at <= ?",
                heartbeat,
                publicId,
                workerId,
                heartbeat) == 1;
    }

    public FailureResult markFailure(
            String publicId,
            String workerId,
            String errorCode,
            Instant failedAt,
            Duration baseDelay,
            int maxAttempts) {
        validateTransitionIdentity(publicId, workerId);
        if (errorCode == null || !errorCode.matches("[A-Z0-9_]{1,64}")
                || failedAt == null || baseDelay == null || baseDelay.isNegative() || baseDelay.isZero()
                || maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException("outbox 失败参数不合法");
        }
        Integer attempts = jdbcTemplate.queryForObject(
                "SELECT attempts FROM ai_outbox_event WHERE public_id = ? "
                        + "AND state = 'PROCESSING' AND locked_by = ?",
                Integer.class, publicId, workerId);
        if (attempts == null) throw new IllegalStateException("outbox event 不可标记失败");
        int nextAttempt = attempts + 1;
        boolean dead = nextAttempt >= maxAttempts;
        Instant retryAt = dead ? null : failedAt.truncatedTo(ChronoUnit.MICROS)
                .plus(baseDelay.multipliedBy(1L << Math.min(nextAttempt - 1, 10)));
        int updated = jdbcTemplate.update(
                "UPDATE ai_outbox_event SET state = ?, attempts = ?, available_at = ?, "
                        + "locked_by = NULL, locked_at = NULL, last_error_code = ?, version = version + 1, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE public_id = ? "
                        + "AND state = 'PROCESSING' AND locked_by = ?",
                dead ? "DEAD" : "RETRYABLE_FAILED", nextAttempt,
                Timestamp.from(dead ? failedAt : retryAt), errorCode, publicId, workerId);
        if (updated != 1) throw new IllegalStateException("outbox event 失败状态 CAS 未命中");
        return new FailureResult(dead, nextAttempt, retryAt);
    }

    /** 把进程崩溃遗留的 PROCESSING lease 安全交还队列。 */
    public int recoverExpiredLeases(Instant now, Duration leaseTimeout) {
        return recoverExpiredLeases(now, leaseTimeout, 100).recoveredCount();
    }

    public LeaseRecoveryResult recoverExpiredLeases(
            Instant now, Duration leaseTimeout, int maxAttempts) {
        return recoverExpiredLeases(now, leaseTimeout, maxAttempts, Set.of());
    }

    public LeaseRecoveryResult recoverExpiredLeases(
            Instant now, Duration leaseTimeout, int maxAttempts, Set<String> supportedEventTypes) {
        if (now == null || leaseTimeout == null || leaseTimeout.isNegative() || leaseTimeout.isZero()) {
            throw new IllegalArgumentException("outbox lease 恢复参数不合法");
        }
        if (maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException("outbox lease 最大尝试次数不合法");
        }
        if (supportedEventTypes == null || supportedEventTypes.stream().anyMatch(value -> value == null
                || !value.matches("[A-Za-z0-9_.-]{1,64}"))) {
            throw new IllegalArgumentException("outbox lease 事件集合不合法");
        }
        Instant expiredBefore = now.minus(leaseTimeout).truncatedTo(ChronoUnit.MICROS);
        List<String> eventTypes = supportedEventTypes.stream().sorted().toList();
        String eventFilter = eventTypes.isEmpty() ? "" : " AND event_type IN ("
                + String.join(",", java.util.Collections.nCopies(eventTypes.size(), "?")) + ")";
        List<Object> parameters = new ArrayList<>();
        parameters.add(Timestamp.from(expiredBefore));
        parameters.addAll(eventTypes);
        List<ClaimedOutboxEvent> expired = jdbcTemplate.query(
                "SELECT public_id,aggregate_type,aggregate_public_id,event_type,payload_redacted,locked_by,"
                        + "attempts,actor_kind,service_principal_code,initiated_by_user_id,effective_subject_user_id "
                        + "FROM ai_outbox_event WHERE state='PROCESSING' AND locked_at IS NOT NULL "
                        + "AND locked_at <= ?" + eventFilter + " ORDER BY id",
                (rs, row) -> new ClaimedOutboxEvent(rs.getString("public_id"),
                        rs.getString("aggregate_type"), rs.getString("aggregate_public_id"),
                        rs.getString("event_type"), rs.getString("payload_redacted"),
                        rs.getString("locked_by"), rs.getInt("attempts"), rs.getString("actor_kind"),
                        rs.getString("service_principal_code"), nullableLong(rs, "initiated_by_user_id"),
                        nullableLong(rs, "effective_subject_user_id")), parameters.toArray());
        int recovered = 0;
        List<ClaimedOutboxEvent> dead = new ArrayList<>();
        for (ClaimedOutboxEvent event : expired) {
            int nextAttempt = event.attempts() + 1;
            boolean terminal = nextAttempt >= maxAttempts;
            int changed = jdbcTemplate.update("UPDATE ai_outbox_event SET state=?,attempts=?,available_at=?,"
                            + "locked_by=NULL,locked_at=NULL,last_error_code='WORKER_LEASE_EXPIRED',"
                            + "version=version+1,updated_at=CURRENT_TIMESTAMP WHERE public_id=? "
                            + "AND state='PROCESSING' AND locked_by=? AND locked_at <= ?",
                    terminal ? "DEAD" : "RETRYABLE_FAILED", nextAttempt, Timestamp.from(now),
                    event.publicId(), event.lockedBy(), Timestamp.from(expiredBefore));
            if (changed == 1) {
                recovered++;
                if (terminal) dead.add(event);
            }
        }
        return new LeaseRecoveryResult(recovered, List.copyOf(dead));
    }

    public boolean markSucceeded(String publicId, String workerId, Instant publishedAt) {
        validateTransitionIdentity(publicId, workerId);
        if (publishedAt == null) throw new IllegalArgumentException("publishedAt 不能为空");
        return jdbcTemplate.update(
                "UPDATE ai_outbox_event SET state = 'SUCCEEDED', published_at = ?, "
                        + "locked_by = NULL, locked_at = NULL, version = version + 1, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE public_id = ? "
                        + "AND state = 'PROCESSING' AND locked_by = ?",
                Timestamp.from(publishedAt),
                publicId,
                workerId) == 1;
    }

    /** Kill Switch/临时治理门关闭时延后，不消耗故障重试预算。 */
    public boolean defer(String publicId, String workerId, String reasonCode, Instant availableAt) {
        validateTransitionIdentity(publicId, workerId);
        if (reasonCode == null || !reasonCode.matches("[A-Z0-9_]{1,64}") || availableAt == null) {
            throw new IllegalArgumentException("outbox defer 参数不合法");
        }
        return jdbcTemplate.update("UPDATE ai_outbox_event SET state='RETRYABLE_FAILED',available_at=?,"
                        + "locked_by=NULL,locked_at=NULL,last_error_code=?,version=version+1,"
                        + "updated_at=CURRENT_TIMESTAMP WHERE public_id=? AND state='PROCESSING' AND locked_by=?",
                Timestamp.from(availableAt), reasonCode, publicId, workerId) == 1;
    }

    private void validateTransitionIdentity(String publicId, String workerId) {
        try {
            UUID.fromString(publicId);
        } catch (Exception exception) {
            throw new IllegalArgumentException("outbox public ID 必须是 UUID", exception);
        }
        if (workerId == null || workerId.isBlank() || workerId.length() > 128) {
            throw new IllegalArgumentException("workerId 不合法");
        }
    }

    public record OutboxDraft(
            String aggregateType,
            String aggregatePublicId,
            String eventType,
            String payloadRedacted,
            ActorDescriptor actor,
            Instant availableAt) {

        public OutboxDraft {
            if (aggregateType == null || !aggregateType.matches("[A-Z0-9_]{1,64}")
                    || eventType == null || !eventType.matches("[A-Za-z0-9_.-]{1,64}")
                    || payloadRedacted == null || payloadRedacted.length() > 65_536
                    || actor == null || actor.kind() == ActorKind.USER || actor.kind() == ActorKind.MODEL
                    || availableAt == null) {
                throw new IllegalArgumentException("outbox draft 不合法");
            }
            try {
                UUID.fromString(aggregatePublicId);
            } catch (Exception exception) {
                throw new IllegalArgumentException("aggregatePublicId 必须是 UUID", exception);
            }
            availableAt = availableAt.truncatedTo(ChronoUnit.MICROS);
        }
    }

    public record ClaimedOutboxEvent(
            String publicId,
            String aggregateType,
            String aggregatePublicId,
            String eventType,
            String payloadRedacted,
            String lockedBy,
            int attempts,
            String actorKind,
            String servicePrincipalCode,
            Long initiatedByUserId,
            Long effectiveSubjectUserId) {
    }

    public record FailureResult(boolean dead, int attempts, Instant retryAt) { }

    public record LeaseRecoveryResult(int recoveredCount, List<ClaimedOutboxEvent> deadEvents) {
        public LeaseRecoveryResult {
            deadEvents = List.copyOf(deadEvents);
        }
    }

    private Long nullableLong(java.sql.ResultSet resultSet, String column) throws java.sql.SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private record ExistingEvent(
            String aggregateType,
            String aggregatePublicId,
            String eventType,
            String payload,
            String actorKind,
            String servicePrincipalCode,
            Long initiatedByUserId,
            Long effectiveSubjectUserId) {
        private boolean matches(OutboxDraft draft) {
            return aggregateType.equals(draft.aggregateType())
                    && aggregatePublicId.equals(draft.aggregatePublicId())
                    && eventType.equals(draft.eventType())
                    && payload.equals(draft.payloadRedacted())
                    && actorKind.equals(draft.actor().kind().name())
                    && java.util.Objects.equals(servicePrincipalCode, draft.actor().servicePrincipalCode())
                    && java.util.Objects.equals(initiatedByUserId, draft.actor().initiatedByUserId())
                    && java.util.Objects.equals(effectiveSubjectUserId, draft.actor().effectiveSubjectUserId());
        }
    }

    private record Candidate(long id, long version) {
    }
}
