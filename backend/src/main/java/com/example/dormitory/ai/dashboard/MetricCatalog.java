package com.example.dormitory.ai.dashboard;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class MetricCatalog {

    public static final String VERSION = "dashboard-metrics.v1";
    private static final Map<String, Integer> DATE_PRESET_DAYS = Map.of(
            "TODAY", 1, "LAST_7_DAYS", 7, "LAST_30_DAYS", 30);
    private static final Set<String> PRESENTATIONS = Set.of("CARD", "TABLE", "LINE", "BAR");
    private static final Set<String> REPAIR_TYPES = Set.of("水电维修", "家具维修", "门窗维修");

    private final Map<String, MetricDefinition> definitions;

    public MetricCatalog(java.util.Collection<MetricDefinition> definitions) {
        Map<String, MetricDefinition> indexed = new LinkedHashMap<>();
        for (MetricDefinition definition : definitions) {
            if (definition == null || definition.id() == null || definition.id().isBlank()) {
                throw new IllegalArgumentException("指标定义不完整");
            }
            if (indexed.putIfAbsent(definition.id(), definition) != null) {
                throw new IllegalArgumentException("指标 ID 重复: " + definition.id());
            }
        }
        this.definitions = Collections.unmodifiableMap(indexed);
    }

    public static MetricCatalog defaults() {
        Set<String> allDates = DATE_PRESET_DAYS.keySet();
        Set<String> today = Set.of("TODAY");
        return new MetricCatalog(List.of(
                metric("dormitory.total", "宿舍总数", "当前授权范围内的宿舍总数", "间",
                        "dormitory.statistics", "宿舍资源统计", "dormitory:read",
                        Set.of(), Set.of(), today, 1),
                metric("student.checked-in.count", "入住学生数", "当前授权范围内已入住床位汇总", "人",
                        "dormitory.statistics", "入住资源统计", "student:read",
                        Set.of(), Set.of(), today, 1),
                metric("bed.available.count", "空余床位", "当前授权范围内可用床位汇总", "个",
                        "dormitory.statistics", "床位资源统计", "dormitory:read",
                        Set.of(), Set.of(), today, 1),
                metric("repair.pending.count", "待维修", "范围内创建且当前未完成的维修工单数", "项",
                        "repair.pending", "维修工单统计", "repair:read",
                        Set.of("repairType"), Set.of("repairTypes"), allDates, 30),
                metric("hygiene.failed.count", "卫生待整改", "范围内不合格的卫生检查数", "项",
                        "hygiene.failed", "卫生检查统计", "hygiene:read",
                        Set.of(), Set.of(), allDates, 30),
                metric("payment.unpaid.count", "未缴账单", "范围内到期且状态为未缴的账单数", "项",
                        "payment.unpaid", "缴费账单统计", "payment:read",
                        Set.of(), Set.of(), allDates, 30)));
    }

    private static MetricDefinition metric(
            String id,
            String label,
            String definition,
            String unit,
            String dataSourceId,
            String dataSourceLabel,
            String permission,
            Set<String> dimensions,
            Set<String> filters,
            Set<String> datePresets,
            int maximumRangeDays) {
        return new MetricDefinition(id, "v1", label, definition, unit, id, dataSourceId, dataSourceLabel,
                Set.of(permission), dimensions, filters, datePresets, maximumRangeDays, 100);
    }

    public String version() {
        return VERSION;
    }

    public Map<String, MetricDefinition> definitions() {
        return definitions;
    }

    public String supportedMetricSummary() {
        return definitions.values().stream().map(definition -> definition.id() + "（" + definition.label() + "）")
                .collect(Collectors.joining("、"));
    }

    public MetricDefinition require(String metricId) {
        MetricDefinition definition = definitions.get(metricId);
        if (definition == null) {
            throw new UnsupportedMetricException("不支持的指标；支持的指标：" + supportedMetricSummary(),
                    definitions.keySet().stream().toList());
        }
        return definition;
    }

    public void validate(DashboardQueryIntent intent) {
        if (intent == null || intent.metricIds().isEmpty() || intent.metricIds().size() > 6) {
            throw new IllegalArgumentException("指标数量不合法");
        }
        if (intent.dateRange() == null || intent.dateRange().preset() == null
                || !DATE_PRESET_DAYS.containsKey(intent.dateRange().preset())) {
            throw new IllegalArgumentException("时间范围不受支持");
        }
        if (intent.presentationHint() == null || !PRESENTATIONS.contains(intent.presentationHint())) {
            throw new IllegalArgumentException("展示方式不受支持");
        }
        if (intent.dimensions().size() > 2 || intent.filters().size() > 4) {
            throw new IllegalArgumentException("指标组合过大");
        }
        if ("CARD".equals(intent.presentationHint())
                && (intent.metricIds().size() != 1 || !intent.dimensions().isEmpty())) {
            throw new IllegalArgumentException("卡片展示只允许单指标无维度结果");
        }
        validateFilterValues(intent.filters());
        int requestedDays = DATE_PRESET_DAYS.get(intent.dateRange().preset());
        for (String metricId : intent.metricIds()) {
            MetricDefinition definition = require(metricId);
            if (!definition.allowedDatePresets().contains(intent.dateRange().preset())
                    || requestedDays > definition.maximumRangeDays()) {
                throw new IllegalArgumentException("指标时间范围不受支持");
            }
            if (!definition.allowedDimensions().containsAll(intent.dimensions())) {
                throw new IllegalArgumentException("指标维度不受支持");
            }
            if (!definition.allowedFilters().containsAll(intent.filters().keySet())) {
                throw new IllegalArgumentException("指标筛选不受支持");
            }
        }
    }

    private void validateFilterValues(Map<String, List<String>> filters) {
        int totalValues = filters.values().stream().mapToInt(List::size).sum();
        if (totalValues > 20) throw new IllegalArgumentException("筛选值过多");
        filters.forEach((key, values) -> {
            if (values == null || values.stream().anyMatch(value -> value == null || value.isBlank() || value.length() > 64)) {
                throw new IllegalArgumentException("指标筛选值不合法");
            }
            if ("buildingIds".equals(key)) {
                for (String value : values) {
                    try {
                        if (Long.parseLong(value) < 1) throw new NumberFormatException("non-positive");
                    } catch (NumberFormatException exception) {
                        throw new IllegalArgumentException("楼栋筛选必须为现有数值 ID", exception);
                    }
                }
            } else if ("repairTypes".equals(key) && !REPAIR_TYPES.containsAll(values)) {
                throw new IllegalArgumentException("维修类型筛选不在固定白名单");
            }
        });
    }
}
