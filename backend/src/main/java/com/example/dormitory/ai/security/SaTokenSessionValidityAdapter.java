package com.example.dormitory.ai.security;

import cn.dev33.satoken.stp.StpUtil;
import com.example.dormitory.ai.application.run.AiActorResolver;
import com.example.dormitory.ai.port.SessionValidityPort;
import com.example.dormitory.service.RbacService;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class SaTokenSessionValidityAdapter implements SessionValidityPort {

    private final RbacService rbacService;
    private final AiActorResolver actorResolver;
    private final SessionFingerprintService fingerprints;

    public SaTokenSessionValidityAdapter(
            RbacService rbacService,
            AiActorResolver actorResolver,
            SessionFingerprintService fingerprints) {
        this.rbacService = rbacService;
        this.actorResolver = actorResolver;
        this.fingerprints = fingerprints;
    }

    @Override
    public SessionValidity check(SessionReference reference) {
        if (reference == null || reference.userId() < 1 || reference.sessionFingerprintKeyVersion() < 1
                || !hash(reference.sessionFingerprintHash()) || !hash(reference.permissionDigest())) {
            return new SessionValidity(false, false, "INVALID_REFERENCE");
        }
        RbacService.AuthorizationSnapshot snapshot = rbacService.authorizationSnapshotForUser(reference.userId());
        if (!snapshot.enabled()) return new SessionValidity(false, false, "ACCOUNT_DISABLED");
        String currentPermissionDigest = actorResolver.permissionDigest(snapshot.permissionCodes());
        if (!constantTimeEquals(reference.permissionDigest(), currentPermissionDigest)) {
            return new SessionValidity(false, true, "PERMISSION_SNAPSHOT_CHANGED");
        }
        for (String token : StpUtil.getStpLogic().getTokenValueListByLoginId(reference.userId())) {
            try {
                SessionFingerprintService.Fingerprint fingerprint = fingerprints.fingerprint(token);
                if (fingerprint.keyVersion() == reference.sessionFingerprintKeyVersion()
                        && constantTimeEquals(reference.sessionFingerprintHash(), fingerprint.hash())
                        && reference.userId() == Long.parseLong(
                                StpUtil.getStpLogic().getLoginIdByToken(token).toString())) {
                    return new SessionValidity(true, true, "VALID");
                }
            } catch (RuntimeException ignored) {
                // A removed/rotated token is simply not a valid candidate; no raw token is surfaced.
            }
        }
        return new SessionValidity(false, true, "SESSION_REVOKED");
    }

    private static boolean hash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }
}
