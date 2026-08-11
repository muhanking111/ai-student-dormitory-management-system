package com.example.dormitory.ai.infrastructure.risk;

import com.example.dormitory.ai.risk.RiskScanScope;
import com.example.dormitory.ai.risk.RiskScanScopeFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/** 将现有维修行级规则固化成 worker 可安全重放的最小资源快照。 */
@Component
public class JdbcRiskScanScopeFactory implements RiskScanScopeFactory {

    private final JdbcTemplate jdbc;

    public JdbcRiskScanScopeFactory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public RiskScanScope capture(
            long userId,
            Set<String> roleCodes,
            Set<String> permissionCodes,
            Instant capturedAt) {
        if (userId < 1 || roleCodes == null || permissionCodes == null || capturedAt == null) {
            throw new IllegalArgumentException("风险扫描授权快照不合法");
        }
        Set<String> permissions = new LinkedHashSet<>(permissionCodes);
        permissions.retainAll(Set.of("repair:read", "dormitory:read", "checkin:read", "hygiene:read", "payment:read"));
        boolean restrictedRepairer = roleCodes.contains("REPAIRER") && !roleCodes.contains("ADMIN");
        Set<Long> repairs = Set.of();
        boolean allRepairs = permissions.contains("repair:read") && !restrictedRepairer;
        if (permissions.contains("repair:read") && restrictedRepairer) {
            repairs = Set.copyOf(jdbc.queryForList(
                    "SELECT id FROM repair_order WHERE assignee_user_id=? ORDER BY id", Long.class, userId));
        }
        return new RiskScanScope(userId, permissions, allRepairs, repairs,
                permissions.contains("dormitory:read"), Set.of(),
                permissions.contains("payment:read"), Set.of(),
                permissions.contains("checkin:read"), Set.of(), capturedAt);
    }
}
