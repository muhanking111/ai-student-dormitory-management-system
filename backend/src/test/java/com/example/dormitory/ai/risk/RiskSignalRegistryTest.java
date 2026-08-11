package com.example.dormitory.ai.risk;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskSignalRegistryTest {

    @Test
    void deduplicatesDeterministicSignalsAndPreservesVersionedEvidence() {
        RiskSignal duplicate = new RiskSignal(
                "repair-backlog", "REPAIR_ORDER", 42L, "subject-token-42", "HIGH",
                "repair-backlog-v1", Map.of("ageHours", 96, "status", "待处理"),
                Instant.parse("2026-07-11T08:00:00Z"));
        RiskSignalRegistry registry = new RiskSignalRegistry(List.of(
                provider("repair-backlog", "repair-backlog-v1", List.of(duplicate)),
                provider("repair-backlog-shadow", "repair-backlog-v1", List.of(duplicate))));

        RiskSignalRegistry.ScanResult result = registry.scan(RiskScanScope.full(
                1L, java.util.Set.of("repair:read", "dormitory:read", "checkin:read"), Instant.now()));

        assertEquals(1, result.signals().size());
        assertEquals(1, result.duplicateCount());
        assertEquals("repair-backlog-v1", result.signals().getFirst().policyVersion());
        assertEquals(96, result.signals().getFirst().evidence().get("ageHours"));
        assertTrue(result.signals().getFirst().dedupKey().matches("[0-9a-f]{64}"));
    }

    @Test
    void rejectsDynamicOrSensitiveEvidenceAndDuplicateProviderIds() {
        assertThrows(IllegalArgumentException.class, () -> new RiskSignalRegistry(List.of(
                provider("same", "v1", List.of()), provider("same", "v2", List.of()))));
        assertThrows(IllegalArgumentException.class, () -> new RiskSignal(
                "bad", "REPAIR_ORDER", 1L, "token", "HIGH", "v1",
                Map.of("sql", "select * from student"), Instant.now()));
        assertThrows(IllegalArgumentException.class, () -> new RiskSignal(
                "bad", "REPAIR_ORDER", 1L, "13800138000", "HIGH", "v1",
                Map.of("status", "待处理"), Instant.now()));
    }

    @Test
    void isolatesUnavailableProviderAndKeepsDeterministicSignalsFromHealthyProviders() {
        RiskSignal healthy = new RiskSignal(
                "repair-backlog", "REPAIR_ORDER", 42L, "subject-token-42", "HIGH",
                "repair-backlog-v1", Map.of("ageHours", 96, "status", "待处理"),
                Instant.parse("2026-07-11T08:00:00Z"));
        RiskSignalProvider unavailable = new RiskSignalProvider() {
            public String id() { return "unavailable"; }
            public String policyVersion() { return "unavailable-v1"; }
            public List<RiskSignal> evaluate(RiskScanScope scope) {
                throw new IllegalStateException("database temporarily unavailable");
            }
        };

        RiskSignalRegistry.ScanResult result = new RiskSignalRegistry(List.of(
                unavailable, provider("healthy", "repair-backlog-v1", List.of(healthy)))).scan(
                        RiskScanScope.full(1L, java.util.Set.of("repair:read"), Instant.now()));

        assertEquals(1, result.signals().size());
        assertEquals(List.of("unavailable@unavailable-v1"), result.unavailableProviders());
    }

    @Test
    void signalContractRejectsEveryUntrustedIdentityAndEvidenceShape() {
        Instant now = Instant.now();
        Map<String, Object> evidence = Map.of("status", "待处理");
        assertThrows(IllegalArgumentException.class,
                () -> new RiskSignal("unknown", "REPAIR_ORDER", 1L, "subject-token", "HIGH", "v1",
                        evidence, now));
        assertThrows(IllegalArgumentException.class,
                () -> new RiskSignal("repair-backlog", "UNKNOWN", 1L, "subject-token", "HIGH", "v1",
                        evidence, now));
        assertThrows(IllegalArgumentException.class,
                () -> new RiskSignal("repair-backlog", "REPAIR_ORDER", null, "subject-token", "HIGH", "v1",
                        evidence, now));
        assertThrows(IllegalArgumentException.class,
                () -> new RiskSignal("repair-backlog", "REPAIR_ORDER", 0L, "subject-token", "HIGH", "v1",
                        evidence, now));
        for (String token : java.util.Arrays.asList(null, "short", "1bad-token", "13800138000")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new RiskSignal("repair-backlog", "REPAIR_ORDER", 1L, token, "HIGH", "v1",
                            evidence, now));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new RiskSignal("repair-backlog", "REPAIR_ORDER", 1L, "subject-token", "UNKNOWN", "v1",
                        evidence, now));
        for (String version : java.util.Arrays.asList(null, "x", "bad version", "x".repeat(65))) {
            assertThrows(IllegalArgumentException.class,
                    () -> new RiskSignal("repair-backlog", "REPAIR_ORDER", 1L, "subject-token", "HIGH", version,
                            evidence, now));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new RiskSignal("repair-backlog", "REPAIR_ORDER", 1L, "subject-token", "HIGH", "v1",
                        null, now));
        assertThrows(IllegalArgumentException.class,
                () -> new RiskSignal("repair-backlog", "REPAIR_ORDER", 1L, "subject-token", "HIGH", "v1",
                        Map.of(), now));
        assertThrows(IllegalArgumentException.class,
                () -> new RiskSignal("repair-backlog", "REPAIR_ORDER", 1L, "subject-token", "HIGH", "v1",
                        Map.of("dynamic", "value"), now));
        assertThrows(IllegalArgumentException.class,
                () -> new RiskSignal("repair-backlog", "REPAIR_ORDER", 1L, "subject-token", "HIGH", "v1",
                        evidence, null));
        assertThrows(IllegalArgumentException.class,
                () -> new RiskSignal("repair-backlog", "REPAIR_ORDER", 1L, "subject-token", "HIGH", "v1",
                        Map.of("status", List.of("not", "scalar")), now));

        Map<String, Object> mutable = new HashMap<>();
        mutable.put("status", "待处理");
        RiskSignal signal = new RiskSignal("repair-backlog", "REPAIR_ORDER", 1L,
                "subject-token", "HIGH", "v1", mutable, now);
        mutable.clear();
        assertEquals(Map.of("status", "待处理"), signal.evidence());
        assertThrows(UnsupportedOperationException.class,
                () -> signal.evidence().put("count", 1));
    }

    private RiskSignalProvider provider(String id, String version, List<RiskSignal> signals) {
        return new RiskSignalProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String policyVersion() {
                return version;
            }

            @Override
            public List<RiskSignal> evaluate(RiskScanScope scope) {
                return signals;
            }
        };
    }
}
