package com.example.dormitory.ai.security;

import java.time.Instant;

public interface StepUpGrantRevocationPort {

    int revokeSession(long actorUserId, String sessionFingerprintHash, Instant revokedAt);

    int revokeActor(long actorUserId, Instant revokedAt);
}
