package com.example.dormitory.ai.infrastructure.business;

import com.example.dormitory.ai.dashboard.MetricQueryExecutor;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.BusinessActorScope;
import com.example.dormitory.ai.port.BusinessReadFacade;
import com.example.dormitory.ai.security.ActorAuthorizationFacade;
import com.example.dormitory.ai.security.PiiClassificationService;
import com.example.dormitory.ai.security.PiiRedactionService;
import com.example.dormitory.common.PageResponse;
import com.example.dormitory.domain.HygieneCheck;
import com.example.dormitory.domain.Notice;
import com.example.dormitory.domain.Payment;
import com.example.dormitory.domain.RepairOrder;
import com.example.dormitory.domain.StatisticCard;
import com.example.dormitory.service.DormitoryQueryService;
import com.example.dormitory.service.OperationsService;
import com.example.dormitory.service.RbacService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DormitoryBusinessReadAdapterBoundaryTest {

    private final ActorAuthorizationFacade authorization = mock(ActorAuthorizationFacade.class);
    private final DormitoryQueryService dashboard = mock(DormitoryQueryService.class);
    private final OperationsService operations = mock(OperationsService.class);
    private final RepairCandidateProvider candidates = mock(RepairCandidateProvider.class);
    private final DormitoryBusinessReadAdapter adapter = new DormitoryBusinessReadAdapter(
            authorization, dashboard, operations, new ObjectMapper(), classification(), candidates);

    @Test
    void readRejectsIncompleteDynamicAndStructuredMetricRequests() {
        assertThrows(IllegalArgumentException.class, () -> adapter.read(scope(), null));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.read(scope(), request(null, Map.of())));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.read(scope(), request(" ", Map.of())));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.read(scope(), request("sql.execute.v1", Map.of())));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.read(scope("dashboard:read", "ai:dashboard:query", "dormitory:read"),
                        request("dormitory.total", Map.of("table", "dormitory"))));
        assertThrows(SecurityException.class, () -> adapter.query(metric("dormitory.total")));
        assertThrows(IllegalArgumentException.class, () -> adapter.query(
                query("dormitory.total", "bed.available.count", "TODAY", date(), date(),
                        Set.of(), Map.of(), 100), scope()));
    }

    @Test
    void dashboardAndNoticeContextsValidateParametersAndRedactNullableFields() {
        RbacService.AuthorizationSnapshot dashboardActor = snapshot(true, List.of("ADMIN"),
                "dashboard:read", "ai:dashboard:query");
        when(authorization.snapshot(7L)).thenReturn(dashboardActor);
        when(dashboard.statisticsForActor(dashboardActor)).thenReturn(List.of(
                new StatisticCard("宿舍总数", 2, "间", "实时", "blue", "home")));
        BusinessReadFacade.BusinessReadResult dashboardResult = adapter.read(
                scope("dashboard:read", "ai:dashboard:query"), request("dashboard.context.v1", Map.of()));
        assertTrue(dashboardResult.payloadJson().contains("宿舍总数"));
        assertThrows(IllegalArgumentException.class, () -> adapter.read(
                scope("dashboard:read", "ai:dashboard:query"),
                request("dashboard.context.v1", Map.of("filter", "dynamic"))));

        assertThrows(IllegalArgumentException.class,
                () -> adapter.read(scope(), request("notice.context.v1", Map.of())));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.read(scope(), request("notice.context.v1", Map.of("noticeId", "bad"))));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.read(scope(), request("notice.context.v1", Map.of("noticeId", "0"))));

        RbacService.AuthorizationSnapshot noticeActor = snapshot(true, List.of("ADMIN"),
                "notice:read", "ai:assistant:use");
        when(authorization.snapshot(7L)).thenReturn(noticeActor);
        Notice notice = mock(Notice.class);
        when(notice.getId()).thenReturn(8L);
        when(notice.getTitle()).thenReturn("联系人张三，电话 13800138000");
        when(notice.getContent()).thenReturn("请联系 13800138000");
        when(notice.getType()).thenReturn("通知");
        when(notice.getStatus()).thenReturn("已发布");
        when(notice.getPublishedAt()).thenReturn(LocalDateTime.parse("2026-07-13T08:00:00"));
        when(operations.noticeContext(8L)).thenReturn(notice);
        String payload = adapter.read(scope("notice:read", "ai:assistant:use"),
                request("notice.context.v1", Map.of("noticeId", "8"))).payloadJson();
        assertFalse(payload.contains("13800138000"));

        Notice nullFields = mock(Notice.class);
        when(nullFields.getId()).thenReturn(9L);
        when(operations.noticeContext(9L)).thenReturn(nullFields);
        String nullable = adapter.read(scope("notice:read", "ai:assistant:use"),
                request("notice.context.v1", Map.of("noticeId", "9"))).payloadJson();
        assertTrue(nullable.contains("\"title\":null"));
        assertTrue(nullable.contains("\"publishedAt\":null"));
    }

    @Test
    void repairContextAndCandidatesRequireExactParametersFreshPermissionsAndAdminRole() {
        assertThrows(IllegalArgumentException.class,
                () -> adapter.read(scope(), request("repair.context.v1", Map.of())));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.read(scope(), request("repair.context.v1", Map.of("repairOrderId", "bad"))));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.read(scope(), request("repair.context.v1", Map.of("repairOrderId", "0"))));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.read(scope(), request("repair.assignment-candidates.v1", Map.of())));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.read(scope(), request("repair.assignment-candidates.v1",
                        Map.of("repairOrderId", "bad"))));

        when(authorization.snapshot(7L)).thenReturn(snapshot(true, List.of("REPAIRER"),
                "repair:read", "repair:write", "ai:repair:triage"));
        assertThrows(SecurityException.class, () -> adapter.read(
                scope("repair:read", "repair:write", "ai:repair:triage"),
                request("repair.assignment-candidates.v1", Map.of("repairOrderId", "1"))));

        RbacService.AuthorizationSnapshot admin = snapshot(true, List.of("ADMIN"),
                "repair:read", "repair:write", "ai:repair:triage");
        when(authorization.snapshot(7L)).thenReturn(admin);
        when(operations.repairContextForActor(eq(1L), any())).thenReturn(new OperationsService.RepairAiContext(
                1L, "WX-1", "水电维修", "待处理", null, null, null));
        when(candidates.enabledRepairers()).thenReturn(List.of(new RepairCandidateProvider.Candidate(11L)));
        String candidatePayload = adapter.read(
                scope("repair:read", "repair:write", "ai:repair:triage"),
                request("repair.assignment-candidates.v1", Map.of("repairOrderId", "1"))).payloadJson();
        assertTrue(candidatePayload.contains("维修人员 #11"));

        when(operations.repairContextForActor(eq(2L), any())).thenReturn(new OperationsService.RepairAiContext(
                2L, "WX-2", "水电维修", "待处理", null, null, null));
        String context = adapter.read(scope("repair:read", "ai:repair:triage"),
                request("repair.context.v1", Map.of("repairOrderId", "2"))).payloadJson();
        assertTrue(context.contains("\"description\":null"));
        assertTrue(context.contains("\"asOf\":null"));
    }

    @Test
    void authorizationRechecksClaimedCurrentDisabledAndServiceSubjectFacts() {
        assertThrows(SecurityException.class, () -> adapter.query(metric("dormitory.total"), null));
        assertThrows(SecurityException.class,
                () -> adapter.query(metric("dormitory.total"), scope("dashboard:read")));

        BusinessActorScope system = new BusinessActorScope(
                ActorDescriptor.system("worker"),
                Set.of("dashboard:read", "dormitory:read", "ai:dashboard:query"), Map.of());
        assertThrows(SecurityException.class, () -> adapter.query(metric("dormitory.total"), system));

        BusinessActorScope service = new BusinessActorScope(
                ActorDescriptor.service("worker", 7L, 7L),
                Set.of("dashboard:read", "dormitory:read", "ai:dashboard:query"), Map.of());
        when(authorization.snapshot(7L)).thenReturn(snapshot(false, List.of("ADMIN"),
                "dashboard:read", "dormitory:read", "ai:dashboard:query"));
        assertThrows(SecurityException.class, () -> adapter.query(metric("dormitory.total"), service));
        when(authorization.snapshot(7L)).thenReturn(snapshot(true, List.of("ADMIN"),
                "dashboard:read", "ai:dashboard:query"));
        assertThrows(SecurityException.class, () -> adapter.query(metric("dormitory.total"), service));
    }

    @Test
    void metricQueryShapeDateAndFixedFilterContractsRejectEveryInvalidCombination() {
        LocalDate today = date();
        assertThrows(IllegalArgumentException.class, () -> adapter.query(
                query("dormitory.total", "dormitory.total", "TODAY", today, today,
                        Set.of(), Map.of(), 0), scope()));
        assertThrows(IllegalArgumentException.class, () -> adapter.query(
                query("dormitory.total", "dormitory.total", "TODAY", today, today,
                        Set.of(), Map.of(), 101), scope()));
        assertThrows(IllegalArgumentException.class, () -> adapter.query(
                new MetricQueryExecutor.FixedMetricQuery("dormitory.total", "dormitory.total", null,
                        Set.of(), Map.of(), 100), scope()));
        assertThrows(IllegalArgumentException.class, () -> adapter.query(
                query("dormitory.total", "dormitory.total", "TODAY", null, today,
                        Set.of(), Map.of(), 100), scope()));
        assertThrows(IllegalArgumentException.class, () -> adapter.query(
                query("dormitory.total", "dormitory.total", "TODAY", today, null,
                        Set.of(), Map.of(), 100), scope()));
        assertThrows(IllegalArgumentException.class, () -> adapter.query(
                query("dormitory.total", "dormitory.total", "TODAY", today, today.minusDays(1),
                        Set.of(), Map.of(), 100), scope()));
        assertThrows(IllegalArgumentException.class, () -> adapter.query(
                query("dormitory.total", "dormitory.total", "UNKNOWN", today, today,
                        Set.of(), Map.of(), 100), scope()));
        assertThrows(IllegalArgumentException.class, () -> adapter.query(
                query("dormitory.total", "dormitory.total", "LAST_7_DAYS", today.minusDays(5), today,
                        Set.of(), Map.of(), 100), scope()));
        assertThrows(IllegalArgumentException.class, () -> adapter.query(
                query("repair.pending.count", "repair.pending.count", "TODAY", today, today,
                        Set.of("buildingId"), Map.of(), 100), scope()));
        assertThrows(IllegalArgumentException.class, () -> adapter.query(
                query("repair.pending.count", "repair.pending.count", "TODAY", today, today,
                        Set.of(), Map.of("buildingIds", List.of("1")), 100), scope()));
        assertThrows(IllegalArgumentException.class, () -> adapter.query(
                query("repair.pending.count", "repair.pending.count", "TODAY", today, today,
                        Set.of(), Map.of("repairTypes", List.of("任意维修")), 100), scope()));
        assertThrows(IllegalArgumentException.class, () -> adapter.query(
                query("hygiene.failed.count", "hygiene.failed.count", "TODAY", today, today,
                        Set.of("repairType"), Map.of(), 100), scope()));
        assertThrows(IllegalArgumentException.class, () -> adapter.query(
                query("dormitory.total", "dormitory.total", "LAST_7_DAYS", today.minusDays(6), today,
                        Set.of(), Map.of(), 100), scope()));
    }

    @Test
    void fixedMetricHandlersCountOnlyInRangeFactsAndCoverEverySwitchBranch() {
        LocalDate today = date();
        RbacService.AuthorizationSnapshot dormitoryActor = snapshot(true, List.of("ADMIN"),
                "dashboard:read", "ai:dashboard:query", "dormitory:read");
        when(authorization.snapshot(7L)).thenReturn(dormitoryActor);
        when(dashboard.statisticsForActor(dormitoryActor)).thenReturn(List.of(
                new StatisticCard("宿舍总数", 4, "间", "实时", "blue", "home")));
        assertEquals(4, adapter.query(metric("dormitory.total"), scope(
                "dashboard:read", "ai:dashboard:query", "dormitory:read")).value());
        assertThrows(IllegalStateException.class, () -> adapter.query(metric("bed.available.count"), scope(
                "dashboard:read", "ai:dashboard:query", "dormitory:read")));

        RbacService.AuthorizationSnapshot hygieneActor = snapshot(true, List.of("ADMIN"),
                "dashboard:read", "ai:dashboard:query", "hygiene:read");
        when(authorization.snapshot(7L)).thenReturn(hygieneActor);
        when(operations.hygieneChecks(1, 100, null, "不合格")).thenReturn(new PageResponse<>(List.of(
                new HygieneCheck(1L, "101", "A", today.toString(), "i", 50, "不合格", null),
                new HygieneCheck(2L, "102", "A", today.minusDays(1).toString(), "i", 50, "不合格", null)
        ), 2, 1, 100));
        assertEquals(1, adapter.query(metric("hygiene.failed.count"), scope(
                "dashboard:read", "ai:dashboard:query", "hygiene:read")).value());

        RbacService.AuthorizationSnapshot paymentActor = snapshot(true, List.of("ADMIN"),
                "dashboard:read", "ai:dashboard:query", "payment:read");
        when(authorization.snapshot(7L)).thenReturn(paymentActor);
        when(operations.paymentBills(1, 100, null, null, "未缴")).thenReturn(new PageResponse<>(List.of(
                new Payment(1L, "S1", "n", "水费", BigDecimal.TEN, BigDecimal.ZERO,
                        "未缴", today.toString()),
                new Payment(2L, "S2", "n", "水费", BigDecimal.TEN, BigDecimal.ZERO,
                        "未缴", today.plusDays(1).toString())
        ), 2, 1, 100));
        assertEquals(1, adapter.query(metric("payment.unpaid.count"), scope(
                "dashboard:read", "ai:dashboard:query", "payment:read")).value());
    }

    @Test
    void metricPaginationFailsClosedForNullOversizedChangingAndIncompleteSnapshots() {
        when(authorization.snapshot(7L)).thenReturn(snapshot(true, List.of("ADMIN"),
                "dashboard:read", "ai:dashboard:query", "hygiene:read"));
        BusinessActorScope scope = scope("dashboard:read", "ai:dashboard:query", "hygiene:read");
        when(operations.hygieneChecks(1, 100, null, "不合格")).thenReturn(null);
        assertThrows(IllegalStateException.class,
                () -> adapter.query(metric("hygiene.failed.count"), scope));
        when(operations.hygieneChecks(1, 100, null, "不合格"))
                .thenReturn(new PageResponse<>(null, 0, 1, 100));
        assertThrows(IllegalStateException.class,
                () -> adapter.query(metric("hygiene.failed.count"), scope));
        when(operations.hygieneChecks(1, 100, null, "不合格"))
                .thenReturn(new PageResponse<>(List.of(), -1, 1, 100));
        assertThrows(IllegalStateException.class,
                () -> adapter.query(metric("hygiene.failed.count"), scope));
        when(operations.hygieneChecks(1, 100, null, "不合格"))
                .thenReturn(new PageResponse<>(List.of(), 10_001, 1, 100));
        assertThrows(IllegalStateException.class,
                () -> adapter.query(metric("hygiene.failed.count"), scope));
        when(operations.hygieneChecks(1, 100, null, "不合格"))
                .thenReturn(new PageResponse<>(Collections.nCopies(100,
                                new HygieneCheck(1L, "101", "A", date().toString(), "i", 50, "不合格", null)),
                        101, 1, 100));
        when(operations.hygieneChecks(2, 100, null, "不合格")).thenReturn(null);
        assertThrows(IllegalStateException.class,
                () -> adapter.query(metric("hygiene.failed.count"), scope));
        when(operations.hygieneChecks(1, 100, null, "不合格"))
                .thenReturn(new PageResponse<>(List.of(), 1, 1, 100));
        assertThrows(IllegalStateException.class,
                () -> adapter.query(metric("hygiene.failed.count"), scope));
    }

    private DormitoryBusinessReadAdapter adapter() {
        return adapter;
    }

    private BusinessReadFacade.BusinessReadRequest request(String id, Map<String, String> parameters) {
        return new BusinessReadFacade.BusinessReadRequest(id, parameters);
    }

    private BusinessActorScope scope(String... permissions) {
        return new BusinessActorScope(ActorDescriptor.user(7L), Set.of(permissions), Map.of());
    }

    private RbacService.AuthorizationSnapshot snapshot(
            boolean enabled, List<String> roles, String... permissions) {
        return new RbacService.AuthorizationSnapshot(7L, enabled, roles, List.of(permissions));
    }

    private MetricQueryExecutor.FixedMetricQuery metric(String id) {
        return query(id, id, "TODAY", date(), date(), Set.of(), Map.of(), 100);
    }

    private MetricQueryExecutor.FixedMetricQuery query(
            String metric,
            String handler,
            String preset,
            LocalDate from,
            LocalDate to,
            Set<String> dimensions,
            Map<String, List<String>> filters,
            int maximumRows) {
        return new MetricQueryExecutor.FixedMetricQuery(metric, handler,
                new MetricQueryExecutor.ResolvedDateRange(preset, from, to),
                dimensions, filters, maximumRows);
    }

    private LocalDate date() {
        return LocalDate.parse("2026-07-13");
    }

    private PiiClassificationService classification() {
        return new PiiClassificationService(new PiiRedactionService(
                "business-boundary-test-key-32-bytes".getBytes(StandardCharsets.UTF_8), "v1"), List::of);
    }
}
