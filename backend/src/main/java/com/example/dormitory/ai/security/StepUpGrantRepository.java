package com.example.dormitory.ai.security;

import java.time.Instant;

public interface StepUpGrantRepository extends StepUpGrantRevocationPort {

    void create(NewGrant grant);

    boolean consume(ConsumeGrant grant);

    record NewGrant(
            String publicId,
            String tokenHmac,
            int tokenKeyVersion,
            String sessionFingerprintHash,
            int sessionFingerprintKeyVersion,
            long actorUserId,
            String actionCode,
            String resourcePublicId,
            String requestHash,
            String authMethod,
            Instant authenticatedAt,
            Instant expiresAt) {
    }

    record ConsumeGrant(
            String tokenHmac,
            int tokenKeyVersion,
            String sessionFingerprintHash,
            int sessionFingerprintKeyVersion,
            long actorUserId,
            String actionCode,
            String resourcePublicId,
            String requestHash,
            Instant consumedAt) {
    }
}
