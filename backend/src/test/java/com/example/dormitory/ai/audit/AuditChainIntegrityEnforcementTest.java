package com.example.dormitory.ai.audit;

import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.infrastructure.runtime.AiRuntimeAuditWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ActiveProfiles("test")
@SpringBootTest(properties = {
        "dormitory.ai.audit.hmac-key=0123456789abcdef0123456789abcdef",
        "dormitory.ai.audit.anchor.enabled=true",
        "dormitory.ai.audit.anchor.sink=fake",
        "dormitory.ai.audit.anchor.zone=UTC"
})
class AuditChainIntegrityEnforcementTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AiRuntimeAuditWriter writer;

    @Autowired
    private JdbcAiAuditAnchorService anchorService;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM ai_audit_anchor");
        jdbcTemplate.update("DELETE FROM ai_audit_event");
        jdbcTemplate.update("DELETE FROM ai_audit_chain_head");
    }

    @ParameterizedTest(name = "startup rejects tampered {0}")
    @MethodSource("signedFieldTampering")
    void startupReadyFailsClosedWhenAnySignedEventFieldWasTampered(TamperCase tamper) {
        String aggregate = appendSignedEvent(tamper.scope());
        tamper.apply(jdbcTemplate, aggregate);

        assertFalse(writer.startupReady());
    }

    @ParameterizedTest(name = "append rejects tampered {0}")
    @MethodSource("signedFieldTampering")
    void appendFailsClosedWithoutWritingWhenAnySignedEventFieldWasTampered(TamperCase tamper) {
        String aggregate = appendSignedEvent(tamper.scope());
        tamper.apply(jdbcTemplate, aggregate);

        assertThrows(IllegalStateException.class, () -> writer.append(
                tamper.scope(),
                "RUN",
                aggregate,
                "RUN_COMPLETED",
                ActorDescriptor.system("ai-test"),
                null,
                1,
                null,
                hash('b'),
                UUID.randomUUID().toString()));
        assertEquals(1, eventCount(aggregate));
    }

    @ParameterizedTest(name = "anchor rejects tampered {0}")
    @MethodSource("signedFieldTampering")
    void anchorFailsClosedWithoutReceiptWhenAnySignedEventFieldWasTampered(TamperCase tamper) {
        String aggregate = appendSignedEvent(tamper.scope());
        tamper.apply(jdbcTemplate, aggregate);

        assertThrows(AuditAnchorIntegrityException.class,
                () -> anchorService.anchor(LocalDate.now(ZoneOffset.UTC), tamper.scope()));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_audit_anchor", Integer.class));
    }

    @Test
    void anchorRejectsOccurredAtTamperingThatWouldHideAChainFromTheDailyRoot() {
        String scope = "RUN_HIDDEN_OCCURRED_AT";
        appendSignedEvent(scope);
        String hiddenAggregate = appendSignedEvent(scope);
        jdbcTemplate.update(
                "UPDATE ai_audit_event SET occurred_at = ? WHERE aggregate_public_id = ?",
                Timestamp.from(Instant.now().plusSeconds(2 * 24 * 60 * 60)),
                hiddenAggregate);

        assertThrows(AuditAnchorIntegrityException.class,
                () -> anchorService.anchor(LocalDate.now(ZoneOffset.UTC), scope));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_audit_anchor", Integer.class));
    }

    private String appendSignedEvent(String scope) {
        String aggregate = UUID.randomUUID().toString();
        writer.append(
                scope,
                "RUN",
                aggregate,
                "RUN_ACCEPTED",
                ActorDescriptor.system("ai-test"),
                null,
                1,
                null,
                hash('a'),
                UUID.randomUUID().toString());
        return aggregate;
    }

    private int eventCount(String aggregate) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_audit_event WHERE aggregate_public_id = ?",
                Integer.class,
                aggregate);
    }

    private static Stream<Arguments> signedFieldTampering() {
        return Stream.of(
                Arguments.of(new TamperCase("RUN_PAYLOAD", "payload_redacted_hash", hash('f'))),
                Arguments.of(new TamperCase("RUN_EVENT_TYPE", "event_type", "RUN_TAMPERED")),
                Arguments.of(new TamperCase(
                        "RUN_ACTOR",
                        "service_principal_code",
                        "tampered-service")),
                Arguments.of(new TamperCase(
                        "RUN_OCCURRED_AT",
                        "occurred_at",
                        Timestamp.from(Instant.now().minusSeconds(3600)))));
    }

    private static String hash(char value) {
        return String.valueOf(value).repeat(64);
    }

    private record TamperCase(String scope, String column, Object value) {

        private void apply(JdbcTemplate jdbcTemplate, String aggregate) {
            jdbcTemplate.update(
                    "UPDATE ai_audit_event SET " + column + " = ? WHERE aggregate_public_id = ?",
                    value,
                    aggregate);
        }

        @Override
        public String toString() {
            return column;
        }
    }
}
