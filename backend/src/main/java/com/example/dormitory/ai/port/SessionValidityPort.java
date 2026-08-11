package com.example.dormitory.ai.port;

public interface SessionValidityPort {

    SessionValidity check(SessionReference reference);

    record SessionReference(
            long userId,
            String sessionFingerprintHash,
            int sessionFingerprintKeyVersion,
            String permissionDigest) {
    }

    record SessionValidity(boolean valid, boolean accountEnabled, String safeReasonCode) {
    }
}
