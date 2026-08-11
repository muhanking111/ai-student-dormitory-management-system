package com.example.dormitory.ai.infrastructure.business;

import com.example.dormitory.ai.dashboard.MetricQueryExecutor;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.ActorKind;
import com.example.dormitory.ai.domain.model.BusinessActorScope;
import com.example.dormitory.ai.port.BusinessReadFacade;
import com.example.dormitory.ai.security.ActorAuthorizationFacade;
import com.example.dormitory.ai.security.PiiClassificationService;
import com.example.dormitory.ai.security.RepairAccessPolicy;
import com.example.dormitory.common.PageResponse;
import com.example.dormitory.domain.HygieneCheck;
import com.example.dormitory.domain.Payment;
import com.example.dormitory.domain.RepairOrder;
import com.example.dormitory.domain.StatisticCard;
import com.example.dormitory.service.DormitoryQueryService;
import com.example.dormitory.service.OperationsService;
import com.example.dormitory.service.RbacService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongFunction;

/**
 * 现有业务服务到 AI 端口的固定白名单适配器。每次读取都会重新加载账号、角色和权限。
 */
public final class DormitoryBusinessReadAdapter
        implements BusinessReadFacade, MetricQueryExecutor.FixedMetricHandler {

    private static final long METRIC_PAGE_SIZE = 100;
    private static final long MAX_METRIC_SCAN_ROWS = 10_000;
    private static final Set<String> REPAIR_TYPES = Set.of("水电维修", "家具维修", "门窗维修");

    private static final Map<String, MetricContract> METRICS = Map.of(
            "dormitory.total", new MetricContract("宿舍总数", Set.of("dashboard:read", "dormitory:read")),
            "student.checked-in.count", new MetricContract("学生入住人数", Set.of("dashboard:read", "student:read")),
            "bed.available.count", new MetricContract("空余床位", Set.of("dashboard:read", "dormitory:read")),
            "repair.pending.count", new MetricContract("待维修数量", Set.of("dashboard:read", "repair:read")),
            "hygiene.failed.count", new MetricContract(null, Set.of("dashboard:read", "hygiene:read")),
            "payment.unpaid.count", new MetricContract(null, Set.of("dashboard:read", "payment:read")));

    private final ActorAuthorizationFacade authorizationFacade;
    private final DormitoryQueryService dashboardService;
    private final OperationsService operationsService;
    private final ObjectMapper objectMapper;
    private final PiiClassificationService classificationService;
    private final RepairCandidateProvider repairCandidateProvider;
    private final Clock clock;

    public DormitoryBusinessReadAdapter(
            ActorAuthorizationFacade authorizationFacade,
            DormitoryQueryService dashboardService,
            OperationsService operationsService,
            ObjectMapper objectMapper,
            PiiClassificationService classificationService) {
        this(authorizationFacade, dashboardService, operationsService, objectMapper, classificationService,
                List::of, Clock.system(ZoneId.of("Asia/Shanghai")));
    }

    public DormitoryBusinessReadAdapter(
            ActorAuthorizationFacade authorizationFacade,
            DormitoryQueryService dashboardService,
            OperationsService operationsService,
            ObjectMapper objectMapper,
            PiiClassificationService classificationService,
            RepairCandidateProvider repairCandidateProvider) {
        this(authorizationFacade, dashboardService, operationsService, objectMapper, classificationService,
                repairCandidateProvider, Clock.system(ZoneId.of("Asia/Shanghai")));
    }

    public DormitoryBusinessReadAdapter(
            ActorAuthorizationFacade authorizationFacade,
            DormitoryQueryService dashboardService,
            OperationsService operationsService,
            ObjectMapper objectMapper,
            PiiClassificationService classificationService,
            RepairCandidateProvider repairCandidateProvider,
            Clock clock) {
        this.authorizationFacade = java.util.Objects.requireNonNull(authorizationFacade);
        this.dashboardService = java.util.Objects.requireNonNull(dashboardService);
        this.operationsService = java.util.Objects.requireNonNull(operationsService);
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper);
        this.classificationService = java.util.Objects.requireNonNull(classificationService);
        this.repairCandidateProvider = java.util.Objects.requireNonNull(repairCandidateProvider);
        this.clock = java.util.Objects.requireNonNull(clock);
    }

    @Override
    public BusinessReadResult read(BusinessActorScope claimedScope, BusinessReadRequest request) {
        if (request == null || request.queryId() == null || request.queryId().isBlank()) {
            throw new IllegalArgumentException("业务读取请求不完整");
        }
        if (METRICS.containsKey(request.queryId())) {
            if (request.parameters() != null && !request.parameters().isEmpty()) {
                throw new IllegalArgumentException("通用业务读取不接受 Dashboard 结构化筛选");
            }
            LocalDate today = LocalDate.now(clock);
            MetricQueryExecutor.FixedMetricQuery metricQuery = new MetricQueryExecutor.FixedMetricQuery(
                    request.queryId(), request.queryId(),
                    new MetricQueryExecutor.ResolvedDateRange("TODAY", today, today),
                    Set.of(), Map.of(), 100);
            MetricQueryExecutor.FixedMetricValue metric = query(metricQuery, claimedScope);
            return json("business-metric-result.v1", Map.of(
                    "queryId", request.queryId(), "value", metric.value(), "asOf", metric.asOf().toString()));
        }
        if ("repair.context.v1".equals(request.queryId())) {
            return readRepairContext(claimedScope, request.parameters());
        }
        if ("repair.assignment-candidates.v1".equals(request.queryId())) {
            return readRepairCandidates(claimedScope, request.parameters());
        }
        if ("dashboard.context.v1".equals(request.queryId())) {
            return readDashboardContext(claimedScope, request.parameters());
        }
        if ("notice.context.v1".equals(request.queryId())) {
            return readNoticeContext(claimedScope, request.parameters());
        }
        throw new IllegalArgumentException("业务读取 queryId 不在固定白名单");
    }

    private BusinessReadResult readDashboardContext(
            BusinessActorScope claimedScope, Map<String, String> parameters) {
        if (parameters == null || !parameters.isEmpty()) {
            throw new IllegalArgumentException("Dashboard 上下文不接受客户端筛选");
        }
        RbacService.AuthorizationSnapshot current = authorize(
                claimedScope, Set.of("dashboard:read", "ai:dashboard:query"));
        List<Map<String, Object>> cards = dashboardService.statisticsForActor(current).stream()
                .map(card -> Map.<String, Object>of(
                        "title", card.title(), "value", card.value(), "unit", card.unit()))
                .toList();
        return json("dashboard-ai-context.v1", Map.of("cards", cards));
    }

    private BusinessReadResult readNoticeContext(
            BusinessActorScope claimedScope, Map<String, String> parameters) {
        if (parameters == null || !parameters.keySet().equals(Set.of("noticeId"))) {
            throw new IllegalArgumentException("公告上下文参数不合法");
        }
        long noticeId;
        try {
            noticeId = Long.parseLong(parameters.get("noticeId"));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("公告 ID 不合法", exception);
        }
        if (noticeId < 1) throw new IllegalArgumentException("公告 ID 不合法");
        authorize(claimedScope, Set.of("notice:read", "ai:assistant:use"));
        com.example.dormitory.domain.Notice notice = operationsService.noticeContext(noticeId);
        String content = notice.getContent() == null ? null
                : classificationService.redact(notice.getContent(), "notice-assistant-context").redactedText();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("noticeId", notice.getId());
        payload.put("title", notice.getTitle() == null ? null
                : classificationService.redact(notice.getTitle(), "notice-assistant-title").redactedText());
        payload.put("type", notice.getType());
        payload.put("status", notice.getStatus());
        payload.put("content", content);
        payload.put("publishedAt", notice.getPublishedAt() == null ? null : notice.getPublishedAt().toString());
        return json("notice-ai-context.v1", payload);
    }

    @Override
    public MetricQueryExecutor.FixedMetricValue query(MetricQueryExecutor.FixedMetricQuery query) {
        throw new SecurityException("生产指标读取必须携带 actor scope");
    }

    @Override
    public MetricQueryExecutor.FixedMetricValue query(
            MetricQueryExecutor.FixedMetricQuery query,
            BusinessActorScope claimedScope) {
        String queryHandlerId = query.queryHandlerId();
        MetricContract contract = METRICS.get(queryHandlerId);
        if (contract == null) throw new IllegalArgumentException("指标 handler 不在固定白名单");
        if (!query.metricId().equals(queryHandlerId)) throw new IllegalArgumentException("指标与固定 handler 不匹配");
        validateMetricQuery(query);
        Set<String> required = new java.util.LinkedHashSet<>(contract.requiredPermissions());
        required.add("ai:dashboard:query");
        RbacService.AuthorizationSnapshot current = authorize(claimedScope, required);

        return switch (queryHandlerId) {
            case "repair.pending.count" -> pendingRepairs(query, current);
            case "hygiene.failed.count" -> failedHygiene(query);
            case "payment.unpaid.count" -> unpaidPayments(query);
            default -> currentSnapshot(contract, current);
        };
    }

    private void validateMetricQuery(MetricQueryExecutor.FixedMetricQuery query) {
        if (query.maximumRows() < 1 || query.maximumRows() > 100 || query.dateRange() == null
                || query.dateRange().from() == null || query.dateRange().to() == null
                || query.dateRange().from().isAfter(query.dateRange().to())) {
            throw new IllegalArgumentException("固定指标执行参数不合法");
        }
        long actualDays = ChronoUnit.DAYS.between(query.dateRange().from(), query.dateRange().to()) + 1;
        long expectedDays = switch (query.dateRange().preset()) {
            case "TODAY" -> 1;
            case "LAST_7_DAYS" -> 7;
            case "LAST_30_DAYS" -> 30;
            default -> throw new IllegalArgumentException("固定指标日期预设不合法");
        };
        if (actualDays != expectedDays) throw new IllegalArgumentException("固定指标日期窗口与预设不一致");
        if ("repair.pending.count".equals(query.metricId())) {
            if (!Set.of("repairType").containsAll(query.dimensions())
                    || !Set.of("repairTypes").containsAll(query.filters().keySet())) {
                throw new IllegalArgumentException("维修指标维度或筛选不受支持");
            }
            List<String> types = query.filters().getOrDefault("repairTypes", List.of());
            if (!REPAIR_TYPES.containsAll(types)) throw new IllegalArgumentException("维修类型不在固定白名单");
            return;
        }
        if (!query.dimensions().isEmpty() || !query.filters().isEmpty()) {
            throw new IllegalArgumentException("当前固定指标不支持维度或筛选");
        }
        if (Set.of("dormitory.total", "student.checked-in.count", "bed.available.count")
                .contains(query.metricId()) && !"TODAY".equals(query.dateRange().preset())) {
            throw new IllegalArgumentException("快照指标只支持 TODAY");
        }
    }

    private MetricQueryExecutor.FixedMetricValue currentSnapshot(
            MetricContract contract,
            RbacService.AuthorizationSnapshot current) {
        Number value = dashboardService.statisticsForActor(current).stream()
                .filter(card -> contract.statisticTitle().equals(card.title()))
                .map(StatisticCard::value)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("固定指标缺少对应业务统计"));
        return new MetricQueryExecutor.FixedMetricValue(value, Instant.now(clock));
    }

    private MetricQueryExecutor.FixedMetricValue pendingRepairs(
            MetricQueryExecutor.FixedMetricQuery query,
            RbacService.AuthorizationSnapshot current) {
        RepairAccessPolicy.RepairActorAccess access = new RepairAccessPolicy.RepairActorAccess(
                current.userId(), current.enabled(), Set.copyOf(current.roleCodes()), Set.copyOf(current.permissionCodes()));
        List<String> filtered = query.filters().getOrDefault("repairTypes", List.of());
        List<String> queryTypes = filtered.isEmpty() && query.dimensions().contains("repairType")
                ? REPAIR_TYPES.stream().sorted().toList() : filtered;
        if (queryTypes.isEmpty()) {
            List<RepairOrder> orders = metricPages(page -> operationsService.repairOrdersForActor(
                    page, METRIC_PAGE_SIZE, null, null, null, access));
            int count = (int) orders.stream().filter(order -> pendingWithin(order, query.dateRange())).count();
            return new MetricQueryExecutor.FixedMetricValue(count, Instant.now(clock));
        }
        List<MetricQueryExecutor.FixedMetricRow> rows = new ArrayList<>();
        int total = 0;
        for (String type : queryTypes) {
            List<RepairOrder> orders = metricPages(page -> operationsService.repairOrdersForActor(
                    page, METRIC_PAGE_SIZE, null, type, null, access));
            int count = (int) orders.stream().filter(order -> pendingWithin(order, query.dateRange())).count();
            total += count;
            if (query.dimensions().contains("repairType")) {
                rows.add(new MetricQueryExecutor.FixedMetricRow(Map.of("repairType", type), count));
            }
        }
        return new MetricQueryExecutor.FixedMetricValue(total, rows, Instant.now(clock));
    }

    private MetricQueryExecutor.FixedMetricValue failedHygiene(MetricQueryExecutor.FixedMetricQuery query) {
        List<HygieneCheck> checks = metricPages(page -> operationsService.hygieneChecks(
                page, METRIC_PAGE_SIZE, null, "不合格"));
        int count = (int) checks.stream().filter(check -> within(check.getDate(), query.dateRange())).count();
        return new MetricQueryExecutor.FixedMetricValue(count, Instant.now(clock));
    }

    private MetricQueryExecutor.FixedMetricValue unpaidPayments(MetricQueryExecutor.FixedMetricQuery query) {
        List<Payment> payments = metricPages(page -> operationsService.paymentBills(
                page, METRIC_PAGE_SIZE, null, null, "未缴"));
        int count = (int) payments.stream().filter(payment -> within(payment.getDeadline(), query.dateRange())).count();
        return new MetricQueryExecutor.FixedMetricValue(count, Instant.now(clock));
    }

    private boolean pendingWithin(RepairOrder order, MetricQueryExecutor.ResolvedDateRange range) {
        return order != null && !"已完成".equals(order.getStatus()) && within(order.getDate(), range);
    }

    private boolean within(String value, MetricQueryExecutor.ResolvedDateRange range) {
        try {
            LocalDate date = LocalDate.parse(value);
            return !date.isBefore(range.from()) && !date.isAfter(range.to());
        } catch (DateTimeParseException | NullPointerException exception) {
            throw new IllegalStateException("业务日期不符合固定指标口径", exception);
        }
    }

    private <T> List<T> metricPages(LongFunction<PageResponse<T>> loader) {
        PageResponse<T> first = loader.apply(1);
        if (first == null || first.records() == null || first.total() < 0 || first.total() > MAX_METRIC_SCAN_ROWS) {
            throw new IllegalStateException("固定指标扫描范围过大或响应无效");
        }
        List<T> records = new ArrayList<>(first.records());
        long pages = (first.total() + METRIC_PAGE_SIZE - 1) / METRIC_PAGE_SIZE;
        for (long page = 2; page <= pages; page++) {
            PageResponse<T> next = loader.apply(page);
            if (next == null || next.records() == null || next.total() != first.total()) {
                throw new IllegalStateException("固定指标分页快照发生变化，请重试");
            }
            records.addAll(next.records());
        }
        if (records.size() != first.total()) throw new IllegalStateException("固定指标分页结果不完整");
        return List.copyOf(records);
    }

    private BusinessReadResult readRepairContext(BusinessActorScope claimedScope, Map<String, String> parameters) {
        if (parameters == null || !parameters.keySet().equals(Set.of("repairOrderId"))) {
            throw new IllegalArgumentException("维修上下文参数不合法");
        }
        final long repairOrderId;
        try {
            repairOrderId = Long.parseLong(parameters.get("repairOrderId"));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("维修单 ID 不合法", exception);
        }
        if (repairOrderId < 1) throw new IllegalArgumentException("维修单 ID 不合法");
        RbacService.AuthorizationSnapshot current = authorize(
                claimedScope, Set.of("repair:read", "ai:repair:triage"));
        RepairAccessPolicy.RepairActorAccess access = new RepairAccessPolicy.RepairActorAccess(
                current.userId(), current.enabled(), Set.copyOf(current.roleCodes()), Set.copyOf(current.permissionCodes()));
        OperationsService.RepairAiContext context = operationsService.repairContextForActor(repairOrderId, access);
        String description = context.description() == null ? null
                : classificationService.redact(context.description(), "repair-triage-context").redactedText();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("repairOrderId", context.repairOrderId());
        payload.put("code", context.code());
        payload.put("type", context.type());
        payload.put("status", context.status());
        payload.put("description", description);
        payload.put("assigneeUserId", context.assigneeUserId());
        payload.put("asOf", context.asOf() == null ? null : context.asOf().toString());
        return json("repair-ai-context.v1", payload);
    }

    private BusinessReadResult readRepairCandidates(
            BusinessActorScope claimedScope,
            Map<String, String> parameters) {
        if (parameters == null || !parameters.keySet().equals(Set.of("repairOrderId"))) {
            throw new IllegalArgumentException("维修候选人参数不合法");
        }
        long repairOrderId;
        try {
            repairOrderId = Long.parseLong(parameters.get("repairOrderId"));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("维修单 ID 不合法", exception);
        }
        RbacService.AuthorizationSnapshot current = authorize(
                claimedScope, Set.of("repair:read", "repair:write", "ai:repair:triage"));
        if (!current.roleCodes().contains("ADMIN")) {
            throw new SecurityException("只有系统管理员可创建维修指派提案");
        }
        RepairAccessPolicy.RepairActorAccess access = new RepairAccessPolicy.RepairActorAccess(
                current.userId(), current.enabled(), Set.copyOf(current.roleCodes()), Set.copyOf(current.permissionCodes()));
        operationsService.repairContextForActor(repairOrderId, access);
        List<Map<String, Object>> candidates = repairCandidateProvider.enabledRepairers().stream()
                .map(candidate -> Map.<String, Object>of(
                        "userId", candidate.userId(),
                        "displayName", "维修人员 #" + candidate.userId()))
                .toList();
        return json("repair-assignment-candidates.v1", Map.of("candidates", candidates));
    }

    private RbacService.AuthorizationSnapshot authorize(BusinessActorScope claimedScope, Set<String> required) {
        if (claimedScope == null || claimedScope.actor() == null || !claimedScope.hasAllPermissions(required)) {
            throw new SecurityException("业务读取的声明权限不足");
        }
        ActorDescriptor actor = claimedScope.actor();
        Long subjectUserId = actor.kind() == ActorKind.USER ? actor.actorUserId() : actor.effectiveSubjectUserId();
        if (subjectUserId == null) throw new SecurityException("后台 actor 未绑定有效业务主体");
        RbacService.AuthorizationSnapshot current = authorizationFacade.snapshot(subjectUserId);
        if (!current.enabled() || !Set.copyOf(current.permissionCodes()).containsAll(required)) {
            throw new SecurityException("账号已停用或权限已撤销");
        }
        return current;
    }

    private BusinessReadResult json(String schemaVersion, Map<String, Object> payload) {
        try {
            return new BusinessReadResult(schemaVersion, objectMapper.writeValueAsString(payload), Instant.now(clock));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("业务读取响应序列化失败", exception);
        }
    }

    private record MetricContract(String statisticTitle, Set<String> requiredPermissions) {
        private MetricContract {
            requiredPermissions = Set.copyOf(requiredPermissions);
        }
    }
}
