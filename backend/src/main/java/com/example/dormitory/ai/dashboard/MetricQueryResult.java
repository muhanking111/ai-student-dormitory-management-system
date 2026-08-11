package com.example.dormitory.ai.dashboard;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record MetricQueryResult(
        Map<String, MetricValue> metrics,
        Instant asOf,
        String catalogVersion,
        QueryParameters queryParameters,
        List<DataCitation> dataCitations) {
    public MetricQueryResult {
        metrics = Collections.unmodifiableMap(new LinkedHashMap<>(metrics));
        dataCitations = List.copyOf(dataCitations);
    }

    public record MetricValue(
            Number value,
            String unit,
            String metricVersion,
            String definition,
            List<MetricRow> rows) {
        public MetricValue {
            rows = List.copyOf(rows == null ? List.of() : rows);
        }
    }

    public record MetricRow(Map<String, String> dimensions, Number value) {
        public MetricRow {
            dimensions = Collections.unmodifiableMap(new LinkedHashMap<>(dimensions));
        }
    }

    public record QueryParameters(
            MetricQueryExecutor.ResolvedDateRange dateRange,
            Set<String> dimensions,
            Map<String, List<String>> filters) {
        public QueryParameters {
            dimensions = Set.copyOf(dimensions);
            Map<String, List<String>> copied = new LinkedHashMap<>();
            filters.forEach((key, values) -> copied.put(key, List.copyOf(values)));
            filters = Collections.unmodifiableMap(copied);
        }
    }

    public record DataCitation(
            String id,
            String label,
            String locator,
            String version,
            String access,
            String metricId,
            Instant asOf) {
    }
}
