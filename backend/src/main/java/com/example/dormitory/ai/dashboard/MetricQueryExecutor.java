package com.example.dormitory.ai.dashboard;

import com.example.dormitory.ai.domain.model.BusinessActorScope;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MetricQueryExecutor {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final MetricCatalog catalog;
    private final FixedMetricHandler handler;
    private final Clock clock;

    public MetricQueryExecutor(MetricCatalog catalog, FixedMetricHandler handler) {
        this(catalog, handler, Clock.system(BUSINESS_ZONE));
    }

    public MetricQueryExecutor(MetricCatalog catalog, FixedMetricHandler handler, Clock clock) {
        this.catalog = java.util.Objects.requireNonNull(catalog);
        this.handler = java.util.Objects.requireNonNull(handler);
        this.clock = java.util.Objects.requireNonNull(clock);
    }

    public MetricQueryResult execute(DashboardQueryIntent intent, Set<String> actorPermissions) {
        return executeInternal(intent, actorPermissions, null);
    }

    public MetricQueryResult execute(DashboardQueryIntent intent, BusinessActorScope actorScope) {
        if (actorScope == null) throw new SecurityException("缺少业务 actor scope");
        return executeInternal(intent, actorScope.permissionCodes(), actorScope);
    }

    private MetricQueryResult executeInternal(
            DashboardQueryIntent intent,
            Set<String> actorPermissions,
            BusinessActorScope actorScope) {
        catalog.validate(intent);
        if (actorPermissions == null || !actorPermissions.contains("ai:dashboard:query")) {
            throw new SecurityException("缺少 AI Dashboard 权限");
        }
        ResolvedDateRange dateRange = resolve(intent.dateRange());
        Map<String, MetricQueryResult.MetricValue> values = new LinkedHashMap<>();
        java.util.ArrayList<MetricQueryResult.DataCitation> citations = new java.util.ArrayList<>();
        Instant asOf = null;
        for (String metricId : intent.metricIds()) {
            MetricDefinition definition = catalog.require(metricId);
            if (!actorPermissions.containsAll(definition.requiredPermissions())) {
                throw new SecurityException("缺少指标底层业务权限");
            }
            FixedMetricQuery query = new FixedMetricQuery(metricId, definition.queryHandlerId(), dateRange,
                    intent.dimensions(), intent.filters(), definition.maximumRows());
            FixedMetricValue value = actorScope == null ? handler.query(query) : handler.query(query, actorScope);
            validateHandlerValue(value, query);
            List<MetricQueryResult.MetricRow> rows = value.rows().stream()
                    .map(row -> new MetricQueryResult.MetricRow(row.dimensions(), row.value())).toList();
            values.put(metricId, new MetricQueryResult.MetricValue(
                    value.value(), definition.unit(), definition.version(), definition.definition(), rows));
            citations.add(new MetricQueryResult.DataCitation(
                    "metric-" + metricId.replace('.', '-') + "-" + definition.version(),
                    definition.dataSourceLabel(), metricId, definition.version(), "available", metricId, value.asOf()));
            if (asOf == null || value.asOf().isBefore(asOf)) asOf = value.asOf();
        }
        MetricQueryResult.QueryParameters parameters = new MetricQueryResult.QueryParameters(
                dateRange, intent.dimensions(), intent.filters());
        return new MetricQueryResult(values, asOf, catalog.version(), parameters, citations);
    }

    private ResolvedDateRange resolve(DashboardQueryIntent.DateRange requested) {
        LocalDate to = LocalDate.now(clock);
        int days = switch (requested.preset()) {
            case "TODAY" -> 1;
            case "LAST_7_DAYS" -> 7;
            case "LAST_30_DAYS" -> 30;
            default -> throw new IllegalArgumentException("时间范围不受支持");
        };
        return new ResolvedDateRange(requested.preset(), to.minusDays(days - 1L), to);
    }

    private void validateHandlerValue(FixedMetricValue value, FixedMetricQuery query) {
        if (value == null || value.value() == null || value.asOf() == null) {
            throw new IllegalStateException("固定指标处理器返回无效结果");
        }
        if (value.rows().size() > query.maximumRows()) {
            throw new IllegalStateException("固定指标处理器超过最大结果行数");
        }
        for (FixedMetricRow row : value.rows()) {
            if (row == null || row.value() == null || !row.dimensions().keySet().equals(query.dimensions())) {
                throw new IllegalStateException("固定指标处理器返回了未请求维度");
            }
        }
    }

    @FunctionalInterface
    public interface FixedMetricHandler {
        FixedMetricValue query(FixedMetricQuery query);

        default FixedMetricValue query(FixedMetricQuery query, BusinessActorScope actorScope) {
            return query(query);
        }
    }

    public record FixedMetricQuery(
            String metricId,
            String queryHandlerId,
            ResolvedDateRange dateRange,
            Set<String> dimensions,
            Map<String, List<String>> filters,
            int maximumRows) {
        public FixedMetricQuery {
            dimensions = Set.copyOf(dimensions);
            Map<String, List<String>> copied = new LinkedHashMap<>();
            filters.forEach((key, values) -> copied.put(key, List.copyOf(values)));
            filters = Collections.unmodifiableMap(copied);
        }
    }

    public record ResolvedDateRange(String preset, LocalDate from, LocalDate to) {
    }

    public record FixedMetricRow(Map<String, String> dimensions, Number value) {
        public FixedMetricRow {
            dimensions = Collections.unmodifiableMap(new LinkedHashMap<>(dimensions));
        }
    }

    public record FixedMetricValue(Number value, List<FixedMetricRow> rows, Instant asOf) {
        public FixedMetricValue {
            rows = List.copyOf(rows == null ? List.of() : rows);
        }

        public FixedMetricValue(Number value, Instant asOf) {
            this(value, List.of(), asOf);
        }
    }
}
