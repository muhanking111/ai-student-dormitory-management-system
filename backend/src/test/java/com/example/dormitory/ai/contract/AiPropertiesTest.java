package com.example.dormitory.ai.contract;

import com.example.dormitory.ai.config.AiProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

class AiPropertiesTest {

    @Test
    void allAiAndBusinessExecutionAreDisabledByDefault() {
        AiProperties properties = new AiProperties();

        assertFalse(properties.isEnabled());
        assertEquals("none", properties.getProviderActive());
        assertFalse(properties.isWriteExecutionEnabled());
        assertEquals(Duration.ofMinutes(5), properties.getStepUp().getProofTtl());
        assertEquals("", properties.getStepUp().getHmacKey());
    }

    @Test
    void providerAndWriteExecutionSettersNormalizeAndEnforceBounds() {
        AiProperties properties = new AiProperties();
        properties.getProvider().setActive(null);
        assertEquals("none", properties.getProvider().getActive());
        properties.getProvider().setActive("  SPRING-AI  ");
        assertEquals("spring-ai", properties.getProvider().getActive());
        assertThrows(IllegalArgumentException.class,
                () -> properties.getProvider().setActive("dynamic-provider"));

        properties.getProvider().setModelAlias(null);
        assertEquals("", properties.getProvider().getModelAlias());
        properties.getProvider().setModelAlias(" approved-model ");
        assertEquals("approved-model", properties.getProvider().getModelAlias());
        for (String invalid : List.of("?", "a", "model alias", "x".repeat(129))) {
            assertThrows(IllegalArgumentException.class,
                    () -> properties.getProvider().setModelAlias(invalid));
        }

        properties.getWriteExecution().setLeaseTimeout(Duration.ofNanos(1));
        properties.getWriteExecution().setLeaseTimeout(Duration.ofHours(1));
        for (Duration invalid : Arrays.asList(null, Duration.ZERO, Duration.ofSeconds(-1),
                Duration.ofHours(1).plusNanos(1))) {
            assertThrows(IllegalArgumentException.class,
                    () -> properties.getWriteExecution().setLeaseTimeout(invalid));
        }
    }

    @Test
    void rolloutSettersRejectUnsafePercentagesPolicyVersionsAndPrincipals() {
        AiProperties.Rollout rollout = new AiProperties().getRollout();
        rollout.setBasisPoints(0);
        rollout.setBasisPoints(10_000);
        assertThrows(IllegalArgumentException.class, () -> rollout.setBasisPoints(-1));
        assertThrows(IllegalArgumentException.class, () -> rollout.setBasisPoints(10_001));

        rollout.setPolicyVersion(" rollout-v2 ");
        assertEquals("rollout-v2", rollout.getPolicyVersion());
        for (String invalid : Arrays.asList(null, "", "v1", "bad policy", "x".repeat(65))) {
            assertThrows(IllegalArgumentException.class, () -> rollout.setPolicyVersion(invalid));
        }

        rollout.setInternalUserIds(null);
        assertTrue(rollout.getInternalUserIds().isEmpty());
        rollout.setInternalUserIds(Set.of(1L, 2L));
        assertEquals(Set.of(1L, 2L), rollout.getInternalUserIds());
        assertThrows(IllegalArgumentException.class, () -> rollout.setInternalUserIds(Set.of(0L)));
        java.util.HashSet<Long> withNull = new java.util.HashSet<>();
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> rollout.setInternalUserIds(withNull));
        java.util.HashSet<Long> tooMany = new java.util.HashSet<>();
        for (long value = 1; value <= 10_001; value++) tooMany.add(value);
        assertThrows(IllegalArgumentException.class, () -> rollout.setInternalUserIds(tooMany));
    }
}
