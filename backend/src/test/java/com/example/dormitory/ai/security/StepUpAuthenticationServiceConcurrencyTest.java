package com.example.dormitory.ai.security;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.infrastructure.runtime.AiRuntimeAuditWriter;
import com.example.dormitory.service.RbacService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StepUpAuthenticationServiceConcurrencyTest {

    private static final Instant NOW = Instant.parse("2026-07-16T02:00:00Z");
    private static final AuthenticatedRunContext CONTEXT = new AuthenticatedRunContext(
            ActorDescriptor.user(7L), "a".repeat(64), 1, "b".repeat(64));

    private JdbcTemplate jdbc;
    private RbacService rbac;
    private StepUpAuthenticationService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:step-up-concurrency-" + java.util.UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE ai_step_up_failure_window (
                  actor_user_id BIGINT PRIMARY KEY,
                  window_started_at TIMESTAMP(6) NOT NULL,
                  failure_count INT NOT NULL DEFAULT 0,
                  version BIGINT NOT NULL DEFAULT 0,
                  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                )
                """);
        rbac = mock(RbacService.class);
        when(rbac.verifyEnabledUserPassword(eq(7L), anyString())).thenReturn(false);
        service = new StepUpAuthenticationService(
                rbac,
                mock(RecentAuthenticationPolicy.class),
                mock(AiRuntimeAuditWriter.class),
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(15),
                5);
    }

    @Test
    void concurrentFailuresAreSerializedAcrossTheDatabaseWindowAndCannotBypassFiveAttempts() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (int index = 0; index < 8; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        service.authenticate(CONTEXT, "wrong-password", "CONFIG_ACTIVATE", null,
                                "c".repeat(64));
                        return "ISSUED";
                    } catch (AiApiException exception) {
                        return exception.errorCode();
                    }
                }));
            }
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> future : futures) outcomes.add(future.get());

            assertEquals(5, outcomes.stream().filter("AI_STEP_UP_REAUTH_FAILED"::equals).count());
            assertEquals(3, outcomes.stream().filter("AI_STEP_UP_RATE_LIMITED"::equals).count());
        }

        verify(rbac, times(5)).verifyEnabledUserPassword(7L, "wrong-password");
        assertEquals(5, jdbc.queryForObject(
                "SELECT failure_count FROM ai_step_up_failure_window WHERE actor_user_id=7", Integer.class));
    }
}
