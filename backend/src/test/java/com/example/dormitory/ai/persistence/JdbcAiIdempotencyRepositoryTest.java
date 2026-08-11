package com.example.dormitory.ai.persistence;

import com.example.dormitory.ai.application.control.IdempotencyPayloadMismatchException;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiIdempotencyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ActiveProfiles("test")
@SpringBootTest
class JdbcAiIdempotencyRepositoryTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JdbcAiIdempotencyRepository repository;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM ai_idempotency_record");
    }

    @Test
    void sameScopedKeyAndPayloadReplaysButDifferentPayloadConflicts() {
        var scope = new JdbcAiIdempotencyRepository.Scope(
                42L,
                "AI_PROPOSAL_APPROVE",
                UUID.randomUUID().toString(),
                "request-key-1");
        Instant expiresAt = Instant.now().plus(10, ChronoUnit.MINUTES);

        var created = repository.reserve(scope, hash('a'), expiresAt);
        var replay = repository.reserve(scope, hash('a'), expiresAt);

        assertEquals(JdbcAiIdempotencyRepository.ReservationStatus.CREATED, created.status());
        assertEquals(JdbcAiIdempotencyRepository.ReservationStatus.REPLAY, replay.status());
        assertEquals(created.recordId(), replay.recordId());
        IdempotencyPayloadMismatchException mismatch = assertThrows(
                IdempotencyPayloadMismatchException.class,
                () -> repository.reserve(scope, hash('b'), expiresAt));
        assertEquals("AI_IDEMPOTENCY_PAYLOAD_MISMATCH", mismatch.errorCode());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_idempotency_record", Integer.class));
    }

    @Test
    void actorRouteAndAggregateAreAllPartOfTheScope() {
        String aggregate = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plusSeconds(600);
        var base = repository.reserve(new JdbcAiIdempotencyRepository.Scope(
                7L, "APPROVE", aggregate, "same-key"), hash('a'), expiresAt);
        var otherActor = repository.reserve(new JdbcAiIdempotencyRepository.Scope(
                8L, "APPROVE", aggregate, "same-key"), hash('a'), expiresAt);
        var otherRoute = repository.reserve(new JdbcAiIdempotencyRepository.Scope(
                7L, "REJECT", aggregate, "same-key"), hash('a'), expiresAt);
        var otherAggregate = repository.reserve(new JdbcAiIdempotencyRepository.Scope(
                7L, "APPROVE", UUID.randomUUID().toString(), "same-key"), hash('a'), expiresAt);

        assertNotEquals(base.recordId(), otherActor.recordId());
        assertNotEquals(base.recordId(), otherRoute.recordId());
        assertNotEquals(base.recordId(), otherAggregate.recordId());
        assertEquals(4, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_idempotency_record", Integer.class));
    }

    private String hash(char value) {
        return String.valueOf(value).repeat(64);
    }
}
