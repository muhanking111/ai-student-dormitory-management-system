package com.example.dormitory.ai.infrastructure.persistence;

import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.ActorKind;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

public class JdbcAiAuditChainRepository {

    private static final String INTEGRITY_ALGORITHM = "HMAC-SHA256";
    private static final String CANONICALIZATION_VERSION = "v1";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final AuditKeyResolver keyResolver;

    public JdbcAiAuditChainRepository(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            AuditKeyResolver keyResolver) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.keyResolver = keyResolver;
    }

    public AuditEventRecord append(AuditAppendCommand originalCommand) {
        AuditAppendCommand command = originalCommand.normalized();
        byte[] key = requireKey(command.integrityKeyVersion());
        return transactionTemplate.execute(status -> {
            ensureHead(command);
            HeadRow head = jdbcTemplate.queryForObject(
                    "SELECT id, last_sequence_no, last_event_hash, integrity_key_version, version "
                            + "FROM ai_audit_chain_head WHERE chain_scope = ? "
                            + "AND aggregate_type = ? AND aggregate_public_id = ? FOR UPDATE",
                    (resultSet, rowNum) -> new HeadRow(
                            resultSet.getLong("id"),
                            resultSet.getLong("last_sequence_no"),
                            resultSet.getString("last_event_hash"),
                            resultSet.getInt("integrity_key_version"),
                            resultSet.getLong("version")),
                    command.chainScope(),
                    command.aggregateType(),
                    command.aggregatePublicId());
            if (head == null) throw new IllegalStateException("审计 chain head 不存在");
            requireConsistentTail(command, head);

            long sequence = head.lastSequenceNo() + 1;
            String publicId = UUID.randomUUID().toString();
            String canonical = canonical(
                    publicId,
                    sequence,
                    head.lastEventHash(),
                    command.chainScope(),
                    command.aggregateType(),
                    command.aggregatePublicId(),
                    command.eventType(),
                    command.actor(),
                    command.sessionFingerprintHash(),
                    command.sessionFingerprintKeyVersion(),
                    command.permissionDigest(),
                    command.payloadRedactedHash(),
                    command.correlationId(),
                    command.occurredAt(),
                    command.integrityKeyVersion());
            String eventHash = hmacHex(key, canonical);

            jdbcTemplate.update(
                    "INSERT INTO ai_audit_event "
                            + "(public_id, chain_scope, aggregate_type, aggregate_public_id, sequence_no, "
                            + "event_type, actor_kind, actor_user_id, service_principal_code, "
                            + "initiated_by_user_id, effective_subject_user_id, session_fingerprint_hash, "
                            + "session_fingerprint_key_version, permission_digest, payload_redacted_hash, "
                            + "previous_event_hash, event_hash, integrity_alg, integrity_key_version, "
                            + "canonicalization_version, correlation_id, occurred_at, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                            + "CURRENT_TIMESTAMP)",
                    publicId,
                    command.chainScope(),
                    command.aggregateType(),
                    command.aggregatePublicId(),
                    sequence,
                    command.eventType(),
                    command.actor().kind().name(),
                    command.actor().actorUserId(),
                    command.actor().servicePrincipalCode(),
                    command.actor().initiatedByUserId(),
                    command.actor().effectiveSubjectUserId(),
                    command.sessionFingerprintHash(),
                    command.sessionFingerprintKeyVersion(),
                    command.permissionDigest(),
                    command.payloadRedactedHash(),
                    head.lastEventHash(),
                    eventHash,
                    INTEGRITY_ALGORITHM,
                    command.integrityKeyVersion(),
                    CANONICALIZATION_VERSION,
                    command.correlationId(),
                    Timestamp.from(command.occurredAt()));
            int updated = jdbcTemplate.update(
                    "UPDATE ai_audit_chain_head SET last_sequence_no = ?, last_event_hash = ?, "
                            + "integrity_key_version = ?, version = version + 1, "
                            + "updated_at = CURRENT_TIMESTAMP WHERE id = ? AND version = ?",
                    sequence,
                    eventHash,
                    command.integrityKeyVersion(),
                    head.id(),
                    head.version());
            if (updated != 1) throw new IllegalStateException("审计 chain head CAS 失败");
            return new AuditEventRecord(
                    publicId,
                    sequence,
                    head.lastEventHash(),
                    eventHash,
                    INTEGRITY_ALGORITHM,
                    command.integrityKeyVersion(),
                    CANONICALIZATION_VERSION);
        });
    }

    private void requireConsistentTail(AuditAppendCommand command, HeadRow head) {
        List<TailRow> tails = jdbcTemplate.query(
                "SELECT sequence_no, event_hash, integrity_key_version FROM ai_audit_event "
                        + "WHERE chain_scope = ? AND aggregate_type = ? AND aggregate_public_id = ? "
                        + "ORDER BY sequence_no DESC LIMIT 1 FOR UPDATE",
                (resultSet, rowNum) -> new TailRow(
                        resultSet.getLong("sequence_no"),
                        resultSet.getString("event_hash"),
                        resultSet.getInt("integrity_key_version")),
                command.chainScope(),
                command.aggregateType(),
                command.aggregatePublicId());
        if (head.lastSequenceNo() == 0 && head.lastEventHash() == null && tails.isEmpty()) {
            return;
        }
        if (head.lastSequenceNo() < 1 || head.lastEventHash() == null || tails.size() != 1) {
            throw new IllegalStateException("审计 chain tail 与 head 不一致");
        }
        TailRow tail = tails.get(0);
        if (tail.sequenceNo() != head.lastSequenceNo()
                || tail.integrityKeyVersion() != head.integrityKeyVersion()
                || !constantTimeEquals(tail.eventHash(), head.lastEventHash())) {
            throw new IllegalStateException("审计 chain tail 与 head 不一致");
        }
        if (!verify(command.chainScope(), command.aggregateType(), command.aggregatePublicId())) {
            throw new IllegalStateException("审计 chain 历史完整性校验失败");
        }
    }

    public boolean verifyAll() {
        List<ChainIdentity> chains = jdbcTemplate.query(
                "SELECT chain_scope, aggregate_type, aggregate_public_id FROM ("
                        + "SELECT chain_scope, aggregate_type, aggregate_public_id FROM ai_audit_chain_head "
                        + "UNION SELECT chain_scope, aggregate_type, aggregate_public_id FROM ai_audit_event"
                        + ") persisted_chains ORDER BY chain_scope, aggregate_type, aggregate_public_id",
                (resultSet, rowNum) -> new ChainIdentity(
                        resultSet.getString("chain_scope"),
                        resultSet.getString("aggregate_type"),
                        resultSet.getString("aggregate_public_id")));
        for (ChainIdentity chain : chains) {
            if (!verify(chain.chainScope(), chain.aggregateType(), chain.aggregatePublicId())) {
                return false;
            }
        }
        return true;
    }

    public boolean verify(String chainScope, String aggregateType, String aggregatePublicId) {
        List<EventRow> events = jdbcTemplate.query(
                "SELECT public_id, sequence_no, previous_event_hash, event_hash, event_type, actor_kind, "
                        + "actor_user_id, service_principal_code, initiated_by_user_id, effective_subject_user_id, "
                        + "session_fingerprint_hash, session_fingerprint_key_version, permission_digest, "
                        + "payload_redacted_hash, integrity_alg, integrity_key_version, canonicalization_version, "
                        + "correlation_id, occurred_at FROM ai_audit_event WHERE chain_scope = ? "
                        + "AND aggregate_type = ? AND aggregate_public_id = ? ORDER BY sequence_no",
                (resultSet, rowNum) -> new EventRow(
                        resultSet.getString("public_id"),
                        resultSet.getLong("sequence_no"),
                        resultSet.getString("previous_event_hash"),
                        resultSet.getString("event_hash"),
                        resultSet.getString("event_type"),
                        actorFrom(resultSet.getString("actor_kind"),
                                nullableLong(resultSet, "actor_user_id"),
                                resultSet.getString("service_principal_code"),
                                nullableLong(resultSet, "initiated_by_user_id"),
                                nullableLong(resultSet, "effective_subject_user_id")),
                        resultSet.getString("session_fingerprint_hash"),
                        resultSet.getInt("session_fingerprint_key_version"),
                        resultSet.getString("permission_digest"),
                        resultSet.getString("payload_redacted_hash"),
                        resultSet.getString("integrity_alg"),
                        resultSet.getInt("integrity_key_version"),
                        resultSet.getString("canonicalization_version"),
                        resultSet.getString("correlation_id"),
                        resultSet.getTimestamp("occurred_at").toInstant()),
                chainScope,
                aggregateType,
                aggregatePublicId);
        String previous = null;
        long expectedSequence = 1;
        for (EventRow event : events) {
            if (event.sequenceNo() != expectedSequence
                    || !java.util.Objects.equals(previous, event.previousEventHash())
                    || !INTEGRITY_ALGORITHM.equals(event.integrityAlgorithm())
                    || !CANONICALIZATION_VERSION.equals(event.canonicalizationVersion())) {
                return false;
            }
            String canonical = canonical(
                    event.publicId(),
                    event.sequenceNo(),
                    event.previousEventHash(),
                    chainScope,
                    aggregateType,
                    aggregatePublicId,
                    event.eventType(),
                    event.actor(),
                    event.sessionFingerprintHash(),
                    event.sessionFingerprintKeyVersion(),
                    event.permissionDigest(),
                    event.payloadRedactedHash(),
                    event.correlationId(),
                    event.occurredAt(),
                    event.integrityKeyVersion());
            if (!constantTimeEquals(event.eventHash(),
                    hmacHex(requireKey(event.integrityKeyVersion()), canonical))) {
                return false;
            }
            previous = event.eventHash();
            expectedSequence++;
        }
        if (events.isEmpty()) return false;
        List<VerifyHead> heads = jdbcTemplate.query(
                "SELECT last_sequence_no, last_event_hash, integrity_key_version "
                        + "FROM ai_audit_chain_head WHERE chain_scope = ? "
                        + "AND aggregate_type = ? AND aggregate_public_id = ?",
                (resultSet, rowNum) -> new VerifyHead(
                        resultSet.getLong("last_sequence_no"),
                        resultSet.getString("last_event_hash"),
                        resultSet.getInt("integrity_key_version")),
                chainScope,
                aggregateType,
                aggregatePublicId);
        EventRow tail = events.get(events.size() - 1);
        return heads.size() == 1
                && heads.get(0).lastSequenceNo() == tail.sequenceNo()
                && heads.get(0).integrityKeyVersion() == tail.integrityKeyVersion()
                && constantTimeEquals(heads.get(0).lastEventHash(), tail.eventHash());
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) return left == null && right == null;
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    private void ensureHead(AuditAppendCommand command) {
        jdbcTemplate.update(
                "INSERT INTO ai_audit_chain_head "
                        + "(chain_scope, aggregate_type, aggregate_public_id, last_sequence_no, "
                        + "last_event_hash, integrity_key_version, version, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 0, NULL, ?, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) "
                        + "ON DUPLICATE KEY UPDATE aggregate_public_id = ?",
                command.chainScope(),
                command.aggregateType(),
                command.aggregatePublicId(),
                command.integrityKeyVersion(),
                command.aggregatePublicId());
    }

    private String canonical(
            String publicId,
            long sequence,
            String previousHash,
            String chainScope,
            String aggregateType,
            String aggregatePublicId,
            String eventType,
            ActorDescriptor actor,
            String sessionFingerprintHash,
            int sessionFingerprintKeyVersion,
            String permissionDigest,
            String payloadHash,
            String correlationId,
            Instant occurredAt,
            int integrityKeyVersion) {
        StringBuilder value = new StringBuilder();
        append(value, CANONICALIZATION_VERSION);
        append(value, publicId);
        append(value, Long.toString(sequence));
        append(value, previousHash);
        append(value, chainScope);
        append(value, aggregateType);
        append(value, aggregatePublicId);
        append(value, eventType);
        append(value, actor.kind().name());
        append(value, actor.actorUserId());
        append(value, actor.servicePrincipalCode());
        append(value, actor.initiatedByUserId());
        append(value, actor.effectiveSubjectUserId());
        append(value, sessionFingerprintHash);
        append(value, Integer.toString(sessionFingerprintKeyVersion));
        append(value, permissionDigest);
        append(value, payloadHash);
        append(value, INTEGRITY_ALGORITHM);
        append(value, Integer.toString(integrityKeyVersion));
        append(value, correlationId);
        append(value, occurredAt.toString());
        return value.toString();
    }

    private static void append(StringBuilder target, Object raw) {
        String value = raw == null ? "" : raw.toString();
        target.append(value.length()).append(':').append(value).append('|');
    }

    private byte[] requireKey(int version) {
        byte[] key = keyResolver.resolve(version);
        if (key == null || key.length < 32) {
            throw new IllegalStateException("缺少合格的审计 HMAC key version " + version);
        }
        return key.clone();
    }

    private String hmacHex(byte[] key, String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算审计 HMAC", exception);
        }
    }

    private static Long nullableLong(java.sql.ResultSet resultSet, String column) throws java.sql.SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static ActorDescriptor actorFrom(
            String kind,
            Long actorUserId,
            String servicePrincipalCode,
            Long initiatedByUserId,
            Long effectiveSubjectUserId) {
        return new ActorDescriptor(
                ActorKind.valueOf(kind),
                actorUserId,
                servicePrincipalCode,
                initiatedByUserId,
                effectiveSubjectUserId);
    }

    @FunctionalInterface
    public interface AuditKeyResolver {
        byte[] resolve(int keyVersion);
    }

    public record AuditAppendCommand(
            String chainScope,
            String aggregateType,
            String aggregatePublicId,
            String eventType,
            ActorDescriptor actor,
            String sessionFingerprintHash,
            int sessionFingerprintKeyVersion,
            String permissionDigest,
            String payloadRedactedHash,
            String correlationId,
            Instant occurredAt,
            int integrityKeyVersion) {

        public AuditAppendCommand {
            if (chainScope == null || !chainScope.matches("[A-Z0-9_]{1,64}")
                    || aggregateType == null || !aggregateType.matches("[A-Z0-9_]{1,64}")
                    || eventType == null || !eventType.matches("[A-Z0-9_]{1,64}")
                    || actor == null || sessionFingerprintKeyVersion < 1
                    || !hashOrNull(sessionFingerprintHash)
                    || !hashOrNull(permissionDigest)
                    || !hash(payloadRedactedHash)
                    || occurredAt == null || integrityKeyVersion < 1) {
                throw new IllegalArgumentException("审计事件命令不合法");
            }
            requireUuid(aggregatePublicId, "aggregatePublicId");
            requireUuid(correlationId, "correlationId");
        }

        private AuditAppendCommand normalized() {
            return new AuditAppendCommand(
                    chainScope,
                    aggregateType,
                    aggregatePublicId,
                    eventType,
                    actor,
                    sessionFingerprintHash,
                    sessionFingerprintKeyVersion,
                    permissionDigest,
                    payloadRedactedHash,
                    correlationId,
                    occurredAt.truncatedTo(ChronoUnit.MICROS),
                    integrityKeyVersion);
        }

        private static boolean hash(String value) {
            return value != null && value.matches("[0-9a-fA-F]{64}");
        }

        private static boolean hashOrNull(String value) {
            return value == null || hash(value);
        }

        private static void requireUuid(String value, String field) {
            try {
                UUID.fromString(value);
            } catch (Exception exception) {
                throw new IllegalArgumentException(field + " 必须是 UUID", exception);
            }
        }
    }

    public record AuditEventRecord(
            String publicId,
            long sequenceNo,
            String previousEventHash,
            String eventHash,
            String integrityAlgorithm,
            int integrityKeyVersion,
            String canonicalizationVersion) {
    }

    private record HeadRow(
            long id,
            long lastSequenceNo,
            String lastEventHash,
            int integrityKeyVersion,
            long version) {
    }

    private record TailRow(long sequenceNo, String eventHash, int integrityKeyVersion) {
    }

    private record ChainIdentity(String chainScope, String aggregateType, String aggregatePublicId) {
    }

    private record VerifyHead(long lastSequenceNo, String lastEventHash, int integrityKeyVersion) {
    }

    private record EventRow(
            String publicId,
            long sequenceNo,
            String previousEventHash,
            String eventHash,
            String eventType,
            ActorDescriptor actor,
            String sessionFingerprintHash,
            int sessionFingerprintKeyVersion,
            String permissionDigest,
            String payloadRedactedHash,
            String integrityAlgorithm,
            int integrityKeyVersion,
            String canonicalizationVersion,
            String correlationId,
            Instant occurredAt) {
    }
}
