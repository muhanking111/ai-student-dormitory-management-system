package com.example.dormitory.ai.audit;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiAuditAnchorConfigurationClockTest {

    @Test
    void allowsAFixedBusinessClockOnlyInDevelopmentOrTestProfiles() throws Exception {
        MockEnvironment development = new MockEnvironment()
                .withProperty("dormitory.ai.business-clock.fixed-instant", "2026-08-03T04:00:00Z")
                .withProperty("dormitory.ai.business-clock.zone", "Asia/Shanghai");
        development.setActiveProfiles("dev");

        Clock clock = invokeClockFactory(development);

        assertEquals(Instant.parse("2026-08-03T04:00:00Z"), clock.instant());
        assertEquals(ZoneId.of("Asia/Shanghai"), clock.getZone());
    }

    @Test
    void rejectsAFixedBusinessClockOutsideDevelopmentAndTestProfiles() throws Exception {
        MockEnvironment production = new MockEnvironment()
                .withProperty("dormitory.ai.business-clock.fixed-instant", "2026-08-03T04:00:00Z");
        production.setActiveProfiles("prod");
        Method factory = clockFactory();

        InvocationTargetException error = assertThrows(InvocationTargetException.class,
                () -> factory.invoke(new AiAuditAnchorConfiguration(), production));

        assertInstanceOf(IllegalStateException.class, error.getCause());
    }

    private Clock invokeClockFactory(Environment environment) throws Exception {
        return (Clock) clockFactory().invoke(new AiAuditAnchorConfiguration(), environment);
    }

    private Method clockFactory() throws NoSuchMethodException {
        Method method = AiAuditAnchorConfiguration.class.getDeclaredMethod("aiSystemClock", Environment.class);
        method.setAccessible(true);
        return method;
    }
}
