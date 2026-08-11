package com.example.dormitory.ai.dashboard;

import java.util.Set;

public record MetricDefinition(
        String id,
        String version,
        String label,
        String definition,
        String unit,
        String queryHandlerId,
        String dataSourceId,
        String dataSourceLabel,
        Set<String> requiredPermissions,
        Set<String> allowedDimensions,
        Set<String> allowedFilters,
        Set<String> allowedDatePresets,
        int maximumRangeDays,
        int maximumRows) {
    public MetricDefinition {
        requiredPermissions = Set.copyOf(requiredPermissions);
        allowedDimensions = Set.copyOf(allowedDimensions);
        allowedFilters = Set.copyOf(allowedFilters);
        allowedDatePresets = Set.copyOf(allowedDatePresets);
        if (maximumRangeDays < 1 || maximumRows < 1) throw new IllegalArgumentException("指标限制必须为正数");
    }
}
