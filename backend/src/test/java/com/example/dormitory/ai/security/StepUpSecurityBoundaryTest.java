package com.example.dormitory.ai.security;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.application.run.AiActorContext;
import com.example.dormitory.ai.config.AiProperties;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.port.SessionValidityPort;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepUpSecurityBoundaryTest {

    private static final Instant NOW = Instant.parse("2026-07-13T12:00:00Z");
    private static final String OLD_KEY = "0123456789abcdef0123456789abcdef";
    private static final String NEW_KEY = "abcdef0123456789abcdef0123456789";
    private static final AuthenticatedRunContext USER = new AuthenticatedRunContext(
            ActorDescriptor.user(7L), "a".repeat(64), 1, "b".repeat(64));

    @Test
    void cryptoGeneratesOpaqueProofsAndAcceptsConfiguredRotationKeyring() {
        StepUpProofCrypto oldCrypto = StepUpProofCrypto.forTesting(1, OLD_KEY);
        StepUpProofCrypto.GeneratedProof old = oldCrypto.generate();
        StepUpProofCrypto.GeneratedProof another = oldCrypto.generate();
        AiProperties properties = stepUpProperties(2, NEW_KEY, 1, OLD_KEY);
        StepUpProofCrypto rotating = new StepUpProofCrypto(properties);

        StepUpProofCrypto.ParsedProof parsed = rotating.parseAndHash(old.proof());

        assertEquals(1, parsed.keyVersion());
        assertEquals(old.hmac(), parsed.hmac());
        assertTrue(old.proof().matches("sup1\\.1\\.[A-Za-z0-9_-]{43}"));
        assertTrue(old.hmac().matches("[0-9a-f]{64}"));
        assertNotEquals(old.proof(), another.proof());
        assertFalse(old.proof().contains(OLD_KEY));
        assertEquals(2, rotating.generate().keyVersion());
    }

    @Test
    void cryptoRejectsMalformedUnknownAndShortKeyProofsWithStableSafeCode() {
        StepUpProofCrypto crypto = StepUpProofCrypto.forTesting(1, OLD_KEY);
        String opaque = "A".repeat(43);
        List<String> malformed = java.util.Arrays.asList(
                null,
                "x".repeat(161),
                "",
                "sup1.1",
                "sup2.1." + opaque,
                "sup1.0." + opaque,
                "sup1.01." + opaque,
                "sup1.1000000000." + opaque,
                "sup1.1." + "A".repeat(42),
                "sup1.1." + "A".repeat(44),
                "sup1.1." + "A".repeat(42) + ".x");

        for (String proof : malformed) {
            AiApiException error = assertThrows(AiApiException.class, () -> crypto.parseAndHash(proof));
            assertEquals("AI_STEP_UP_PROOF_INVALID", error.errorCode());
        }

        String syntacticallyValid = "sup1.1." + opaque;
        for (StepUpProofCrypto invalidKeyring : List.of(
                new StepUpProofCrypto(2, Map.of(2, NEW_KEY.getBytes(StandardCharsets.UTF_8)), new SecureRandom()),
                new StepUpProofCrypto(1, Map.of(1, "short".getBytes(StandardCharsets.UTF_8)), new SecureRandom()))) {
            assertEquals("AI_STEP_UP_PROOF_INVALID",
                    assertThrows(AiApiException.class,
                            () -> invalidKeyring.parseAndHash(syntacticallyValid)).errorCode());
        }
    }

    @Test
    void cryptoConfigurationFailsClosedForMissingActiveKeyAndAmbiguousRotation() {
        for (StepUpProofCrypto crypto : List.of(
                new StepUpProofCrypto(stepUpProperties(0, NEW_KEY, 0, "")),
                new StepUpProofCrypto(stepUpProperties(1, "", 0, "")),
                new StepUpProofCrypto(stepUpProperties(1, "short", 0, "")))) {
            assertEquals("AI_STEP_UP_KEY_UNAVAILABLE",
                    assertThrows(AiApiException.class, crypto::generate).errorCode());
        }

        AiProperties sameVersion = stepUpProperties(1, NEW_KEY, 1, OLD_KEY);
        assertThrows(IllegalArgumentException.class, () -> new StepUpProofCrypto(sameVersion));

        AiProperties blankPrevious = stepUpProperties(1, NEW_KEY, 1, "");
        assertEquals(1, new StepUpProofCrypto(blankPrevious).generate().keyVersion());
        AiProperties noPreviousVersion = stepUpProperties(1, NEW_KEY, 0, OLD_KEY);
        assertEquals(1, new StepUpProofCrypto(noPreviousVersion).generate().keyVersion());
    }

    @Test
    void policyNormalizesAndBindsIssuedProofToUserSessionRequestAndResource() {
        RecordingRepository repository = new RecordingRepository();
        RecentAuthenticationPolicy policy = policy(repository, validSession(), Duration.ofSeconds(90));

        RecentAuthenticationPolicy.IssuedProof issued = policy.issue(
                USER, "  config_activate ", " 11111111-1111-1111-1111-111111111111 ",
                "C".repeat(64));

        StepUpGrantRepository.NewGrant grant = repository.created;
        assertEquals("CONFIG_ACTIVATE", grant.actionCode());
        assertEquals("11111111-1111-1111-1111-111111111111", grant.resourcePublicId());
        assertEquals("c".repeat(64), grant.requestHash());
        assertEquals(7L, grant.actorUserId());
        assertEquals(USER.sessionFingerprintHash(), grant.sessionFingerprintHash());
        assertEquals(NOW.plusSeconds(90), grant.expiresAt());
        assertEquals("PASSWORD", issued.authMethod());
        assertEquals(grant.publicId(), issued.grantPublicId());
    }

    @Test
    void policyRejectsUnsupportedActionsMalformedResourcesAndRequestHashes() {
        RecentAuthenticationPolicy policy = policy(new RecordingRepository(), validSession(), Duration.ofMinutes(1));

        for (String action : java.util.Arrays.asList(null, "", "delete_all")) {
            assertEquals("AI_STEP_UP_ACTION_UNSUPPORTED",
                    assertThrows(AiApiException.class,
                            () -> policy.issue(USER, action, null, "c".repeat(64))).errorCode());
        }
        assertEquals("AI_STEP_UP_RESOURCE_INVALID",
                assertThrows(AiApiException.class,
                        () -> policy.issue(USER, "CONFIG_ACTIVATE", "not-a-uuid", "c".repeat(64)))
                        .errorCode());
        for (String hash : java.util.Arrays.asList(null, "", "c".repeat(63), "g".repeat(64))) {
            assertEquals("AI_STEP_UP_REQUEST_HASH_INVALID",
                    assertThrows(AiApiException.class,
                            () -> policy.issue(USER, "CONFIG_ACTIVATE", " ", hash)).errorCode());
        }
    }

    @Test
    void policyRejectsNullOrInvalidActorsInvalidSessionsAndReplayedProofs() {
        RecordingRepository repository = new RecordingRepository();
        RecentAuthenticationPolicy policy = policy(repository, validSession(), Duration.ofMinutes(1));
        assertEquals("AI_STEP_UP_USER_ACTOR_REQUIRED",
                assertThrows(AiApiException.class,
                        () -> policy.issue(null, "CONFIG_ACTIVATE", null, "c".repeat(64))).errorCode());

        SessionValidityPort disabledAccount = ignored ->
                new SessionValidityPort.SessionValidity(true, false, "ACCOUNT_DISABLED");
        RecentAuthenticationPolicy disabled = policy(repository, disabledAccount, Duration.ofMinutes(1));
        assertEquals("AI_STEP_UP_SESSION_INVALID",
                assertThrows(AiApiException.class,
                        () -> disabled.issue(USER, "CONFIG_ACTIVATE", null, "c".repeat(64))).errorCode());

        RecentAuthenticationPolicy invalidSession = policy(repository, ignored ->
                new SessionValidityPort.SessionValidity(false, true, "SESSION_REVOKED"), Duration.ofMinutes(1));
        assertEquals("AI_STEP_UP_SESSION_INVALID",
                assertThrows(AiApiException.class, () -> invalidSession.consume(
                        "sup1.1." + "A".repeat(43), USER, "CONFIG_ACTIVATE", null,
                        "c".repeat(64))).errorCode());

        repository.consumeResult = false;
        assertEquals("AI_STEP_UP_PROOF_INVALID",
                assertThrows(AiApiException.class, () -> policy.consume(
                        "sup1.1." + "A".repeat(43), USER, "CONFIG_ACTIVATE", null,
                        "C".repeat(64))).errorCode());
        assertEquals("c".repeat(64), repository.consumed.requestHash());
    }

    @Test
    void policyConstructorRejectsProofTtlOutsideStrictFiveMinuteWindow() {
        List<Duration> invalid = java.util.Arrays.asList(
                null, Duration.ZERO, Duration.ofNanos(-1), Duration.ofMinutes(5).plusNanos(1));
        for (Duration ttl : invalid) {
            assertThrows(IllegalArgumentException.class,
                    () -> policy(new RecordingRepository(), validSession(), ttl));
        }
        assertEquals(NOW.plus(Duration.ofNanos(1)),
                policy(new RecordingRepository(), validSession(), Duration.ofNanos(1))
                        .issue(USER, "CONFIG_ACTIVATE", null, "c".repeat(64)).expiresAt());
    }

    @Test
    void authenticatedRunContextRejectsMalformedSecurityBindingsAndCanCopyActorContext() {
        assertThrows(IllegalArgumentException.class,
                () -> new AuthenticatedRunContext(null, "a".repeat(64), 1, "b".repeat(64)));
        assertThrows(IllegalArgumentException.class,
                () -> new AuthenticatedRunContext(ActorDescriptor.user(7), "a".repeat(64), 0, "b".repeat(64)));
        assertThrows(IllegalArgumentException.class,
                () -> new AuthenticatedRunContext(ActorDescriptor.user(7), null, 1, "b".repeat(64)));
        assertThrows(IllegalArgumentException.class,
                () -> new AuthenticatedRunContext(ActorDescriptor.user(7), "A".repeat(64), 1, "b".repeat(64)));
        assertThrows(IllegalArgumentException.class,
                () -> new AuthenticatedRunContext(ActorDescriptor.user(7), "a".repeat(64), 1, null));
        assertThrows(IllegalArgumentException.class,
                () -> new AuthenticatedRunContext(ActorDescriptor.user(7), "a".repeat(64), 1, "B".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> AuthenticatedRunContext.from(null));

        AiActorContext actor = new AiActorContext(7, "session", "a".repeat(64), 1, "b".repeat(64),
                List.of("ADMIN"), List.of("repair:read"), ActorDescriptor.user(7));
        AuthenticatedRunContext copied = AuthenticatedRunContext.from(actor);
        assertEquals(7L, copied.requireUserActorId());
        assertEquals(actor.actor(), copied.actor());
    }

    private RecentAuthenticationPolicy policy(
            RecordingRepository repository, SessionValidityPort validity, Duration ttl) {
        return new RecentAuthenticationPolicy(repository, StepUpProofCrypto.forTesting(1, OLD_KEY), validity,
                Clock.fixed(NOW, ZoneOffset.UTC), ttl);
    }

    private SessionValidityPort validSession() {
        return ignored -> new SessionValidityPort.SessionValidity(true, true, "VALID");
    }

    private AiProperties stepUpProperties(
            int activeVersion, String activeKey, int previousVersion, String previousKey) {
        AiProperties properties = new AiProperties();
        properties.getStepUp().setActiveKeyVersion(activeVersion);
        properties.getStepUp().setHmacKey(activeKey);
        properties.getStepUp().setPreviousKeyVersion(previousVersion);
        properties.getStepUp().setPreviousHmacKey(previousKey);
        return properties;
    }

    private static final class RecordingRepository implements StepUpGrantRepository {
        private NewGrant created;
        private ConsumeGrant consumed;
        private boolean consumeResult = true;

        @Override public void create(NewGrant grant) { created = grant; }
        @Override public boolean consume(ConsumeGrant grant) { consumed = grant; return consumeResult; }
        @Override public int revokeSession(long actorUserId, String sessionFingerprintHash, Instant revokedAt) {
            return 0;
        }
        @Override public int revokeActor(long actorUserId, Instant revokedAt) { return 0; }
    }
}
