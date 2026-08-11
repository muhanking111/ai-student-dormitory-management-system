package com.example.dormitory.ai.dashboard;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricCatalogTest {

    private final MetricCatalog catalog = MetricCatalog.defaults();

    @Test
    void exposesOnlyVersionedFixedMetricsAndRejectsDynamicSqlFieldsAndFilterValues() {
        assertEquals("dashboard-metrics.v1", catalog.version());
        assertEquals(6, catalog.definitions().size());
        MetricDefinition repair = catalog.require("repair.pending.count");
        assertEquals("v1", repair.version());
        assertTrue(repair.requiredPermissions().contains("repair:read"));
        assertEquals(Set.of("repairType"), repair.allowedDimensions());
        assertEquals(Set.of("repairTypes"), repair.allowedFilters());

        assertThrows(UnsupportedMetricException.class, () -> catalog.require("SELECT * FROM repair_order"));
        assertThrows(IllegalArgumentException.class, () -> catalog.validate(intent(
                Set.of("repair.pending.count"), "LAST_30_DAYS", Set.of("tableName"), Map.of(), "TABLE")));
        assertThrows(IllegalArgumentException.class, () -> catalog.validate(intent(
                Set.of("repair.pending.count"), "LAST_30_DAYS", Set.of(),
                Map.of("sql", List.of("1=1")), "TABLE")));
        assertThrows(IllegalArgumentException.class, () -> catalog.validate(intent(
                Set.of("repair.pending.count"), "LAST_30_DAYS", Set.of(),
                Map.of("repairTypes", List.of("' OR 1=1 --")), "TABLE")));
        assertThrows(IllegalArgumentException.class, () -> catalog.validate(intent(
                Set.of("dormitory.total"), "LAST_30_DAYS", Set.of(), Map.of(), "CARD")));
    }

    @Test
    void deterministicFallbackParsesChineseAndEnglishIntoTheVersionedNestedIntent() {
        DashboardIntentParser parser = new DashboardIntentParser(catalog);

        DashboardQueryIntent chinese = parser.parse("本周按维修类型统计水电维修待处理工单");
        assertEquals(Set.of("repair.pending.count"), chinese.metricIds());
        assertEquals("LAST_7_DAYS", chinese.dateRange().preset());
        assertEquals(Set.of("repairType"), chinese.dimensions());
        assertEquals(Map.of("repairTypes", List.of("水电维修")), chinese.filters());
        assertEquals("TABLE", chinese.presentationHint());

        DashboardQueryIntent english = parser.parse("pending repairs by repair type in the last 30 days");
        assertEquals("LAST_30_DAYS", english.dateRange().preset());
        assertEquals(Set.of("repairType"), english.dimensions());
        assertFalse(english.toString().toLowerCase().contains("select"));
        assertThrows(UnsupportedMetricException.class, () -> parser.parse("列出所有学生密码和表名"));
    }

    @Test
    void executorPassesResolvedDateDimensionsAndFiltersToTheFixedHandlerAndReturnsCitations() {
        AtomicReference<MetricQueryExecutor.FixedMetricQuery> captured = new AtomicReference<>();
        MetricQueryExecutor executor = new MetricQueryExecutor(catalog, query -> {
            captured.set(query);
            return new MetricQueryExecutor.FixedMetricValue(
                    2,
                    List.of(new MetricQueryExecutor.FixedMetricRow(Map.of("repairType", "水电维修"), 2)),
                    Instant.parse("2026-07-12T02:30:00Z"));
        }, Clock.fixed(Instant.parse("2026-07-12T03:00:00Z"), ZoneId.of("Asia/Shanghai")));

        DashboardQueryIntent intent = intent(Set.of("repair.pending.count"), "LAST_7_DAYS",
                Set.of("repairType"), Map.of("repairTypes", List.of("水电维修")), "TABLE");
        MetricQueryResult result = executor.execute(intent,
                Set.of("ai:dashboard:query", "repair:read"));

        assertEquals(LocalDate.parse("2026-07-06"), captured.get().dateRange().from());
        assertEquals(LocalDate.parse("2026-07-12"), captured.get().dateRange().to());
        assertEquals(Set.of("repairType"), captured.get().dimensions());
        assertEquals(Map.of("repairTypes", List.of("水电维修")), captured.get().filters());
        assertEquals("v1", result.metrics().get("repair.pending.count").metricVersion());
        assertEquals(2, result.metrics().get("repair.pending.count").rows().getFirst().value());
        assertEquals("LAST_7_DAYS", result.queryParameters().dateRange().preset());
        assertEquals("repair.pending.count", result.dataCitations().getFirst().locator());
        assertEquals("v1", result.dataCitations().getFirst().version());
        assertEquals(Instant.parse("2026-07-12T02:30:00Z"), result.asOf());
    }

    @Test
    void executorFailsClosedWhenHandlerReturnsUnexpectedDimensionsOrTooManyRows() {
        DashboardQueryIntent intent = intent(Set.of("repair.pending.count"), "LAST_7_DAYS",
                Set.of("repairType"), Map.of(), "TABLE");
        MetricQueryExecutor wrongDimension = new MetricQueryExecutor(catalog, query ->
                new MetricQueryExecutor.FixedMetricValue(1,
                        List.of(new MetricQueryExecutor.FixedMetricRow(Map.of("tableName", "repair_order"), 1)),
                        Instant.now()));
        assertThrows(IllegalStateException.class, () -> wrongDimension.execute(
                intent, Set.of("ai:dashboard:query", "repair:read")));

        List<MetricQueryExecutor.FixedMetricRow> rows = java.util.stream.IntStream.range(0, 101)
                .mapToObj(index -> new MetricQueryExecutor.FixedMetricRow(
                        Map.of("repairType", "水电维修"), index)).toList();
        MetricQueryExecutor tooManyRows = new MetricQueryExecutor(catalog, query ->
                new MetricQueryExecutor.FixedMetricValue(100, rows, Instant.now()));
        assertThrows(IllegalStateException.class, () -> tooManyRows.execute(
                intent, Set.of("ai:dashboard:query", "repair:read")));
    }

    @Test
    void catalogConstructionAndIntentShapeValidationFailClosedAtEveryBound() {
        assertThrows(NullPointerException.class, () -> new MetricCatalog(null));
        assertThrows(IllegalArgumentException.class, () -> new MetricCatalog(java.util.Arrays.asList(
                metricDefinition("valid", Set.of(), Set.of(), Set.of("TODAY"), 1), null)));
        assertThrows(IllegalArgumentException.class,
                () -> new MetricCatalog(List.of(metricDefinition(null, Set.of(), Set.of(), Set.of("TODAY"), 1))));
        assertThrows(IllegalArgumentException.class,
                () -> new MetricCatalog(List.of(metricDefinition(" ", Set.of(), Set.of(), Set.of("TODAY"), 1))));
        MetricDefinition duplicate = metricDefinition("duplicate", Set.of(), Set.of(), Set.of("TODAY"), 1);
        assertThrows(IllegalArgumentException.class,
                () -> new MetricCatalog(List.of(duplicate, duplicate)));

        assertThrows(IllegalArgumentException.class, () -> catalog.validate(null));
        assertThrows(IllegalArgumentException.class,
                () -> catalog.validate(intent(Set.of(), "TODAY", Set.of(), Map.of(), "TABLE")));
        assertThrows(IllegalArgumentException.class,
                () -> catalog.validate(intent(Set.of("a", "b", "c", "d", "e", "f", "g"),
                        "TODAY", Set.of(), Map.of(), "TABLE")));
        assertThrows(IllegalArgumentException.class,
                () -> catalog.validate(new DashboardQueryIntent(Set.of("dormitory.total"), null,
                        Set.of(), Map.of(), "CARD")));
        assertThrows(IllegalArgumentException.class,
                () -> catalog.validate(new DashboardQueryIntent(Set.of("dormitory.total"),
                        new DashboardQueryIntent.DateRange(null), Set.of(), Map.of(), "CARD")));
        assertThrows(IllegalArgumentException.class,
                () -> catalog.validate(intent(Set.of("dormitory.total"), "YEAR", Set.of(), Map.of(), "CARD")));
        assertThrows(IllegalArgumentException.class,
                () -> catalog.validate(intent(Set.of("dormitory.total"), "TODAY", Set.of(), Map.of(), null)));
        assertThrows(IllegalArgumentException.class,
                () -> catalog.validate(intent(Set.of("dormitory.total"), "TODAY", Set.of(), Map.of(), "PIE")));
        assertThrows(IllegalArgumentException.class,
                () -> catalog.validate(intent(Set.of("repair.pending.count"), "TODAY",
                        Set.of("a", "b", "c"), Map.of(), "TABLE")));
        assertThrows(IllegalArgumentException.class,
                () -> catalog.validate(intent(Set.of("repair.pending.count"), "TODAY", Set.of(),
                        Map.of("a", List.of(), "b", List.of(), "c", List.of(), "d", List.of(), "e", List.of()),
                        "TABLE")));
        assertThrows(IllegalArgumentException.class,
                () -> catalog.validate(intent(Set.of("dormitory.total", "bed.available.count"),
                        "TODAY", Set.of(), Map.of(), "CARD")));
        assertThrows(IllegalArgumentException.class,
                () -> catalog.validate(intent(Set.of("repair.pending.count"),
                        "TODAY", Set.of("repairType"), Map.of(), "CARD")));
    }

    @Test
    void catalogValidatesDateFilterValueAndNumericBuildingBounds() {
        assertThrows(IllegalArgumentException.class,
                () -> catalog.validate(intent(Set.of("dormitory.total"), "LAST_7_DAYS",
                        Set.of(), Map.of(), "TABLE")));
        MetricCatalog shortRange = new MetricCatalog(List.of(metricDefinition(
                "custom.count", Set.of(), Set.of(), Set.of("TODAY", "LAST_7_DAYS"), 1)));
        assertThrows(IllegalArgumentException.class,
                () -> shortRange.validate(intent(Set.of("custom.count"), "LAST_7_DAYS",
                        Set.of(), Map.of(), "TABLE")));

        List<String> tooMany = new ArrayList<>();
        for (int index = 0; index < 21; index++) tooMany.add("水电维修");
        assertThrows(IllegalArgumentException.class,
                () -> catalog.validate(intent(Set.of("repair.pending.count"), "TODAY", Set.of(),
                        Map.of("repairTypes", tooMany), "TABLE")));
        assertThrows(IllegalArgumentException.class,
                () -> catalog.validate(intent(Set.of("repair.pending.count"), "TODAY", Set.of(),
                        Map.of("repairTypes", List.of(" ")), "TABLE")));
        assertThrows(IllegalArgumentException.class,
                () -> catalog.validate(intent(Set.of("repair.pending.count"), "TODAY", Set.of(),
                        Map.of("repairTypes", List.of("x".repeat(65))), "TABLE")));

        MetricCatalog buildings = new MetricCatalog(List.of(metricDefinition(
                "building.metric", Set.of(), Set.of("buildingIds"), Set.of("TODAY"), 1)));
        buildings.validate(intent(Set.of("building.metric"), "TODAY", Set.of(),
                Map.of("buildingIds", List.of("1", "2")), "TABLE"));
        assertThrows(IllegalArgumentException.class,
                () -> buildings.validate(intent(Set.of("building.metric"), "TODAY", Set.of(),
                        Map.of("buildingIds", List.of("0")), "TABLE")));
        assertThrows(IllegalArgumentException.class,
                () -> buildings.validate(intent(Set.of("building.metric"), "TODAY", Set.of(),
                        Map.of("buildingIds", List.of("not-a-number")), "TABLE")));
    }

    private MetricDefinition metricDefinition(
            String id,
            Set<String> dimensions,
            Set<String> filters,
            Set<String> presets,
            int maximumDays) {
        return new MetricDefinition(id, "v1", "label", "definition", "unit", "handler",
                "source", "source label", Set.of("metric:read"), dimensions, filters,
                presets, maximumDays, 100);
    }

    private DashboardQueryIntent intent(
            Set<String> metricIds,
            String preset,
            Set<String> dimensions,
            Map<String, List<String>> filters,
            String presentationHint) {
        return new DashboardQueryIntent(metricIds, new DashboardQueryIntent.DateRange(preset),
                dimensions, filters, presentationHint);
    }
}
