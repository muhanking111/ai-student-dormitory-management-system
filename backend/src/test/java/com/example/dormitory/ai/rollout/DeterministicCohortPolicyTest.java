package com.example.dormitory.ai.rollout;

import com.example.dormitory.ai.domain.model.AiCapability;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicCohortPolicyTest {

    @Test
    void defaultPolicyIsZeroPercentAndOff() {
        DeterministicCohortPolicy policy = new DeterministicCohortPolicy();

        DeterministicCohortPolicy.Decision decision = policy.decide(42L, AiCapability.ASSISTANT);

        assertFalse(decision.included());
        assertEquals(-1, decision.bucket());
        assertEquals("ROLLOUT_DISABLED", decision.reasonCode());
        assertEquals(0, decision.basisPoints());
    }

    @Test
    void enabledPolicyUsesStableServerSideHmacBucket() {
        byte[] key = "rollout-test-key-that-is-at-least-32-bytes".getBytes(StandardCharsets.UTF_8);
        var configuration = DeterministicCohortPolicy.Configuration.enabled(2_500, "rollout-policy-v1", key);

        var first = new DeterministicCohortPolicy(configuration).decide(42L, AiCapability.DASHBOARD);
        var second = new DeterministicCohortPolicy(configuration).decide(42L, AiCapability.DASHBOARD);

        assertEquals(first, second);
        assertTrue(first.bucket() >= 0 && first.bucket() < 10_000);
        assertEquals(first.bucket() < 2_500, first.included());
        assertEquals("rollout-policy-v1", first.policyVersion());
    }

    @Test
    void fullCohortStillRequiresAValidKeyAndNeverActsAsAuthorization() {
        byte[] key = "rollout-test-key-that-is-at-least-32-bytes".getBytes(StandardCharsets.UTF_8);
        var policy = new DeterministicCohortPolicy(
                DeterministicCohortPolicy.Configuration.enabled(10_000, "rollout-policy-v1", key));

        assertTrue(policy.decide(7L, AiCapability.KNOWLEDGE).included());
        assertThrows(IllegalArgumentException.class, () -> DeterministicCohortPolicy.Configuration.enabled(
                100, "rollout-policy-v1", "short-key".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> policy.decide(0L, AiCapability.KNOWLEDGE));
    }
}
