package com.example.dormitory.service;

import cn.dev33.satoken.stp.StpUtil;
import com.example.dormitory.common.BusinessException;
import com.example.dormitory.domain.UserAccount;
import com.example.dormitory.dto.UserUpdateRequest;
import com.example.dormitory.mapper.UserAccountMapper;
import com.example.dormitory.mapper.UserRoleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ActiveProfiles("test")
@Import(RbacLastEnabledAdminConcurrencyTest.CountBarrierConfiguration.class)
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:rbac-last-admin;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "dormitory.bootstrap-admin.username=",
        "dormitory.bootstrap-admin.password=",
        "dormitory.ai.audit.hmac-key=0123456789abcdef0123456789abcdef",
        "dormitory.ai.tokenization.hmac-key=fedcba9876543210fedcba9876543210"
})
class RbacLastEnabledAdminConcurrencyTest {

    private final RbacService service;
    private final UserAccountMapper users;
    private final JdbcTemplate jdbc;
    private final CountBarrier countBarrier;

    private long adminRoleId;
    private long viewerRoleId;
    private long writerRoleId;
    private long writerUserId;

    @Autowired
    RbacLastEnabledAdminConcurrencyTest(
            RbacService service,
            UserAccountMapper users,
            JdbcTemplate jdbc,
            CountBarrier countBarrier) {
        this.service = service;
        this.users = users;
        this.jdbc = jdbc;
        this.countBarrier = countBarrier;
    }

    @BeforeEach
    void resetUsers() {
        countBarrier.disable();
        jdbc.update("DELETE FROM sys_user_role");
        jdbc.update("DELETE FROM sys_user");
        jdbc.update("DELETE FROM sys_role_permission WHERE role_id IN "
                + "(SELECT id FROM sys_role WHERE code = 'TEST_USER_WRITER')");
        jdbc.update("DELETE FROM sys_role WHERE code = 'TEST_USER_WRITER'");

        adminRoleId = id("SELECT id FROM sys_role WHERE code = 'ADMIN'");
        viewerRoleId = id("SELECT id FROM sys_role WHERE code = 'VIEWER'");
        jdbc.update("INSERT INTO sys_role(code,name,description,enabled,built_in) "
                + "VALUES('TEST_USER_WRITER','用户安全测试写入者','test',TRUE,FALSE)");
        writerRoleId = id("SELECT id FROM sys_role WHERE code = 'TEST_USER_WRITER'");
        long permissionId = id("SELECT id FROM sys_permission WHERE code = 'system:user:write'");
        jdbc.update("INSERT INTO sys_role_permission(role_id,permission_id) VALUES(?,?)",
                writerRoleId, permissionId);
        writerUserId = createUser("rbac-writer", "TEST_USER_WRITER", true, writerRoleId);
    }

    @Test
    void uniqueEnabledAdminCannotBeDisabledWhileRetainingAdminRole() {
        long adminId = createUser("only-admin-disable", "ADMIN", true, adminRoleId);

        BusinessException failure = asWriter(() -> service.updateUser(
                adminId, new UserUpdateRequest("唯一管理员", null, false, List.of(adminRoleId)), writerUserId));

        assertConflict(failure);
        assertEquals(1, enabledAdminCount());
    }

    @Test
    void uniqueEnabledAdminCannotLoseAdminRole() {
        long adminId = createUser("only-admin-demote", "ADMIN", true, adminRoleId);

        BusinessException failure = asWriter(() -> service.updateUser(
                adminId, new UserUpdateRequest("唯一管理员", null, true, List.of(viewerRoleId)), writerUserId));

        assertConflict(failure);
        assertEquals(1, enabledAdminCount());
    }

    @Test
    void uniqueEnabledAdminCannotBeDeleted() {
        long adminId = createUser("only-admin-delete", "ADMIN", true, adminRoleId);

        BusinessException failure = asWriter(() -> service.deleteUser(adminId, writerUserId));

        assertConflict(failure);
        assertEquals(1, enabledAdminCount());
    }

    @Test
    void concurrentDemotionsOfDifferentAdminsLeaveOneEnabledAdmin() throws Exception {
        long firstAdmin = createUser("concurrent-admin-a", "ADMIN", true, adminRoleId);
        long secondAdmin = createUser("concurrent-admin-b", "ADMIN", true, adminRoleId);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        countBarrier.enableForTwoCallers();

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> demote(firstAdmin, ready, start));
            var second = executor.submit(() -> demote(secondAdmin, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            List<String> outcomes = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream().filter("UPDATED"::equals).count());
            assertEquals(1, outcomes.stream().filter("CONFLICT"::equals).count());
            assertEquals(1, enabledAdminCount());
        } finally {
            countBarrier.disable();
        }
    }

    private String demote(long userId, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        bindRequestContext();
        try {
            StpUtil.login(writerUserId);
            service.updateUser(userId,
                    new UserUpdateRequest("并发降权管理员", null, true, List.of(viewerRoleId)), writerUserId);
            return "UPDATED";
        } catch (BusinessException conflict) {
            if (conflict.getStatus().value() == 409) return "CONFLICT";
            throw conflict;
        } finally {
            StpUtil.logout();
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private BusinessException asWriter(Runnable action) {
        StpUtil.login(writerUserId);
        try {
            return assertThrows(BusinessException.class, action::run);
        } finally {
            StpUtil.logout();
        }
    }

    private long createUser(String username, String roleCode, boolean enabled, long roleId) {
        UserAccount user = new UserAccount(null, username, "test-password-hash", username, roleCode, enabled);
        users.insert(user);
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)", user.getId(), roleId);
        return user.getId();
    }

    private long enabledAdminCount() {
        return id("SELECT COUNT(DISTINCT u.id) FROM sys_user u "
                + "JOIN sys_user_role ur ON ur.user_id=u.id "
                + "JOIN sys_role r ON r.id=ur.role_id "
                + "WHERE u.enabled=TRUE AND r.code='ADMIN'");
    }

    private long id(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private void assertConflict(BusinessException failure) {
        assertEquals(409, failure.getStatus().value());
        assertEquals("系统至少需要保留一个启用的管理员", failure.getMessage());
    }

    private void bindRequestContext() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(
                new MockHttpServletRequest(), new MockHttpServletResponse()));
    }

    static final class CountBarrier {
        private final AtomicReference<CountDownLatch> callers = new AtomicReference<>();

        void enableForTwoCallers() {
            callers.set(new CountDownLatch(2));
        }

        void disable() {
            callers.set(null);
        }

        void awaitConcurrentCaller() throws InterruptedException {
            CountDownLatch latch = callers.get();
            if (latch == null) return;
            latch.countDown();
            latch.await(500, TimeUnit.MILLISECONDS);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CountBarrierConfiguration {

        @Bean
        CountBarrier countBarrier() {
            return new CountBarrier();
        }

        @Bean
        static BeanPostProcessor userRoleMapperCountBarrier(CountBarrier barrier) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                    if (!(bean instanceof UserRoleMapper mapper)) return bean;
                    return Proxy.newProxyInstance(
                            mapper.getClass().getClassLoader(),
                            new Class<?>[]{UserRoleMapper.class},
                            (proxy, method, args) -> {
                                if ("countEnabledUsersByRoleCode".equals(method.getName())) {
                                    barrier.awaitConcurrentCaller();
                                }
                                try {
                                    return method.invoke(mapper, args);
                                } catch (InvocationTargetException failure) {
                                    throw failure.getCause();
                                }
                            });
                }
            };
        }
    }
}
