package com.example.dormitory.ai.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;

@Repository
public class JdbcStepUpGrantRepository implements StepUpGrantRepository {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public JdbcStepUpGrantRepository(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void create(NewGrant grant) {
        validateGrant(grant);
        jdbcTemplate.update("INSERT INTO ai_step_up_grant "
                        + "(public_id, grant_token_hmac, grant_token_key_version, session_fingerprint_hash, "
                        + "session_fingerprint_key_version, actor_user_id, action_code, resource_public_id, "
                        + "request_hash, auth_method, authenticated_at, expires_at, state, version, "
                        + "created_operator_user_id, updated_operator_user_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 0, ?, ?, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                grant.publicId(), grant.tokenHmac().toLowerCase(Locale.ROOT), grant.tokenKeyVersion(),
                grant.sessionFingerprintHash().toLowerCase(Locale.ROOT), grant.sessionFingerprintKeyVersion(),
                grant.actorUserId(), grant.actionCode(), grant.resourcePublicId(),
                grant.requestHash().toLowerCase(Locale.ROOT), grant.authMethod(),
                Timestamp.from(grant.authenticatedAt()), Timestamp.from(grant.expiresAt()),
                grant.actorUserId(), grant.actorUserId());
    }

    @Override
    public boolean consume(ConsumeGrant grant) {
        validateConsume(grant);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("step-up proof 必须在调用方事务外消费");
        }
        Boolean consumed = transactionTemplate.execute(status -> jdbcTemplate.update(
                "UPDATE ai_step_up_grant SET state = 'USED', used_at = ?, version = version + 1, "
                        + "updated_operator_user_id = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE grant_token_hmac = ? AND grant_token_key_version = ? "
                        + "AND session_fingerprint_hash = ? AND session_fingerprint_key_version = ? "
                        + "AND actor_user_id = ? AND action_code = ? "
                        + "AND ((resource_public_id = ?) OR (resource_public_id IS NULL AND ? IS NULL)) "
                        + "AND request_hash = ? AND state = 'ACTIVE' AND expires_at > ?",
                Timestamp.from(grant.consumedAt()), grant.actorUserId(), grant.tokenHmac().toLowerCase(Locale.ROOT),
                grant.tokenKeyVersion(), grant.sessionFingerprintHash().toLowerCase(Locale.ROOT),
                grant.sessionFingerprintKeyVersion(), grant.actorUserId(), grant.actionCode(),
                grant.resourcePublicId(), grant.resourcePublicId(), grant.requestHash().toLowerCase(Locale.ROOT),
                Timestamp.from(grant.consumedAt())) == 1);
        return Boolean.TRUE.equals(consumed);
    }

    @Override
    public int revokeSession(long actorUserId, String sessionFingerprintHash, Instant revokedAt) {
        if (actorUserId < 1 || !hash(sessionFingerprintHash) || revokedAt == null) {
            throw new IllegalArgumentException("step-up session 撤销参数不合法");
        }
        return jdbcTemplate.update("UPDATE ai_step_up_grant SET state = 'REVOKED', version = version + 1, "
                        + "updated_operator_user_id = ?, updated_at = ? "
                        + "WHERE actor_user_id = ? AND session_fingerprint_hash = ? AND state = 'ACTIVE'",
                actorUserId, Timestamp.from(revokedAt), actorUserId,
                sessionFingerprintHash.toLowerCase(Locale.ROOT));
    }

    @Override
    public int revokeActor(long actorUserId, Instant revokedAt) {
        if (actorUserId < 1 || revokedAt == null) {
            throw new IllegalArgumentException("step-up actor 撤销参数不合法");
        }
        return jdbcTemplate.update("UPDATE ai_step_up_grant SET state = 'REVOKED', version = version + 1, "
                        + "updated_operator_user_id = ?, updated_at = ? "
                        + "WHERE actor_user_id = ? AND state = 'ACTIVE'",
                actorUserId, Timestamp.from(revokedAt), actorUserId);
    }

    private static void validateGrant(NewGrant grant) {
        if (grant == null || !uuid(grant.publicId()) || !hash(grant.tokenHmac())
                || grant.tokenKeyVersion() < 1 || !hash(grant.sessionFingerprintHash())
                || grant.sessionFingerprintKeyVersion() < 1 || grant.actorUserId() < 1
                || !action(grant.actionCode()) || !nullableUuid(grant.resourcePublicId())
                || !hash(grant.requestHash()) || !"PASSWORD".equals(grant.authMethod())
                || grant.authenticatedAt() == null || grant.expiresAt() == null
                || !grant.expiresAt().isAfter(grant.authenticatedAt())) {
            throw new IllegalArgumentException("step-up grant 参数不合法");
        }
    }

    private static void validateConsume(ConsumeGrant grant) {
        if (grant == null || !hash(grant.tokenHmac()) || grant.tokenKeyVersion() < 1
                || !hash(grant.sessionFingerprintHash()) || grant.sessionFingerprintKeyVersion() < 1
                || grant.actorUserId() < 1 || !action(grant.actionCode())
                || !nullableUuid(grant.resourcePublicId()) || !hash(grant.requestHash())
                || grant.consumedAt() == null) {
            throw new IllegalArgumentException("step-up proof 消费参数不合法");
        }
    }

    private static boolean action(String value) { return value != null && value.matches("[A-Z][A-Z0-9_]{2,63}"); }
    private static boolean hash(String value) { return value != null && value.matches("[0-9a-fA-F]{64}"); }
    private static boolean nullableUuid(String value) { return value == null || uuid(value); }
    private static boolean uuid(String value) {
        try { return value != null && java.util.UUID.fromString(value).toString().equalsIgnoreCase(value); }
        catch (IllegalArgumentException exception) { return false; }
    }
}
