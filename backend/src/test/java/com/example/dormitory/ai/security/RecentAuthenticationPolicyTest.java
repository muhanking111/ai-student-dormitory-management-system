package com.example.dormitory.ai.security;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.port.SessionValidityPort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecentAuthenticationPolicyTest {

    private static final Instant NOW = Instant.parse("2026-07-11T12:00:00Z");
    private static final AuthenticatedRunContext USER = new AuthenticatedRunContext(
            ActorDescriptor.user(7L), "a".repeat(64), 1, "b".repeat(64));

    @Test
    void issuesFiveMinuteProofAndConsumesOnlyForValidUserSession() {
        RecordingRepository repository = new RecordingRepository();
        StepUpProofCrypto crypto = StepUpProofCrypto.forTesting(1, "0123456789abcdef0123456789abcdef");
        SessionValidityPort validity = reference -> new SessionValidityPort.SessionValidity(true, true, "VALID");
        RecentAuthenticationPolicy policy = new RecentAuthenticationPolicy(
                repository, crypto, validity, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(5));

        RecentAuthenticationPolicy.IssuedProof issued = policy.issue(
                USER, "AUDIT_CONTENT_READ", "11111111-1111-1111-1111-111111111111", "c".repeat(64));

        assertEquals(NOW.plusSeconds(300), issued.expiresAt());
        assertTrue(issued.proof().startsWith("sup1.1."));
        policy.consume(issued.proof(), USER, "AUDIT_CONTENT_READ",
                "11111111-1111-1111-1111-111111111111", "c".repeat(64));
        assertTrue(repository.consumed.get());
    }

    @Test
    void rejectsServiceActorBeforeIssuingProof() {
        RecordingRepository repository = new RecordingRepository();
        RecentAuthenticationPolicy policy = new RecentAuthenticationPolicy(repository,
                StepUpProofCrypto.forTesting(1, "0123456789abcdef0123456789abcdef"),
                reference -> new SessionValidityPort.SessionValidity(true, true, "VALID"),
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(5));
        AuthenticatedRunContext service = new AuthenticatedRunContext(
                ActorDescriptor.service("risk-scanner", 7L, null), "a".repeat(64), 1, "b".repeat(64));

        AiApiException error = assertThrows(AiApiException.class, () -> policy.issue(
                service, "CONFIG_ACTIVATE", null, "c".repeat(64)));
        assertEquals("AI_STEP_UP_USER_ACTOR_REQUIRED", error.errorCode());
    }

    @Test
    void allowsKillSwitchClearProofBoundToStableSwitchResource() {
        RecordingRepository repository = new RecordingRepository();
        RecentAuthenticationPolicy policy = new RecentAuthenticationPolicy(repository,
                StepUpProofCrypto.forTesting(1, "0123456789abcdef0123456789abcdef"),
                reference -> new SessionValidityPort.SessionValidity(true, true, "VALID"),
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(5));
        var issued = policy.issue(USER, "KILL_SWITCH_CLEAR",
                "22222222-2222-2222-2222-222222222222", "d".repeat(64));
        assertTrue(issued.proof().startsWith("sup1.1."));
    }

    @Test
    void rejectsProofWhenSessionOrPermissionSnapshotIsNoLongerValid() {
        RecordingRepository repository = new RecordingRepository();
        RecentAuthenticationPolicy policy = new RecentAuthenticationPolicy(repository,
                StepUpProofCrypto.forTesting(1, "0123456789abcdef0123456789abcdef"),
                reference -> new SessionValidityPort.SessionValidity(false, true, "SESSION_REVOKED"),
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(5));

        AiApiException error = assertThrows(AiApiException.class, () -> policy.consume(
                "sup1.1.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", USER,
                "CONFIG_ACTIVATE", null, "c".repeat(64)));
        assertEquals("AI_STEP_UP_SESSION_INVALID", error.errorCode());
    }

    @Test
    void servicePrincipalCannotBecomeBusinessExecutionUser() {
        ServicePrincipalPolicy servicePrincipalPolicy = new ServicePrincipalPolicy();
        AiApiException error = assertThrows(AiApiException.class, () ->
                servicePrincipalPolicy.requireBusinessExecutionUser(
                        ActorDescriptor.service("proposal-worker", 7L, null)));
        assertEquals("AI_SERVICE_ACTOR_WRITE_FORBIDDEN", error.errorCode());
        assertEquals(7L, servicePrincipalPolicy.requireBusinessExecutionUser(ActorDescriptor.user(7L)));
    }

    @Test
    void previousProofKeyIsAcceptedOnlyWhilePresentInRotationKeyring() {
        String oldKey = "0123456789abcdef0123456789abcdef";
        String newKey = "abcdef0123456789abcdef0123456789";
        StepUpProofCrypto.GeneratedProof oldProof = StepUpProofCrypto.forTesting(1, oldKey).generate();
        StepUpProofCrypto rotating = new StepUpProofCrypto(2, Map.of(
                1, oldKey.getBytes(StandardCharsets.UTF_8),
                2, newKey.getBytes(StandardCharsets.UTF_8)), new SecureRandom());

        StepUpProofCrypto.ParsedProof parsed = rotating.parseAndHash(oldProof.proof());
        assertEquals(1, parsed.keyVersion());
        assertEquals(oldProof.hmac(), parsed.hmac());

        StepUpProofCrypto afterGrace = StepUpProofCrypto.forTesting(2, newKey);
        AiApiException error = assertThrows(AiApiException.class,
                () -> afterGrace.parseAndHash(oldProof.proof()));
        assertEquals("AI_STEP_UP_PROOF_INVALID", error.errorCode());
    }

    private static final class RecordingRepository implements StepUpGrantRepository {
        private final AtomicBoolean consumed = new AtomicBoolean();

        @Override public void create(NewGrant grant) { }
        @Override public boolean consume(ConsumeGrant grant) { consumed.set(true); return true; }
        @Override public int revokeSession(long actorUserId, String sessionFingerprintHash, Instant revokedAt) { return 0; }
        @Override public int revokeActor(long actorUserId, Instant revokedAt) { return 0; }
    }
}
