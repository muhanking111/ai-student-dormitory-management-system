package com.example.dormitory.ai.rollout;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.config.AiProperties;
import com.example.dormitory.ai.domain.model.AiCapability;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiRolloutGateTest {

    @Test
    void defaultDisabledPolicyIncludesNobody() {
        AiRolloutGate gate = new AiRolloutGate(new AiProperties());

        AiApiException failure = assertThrows(AiApiException.class,
                () -> gate.requireIncluded(7L, AiCapability.ASSISTANT));

        assertEquals("AI_ROLLOUT_NOT_INCLUDED", failure.errorCode());
    }

    @Test
    void internalAllowlistPrecedesPercentageCohort() {
        AiProperties properties = new AiProperties();
        properties.getRollout().setEnabled(true);
        properties.getRollout().setBasisPoints(0);
        properties.getRollout().setPolicyVersion("rollout-policy-v1");
        properties.getRollout().setInternalUserIds(Set.of(7L));
        AiRolloutGate gate = new AiRolloutGate(properties);

        AiRolloutGate.Decision included = gate.requireIncluded(7L, AiCapability.RISK);

        assertTrue(included.internalAllowlist());
        assertThrows(AiApiException.class, () -> gate.requireIncluded(8L, AiCapability.RISK));
    }

    @Test
    void authorizedPercentageUsesStableHmacCohort() {
        AiProperties properties = new AiProperties();
        properties.getRollout().setEnabled(true);
        properties.getRollout().setBasisPoints(10_000);
        properties.getRollout().setPolicyVersion("rollout-policy-v1");
        properties.getRollout().setHmacKey("rollout-test-key-with-at-least-32-bytes");
        AiRolloutGate gate = new AiRolloutGate(properties);

        AiRolloutGate.Decision first = gate.requireIncluded(42L, AiCapability.DASHBOARD);
        AiRolloutGate.Decision second = gate.requireIncluded(42L, AiCapability.DASHBOARD);

        assertEquals(first.bucket(), second.bucket());
        assertEquals(10_000, first.basisPoints());
    }
}
