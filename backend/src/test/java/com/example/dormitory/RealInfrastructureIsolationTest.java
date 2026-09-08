package com.example.dormitory;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RealInfrastructureIsolationTest {
    @Test
    void permitsOnlyDedicatedLoopbackDatabaseAndNonDefaultRedisDatabase() {
        assertDoesNotThrow(() -> RealInfrastructureIT.RootEnvInitializer.requireDedicatedTargets(Map.of(
                "DB_URL", "jdbc:mysql://127.0.0.1:3307/remediation_e2e?useSSL=false",
                "REDIS_HOST", "127.0.0.1", "REDIS_PORT", "6380", "REDIS_DATABASE", "10")));
    }

    @Test
    void rejectsOrdinaryDatabaseAndDefaultRedisDatabase() {
        assertThrows(IllegalStateException.class, () -> RealInfrastructureIT.RootEnvInitializer.requireDedicatedTargets(Map.of(
                "DB_URL", "jdbc:mysql://127.0.0.1:3306/student_dormitory",
                "REDIS_HOST", "127.0.0.1", "REDIS_PORT", "6380", "REDIS_DATABASE", "10")));
        assertThrows(IllegalStateException.class, () -> RealInfrastructureIT.RootEnvInitializer.requireDedicatedTargets(Map.of(
                "DB_URL", "jdbc:mysql://127.0.0.1:3307/remediation_e2e",
                "REDIS_HOST", "127.0.0.1", "REDIS_PORT", "6380", "REDIS_DATABASE", "0")));
    }

    @Test
    void rejectsRemoteTargetsEvenWhenDatabaseNameLooksLikeTest() {
        assertThrows(IllegalStateException.class, () -> RealInfrastructureIT.RootEnvInitializer.requireDedicatedTargets(Map.of(
                "DB_URL", "jdbc:mysql://remote.example:3307/remediation_e2e",
                "REDIS_HOST", "127.0.0.1", "REDIS_PORT", "6380", "REDIS_DATABASE", "10")));
    }
}
