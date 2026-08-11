package com.example.dormitory.ai.security;

import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.application.run.AiActorResolver;
import com.example.dormitory.ai.config.AiProperties;
import com.example.dormitory.ai.port.SessionValidityPort;
import com.example.dormitory.common.BusinessException;
import com.example.dormitory.domain.RepairOrder;
import com.example.dormitory.service.RbacService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RepairAndSessionSecurityAdaptersTest {

    private static final String SESSION_HASH = "a".repeat(64);
    private static final String PERMISSION_DIGEST = "b".repeat(64);

    @Test
    void repairAccessRequiresEnabledReadPermissionAndHidesOtherRepairerOrders() {
        RepairAccessPolicy policy = new RepairAccessPolicy();
        RepairAccessPolicy.RepairActorAccess repairer = actor(7, true, Set.of("REPAIRER"), Set.of("repair:read"));
        RepairOrder own = order(7L);
        RepairOrder other = order(8L);

        assertDoesNotThrow(() -> policy.requireRead(repairer));
        assertDoesNotThrow(() -> policy.requireOrderRead(repairer, own));
        BusinessException hidden = assertThrows(BusinessException.class,
                () -> policy.requireOrderRead(repairer, other));
        assertEquals(404, hidden.getStatus().value());

        BusinessException missing = assertThrows(BusinessException.class,
                () -> policy.requireOrderRead(repairer, null));
        assertEquals(404, missing.getStatus().value());
    }

    @Test
    void repairAccessAllowsAdminOrUnrestrictedReaderAndRejectsInvalidActors() {
        RepairAccessPolicy policy = new RepairAccessPolicy();
        RepairOrder other = order(99L);
        RepairAccessPolicy.RepairActorAccess adminRepairer = actor(
                7, true, Set.of("REPAIRER", "ADMIN"), Set.of("repair:read"));
        RepairAccessPolicy.RepairActorAccess viewer = actor(7, true, Set.of(), Set.of("repair:read"));

        assertFalse(policy.isRestrictedRepairer(adminRepairer));
        assertFalse(policy.isRestrictedRepairer(viewer));
        assertDoesNotThrow(() -> policy.requireOrderRead(adminRepairer, other));
        assertDoesNotThrow(() -> policy.requireOrderRead(viewer, other));

        assertEquals(403, assertThrows(BusinessException.class, () -> policy.requireRead(null))
                .getStatus().value());
        assertEquals(403, assertThrows(BusinessException.class,
                () -> policy.requireRead(actor(7, false, Set.of(), Set.of("repair:read"))))
                .getStatus().value());
        assertEquals(403, assertThrows(BusinessException.class,
                () -> policy.requireRead(actor(7, true, null, null))).getStatus().value());
        assertThrows(IllegalArgumentException.class,
                () -> new RepairAccessPolicy.RepairActorAccess(0, true, Set.of(), Set.of()));
    }

    @Test
    void sessionValidityRejectsMalformedReferencesDisabledAccountsAndPermissionDrift() {
        RbacService rbac = mock(RbacService.class);
        AiActorResolver actors = mock(AiActorResolver.class);
        SessionFingerprintService fingerprints = mock(SessionFingerprintService.class);
        SaTokenSessionValidityAdapter adapter = new SaTokenSessionValidityAdapter(rbac, actors, fingerprints);
        List<SessionValidityPort.SessionReference> invalid = List.of(
                new SessionValidityPort.SessionReference(0, SESSION_HASH, 1, PERMISSION_DIGEST),
                new SessionValidityPort.SessionReference(7, SESSION_HASH, 0, PERMISSION_DIGEST),
                new SessionValidityPort.SessionReference(7, "A".repeat(64), 1, PERMISSION_DIGEST),
                new SessionValidityPort.SessionReference(7, SESSION_HASH, 1, "B".repeat(64)));

        assertEquals("INVALID_REFERENCE", adapter.check(null).safeReasonCode());
        for (SessionValidityPort.SessionReference reference : invalid) {
            SessionValidityPort.SessionValidity validity = adapter.check(reference);
            assertFalse(validity.valid());
            assertFalse(validity.accountEnabled());
            assertEquals("INVALID_REFERENCE", validity.safeReasonCode());
        }

        SessionValidityPort.SessionReference reference = reference();
        when(rbac.authorizationSnapshotForUser(7L)).thenReturn(snapshot(false, List.of()));
        assertEquals("ACCOUNT_DISABLED", adapter.check(reference).safeReasonCode());
        verify(actors, never()).permissionDigest(List.of());

        when(rbac.authorizationSnapshotForUser(7L)).thenReturn(snapshot(true, List.of("repair:read")));
        when(actors.permissionDigest(List.of("repair:read"))).thenReturn("c".repeat(64));
        SessionValidityPort.SessionValidity drifted = adapter.check(reference);
        assertFalse(drifted.valid());
        assertTrue(drifted.accountEnabled());
        assertEquals("PERMISSION_SNAPSHOT_CHANGED", drifted.safeReasonCode());
    }

    @Test
    void sessionValiditySkipsRotatedBrokenAndMismatchedTokensUntilExactCandidateMatches() {
        RbacService rbac = mock(RbacService.class);
        AiActorResolver actors = mock(AiActorResolver.class);
        SessionFingerprintService fingerprints = mock(SessionFingerprintService.class);
        SaTokenSessionValidityAdapter adapter = new SaTokenSessionValidityAdapter(rbac, actors, fingerprints);
        when(rbac.authorizationSnapshotForUser(7L)).thenReturn(snapshot(true, List.of("repair:read")));
        when(actors.permissionDigest(List.of("repair:read"))).thenReturn(PERMISSION_DIGEST);
        when(fingerprints.fingerprint("removed")).thenThrow(new IllegalStateException("removed"));
        when(fingerprints.fingerprint("old-key"))
                .thenReturn(new SessionFingerprintService.Fingerprint(SESSION_HASH, 2));
        when(fingerprints.fingerprint("wrong-hash"))
                .thenReturn(new SessionFingerprintService.Fingerprint("c".repeat(64), 1));
        when(fingerprints.fingerprint("wrong-user"))
                .thenReturn(new SessionFingerprintService.Fingerprint(SESSION_HASH, 1));
        when(fingerprints.fingerprint("valid"))
                .thenReturn(new SessionFingerprintService.Fingerprint(SESSION_HASH, 1));
        StpLogic logic = mock(StpLogic.class);
        when(logic.getTokenValueListByLoginId(7L)).thenReturn(
                List.of("removed", "old-key", "wrong-hash", "wrong-user", "valid"));
        when(logic.getLoginIdByToken("wrong-user")).thenReturn(8L);
        when(logic.getLoginIdByToken("valid")).thenReturn(7L);

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getStpLogic).thenReturn(logic);
            SessionValidityPort.SessionValidity validity = adapter.check(reference());
            assertTrue(validity.valid());
            assertTrue(validity.accountEnabled());
            assertEquals("VALID", validity.safeReasonCode());
        }
    }

    @Test
    void sessionValidityReturnsRevokedWhenNoExactLiveTokenRemains() {
        RbacService rbac = mock(RbacService.class);
        AiActorResolver actors = mock(AiActorResolver.class);
        SessionFingerprintService fingerprints = mock(SessionFingerprintService.class);
        SaTokenSessionValidityAdapter adapter = new SaTokenSessionValidityAdapter(rbac, actors, fingerprints);
        when(rbac.authorizationSnapshotForUser(7L)).thenReturn(snapshot(true, List.of()));
        when(actors.permissionDigest(List.of())).thenReturn(PERMISSION_DIGEST);
        when(fingerprints.fingerprint("wrong-user"))
                .thenReturn(new SessionFingerprintService.Fingerprint(SESSION_HASH, 1));
        StpLogic logic = mock(StpLogic.class);
        when(logic.getTokenValueListByLoginId(7L)).thenReturn(List.of("wrong-user"));
        when(logic.getLoginIdByToken("wrong-user")).thenReturn("not-a-user-id");

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getStpLogic).thenReturn(logic);
            SessionValidityPort.SessionValidity validity = adapter.check(reference());
            assertFalse(validity.valid());
            assertTrue(validity.accountEnabled());
            assertEquals("SESSION_REVOKED", validity.safeReasonCode());
        }
    }

    @Test
    void rateLimitIdentityRequiresLoginRemoteAddressAndConfiguredHmac() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        AiProperties valid = properties("0123456789abcdef0123456789abcdef", 1);
        SaTokenAiRateLimitIdentityResolver resolver = new SaTokenAiRateLimitIdentityResolver(valid);

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::isLogin).thenReturn(false);
            AiApiException unauthenticated = assertThrows(AiApiException.class, () -> resolver.resolve(request));
            assertEquals("AI_AUTHENTICATION_REQUIRED", unauthenticated.errorCode());
        }

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::isLogin).thenReturn(true);
            when(request.getRemoteAddr()).thenReturn(null, " ");
            assertEquals("AI_RATE_LIMIT_IDENTITY_UNAVAILABLE",
                    assertThrows(AiApiException.class, () -> resolver.resolve(request)).errorCode());
            assertEquals("AI_RATE_LIMIT_IDENTITY_UNAVAILABLE",
                    assertThrows(AiApiException.class, () -> resolver.resolve(request)).errorCode());
        }

        for (AiProperties invalid : List.of(properties("short", 1),
                properties("0123456789abcdef0123456789abcdef", 0))) {
            SaTokenAiRateLimitIdentityResolver invalidResolver =
                    new SaTokenAiRateLimitIdentityResolver(invalid);
            try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
                stp.when(StpUtil::isLogin).thenReturn(true);
                stp.when(StpUtil::getLoginId).thenReturn(7L);
                when(request.getRemoteAddr()).thenReturn("127.0.0.1");
                assertEquals("AI_TOKENIZATION_CONTROL_UNAVAILABLE",
                        assertThrows(AiApiException.class, () -> invalidResolver.resolve(request)).errorCode());
            }
        }
    }

    @Test
    void rateLimitIdentityIsDeterministicDomainSeparatedAndContainsNoRawIdentity() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("7");
        SaTokenAiRateLimitIdentityResolver resolver = new SaTokenAiRateLimitIdentityResolver(
                properties("0123456789abcdef0123456789abcdef", 1));

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::isLogin).thenReturn(true);
            stp.when(StpUtil::getLoginId).thenReturn(7L);
            AiRateLimitIdentityResolver.Identity first = resolver.resolve(request);
            AiRateLimitIdentityResolver.Identity second = resolver.resolve(request);

            assertEquals(first, second);
            assertTrue(first.actorKey().matches("[0-9a-f]{64}"));
            assertTrue(first.ipKey().matches("[0-9a-f]{64}"));
            assertNotEquals(first.actorKey(), first.ipKey());
            assertFalse(first.actorKey().contains("7".repeat(8)));
        }
    }

    private RepairAccessPolicy.RepairActorAccess actor(
            long userId, boolean enabled, Set<String> roles, Set<String> permissions) {
        return new RepairAccessPolicy.RepairActorAccess(userId, enabled, roles, permissions);
    }

    private RepairOrder order(Long assignee) {
        return new RepairOrder(1L, "WX-1", "报修人", "101室", "水电", "2026-07-13",
                "待处理", "灯具故障", assignee);
    }

    private SessionValidityPort.SessionReference reference() {
        return new SessionValidityPort.SessionReference(7, SESSION_HASH, 1, PERMISSION_DIGEST);
    }

    private RbacService.AuthorizationSnapshot snapshot(boolean enabled, List<String> permissions) {
        return new RbacService.AuthorizationSnapshot(7L, enabled, List.of("ADMIN"), permissions);
    }

    private AiProperties properties(String key, int version) {
        AiProperties properties = new AiProperties();
        properties.getTokenization().setHmacKey(key);
        properties.getTokenization().setActiveKeyVersion(version);
        return properties;
    }
}
