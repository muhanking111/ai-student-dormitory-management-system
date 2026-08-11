package com.example.dormitory.ai.dashboard;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DashboardQueryIntent.v1 的项目自有合同。该对象只包含目录 ID 和白名单参数，不包含字段名、SQL 或 handler 名。
 */
public record DashboardQueryIntent(
        Set<String> metricIds,
        DateRange dateRange,
        Set<String> dimensions,
        Map<String, List<String>> filters,
        String presentationHint) {

    public static final String SCHEMA_VERSION = "DashboardQueryIntent.v1";

    public DashboardQueryIntent {
        metricIds = immutableSet(metricIds);
        dimensions = immutableSet(dimensions);
        filters = immutableFilters(filters);
    }

    private static Set<String> immutableSet(Set<String> values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(values == null ? Set.of() : values));
    }

    private static Map<String, List<String>> immutableFilters(Map<String, List<String>> values) {
        Map<String, List<String>> copied = new LinkedHashMap<>();
        if (values != null) {
            values.forEach((key, filterValues) -> copied.put(
                    key, List.copyOf(filterValues == null ? List.of() : filterValues)));
        }
        return Collections.unmodifiableMap(copied);
    }

    public record DateRange(String preset) {
    }
}
