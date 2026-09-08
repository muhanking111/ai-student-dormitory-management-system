package com.example.dormitory.ai.security;

import cn.dev33.satoken.stp.StpUtil;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.infrastructure.runtime.AiRuntimeAuditWriter;
import com.example.dormitory.dto.UserUpdateRequest;
import com.example.dormitory.service.RbacService;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:step-up-password-change;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "dormitory.bootstrap-admin.username=",
        "dormitory.bootstrap-admin.password=",
        "dormitory.ai.audit.hmac-key=step-up-password-change-audit-key-32-bytes",
        "dormitory.ai.tokenization.hmac-key=step-up-password-change-token-key-32-bytes"
})
class StepUpPasswordChangeConcurrencyTest {

    private static final Instant NOW = Instant.parse("2026-09-08T06:30:00Z");
    private static final String OLD_PASSWORD = "Old-password-123";
    private static final String NEW_PASSWORD = "New-password-456";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private RbacService rbacService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void verifiedPasswordAndGrantIssueSerializeBeforeSelfPasswordChangeAndRevocation() throws Exception {
        long userId = createViewer();
        CountDownLatch issueEntered = new CountDownLatch(1);
        CountDownLatch releaseIssue = new CountDownLatch(1);
        CountDownLatch passwordChangeAttempted = new CountDownLatch(1);

        RecentAuthenticationPolicy policy = mock(RecentAuthenticationPolicy.class);
        when(policy.issue(any(), anyString(), any(), anyString())).thenAnswer(invocation -> {
            issueEntered.countDown();
            assertTrue(releaseIssue.await(5, TimeUnit.SECONDS));
            return new RecentAuthenticationPolicy.IssuedProof(
                    UUID.randomUUID().toString(), "proof", NOW, NOW.plusSeconds(60), "PASSWORD");
        });
        AiRuntimeAuditWriter audit = mock(AiRuntimeAuditWriter.class);
        StepUpAuthenticationService service = new StepUpAuthenticationService(
                rbacService, policy, audit, jdbc, new TransactionTemplate(transactionManager),
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(15), 5);
        AuthenticatedRunContext context = new AuthenticatedRunContext(
                ActorDescriptor.user(userId), "a".repeat(64), 1, "b".repeat(64));
        Long viewerRoleId = jdbc.queryForObject(
                "SELECT id FROM sys_role WHERE code='VIEWER'", Long.class);
        assertNotNull(viewerRoleId);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var stepUp = executor.submit(() -> service.authenticate(
                    context, OLD_PASSWORD, "CONFIG_ACTIVATE", null, "c".repeat(64)));
            assertTrue(issueEntered.await(5, TimeUnit.SECONDS));

            var passwordChange = executor.submit(() -> {
                passwordChangeAttempted.countDown();
                try (MockedStatic<StpUtil> stp = org.mockito.Mockito.mockStatic(StpUtil.class)) {
                    rbacService.updateUser(userId,
                            new UserUpdateRequest("并发改密用户", NEW_PASSWORD, true, List.of(viewerRoleId)),
                            userId);
                }
                return true;
            });
            assertTrue(passwordChangeAttempted.await(5, TimeUnit.SECONDS));

            // 正确实现必须让改密等待 step-up 的用户行锁，确保随后 revoke 能覆盖刚签发的 grant。
            assertThrows(TimeoutException.class, () -> passwordChange.get(300, TimeUnit.MILLISECONDS));

            releaseIssue.countDown();
            assertNotNull(stepUp.get(5, TimeUnit.SECONDS));
            assertTrue(passwordChange.get(5, TimeUnit.SECONDS));
        } finally {
            releaseIssue.countDown();
        }

        String storedHash = jdbc.queryForObject(
                "SELECT password_hash FROM sys_user WHERE id=?", String.class, userId);
        assertTrue(passwordEncoder.matches(NEW_PASSWORD, storedHash));
        assertFalse(passwordEncoder.matches(OLD_PASSWORD, storedHash));
    }

    private long createViewer() {
        String username = "step-up-race-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO sys_user(username,password_hash,display_name,role_code,enabled,deleted) "
                        + "VALUES(?,?,?,'VIEWER',TRUE,FALSE)",
                username, passwordEncoder.encode(OLD_PASSWORD), "并发改密用户");
        Long userId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username=?", Long.class, username);
        Long roleId = jdbc.queryForObject("SELECT id FROM sys_role WHERE code='VIEWER'", Long.class);
        assertNotNull(userId);
        assertNotNull(roleId);
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)", userId, roleId);
        return userId;
    }
}
