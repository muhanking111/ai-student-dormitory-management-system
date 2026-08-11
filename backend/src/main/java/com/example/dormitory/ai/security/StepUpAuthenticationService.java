package com.example.dormitory.ai.security;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.config.AiProperties;
import com.example.dormitory.ai.infrastructure.runtime.AiRuntimeAuditWriter;
import com.example.dormitory.service.RbacService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class StepUpAuthenticationService {

    private final RbacService rbacService;
    private final RecentAuthenticationPolicy policy;
    private final AiRuntimeAuditWriter auditWriter;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final Duration failureWindow;
    private final int maxFailures;

    @Autowired
    public StepUpAuthenticationService(
            RbacService rbacService,
            RecentAuthenticationPolicy policy,
            AiRuntimeAuditWriter auditWriter,
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            AiProperties properties) {
        this(rbacService, policy, auditWriter, jdbcTemplate,
                new TransactionTemplate(transactionManager), Clock.systemUTC(),
                properties.getStepUp().getFailureWindow(), properties.getStepUp().getMaxFailures());
    }

    StepUpAuthenticationService(
            RbacService rbacService,
            RecentAuthenticationPolicy policy,
            AiRuntimeAuditWriter auditWriter,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            Clock clock,
            Duration failureWindow,
            int maxFailures) {
        this.rbacService = rbacService;
        this.policy = policy;
        this.auditWriter = auditWriter;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        if (failureWindow == null || failureWindow.isNegative() || failureWindow.isZero()
                || maxFailures < 1 || maxFailures > 20) {
            throw new IllegalArgumentException("step-up 爆破限流配置不合法");
        }
        this.failureWindow = failureWindow;
        this.maxFailures = maxFailures;
    }

    public RecentAuthenticationPolicy.IssuedProof authenticate(
            AuthenticatedRunContext context,
            String password,
            String actionCode,
            String resourcePublicId,
            String requestHash) {
        long userId = context.requireUserActorId();
        auditWriter.requireWritable();
        AuthenticationOutcome outcome = transactionTemplate.execute(status -> authenticateLocked(
                userId, context, password, actionCode, resourcePublicId, requestHash, clock.instant()));
        if (outcome == null) throw new IllegalStateException("step-up 验证事务未返回结果");
        if (outcome.proof() != null) return outcome.proof();
        if (outcome.rateLimited()) {
            throw new AiApiException(HttpStatus.TOO_MANY_REQUESTS, "AI_STEP_UP_RATE_LIMITED",
                    "step-up 验证失败次数过多，请稍后重试", true,
                    Math.toIntExact(Math.min(Integer.MAX_VALUE, failureWindow.toSeconds())),
                    null, List.of(), java.util.Map.of());
        }
        throw new AiApiException(HttpStatus.UNAUTHORIZED, "AI_STEP_UP_REAUTH_FAILED",
                "账号或密码错误", false);
    }

    private AuthenticationOutcome authenticateLocked(
            long userId,
            AuthenticatedRunContext context,
            String password,
            String actionCode,
            String resourcePublicId,
            String requestHash,
            Instant now) {
        FailureWindow window = lockFailureWindow(userId, now);
        if (window.failureCount() >= maxFailures) {
            appendAudit("STEP_UP_AUTH_RATE_LIMITED", UUID.randomUUID().toString(), context,
                    actionCode, resourcePublicId, requestHash);
            return AuthenticationOutcome.rateLimitedOutcome();
        }
        if (!rbacService.verifyEnabledUserPassword(userId, password)) {
            int updated = jdbcTemplate.update(
                    "UPDATE ai_step_up_failure_window SET failure_count=failure_count+1, "
                            + "version=version+1, updated_at=CURRENT_TIMESTAMP "
                            + "WHERE actor_user_id=? AND failure_count=?",
                    userId, window.failureCount());
            if (updated != 1) throw new IllegalStateException("step-up 失败计数 CAS 失败");
            appendAudit("STEP_UP_AUTH_FAILED", UUID.randomUUID().toString(), context,
                    actionCode, resourcePublicId, requestHash);
            return AuthenticationOutcome.failedOutcome();
        }
        jdbcTemplate.update(
                "UPDATE ai_step_up_failure_window SET failure_count=0, window_started_at=?, "
                        + "version=version+1, updated_at=CURRENT_TIMESTAMP WHERE actor_user_id=?",
                java.sql.Timestamp.from(now), userId);
        RecentAuthenticationPolicy.IssuedProof proof = policy.issue(
                context, actionCode, resourcePublicId, requestHash);
        appendAudit("STEP_UP_AUTH_SUCCEEDED", proof.grantPublicId(), context,
                actionCode, resourcePublicId, requestHash);
        return AuthenticationOutcome.succeeded(proof);
    }

    private FailureWindow lockFailureWindow(long userId, Instant now) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO ai_step_up_failure_window "
                            + "(actor_user_id,window_started_at,failure_count,version,created_at,updated_at) "
                            + "VALUES (?,?,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    userId, java.sql.Timestamp.from(now));
        } catch (DuplicateKeyException ignored) {
            // The existing actor row is locked below; concurrent instances serialize on this primary key.
        }
        FailureWindow current = jdbcTemplate.queryForObject(
                "SELECT window_started_at,failure_count FROM ai_step_up_failure_window "
                        + "WHERE actor_user_id=? FOR UPDATE",
                (resultSet, rowNum) -> new FailureWindow(
                        resultSet.getTimestamp("window_started_at").toInstant(),
                        resultSet.getInt("failure_count")),
                userId);
        if (current == null) throw new IllegalStateException("step-up 失败窗口不存在");
        if (!now.isBefore(current.windowStartedAt().plus(failureWindow))) {
            int updated = jdbcTemplate.update(
                    "UPDATE ai_step_up_failure_window SET window_started_at=?,failure_count=0,"
                            + "version=version+1,updated_at=CURRENT_TIMESTAMP WHERE actor_user_id=?",
                    java.sql.Timestamp.from(now), userId);
            if (updated != 1) throw new IllegalStateException("step-up 失败窗口重置失败");
            return new FailureWindow(now, 0);
        }
        return current;
    }

    private void appendAudit(
            String eventType,
            String aggregatePublicId,
            AuthenticatedRunContext context,
            String actionCode,
            String resourcePublicId,
            String requestHash) {
        String payloadHash = sha256(String.join("|",
                safe(actionCode), safe(resourcePublicId), safe(requestHash), eventType));
        auditWriter.append("SECURITY", "STEP_UP_GRANT", aggregatePublicId, eventType,
                context.actor(), context.sessionFingerprintHash(), context.sessionFingerprintKeyVersion(),
                context.permissionDigest(), payloadHash, UUID.randomUUID().toString());
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private record FailureWindow(Instant windowStartedAt, int failureCount) { }

    private record AuthenticationOutcome(
            RecentAuthenticationPolicy.IssuedProof proof,
            boolean rateLimited) {

        private static AuthenticationOutcome succeeded(RecentAuthenticationPolicy.IssuedProof proof) {
            return new AuthenticationOutcome(java.util.Objects.requireNonNull(proof), false);
        }

        private static AuthenticationOutcome failedOutcome() {
            return new AuthenticationOutcome(null, false);
        }

        private static AuthenticationOutcome rateLimitedOutcome() {
            return new AuthenticationOutcome(null, true);
        }
    }
}
