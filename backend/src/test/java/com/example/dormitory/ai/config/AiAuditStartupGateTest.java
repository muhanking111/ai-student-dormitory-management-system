package com.example.dormitory.ai.config;

import com.example.dormitory.ai.infrastructure.runtime.AiRuntimeAuditWriter;
import com.example.dormitory.ai.infrastructure.persistence.AiModelAttemptRecovery;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.annotation.Order;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiAuditStartupGateTest {

    @Test
    void enabledAiFailsStartupWhenAuditIsNotReadyButDisabledAiDoesNotBlockOriginalBusiness() {
        AiProperties properties = new AiProperties();
        AiRuntimeAuditWriter audit = mock(AiRuntimeAuditWriter.class);
        AiAuditStartupGate gate = new AiAuditStartupGate(properties, audit);
        ApplicationArguments args = mock(ApplicationArguments.class);

        properties.setEnabled(false);
        assertDoesNotThrow(() -> gate.run(args));

        properties.setEnabled(true);
        when(audit.startupReady()).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> gate.run(args));
        when(audit.startupReady()).thenReturn(true);
        assertDoesNotThrow(() -> gate.run(args));
    }

    @Test
    void safetyGatesAreOrderedBeforeAnyProviderAttemptRecoveryWrite() {
        Order audit = AiAuditStartupGate.class.getAnnotation(Order.class);
        Order production = ProductionAiSecurityGate.class.getAnnotation(Order.class);
        Order recovery = AiModelAttemptRecovery.class.getAnnotation(Order.class);

        assertNotNull(audit);
        assertNotNull(production);
        assertNotNull(recovery);
        assertTrue(audit.value() < production.value());
        assertTrue(production.value() < recovery.value());
    }
}
