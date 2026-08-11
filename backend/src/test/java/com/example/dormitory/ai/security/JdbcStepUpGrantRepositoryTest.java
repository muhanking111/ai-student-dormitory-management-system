package com.example.dormitory.ai.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcStepUpGrantRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-07-11T12:00:00Z");
    private static final String TOKEN_HMAC = "a".repeat(64);
    private static final String SESSION_HASH = "b".repeat(64);
    private static final String REQUEST_HASH = "c".repeat(64);
    private static final String RESOURCE_ID = "11111111-1111-1111-1111-111111111111";

    private JdbcTemplate jdbc;
    private JdbcStepUpGrantRepository repository;
    private DataSourceTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:step-up-repository;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS ai_step_up_grant");
        jdbc.execute("""
                CREATE TABLE ai_step_up_grant (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  public_id CHAR(36) NOT NULL UNIQUE,
                  grant_token_hmac CHAR(64) NOT NULL UNIQUE,
                  grant_token_key_version INT NOT NULL,
                  session_fingerprint_hash CHAR(64) NOT NULL,
                  session_fingerprint_key_version INT NOT NULL,
                  actor_user_id BIGINT NOT NULL,
                  action_code VARCHAR(64) NOT NULL,
                  resource_public_id CHAR(36),
                  request_hash CHAR(64) NOT NULL,
                  auth_method VARCHAR(32) NOT NULL,
                  authenticated_at TIMESTAMP(6) NOT NULL,
                  expires_at TIMESTAMP(6) NOT NULL,
                  state VARCHAR(32) NOT NULL,
                  used_at TIMESTAMP(6),
                  version BIGINT NOT NULL DEFAULT 0,
                  created_operator_user_id BIGINT,
                  updated_operator_user_id BIGINT,
                  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        transactionManager = new DataSourceTransactionManager(dataSource);
        repository = new JdbcStepUpGrantRepository(jdbc, transactionManager);
    }

    @Test
    void storesOnlyHmacAndConsumesExactlyMatchingBindingOnce() {
        String publicId = UUID.randomUUID().toString();
        repository.create(new StepUpGrantRepository.NewGrant(publicId, TOKEN_HMAC, 3,
                SESSION_HASH, 2, 9L, "AUDIT_CONTENT_READ", RESOURCE_ID, REQUEST_HASH,
                "PASSWORD", NOW, NOW.plusSeconds(300)));

        assertEquals(TOKEN_HMAC, jdbc.queryForObject(
                "SELECT grant_token_hmac FROM ai_step_up_grant WHERE public_id = ?", String.class, publicId));
        assertFalse(repository.consume(new StepUpGrantRepository.ConsumeGrant(
                TOKEN_HMAC, 3, SESSION_HASH, 2, 9L, "AUDIT_CONTENT_READ",
                "22222222-2222-2222-2222-222222222222", REQUEST_HASH, NOW.plusSeconds(1))));
        assertFalse(repository.consume(new StepUpGrantRepository.ConsumeGrant(
                TOKEN_HMAC, 3, "d".repeat(64), 2, 9L, "AUDIT_CONTENT_READ",
                RESOURCE_ID, REQUEST_HASH, NOW.plusSeconds(1))));
        assertFalse(repository.consume(new StepUpGrantRepository.ConsumeGrant(
                TOKEN_HMAC, 3, SESSION_HASH, 2, 10L, "AUDIT_CONTENT_READ",
                RESOURCE_ID, REQUEST_HASH, NOW.plusSeconds(1))));
        assertFalse(repository.consume(new StepUpGrantRepository.ConsumeGrant(
                TOKEN_HMAC, 3, SESSION_HASH, 2, 9L, "CONFIG_ACTIVATE",
                RESOURCE_ID, REQUEST_HASH, NOW.plusSeconds(1))));
        assertFalse(repository.consume(new StepUpGrantRepository.ConsumeGrant(
                TOKEN_HMAC, 3, SESSION_HASH, 2, 9L, "AUDIT_CONTENT_READ",
                RESOURCE_ID, "e".repeat(64), NOW.plusSeconds(1))));
        assertTrue(repository.consume(new StepUpGrantRepository.ConsumeGrant(
                TOKEN_HMAC, 3, SESSION_HASH, 2, 9L, "AUDIT_CONTENT_READ",
                RESOURCE_ID, REQUEST_HASH, NOW.plusSeconds(1))));
        assertFalse(repository.consume(new StepUpGrantRepository.ConsumeGrant(
                TOKEN_HMAC, 3, SESSION_HASH, 2, 9L, "AUDIT_CONTENT_READ",
                RESOURCE_ID, REQUEST_HASH, NOW.plusSeconds(2))));
        assertEquals("USED", jdbc.queryForObject(
                "SELECT state FROM ai_step_up_grant WHERE public_id = ?", String.class, publicId));
    }

    @Test
    void expiredGrantCannotBeConsumed() {
        repository.create(new StepUpGrantRepository.NewGrant(UUID.randomUUID().toString(), TOKEN_HMAC, 1,
                SESSION_HASH, 1, 9L, "CONFIG_ACTIVATE", null, REQUEST_HASH,
                "PASSWORD", NOW.minusSeconds(301), NOW.minusSeconds(1)));

        assertFalse(repository.consume(new StepUpGrantRepository.ConsumeGrant(
                TOKEN_HMAC, 1, SESSION_HASH, 1, 9L, "CONFIG_ACTIVATE", null, REQUEST_HASH, NOW)));
    }

    @Test
    void survivesRepositoryRecreationWithoutKeepingRawProofInMemory() {
        repository.create(new StepUpGrantRepository.NewGrant(UUID.randomUUID().toString(), TOKEN_HMAC, 1,
                SESSION_HASH, 1, 9L, "CONFIG_ACTIVATE", null, REQUEST_HASH,
                "PASSWORD", NOW, NOW.plusSeconds(300)));
        JdbcStepUpGrantRepository restarted = new JdbcStepUpGrantRepository(
                jdbc, new DataSourceTransactionManager(jdbc.getDataSource()));

        assertTrue(restarted.consume(new StepUpGrantRepository.ConsumeGrant(
                TOKEN_HMAC, 1, SESSION_HASH, 1, 9L, "CONFIG_ACTIVATE", null,
                REQUEST_HASH, NOW.plusSeconds(1))));
    }

    @Test
    void concurrentDoubleUseHasExactlyOneWinner() throws Exception {
        repository.create(new StepUpGrantRepository.NewGrant(UUID.randomUUID().toString(), TOKEN_HMAC, 1,
                SESSION_HASH, 1, 9L, "PROMPT_ACTIVATE", RESOURCE_ID, REQUEST_HASH,
                "PASSWORD", NOW, NOW.plusSeconds(300)));
        StepUpGrantRepository.ConsumeGrant command = new StepUpGrantRepository.ConsumeGrant(
                TOKEN_HMAC, 1, SESSION_HASH, 1, 9L, "PROMPT_ACTIVATE", RESOURCE_ID, REQUEST_HASH,
                NOW.plusSeconds(1));
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return repository.consume(command);
                }));
            }
            start.countDown();
            int winners = 0;
            for (Future<Boolean> future : futures) if (future.get()) winners++;
            assertEquals(1, winners);
        }
    }

    @Test
    void topLevelConsumptionSurvivesLaterBusinessRollback() {
        repository.create(new StepUpGrantRepository.NewGrant(UUID.randomUUID().toString(), TOKEN_HMAC, 1,
                SESSION_HASH, 1, 9L, "PROPOSAL_APPROVE", RESOURCE_ID, REQUEST_HASH,
                "PASSWORD", NOW, NOW.plusSeconds(300)));
        StepUpGrantRepository.ConsumeGrant command = new StepUpGrantRepository.ConsumeGrant(
                TOKEN_HMAC, 1, SESSION_HASH, 1, 9L, "PROPOSAL_APPROVE", RESOURCE_ID,
                REQUEST_HASH, NOW.plusSeconds(1));

        assertTrue(repository.consume(command));
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactionManager).execute(status -> {
            throw new IllegalStateException("simulate fresh-RBAC denial after proof consumption");
        }));

        assertFalse(repository.consume(command));
        assertEquals("USED", jdbc.queryForObject(
                "SELECT state FROM ai_step_up_grant WHERE grant_token_hmac = ?", String.class, TOKEN_HMAC));
    }

    @Test
    void consumeRejectsCallerOwnedTransactionBeforeRequestingAnotherConnection() {
        repository.create(new StepUpGrantRepository.NewGrant(UUID.randomUUID().toString(), TOKEN_HMAC, 1,
                SESSION_HASH, 1, 9L, "PROPOSAL_APPROVE", RESOURCE_ID, REQUEST_HASH,
                "PASSWORD", NOW, NOW.plusSeconds(300)));
        StepUpGrantRepository.ConsumeGrant command = new StepUpGrantRepository.ConsumeGrant(
                TOKEN_HMAC, 1, SESSION_HASH, 1, 9L, "PROPOSAL_APPROVE", RESOURCE_ID,
                REQUEST_HASH, NOW.plusSeconds(1));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new TransactionTemplate(transactionManager).execute(status -> repository.consume(command)));

        assertTrue(failure.getMessage().contains("调用方事务外"));
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT state FROM ai_step_up_grant WHERE grant_token_hmac = ?", String.class, TOKEN_HMAC));
    }

    @Test
    void revokesBySessionOrActorWithoutTouchingUsedRows() {
        repository.create(new StepUpGrantRepository.NewGrant(UUID.randomUUID().toString(), TOKEN_HMAC, 1,
                SESSION_HASH, 1, 9L, "CONFIG_ACTIVATE", null, REQUEST_HASH,
                "PASSWORD", NOW, NOW.plusSeconds(300)));
        assertEquals(1, repository.revokeSession(9L, SESSION_HASH, NOW.plusSeconds(1)));
        assertEquals("REVOKED", jdbc.queryForObject(
                "SELECT state FROM ai_step_up_grant WHERE grant_token_hmac = ?", String.class, TOKEN_HMAC));

        String anotherHmac = "d".repeat(64);
        repository.create(new StepUpGrantRepository.NewGrant(UUID.randomUUID().toString(), anotherHmac, 1,
                "e".repeat(64), 1, 9L, "CONFIG_ACTIVATE", null, REQUEST_HASH,
                "PASSWORD", NOW, NOW.plusSeconds(300)));
        assertEquals(1, repository.revokeActor(9L, NOW.plusSeconds(2)));
    }

    @Test
    void createRejectsEveryInvalidBindingBeforeWriting() {
        List<Consumer<NewGrantBuilder>> invalidInputs = List.of(
                value -> value.publicId = null,
                value -> value.publicId = "not-a-uuid",
                value -> value.tokenHmac = null,
                value -> value.tokenHmac = "a".repeat(63),
                value -> value.tokenKeyVersion = 0,
                value -> value.sessionHash = null,
                value -> value.sessionHash = "b".repeat(63),
                value -> value.sessionKeyVersion = 0,
                value -> value.actorUserId = 0,
                value -> value.actionCode = null,
                value -> value.actionCode = "ab",
                value -> value.resourceId = "not-a-uuid",
                value -> value.requestHash = "c".repeat(63),
                value -> value.authMethod = null,
                value -> value.authMethod = "MFA",
                value -> value.authenticatedAt = null,
                value -> value.expiresAt = null,
                value -> value.expiresAt = NOW);

        assertThrows(IllegalArgumentException.class, () -> repository.create(null));
        for (Consumer<NewGrantBuilder> invalidInput : invalidInputs) {
            NewGrantBuilder builder = new NewGrantBuilder();
            invalidInput.accept(builder);
            assertThrows(IllegalArgumentException.class, () -> repository.create(builder.build()));
        }
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM ai_step_up_grant", Long.class));
    }

    @Test
    void consumeAndRevocationRejectEveryInvalidBindingBeforeUpdating() {
        List<Consumer<ConsumeGrantBuilder>> invalidInputs = List.of(
                value -> value.tokenHmac = null,
                value -> value.tokenHmac = "a".repeat(63),
                value -> value.tokenKeyVersion = 0,
                value -> value.sessionHash = null,
                value -> value.sessionHash = "b".repeat(63),
                value -> value.sessionKeyVersion = 0,
                value -> value.actorUserId = 0,
                value -> value.actionCode = null,
                value -> value.actionCode = "ab",
                value -> value.resourceId = "not-a-uuid",
                value -> value.requestHash = null,
                value -> value.requestHash = "c".repeat(63),
                value -> value.consumedAt = null);

        assertThrows(IllegalArgumentException.class, () -> repository.consume(null));
        for (Consumer<ConsumeGrantBuilder> invalidInput : invalidInputs) {
            ConsumeGrantBuilder builder = new ConsumeGrantBuilder();
            invalidInput.accept(builder);
            assertThrows(IllegalArgumentException.class, () -> repository.consume(builder.build()));
        }
        assertThrows(IllegalArgumentException.class, () -> repository.revokeSession(0, SESSION_HASH, NOW));
        assertThrows(IllegalArgumentException.class, () -> repository.revokeSession(9, null, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> repository.revokeSession(9, "b".repeat(63), NOW));
        assertThrows(IllegalArgumentException.class, () -> repository.revokeSession(9, SESSION_HASH, null));
        assertThrows(IllegalArgumentException.class, () -> repository.revokeActor(0, NOW));
        assertThrows(IllegalArgumentException.class, () -> repository.revokeActor(9, null));
    }

    @Test
    void normalizesUppercaseHashesAtStorageConsumptionAndRevocationBoundaries() {
        String upperToken = "A".repeat(64);
        String upperSession = "B".repeat(64);
        String upperRequest = "C".repeat(64);
        repository.create(new StepUpGrantRepository.NewGrant(UUID.randomUUID().toString(), upperToken, 1,
                upperSession, 1, 9L, "CONFIG_ACTIVATE", null, upperRequest,
                "PASSWORD", NOW, NOW.plusSeconds(300)));

        assertEquals(TOKEN_HMAC, jdbc.queryForObject(
                "SELECT grant_token_hmac FROM ai_step_up_grant", String.class));
        assertTrue(repository.consume(new StepUpGrantRepository.ConsumeGrant(
                upperToken, 1, upperSession, 1, 9L, "CONFIG_ACTIVATE", null,
                upperRequest, NOW.plusSeconds(1))));

        String secondToken = "D".repeat(64);
        repository.create(new StepUpGrantRepository.NewGrant(UUID.randomUUID().toString(), secondToken, 1,
                upperSession, 1, 9L, "CONFIG_ACTIVATE", null, upperRequest,
                "PASSWORD", NOW, NOW.plusSeconds(300)));
        assertEquals(1, repository.revokeSession(9L, upperSession, NOW.plusSeconds(2)));
    }

    private static final class NewGrantBuilder {
        private String publicId = RESOURCE_ID;
        private String tokenHmac = TOKEN_HMAC;
        private int tokenKeyVersion = 1;
        private String sessionHash = SESSION_HASH;
        private int sessionKeyVersion = 1;
        private long actorUserId = 9;
        private String actionCode = "CONFIG_ACTIVATE";
        private String resourceId;
        private String requestHash = REQUEST_HASH;
        private String authMethod = "PASSWORD";
        private Instant authenticatedAt = NOW;
        private Instant expiresAt = NOW.plusSeconds(300);

        private StepUpGrantRepository.NewGrant build() {
            return new StepUpGrantRepository.NewGrant(publicId, tokenHmac, tokenKeyVersion, sessionHash,
                    sessionKeyVersion, actorUserId, actionCode, resourceId, requestHash, authMethod,
                    authenticatedAt, expiresAt);
        }
    }

    private static final class ConsumeGrantBuilder {
        private String tokenHmac = TOKEN_HMAC;
        private int tokenKeyVersion = 1;
        private String sessionHash = SESSION_HASH;
        private int sessionKeyVersion = 1;
        private long actorUserId = 9;
        private String actionCode = "CONFIG_ACTIVATE";
        private String resourceId;
        private String requestHash = REQUEST_HASH;
        private Instant consumedAt = NOW;

        private StepUpGrantRepository.ConsumeGrant build() {
            return new StepUpGrantRepository.ConsumeGrant(tokenHmac, tokenKeyVersion, sessionHash,
                    sessionKeyVersion, actorUserId, actionCode, resourceId, requestHash, consumedAt);
        }
    }
}
