package com.example.dormitory.ai.risk;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskScanScopeTest {

    private static final Instant CAPTURED_AT = Instant.parse("2026-07-13T00:00:00Z");

    @Test
    void constructorRejectsInvalidSubjectResourceIdsAndUnauthorizedRanges() {
        assertThrows(IllegalArgumentException.class,
                () -> RiskScanScope.full(0, Set.of("repair:read"), CAPTURED_AT));
        assertThrows(IllegalArgumentException.class,
                () -> RiskScanScope.full(1, Set.of("repair:read"), null));
        assertThrows(IllegalArgumentException.class,
                () -> new RiskScanScope(1, Set.of(), true, Set.of(), false, Set.of(),
                        false, Set.of(), CAPTURED_AT));
        assertThrows(IllegalArgumentException.class,
                () -> new RiskScanScope(1, Set.of(), false, Set.of(), true, Set.of(),
                        false, Set.of(), CAPTURED_AT));
        assertThrows(IllegalArgumentException.class,
                () -> new RiskScanScope(1, Set.of(), false, Set.of(), false, Set.of(),
                        true, Set.of(), CAPTURED_AT));
        assertThrows(IllegalArgumentException.class,
                () -> new RiskScanScope(1, Set.of(), false, Set.of(), false, Set.of(),
                        true, Set.of(), false, Set.of(), CAPTURED_AT));

        assertThrows(IllegalArgumentException.class,
                () -> RiskScanScope.restricted(1, Set.of(), Set.of(1L), Set.of(), Set.of(), CAPTURED_AT));
        assertThrows(IllegalArgumentException.class,
                () -> RiskScanScope.restricted(1, Set.of(), Set.of(), Set.of(1L), Set.of(), CAPTURED_AT));
        assertThrows(IllegalArgumentException.class,
                () -> RiskScanScope.restricted(1, Set.of(), Set.of(), Set.of(), Set.of(1L), CAPTURED_AT));
        assertThrows(IllegalArgumentException.class,
                () -> RiskScanScope.restricted(1, Set.of(), Set.of(), Set.of(), Set.of(1L),
                        Set.of(), CAPTURED_AT));
        assertThrows(IllegalArgumentException.class,
                () -> RiskScanScope.restricted(1, Set.of("repair:read"), Set.of(0L),
                        Set.of(), Set.of(), CAPTURED_AT));
        HashSet<Long> withNull = new HashSet<>();
        withNull.add(null);
        assertThrows(IllegalArgumentException.class,
                () -> RiskScanScope.restricted(1, Set.of("repair:read"), withNull,
                        Set.of(), Set.of(), CAPTURED_AT));
    }

    @Test
    void fullScopeFiltersUntrustedPermissionsAndChecksEachResourceFamily() {
        RiskScanScope scope = RiskScanScope.full(7,
                Set.of("repair:read", "dormitory:read", "checkin:read", "hygiene:read",
                        "payment:read", "admin:*"), CAPTURED_AT);

        assertEquals(Set.of("repair:read", "dormitory:read", "checkin:read", "hygiene:read", "payment:read"),
                scope.permissionCodes());
        assertTrue(scope.canReadRepair(999));
        assertTrue(scope.canReadDormitory(999));
        assertTrue(scope.canReadPayment(999));
        assertTrue(scope.canReadCheckInApplication(999));

        RiskScanScope none = RiskScanScope.full(7, null, CAPTURED_AT);
        assertFalse(none.canReadRepair(1));
        assertFalse(none.canReadDormitory(1));
        assertFalse(none.canReadPayment(1));
        assertFalse(none.canReadCheckInApplication(1));
    }

    @Test
    void intersectionCoversAllRestrictedAndPermissionRevocationCombinations() {
        RiskScanScope requestedAll = RiskScanScope.full(7,
                Set.of("repair:read", "dormitory:read", "checkin:read", "payment:read"),
                CAPTURED_AT.minusSeconds(60));
        RiskScanScope currentRestricted = RiskScanScope.restricted(7,
                Set.of("repair:read", "dormitory:read", "checkin:read", "payment:read"),
                Set.of(1L, 2L), Set.of(3L), Set.of(5L), Set.of(4L), CAPTURED_AT);
        RiskScanScope allWithRestricted = requestedAll.intersect(currentRestricted);
        assertFalse(allWithRestricted.allRepairOrders());
        assertEquals(Set.of(1L, 2L), allWithRestricted.repairOrderIds());
        assertEquals(Set.of(3L), allWithRestricted.dormitoryIds());
        assertEquals(Set.of(5L), allWithRestricted.paymentIds());
        assertEquals(Set.of(4L), allWithRestricted.checkInApplicationIds());

        RiskScanScope requestedRestricted = RiskScanScope.restricted(7,
                Set.of("repair:read", "dormitory:read", "checkin:read", "payment:read"),
                Set.of(2L, 9L), Set.of(3L, 8L), Set.of(5L, 6L), Set.of(4L, 7L),
                CAPTURED_AT.minusSeconds(60));
        RiskScanScope currentAll = RiskScanScope.full(7,
                Set.of("repair:read", "dormitory:read", "checkin:read", "payment:read"), CAPTURED_AT);
        RiskScanScope restrictedWithAll = requestedRestricted.intersect(currentAll);
        assertEquals(Set.of(2L, 9L), restrictedWithAll.repairOrderIds());
        assertEquals(Set.of(3L, 8L), restrictedWithAll.dormitoryIds());
        assertEquals(Set.of(5L, 6L), restrictedWithAll.paymentIds());
        assertEquals(Set.of(4L, 7L), restrictedWithAll.checkInApplicationIds());

        RiskScanScope bothRestricted = requestedRestricted.intersect(currentRestricted);
        assertEquals(Set.of(2L), bothRestricted.repairOrderIds());
        assertEquals(Set.of(3L), bothRestricted.dormitoryIds());
        assertEquals(Set.of(5L), bothRestricted.paymentIds());
        assertEquals(Set.of(4L), bothRestricted.checkInApplicationIds());

        RiskScanScope bothAll = requestedAll.intersect(currentAll);
        assertTrue(bothAll.allRepairOrders());
        assertTrue(bothAll.allDormitories());
        assertTrue(bothAll.allPayments());
        assertTrue(bothAll.allCheckInApplications());

        RiskScanScope revoked = requestedAll.intersect(RiskScanScope.full(7, Set.of(), CAPTURED_AT));
        assertTrue(revoked.permissionCodes().isEmpty());
        assertFalse(revoked.allRepairOrders());
        assertFalse(revoked.canReadRepair(1));
    }

    @Test
    void intersectionRejectsMissingOrDifferentAuthorizationSubject() {
        RiskScanScope requested = RiskScanScope.full(7, Set.of("repair:read"), CAPTURED_AT);
        assertThrows(SecurityException.class, () -> requested.intersect(null));
        assertThrows(SecurityException.class,
                () -> requested.intersect(RiskScanScope.full(8, Set.of("repair:read"), CAPTURED_AT)));
    }
}
