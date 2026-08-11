package com.example.dormitory.ai.security;

import cn.dev33.satoken.stp.StpUtil;
import com.example.dormitory.ai.approval.ActionProposalService;
import com.example.dormitory.ai.approval.ActionType;
import com.example.dormitory.ai.approval.InMemoryActionProposalRepository;
import com.example.dormitory.ai.approval.ProposalOrigin;
import com.example.dormitory.ai.approval.ProposalPreview;
import com.example.dormitory.ai.approval.SpringActionProposalTransactionRunner;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.BusinessExecutionActor;
import com.example.dormitory.ai.port.AiAuditPort;
import com.example.dormitory.ai.port.ApprovedBusinessActionPort;
import com.example.dormitory.domain.UserAccount;
import com.example.dormitory.dto.RoleUpdateRequest;
import com.example.dormitory.dto.UserUpdateRequest;
import com.example.dormitory.mapper.UserAccountMapper;
import com.example.dormitory.service.OperationsService;
import com.example.dormitory.service.RbacService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@ActiveProfiles("test")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:fresh-rbac-lock;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.minimum-idle=1",
        "spring.datasource.hikari.connection-timeout=5000",
        "dormitory.bootstrap-admin.username=",
        "dormitory.bootstrap-admin.password=",
        "dormitory.ai.audit.hmac-key=0123456789abcdef0123456789abcdef",
        "dormitory.ai.tokenization.hmac-key=fedcba9876543210fedcba9876543210"
})
class FreshRbacExecutionLockConcurrencyTest {

    @Autowired
    private RbacService rbac;

    @Autowired
    private UserAccountMapper users;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SpringActionProposalTransactionRunner transactions;

    private long adminRoleId;
    private long viewerRoleId;
    private long writerRoleId;
    private long actorRoleId;
    private long approvalPermissionId;
    private long noticeWritePermissionId;
    private long writerUserId;
    private long actorUserId;

    @BeforeEach
    void resetUsers() {
        jdbc.update("DELETE FROM sys_user_role");
        jdbc.update("DELETE FROM sys_user");
        jdbc.update("DELETE FROM sys_role_permission WHERE role_id IN "
                + "(SELECT id FROM sys_role WHERE code IN ('TEST_FRESH_RBAC_WRITER','TEST_FRESH_RBAC_ACTOR'))");
        jdbc.update("DELETE FROM sys_role WHERE code IN ('TEST_FRESH_RBAC_WRITER','TEST_FRESH_RBAC_ACTOR')");
        adminRoleId = id("SELECT id FROM sys_role WHERE code='ADMIN'");
        viewerRoleId = id("SELECT id FROM sys_role WHERE code='VIEWER'");
        jdbc.update("INSERT INTO sys_role(code,name,description,enabled,built_in) "
                + "VALUES('TEST_FRESH_RBAC_WRITER','fresh RBAC 撤权者','test',TRUE,FALSE)");
        writerRoleId = id("SELECT id FROM sys_role WHERE code='TEST_FRESH_RBAC_WRITER'");
        long userWrite = id("SELECT id FROM sys_permission WHERE code='system:user:write'");
        long roleWrite = id("SELECT id FROM sys_permission WHERE code='system:role:write'");
        jdbc.update("INSERT INTO sys_role_permission(role_id,permission_id) VALUES(?,?)", writerRoleId, userWrite);
        jdbc.update("INSERT INTO sys_role_permission(role_id,permission_id) VALUES(?,?)", writerRoleId, roleWrite);
        jdbc.update("INSERT INTO sys_role(code,name,description,enabled,built_in) "
                + "VALUES('TEST_FRESH_RBAC_ACTOR','fresh RBAC 执行者','test',TRUE,FALSE)");
        actorRoleId = id("SELECT id FROM sys_role WHERE code='TEST_FRESH_RBAC_ACTOR'");
        approvalPermissionId = id("SELECT id FROM sys_permission WHERE code='ai:approval:review'");
        noticeWritePermissionId = id("SELECT id FROM sys_permission WHERE code='notice:write'");
        jdbc.update("INSERT INTO sys_role_permission(role_id,permission_id) VALUES(?,?)",
                actorRoleId, approvalPermissionId);
        jdbc.update("INSERT INTO sys_role_permission(role_id,permission_id) VALUES(?,?)",
                actorRoleId, noticeWritePermissionId);
        writerUserId = createUser("fresh-rbac-writer", "TEST_FRESH_RBAC_WRITER", writerRoleId);
        actorUserId = createUser("fresh-rbac-actor", "TEST_FRESH_RBAC_ACTOR", actorRoleId);
    }

    @Test
    void revocationAfterFreshCheckWaitsForBusinessTransactionToCommit() throws Exception {
        CountDownLatch executionEntered = new CountDownLatch(1);
        CountDownLatch releaseExecution = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();
        ActionProposalService service = proposalService(executionEntered, releaseExecution, writes);
        BusinessExecutionActor actor = BusinessExecutionActor.from(ActorDescriptor.user(actorUserId));
        ActionProposalService.ProposalView proposal = service.create(command(actorUserId), "create", hash('c'), actor);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var approval = executor.submit(() -> service.approve(
                    proposal.publicId(), proposal.version(), proposal.payloadHash(), proposal.businessSnapshotHash(),
                    actor, Set.of("ai:approval:review", "notice:write"), "approve", hash('a')));
            assertTrue(executionEntered.await(5, TimeUnit.SECONDS));
            var revocation = executor.submit(this::demoteActor);

            Thread.sleep(300);
            assertFalse(revocation.isDone(), "撤权事务不得越过执行事务持有的 fresh-RBAC 行锁");
            releaseExecution.countDown();

            assertEquals("SUCCEEDED", approval.get(5, TimeUnit.SECONDS).state().name());
            assertEquals("DEMOTED", revocation.get(5, TimeUnit.SECONDS));
            assertEquals(1, writes.get());
            assertEquals("VIEWER", jdbc.queryForObject(
                    "SELECT role_code FROM sys_user WHERE id=?", String.class, actorUserId));
        } finally {
            releaseExecution.countDown();
        }
    }

    @Test
    void rolePermissionRevocationAfterFreshCheckWaitsForBusinessTransactionToCommit() throws Exception {
        CountDownLatch executionEntered = new CountDownLatch(1);
        CountDownLatch releaseExecution = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();
        ActionProposalService service = proposalService(executionEntered, releaseExecution, writes);
        BusinessExecutionActor actor = BusinessExecutionActor.from(ActorDescriptor.user(actorUserId));
        ActionProposalService.ProposalView proposal = service.create(
                command(actorUserId), "permission-create", hash('c'), actor);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var approval = executor.submit(() -> service.approve(
                    proposal.publicId(), proposal.version(), proposal.payloadHash(), proposal.businessSnapshotHash(),
                    actor, Set.of("ai:approval:review", "notice:write"), "permission-approve", hash('a')));
            assertTrue(executionEntered.await(5, TimeUnit.SECONDS));
            var revocation = executor.submit(this::revokeActorRolePermission);

            Thread.sleep(300);
            assertFalse(revocation.isDone(), "角色权限撤销不得越过 fresh-RBAC 持有的角色行锁");
            releaseExecution.countDown();

            assertEquals("SUCCEEDED", approval.get(5, TimeUnit.SECONDS).state().name());
            assertEquals("PERMISSION_REVOKED", revocation.get(5, TimeUnit.SECONDS));
            assertEquals(1, writes.get());
            assertEquals(0L, id("SELECT COUNT(*) FROM sys_role_permission "
                    + "WHERE role_id=" + actorRoleId + " AND permission_id=" + noticeWritePermissionId));
        } finally {
            releaseExecution.countDown();
        }
    }

    @Test
    void revocationCommittedBeforeFreshCheckRejectsExecutionWithoutBusinessWrite() throws Exception {
        CountDownLatch executionEntered = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();
        ActionProposalService service = proposalService(executionEntered, new CountDownLatch(0), writes);
        BusinessExecutionActor actor = BusinessExecutionActor.from(ActorDescriptor.user(actorUserId));
        ActionProposalService.ProposalView proposal = service.create(
                command(actorUserId), "revoked-create", hash('c'), actor);

        try (var executor = Executors.newFixedThreadPool(2)) {
            assertEquals("DEMOTED", executor.submit(this::demoteActor).get(5, TimeUnit.SECONDS));
            var approval = executor.submit(() -> service.approve(
                    proposal.publicId(), proposal.version(), proposal.payloadHash(), proposal.businessSnapshotHash(),
                    actor, Set.of("ai:approval:review", "notice:write"), "revoked-approve", hash('a')));

            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> approval.get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof com.example.dormitory.ai.api.AiApiException);
            assertEquals(0, writes.get());
            assertEquals(1L, executionEntered.getCount());
        }
    }

    private ActionProposalService proposalService(
            CountDownLatch executionEntered,
            CountDownLatch releaseExecution,
            AtomicInteger writes) {
        AiAuditPort audit = writableAudit();
        ActionAuthorizationPolicy authorization = new ActionAuthorizationPolicy(
                new ActorAuthorizationFacade(rbac), mock(OperationsService.class), audit);
        return new ActionProposalService(
                new InMemoryActionProposalRepository(),
                (actor, action) -> {
                    executionEntered.countDown();
                    await(releaseExecution);
                    writes.incrementAndGet();
                    return new ApprovedBusinessActionPort.BusinessActionResult("NOTICE", 1L, hash('e'));
                },
                (type, payload) -> hash('d'), audit, () -> true, transactions,
                (actor, proposal, ignored) -> authorization.requireFreshExecutionAccess(actor, proposal));
    }

    private String demoteActor() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(
                new MockHttpServletRequest(), new MockHttpServletResponse()));
        try {
            StpUtil.login(writerUserId);
            rbac.updateUser(actorUserId,
                    new UserUpdateRequest("已撤权执行者", null, true, List.of(viewerRoleId)), writerUserId);
            return "DEMOTED";
        } finally {
            StpUtil.logout();
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private String revokeActorRolePermission() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(
                new MockHttpServletRequest(), new MockHttpServletResponse()));
        try {
            StpUtil.login(writerUserId);
            rbac.updateRole(actorRoleId, new RoleUpdateRequest(
                    "fresh RBAC 执行者", "test", true, List.of(approvalPermissionId)));
            return "PERMISSION_REVOKED";
        } finally {
            StpUtil.logout();
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private ActionProposalService.CreateProposalCommand command(long actorId) {
        ProposalPreview preview = new ProposalPreview("当前值", "公告草稿", "有限影响", Instant.now(),
                ProposalPreview.EvidenceBasis.DETERMINISTIC, null,
                List.of(new ProposalPreview.Citation("USER_COMMAND", "RUN:test", "测试命令", hash('b'))));
        return new ActionProposalService.CreateProposalCommand(
                ActionType.NOTICE_CREATE_DRAFT, "NOTICE", null,
                "{\"title\":\"test\",\"status\":\"草稿\"}", preview,
                "notice:write", 1, "HIGH", actorId, Instant.now().plusSeconds(300),
                ProposalOrigin.forAction(UUID.randomUUID().toString(), ActionType.NOTICE_CREATE_DRAFT));
    }

    private AiAuditPort writableAudit() {
        return new AiAuditPort() {
            @Override
            public boolean writable() {
                return true;
            }

            @Override
            public void append(AiAuditEvent event) {
            }
        };
    }

    private long createUser(String username, String roleCode, long roleId) {
        UserAccount user = new UserAccount(null, username, "test-password-hash", username, roleCode, true);
        users.insert(user);
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)", user.getId(), roleId);
        return user.getId();
    }

    private long id(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private String hash(char value) {
        return String.valueOf(value).repeat(64);
    }

    private void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待并发测试信号时被中断", interrupted);
        }
    }
}
