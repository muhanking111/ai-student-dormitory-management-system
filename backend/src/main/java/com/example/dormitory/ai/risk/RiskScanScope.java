package com.example.dormitory.ai.risk;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 风险扫描的不可变业务读取范围。它来自可信 RBAC/行级事实，而不是请求正文；worker
 * 只能使用请求时快照与执行时当前授权的交集。
 */
public record RiskScanScope(
        long effectiveSubjectUserId,
        Set<String> permissionCodes,
        boolean allRepairOrders,
        Set<Long> repairOrderIds,
        boolean allDormitories,
        Set<Long> dormitoryIds,
        boolean allPayments,
        Set<Long> paymentIds,
        boolean allCheckInApplications,
        Set<Long> checkInApplicationIds,
        Instant capturedAt) {

    public RiskScanScope(
            long userId,
            Set<String> permissions,
            boolean allRepairOrders,
            Set<Long> repairOrderIds,
            boolean allDormitories,
            Set<Long> dormitoryIds,
            boolean allCheckInApplications,
            Set<Long> checkInApplicationIds,
            Instant capturedAt) {
        this(userId, permissions, allRepairOrders, repairOrderIds, allDormitories, dormitoryIds,
                false, Set.of(), allCheckInApplications, checkInApplicationIds, capturedAt);
    }

    private static final Set<String> BUSINESS_READ_PERMISSIONS = Set.of(
            "repair:read", "dormitory:read", "checkin:read", "hygiene:read", "payment:read");

    public RiskScanScope {
        if (effectiveSubjectUserId < 1 || capturedAt == null) {
            throw new IllegalArgumentException("风险扫描 subject scope 不合法");
        }
        permissionCodes = permissionCodes == null ? Set.of() : permissionCodes.stream()
                .filter(BUSINESS_READ_PERMISSIONS::contains)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        repairOrderIds = positiveIds(repairOrderIds);
        dormitoryIds = positiveIds(dormitoryIds);
        paymentIds = positiveIds(paymentIds);
        checkInApplicationIds = positiveIds(checkInApplicationIds);
        if (!permissionCodes.contains("repair:read") && (allRepairOrders || !repairOrderIds.isEmpty())) {
            throw new IllegalArgumentException("无维修读取权限时不得携带维修范围");
        }
        if (!permissionCodes.contains("dormitory:read") && (allDormitories || !dormitoryIds.isEmpty())) {
            throw new IllegalArgumentException("无宿舍读取权限时不得携带宿舍范围");
        }
        if (!permissionCodes.contains("payment:read") && (allPayments || !paymentIds.isEmpty())) {
            throw new IllegalArgumentException("无缴费读取权限时不得携带缴费范围");
        }
        if (!permissionCodes.contains("checkin:read")
                && (allCheckInApplications || !checkInApplicationIds.isEmpty())) {
            throw new IllegalArgumentException("无入住读取权限时不得携带入住范围");
        }
    }

    public static RiskScanScope full(long userId, Set<String> permissions, Instant capturedAt) {
        Set<String> normalized = permissions == null ? Set.of() : Set.copyOf(permissions);
        return new RiskScanScope(userId, normalized,
                normalized.contains("repair:read"), Set.of(),
                normalized.contains("dormitory:read"), Set.of(),
                normalized.contains("payment:read"), Set.of(),
                normalized.contains("checkin:read"), Set.of(), capturedAt);
    }

    public static RiskScanScope restricted(
            long userId,
            Set<String> permissions,
            Set<Long> repairOrderIds,
            Set<Long> dormitoryIds,
            Set<Long> paymentIds,
            Set<Long> checkInApplicationIds,
            Instant capturedAt) {
        return new RiskScanScope(userId, permissions, false, repairOrderIds,
                false, dormitoryIds, false, paymentIds, false, checkInApplicationIds, capturedAt);
    }

    public static RiskScanScope restricted(
            long userId,
            Set<String> permissions,
            Set<Long> repairOrderIds,
            Set<Long> dormitoryIds,
            Set<Long> checkInApplicationIds,
            Instant capturedAt) {
        return restricted(userId, permissions, repairOrderIds, dormitoryIds, Set.of(),
                checkInApplicationIds, capturedAt);
    }

    public RiskScanScope intersect(RiskScanScope current) {
        if (current == null || current.effectiveSubjectUserId != effectiveSubjectUserId) {
            throw new SecurityException("风险扫描授权主体不一致");
        }
        Set<String> permissions = new LinkedHashSet<>(permissionCodes);
        permissions.retainAll(current.permissionCodes);
        ScopePart repairs = intersectPart(allRepairOrders, repairOrderIds,
                current.allRepairOrders, current.repairOrderIds, permissions.contains("repair:read"));
        ScopePart dormitories = intersectPart(allDormitories, dormitoryIds,
                current.allDormitories, current.dormitoryIds, permissions.contains("dormitory:read"));
        ScopePart payments = intersectPart(allPayments, paymentIds,
                current.allPayments, current.paymentIds, permissions.contains("payment:read"));
        ScopePart checkIns = intersectPart(allCheckInApplications, checkInApplicationIds,
                current.allCheckInApplications, current.checkInApplicationIds, permissions.contains("checkin:read"));
        return new RiskScanScope(effectiveSubjectUserId, permissions,
                repairs.all(), repairs.ids(), dormitories.all(), dormitories.ids(),
                payments.all(), payments.ids(), checkIns.all(), checkIns.ids(), current.capturedAt);
    }

    public boolean canReadRepair(long id) {
        return permissionCodes.contains("repair:read") && (allRepairOrders || repairOrderIds.contains(id));
    }

    public boolean canReadDormitory(long id) {
        return permissionCodes.contains("dormitory:read") && (allDormitories || dormitoryIds.contains(id));
    }

    public boolean canReadPayment(long id) {
        return permissionCodes.contains("payment:read") && (allPayments || paymentIds.contains(id));
    }

    public boolean canReadCheckInApplication(long id) {
        return permissionCodes.contains("checkin:read")
                && (allCheckInApplications || checkInApplicationIds.contains(id));
    }

    private static ScopePart intersectPart(
            boolean requestedAll,
            Set<Long> requested,
            boolean currentAll,
            Set<Long> current,
            boolean permissionAllowed) {
        if (!permissionAllowed) return new ScopePart(false, Set.of());
        if (requestedAll && currentAll) return new ScopePart(true, Set.of());
        if (requestedAll) return new ScopePart(false, current);
        if (currentAll) return new ScopePart(false, requested);
        Set<Long> ids = new LinkedHashSet<>(requested);
        ids.retainAll(current);
        return new ScopePart(false, Set.copyOf(ids));
    }

    private static Set<Long> positiveIds(Set<Long> values) {
        if (values == null || values.isEmpty()) return Set.of();
        if (values.stream().anyMatch(value -> value == null || value < 1)) {
            throw new IllegalArgumentException("风险扫描资源 ID 不合法");
        }
        return Set.copyOf(values);
    }

    private record ScopePart(boolean all, Set<Long> ids) { }
}
