package com.example.dormitory.ai.infrastructure.business;

import com.example.dormitory.ai.dashboard.MetricQueryExecutor;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.BusinessActorScope;
import com.example.dormitory.ai.port.BusinessReadFacade;
import com.example.dormitory.ai.security.ActorAuthorizationFacade;
import com.example.dormitory.ai.security.PiiClassificationService;
import com.example.dormitory.ai.security.PiiRedactionService;
import com.example.dormitory.ai.security.RepairAccessPolicy;
import com.example.dormitory.common.PageResponse;
import com.example.dormitory.domain.RepairOrder;
import com.example.dormitory.domain.StatisticCard;
import com.example.dormitory.service.DormitoryQueryService;
import com.example.dormitory.service.OperationsService;
import com.example.dormitory.service.RbacService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class DormitoryBusinessReadAdapterTest {

    private final ActorAuthorizationFacade authorization = mock(ActorAuthorizationFacade.class);
    private final DormitoryQueryService dashboard = mock(DormitoryQueryService.class);
    private final OperationsService operations = mock(OperationsService.class);
    private final DormitoryBusinessReadAdapter adapter = new DormitoryBusinessReadAdapter(
            authorization, dashboard, operations, new ObjectMapper(),
            new PiiClassificationService(
                    new PiiRedactionService("business-read-test-key".getBytes(StandardCharsets.UTF_8), "test-v1"),
                    List::of));

    @Test
    void reauthorizesFreshActorAndUsesOnlyFixedMetricHandlers() {
        RbacService.AuthorizationSnapshot snapshot = new RbacService.AuthorizationSnapshot(
                7L, true, List.of("ADMIN"),
                List.of("dashboard:read", "ai:dashboard:query", "dormitory:read"));
        when(authorization.snapshot(7L)).thenReturn(snapshot);
        when(dashboard.statisticsForActor(snapshot)).thenReturn(List.of(
                new StatisticCard("宿舍总数", 3, "间", "实时数据", "blue", "home")));

        var value = adapter.query(metric("dormitory.total"), scope(
                "dashboard:read", "ai:dashboard:query", "dormitory:read", "payment:read"));

        assertEquals(3, value.value());
        verify(dashboard).statisticsForActor(snapshot);
        assertThrows(IllegalArgumentException.class,
                () -> adapter.query(metric("SELECT * FROM repair_order"), scope("dashboard:read")));
        verify(operations, never()).paymentBills(1, 1, null, null, "未缴");
    }

    @Test
    void metricSnapshotsUseTheInjectedBusinessClock() throws Exception {
        Instant reference = Instant.parse("2026-08-03T04:00:00Z");
        DormitoryBusinessReadAdapter fixedClockAdapter = DormitoryBusinessReadAdapter.class
                .getConstructor(ActorAuthorizationFacade.class, DormitoryQueryService.class,
                        OperationsService.class, ObjectMapper.class, PiiClassificationService.class,
                        RepairCandidateProvider.class, Clock.class)
                .newInstance(authorization, dashboard, operations, new ObjectMapper(),
                        new PiiClassificationService(
                                new PiiRedactionService(
                                        "business-read-test-key".getBytes(StandardCharsets.UTF_8), "test-v1"),
                                List::of),
                        (RepairCandidateProvider) List::of,
                        Clock.fixed(reference, ZoneId.of("Asia/Shanghai")));
        RbacService.AuthorizationSnapshot snapshot = new RbacService.AuthorizationSnapshot(
                7L, true, List.of("ADMIN"),
                List.of("dashboard:read", "ai:dashboard:query", "dormitory:read"));
        when(authorization.snapshot(7L)).thenReturn(snapshot);
        when(dashboard.statisticsForActor(snapshot)).thenReturn(List.of(
                new StatisticCard("宿舍总数", 3, "间", "实时数据", "blue", "home")));

        MetricQueryExecutor.FixedMetricValue value = fixedClockAdapter.query(metric("dormitory.total"), scope(
                "dashboard:read", "ai:dashboard:query", "dormitory:read"));

        assertEquals(reference, value.asOf());
    }

    @Test
    void ignoresClaimedPermissionsAfterRevocationAndRedactsRepairContext() {
        when(authorization.snapshot(7L)).thenReturn(new RbacService.AuthorizationSnapshot(
                7L, true, List.of("REPAIRER"), List.of("dashboard:read", "ai:dashboard:query")));
        assertThrows(SecurityException.class,
                () -> adapter.query(metric("repair.pending.count"), scope(
                        "dashboard:read", "ai:dashboard:query", "repair:read")));

        RbacService.AuthorizationSnapshot allowed = new RbacService.AuthorizationSnapshot(
                7L, true, List.of("REPAIRER"),
                List.of("repair:read", "ai:repair:triage"));
        when(authorization.snapshot(7L)).thenReturn(allowed);
        when(operations.repairContextForActor(org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.any())).thenReturn(new OperationsService.RepairAiContext(
                        42L, "WX-42", "水电", "待处理", "请联系 13800138000", 7L,
                        LocalDateTime.parse("2026-07-11T12:00:00")));

        BusinessReadFacade.BusinessReadResult result = adapter.read(
                scope("repair:read", "ai:repair:triage"),
                new BusinessReadFacade.BusinessReadRequest("repair.context.v1", Map.of("repairOrderId", "42")));

        assertFalse(result.payloadJson().contains("13800138000"));
        assertFalse(result.payloadJson().contains("reporter"));
    }

    @Test
    void appliesResolvedDateAndWhitelistedRepairTypeDimensionWithoutDynamicQueryNames() {
        RbacService.AuthorizationSnapshot snapshot = new RbacService.AuthorizationSnapshot(
                7L, true, List.of("REPAIRER"),
                List.of("dashboard:read", "ai:dashboard:query", "repair:read"));
        when(authorization.snapshot(7L)).thenReturn(snapshot);
        when(operations.repairOrdersForActor(eq(1L), eq(100L), eq(null), eq("水电维修"), eq(null),
                any(RepairAccessPolicy.RepairActorAccess.class))).thenReturn(new PageResponse<>(List.of(
                        new RepairOrder(1L, "WX-1", "token", "token", "水电维修", "2026-07-07", "待处理", null, 7L),
                        new RepairOrder(2L, "WX-2", "token", "token", "水电维修", "2026-07-01", "待处理", null, 7L),
                        new RepairOrder(3L, "WX-3", "token", "token", "水电维修", "2026-07-08", "已完成", null, 7L)
                ), 3, 1, 100));
        MetricQueryExecutor.FixedMetricQuery query = new MetricQueryExecutor.FixedMetricQuery(
                "repair.pending.count", "repair.pending.count",
                new MetricQueryExecutor.ResolvedDateRange("LAST_7_DAYS",
                        LocalDate.parse("2026-07-05"), LocalDate.parse("2026-07-11")),
                Set.of("repairType"), Map.of("repairTypes", List.of("水电维修")), 100);

        MetricQueryExecutor.FixedMetricValue value = adapter.query(query,
                scope("dashboard:read", "ai:dashboard:query", "repair:read"));

        assertEquals(1, value.value());
        assertEquals(Map.of("repairType", "水电维修"), value.rows().getFirst().dimensions());
        assertThrows(IllegalArgumentException.class, () -> adapter.query(
                new MetricQueryExecutor.FixedMetricQuery("repair.pending.count", "repair.pending.count",
                        query.dateRange(), Set.of("tableName"), Map.of("sql", List.of("1=1")), 100),
                scope("dashboard:read", "ai:dashboard:query", "repair:read")));
        verify(dashboard, never()).statisticsForActor(snapshot);
    }

    private BusinessActorScope scope(String... permissions) {
        return new BusinessActorScope(ActorDescriptor.user(7), Set.of(permissions), Map.of());
    }

    private MetricQueryExecutor.FixedMetricQuery metric(String metricId) {
        LocalDate date = LocalDate.parse("2026-07-11");
        return new MetricQueryExecutor.FixedMetricQuery(metricId, metricId,
                new MetricQueryExecutor.ResolvedDateRange("TODAY", date, date), Set.of(), Map.of(), 100);
    }
}
