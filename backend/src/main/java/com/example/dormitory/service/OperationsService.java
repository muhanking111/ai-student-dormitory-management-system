package com.example.dormitory.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.dormitory.ai.security.RepairAccessPolicy;
import com.example.dormitory.common.BusinessException;
import com.example.dormitory.common.PageResponse;
import com.example.dormitory.domain.HygieneCheck;
import com.example.dormitory.domain.Notice;
import com.example.dormitory.domain.Payment;
import com.example.dormitory.domain.PaymentRecord;
import com.example.dormitory.domain.RepairOrder;
import com.example.dormitory.domain.RepairRecord;
import com.example.dormitory.domain.UserAccount;
import com.example.dormitory.dto.HygieneCheckRequest;
import com.example.dormitory.dto.NoticeRequest;
import com.example.dormitory.dto.PaymentBillRequest;
import com.example.dormitory.dto.PaymentRecordRequest;
import com.example.dormitory.dto.PaymentRecordResponse;
import com.example.dormitory.dto.RepairOrderRequest;
import com.example.dormitory.dto.RepairRecordRequest;
import com.example.dormitory.dto.RepairRecordResponse;
import com.example.dormitory.mapper.HygieneCheckMapper;
import com.example.dormitory.mapper.NoticeMapper;
import com.example.dormitory.mapper.PaymentMapper;
import com.example.dormitory.mapper.PaymentRecordMapper;
import com.example.dormitory.mapper.RepairOrderMapper;
import com.example.dormitory.mapper.RepairRecordMapper;
import com.example.dormitory.mapper.UserAccountMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class OperationsService {

    private final RepairOrderMapper repairOrderMapper;
    private final RepairRecordMapper repairRecordMapper;
    private final PaymentMapper paymentMapper;
    private final PaymentRecordMapper paymentRecordMapper;
    private final HygieneCheckMapper hygieneCheckMapper;
    private final NoticeMapper noticeMapper;
    private final UserAccountMapper userAccountMapper;
    private final RbacService rbacService;
    private final DashboardCacheService dashboardCacheService;
    private final RepairAccessPolicy repairAccessPolicy;
    private final Clock clock;

    public OperationsService(
            RepairOrderMapper repairOrderMapper,
            RepairRecordMapper repairRecordMapper,
            PaymentMapper paymentMapper,
            PaymentRecordMapper paymentRecordMapper,
            HygieneCheckMapper hygieneCheckMapper,
            NoticeMapper noticeMapper,
            UserAccountMapper userAccountMapper,
            RbacService rbacService,
            DashboardCacheService dashboardCacheService,
            RepairAccessPolicy repairAccessPolicy,
            Clock clock) {
        this.repairOrderMapper = repairOrderMapper;
        this.repairRecordMapper = repairRecordMapper;
        this.paymentMapper = paymentMapper;
        this.paymentRecordMapper = paymentRecordMapper;
        this.hygieneCheckMapper = hygieneCheckMapper;
        this.noticeMapper = noticeMapper;
        this.userAccountMapper = userAccountMapper;
        this.rbacService = rbacService;
        this.dashboardCacheService = dashboardCacheService;
        this.repairAccessPolicy = repairAccessPolicy;
        this.clock = java.util.Objects.requireNonNull(clock);
    }

    public PageResponse<RepairOrder> repairOrders(long page, long pageSize, String keyword, String type, String status) {
        Long restrictedAssigneeId = isRestrictedRepairer() ? StpUtil.getLoginIdAsLong() : null;
        return queryRepairOrders(page, pageSize, keyword, type, status, restrictedAssigneeId);
    }

    public PageResponse<RepairOrder> repairOrdersForActor(
            long page,
            long pageSize,
            String keyword,
            String type,
            String status,
            RepairAccessPolicy.RepairActorAccess actor) {
        repairAccessPolicy.requireRead(actor);
        Long restrictedAssigneeId = repairAccessPolicy.isRestrictedRepairer(actor) ? actor.userId() : null;
        return queryRepairOrders(page, pageSize, keyword, type, status, restrictedAssigneeId);
    }

    private PageResponse<RepairOrder> queryRepairOrders(
            long page, long pageSize, String keyword, String type, String status, Long restrictedAssigneeId) {
        validatePage(page, pageSize);
        LambdaQueryWrapper<RepairOrder> query = Wrappers.lambdaQuery();
        if (StringUtils.hasText(keyword)) {
            String value = keyword.trim();
            query.and(wrapper -> wrapper.like(RepairOrder::getCode, value)
                    .or().like(RepairOrder::getReporter, value)
                    .or().like(RepairOrder::getLocation, value));
        }
        if (StringUtils.hasText(type)) query.eq(RepairOrder::getType, type.trim());
        if (StringUtils.hasText(status)) query.eq(RepairOrder::getStatus, status.trim());
        if (restrictedAssigneeId != null) query.eq(RepairOrder::getAssigneeUserId, restrictedAssigneeId);
        query.orderByDesc(RepairOrder::getId);
        IPage<RepairOrder> result = repairOrderMapper.selectPage(Page.of(page, pageSize), query);
        return new PageResponse<>(result.getRecords(), result.getTotal(), page, pageSize);
    }

    public PageResponse<RepairRecordResponse> repairRecords(
            long page, long pageSize, String keyword, Long repairOrderId) {
        Long restrictedAssigneeId = isRestrictedRepairer() ? StpUtil.getLoginIdAsLong() : null;
        return queryRepairRecords(page, pageSize, keyword, repairOrderId, restrictedAssigneeId);
    }

    public PageResponse<RepairRecordResponse> repairRecordsForActor(
            long page,
            long pageSize,
            String keyword,
            Long repairOrderId,
            RepairAccessPolicy.RepairActorAccess actor) {
        repairAccessPolicy.requireRead(actor);
        Long restrictedAssigneeId = repairAccessPolicy.isRestrictedRepairer(actor) ? actor.userId() : null;
        return queryRepairRecords(page, pageSize, keyword, repairOrderId, restrictedAssigneeId);
    }

    private PageResponse<RepairRecordResponse> queryRepairRecords(
            long page, long pageSize, String keyword, Long repairOrderId, Long restrictedAssigneeId) {
        validatePage(page, pageSize);
        LambdaQueryWrapper<RepairRecord> query = Wrappers.lambdaQuery();
        if (repairOrderId != null) query.eq(RepairRecord::getRepairOrderId, repairOrderId);
        if (restrictedAssigneeId != null) {
            List<Long> assignedOrderIds = assignedRepairOrderIds(restrictedAssigneeId);
            if (assignedOrderIds.isEmpty()) return new PageResponse<>(List.of(), 0, page, pageSize);
            query.in(RepairRecord::getRepairOrderId, assignedOrderIds);
        }
        if (StringUtils.hasText(keyword)) {
            String value = keyword.trim();
            List<Long> matchingOrderIds = repairOrderMapper.selectList(Wrappers.<RepairOrder>lambdaQuery()
                            .select(RepairOrder::getId)
                            .and(wrapper -> wrapper.like(RepairOrder::getCode, value)
                                    .or().like(RepairOrder::getReporter, value)
                                    .or().like(RepairOrder::getLocation, value)
                                    .or().like(RepairOrder::getType, value)))
                    .stream().map(RepairOrder::getId).toList();
            query.and(wrapper -> {
                wrapper.like(RepairRecord::getHandler, value)
                        .or().like(RepairRecord::getContent, value)
                        .or().like(RepairRecord::getStatus, value);
                if (!matchingOrderIds.isEmpty()) {
                    wrapper.or().in(RepairRecord::getRepairOrderId, matchingOrderIds);
                }
            });
        }
        query.orderByDesc(RepairRecord::getHandledAt).orderByDesc(RepairRecord::getId);
        IPage<RepairRecord> result = repairRecordMapper.selectPage(Page.of(page, pageSize), query);
        return new PageResponse<>(enrichRepairRecords(result.getRecords()), result.getTotal(), page, pageSize);
    }

    public RepairAiContext repairContextForActor(
            Long repairOrderId,
            RepairAccessPolicy.RepairActorAccess actor) {
        RepairOrder order = repairOrderMapper.selectById(repairOrderId);
        repairAccessPolicy.requireOrderRead(actor, order);
        return new RepairAiContext(order.getId(), order.getCode(), order.getType(), order.getStatus(),
                normalizeNullable(order.getDescription()), order.getAssigneeUserId(), order.getUpdatedAt());
    }

    public record RepairAiContext(
            Long repairOrderId,
            String code,
            String type,
            String status,
            String description,
            Long assigneeUserId,
            LocalDateTime asOf) {
    }

    public Optional<RepairAssignmentSnapshot> repairAssignmentSnapshot(Long repairOrderId) {
        if (repairOrderId == null || repairOrderId < 1) return Optional.empty();
        RepairOrder order = repairOrderMapper.selectById(repairOrderId);
        if (order == null) return Optional.empty();
        return Optional.of(new RepairAssignmentSnapshot(
                order.getId(), order.getStatus(), order.getAssigneeUserId(), order.getUpdatedAt()));
    }

    public record RepairAssignmentSnapshot(
            Long repairOrderId,
            String status,
            Long assigneeUserId,
            LocalDateTime updatedAt) {
    }

    @Transactional
    public RepairOrder createRepairOrder(RepairOrderRequest request) {
        StpUtil.checkPermission("repair:write");
        Long assigneeUserId = request.assigneeUserId();
        if (isRestrictedRepairer()) {
            long currentUserId = StpUtil.getLoginIdAsLong();
            if (assigneeUserId != null && assigneeUserId != currentUserId) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "维修人员只能将新报修单分配给自己");
            }
            assigneeUserId = currentUserId;
        } else if (assigneeUserId != null) {
            requireAdmin();
            requireEnabledRepairer(assigneeUserId);
        }
        RepairOrder order = new RepairOrder(null, nextRepairCode(), request.reporter().trim(),
                request.location().trim(), request.type().trim(), LocalDate.now(clock).toString(), "待处理",
                normalizeNullable(request.description()), assigneeUserId);
        repairOrderMapper.insert(order);
        dashboardCacheService.evictStatistics();
        return order;
    }

    @Transactional
    public RepairOrder assignRepairOrder(Long orderId, Long assigneeUserId) {
        return assignRepairOrder(orderId, assigneeUserId, ignored -> { });
    }

    @Transactional
    public RepairOrder assignRepairOrder(
            Long orderId,
            Long assigneeUserId,
            RepairAssignmentPrecondition precondition) {
        StpUtil.checkPermission("repair:write");
        requireAdmin();
        RepairOrder order = requireRepairOrderForUpdate(orderId);
        if (!rbacService.hasEnabledRoleForUserForUpdate(assigneeUserId, "REPAIRER")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "指派用户必须是已启用的维修人员");
        }
        if (precondition == null) throw new IllegalArgumentException("维修指派前置条件不能为空");
        precondition.verify(new RepairAssignmentSnapshot(
                order.getId(), order.getStatus(), order.getAssigneeUserId(), order.getUpdatedAt()));
        if ("已完成".equals(order.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "已完成报修单不能重新分配");
        }
        order.setAssigneeUserId(assigneeUserId);
        repairOrderMapper.updateById(order);
        dashboardCacheService.evictStatistics();
        return order;
    }

    @FunctionalInterface
    public interface RepairAssignmentPrecondition {
        void verify(RepairAssignmentSnapshot lockedSnapshot);
    }

    @Transactional
    public RepairOrder addRepairRecord(Long orderId, RepairRecordRequest request) {
        StpUtil.checkPermission("repair:write");
        RepairOrder order = requireRepairOrderForUpdate(orderId);
        ensureRepairOrderAccess(order);
        if ("已完成".equals(order.getStatus())) {
            if (!"已完成".equals(request.status())) {
                throw new BusinessException(HttpStatus.CONFLICT, "已完成报修单只能补充已完成记录");
            }
        } else {
            String expectedStatus = "待处理".equals(order.getStatus()) ? "处理中" : "已完成";
            if (!expectedStatus.equals(request.status())) {
                throw new BusinessException(HttpStatus.CONFLICT,
                        "待处理".equals(order.getStatus())
                                ? "维修状态只能从待处理变更为处理中"
                                : "维修状态只能从处理中变更为已完成");
            }
        }
        UserAccount operator = currentUser();
        RepairRecord record = new RepairRecord(null, orderId, operator.getDisplayName(), request.content().trim(),
                request.cost(), request.status(), LocalDateTime.now(clock), operator.getId());
        repairRecordMapper.insert(record);
        if (!"已完成".equals(order.getStatus())) {
            order.setStatus(request.status());
            repairOrderMapper.updateById(order);
        }
        dashboardCacheService.evictStatistics();
        return order;
    }

    public PageResponse<Payment> paymentBills(long page, long pageSize, String keyword, String type, String status) {
        validatePage(page, pageSize);
        LambdaQueryWrapper<Payment> query = Wrappers.lambdaQuery();
        if (StringUtils.hasText(keyword)) {
            String value = keyword.trim();
            query.and(wrapper -> wrapper.like(Payment::getStudentNo, value).or().like(Payment::getName, value));
        }
        if (StringUtils.hasText(type)) query.eq(Payment::getType, type.trim());
        if (StringUtils.hasText(status)) query.eq(Payment::getStatus, status.trim());
        query.orderByDesc(Payment::getId);
        IPage<Payment> result = paymentMapper.selectPage(Page.of(page, pageSize), query);
        return new PageResponse<>(result.getRecords(), result.getTotal(), page, pageSize);
    }

    public PageResponse<PaymentRecordResponse> paymentRecords(
            long page, long pageSize, String keyword, Long paymentId) {
        validatePage(page, pageSize);
        LambdaQueryWrapper<PaymentRecord> query = Wrappers.lambdaQuery();
        if (paymentId != null) query.eq(PaymentRecord::getPaymentId, paymentId);
        if (StringUtils.hasText(keyword)) {
            String value = keyword.trim();
            List<Long> matchingPaymentIds = paymentMapper.selectList(Wrappers.<Payment>lambdaQuery()
                            .select(Payment::getId)
                            .and(wrapper -> wrapper.like(Payment::getStudentNo, value)
                                    .or().like(Payment::getName, value)
                                    .or().like(Payment::getType, value)
                                    .or().like(Payment::getStatus, value)))
                    .stream().map(Payment::getId).toList();
            query.and(wrapper -> {
                wrapper.like(PaymentRecord::getMethod, value);
                if (!matchingPaymentIds.isEmpty()) {
                    wrapper.or().in(PaymentRecord::getPaymentId, matchingPaymentIds);
                }
            });
        }
        query.orderByDesc(PaymentRecord::getPaidAt).orderByDesc(PaymentRecord::getId);
        IPage<PaymentRecord> result = paymentRecordMapper.selectPage(Page.of(page, pageSize), query);
        return new PageResponse<>(enrichPaymentRecords(result.getRecords()), result.getTotal(), page, pageSize);
    }

    @Transactional
    public Payment createPaymentBill(PaymentBillRequest request) {
        StpUtil.checkPermission("payment:write");
        Payment payment = new Payment(null, request.studentNo().trim(), request.name().trim(), request.type().trim(),
                request.amountDue(), BigDecimal.ZERO, "未缴", request.deadline().trim());
        paymentMapper.insert(payment);
        return payment;
    }

    @Transactional
    public Payment payBill(Long billId, PaymentRecordRequest request) {
        StpUtil.checkPermission("payment:write");
        Payment payment = requirePaymentForUpdate(billId);
        BigDecimal remaining = payment.getAmountDue().subtract(payment.getAmountPaid());
        if (request.amount().compareTo(remaining) > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "缴费金额超过待缴金额");
        }
        paymentRecordMapper.insert(new PaymentRecord(null, billId, request.amount(), request.method().trim(),
                LocalDateTime.now(clock), StpUtil.getLoginIdAsLong()));
        payment.setAmountPaid(payment.getAmountPaid().add(request.amount()));
        int paidComparison = payment.getAmountPaid().compareTo(payment.getAmountDue());
        payment.setStatus(paidComparison >= 0 ? "已缴"
                : payment.getAmountPaid().signum() > 0 ? "部分缴" : "未缴");
        paymentMapper.updateById(payment);
        return payment;
    }

    public PageResponse<HygieneCheck> hygieneChecks(long page, long pageSize, String keyword, String resultValue) {
        validatePage(page, pageSize);
        LambdaQueryWrapper<HygieneCheck> query = Wrappers.lambdaQuery();
        if (StringUtils.hasText(keyword)) {
            String value = keyword.trim();
            query.and(wrapper -> wrapper.like(HygieneCheck::getDormitory, value).or().like(HygieneCheck::getBuilding, value));
        }
        if (StringUtils.hasText(resultValue)) query.eq(HygieneCheck::getResult, resultValue.trim());
        query.orderByDesc(HygieneCheck::getId);
        IPage<HygieneCheck> result = hygieneCheckMapper.selectPage(Page.of(page, pageSize), query);
        return new PageResponse<>(result.getRecords(), result.getTotal(), page, pageSize);
    }

    @Transactional
    public HygieneCheck createHygieneCheck(HygieneCheckRequest request) {
        StpUtil.checkPermission("hygiene:write");
        HygieneCheck check = toHygieneCheck(null, request);
        hygieneCheckMapper.insert(check);
        dashboardCacheService.evictStatistics();
        return check;
    }

    @Transactional
    public HygieneCheck updateHygieneCheck(Long id, HygieneCheckRequest request) {
        StpUtil.checkPermission("hygiene:write");
        requireHygieneCheck(id);
        HygieneCheck check = toHygieneCheck(id, request);
        hygieneCheckMapper.updateById(check);
        dashboardCacheService.evictStatistics();
        return check;
    }

    @Transactional
    public void deleteHygieneCheck(Long id) {
        StpUtil.checkPermission("hygiene:write");
        requireHygieneCheck(id);
        hygieneCheckMapper.deleteById(id);
        dashboardCacheService.evictStatistics();
    }

    public PageResponse<Notice> notices(long page, long pageSize, String keyword, String type, String status) {
        validatePage(page, pageSize);
        LambdaQueryWrapper<Notice> query = Wrappers.lambdaQuery();
        if (StringUtils.hasText(keyword)) query.like(Notice::getTitle, keyword.trim());
        if (StringUtils.hasText(type)) query.eq(Notice::getType, type.trim());
        if (StringUtils.hasText(status)) query.eq(Notice::getStatus, status.trim());
        if ("已发布".equals(status)) {
            query.orderByDesc(Notice::getPublishedAt).orderByDesc(Notice::getId);
        } else {
            query.orderByDesc(Notice::getId);
        }
        IPage<Notice> result = noticeMapper.selectPage(Page.of(page, pageSize), query);
        return new PageResponse<>(result.getRecords(), result.getTotal(), page, pageSize);
    }

    /**
     * AI 业务读取适配器使用的只读事实查询。调用方必须先通过 actor-aware RBAC 复核。
     */
    public Notice noticeContext(Long id) {
        return requireNotice(id);
    }

    @Transactional
    public Notice createNotice(NoticeRequest request) {
        StpUtil.checkPermission("notice:write");
        LocalDateTime publishedAt = "已发布".equals(request.status()) ? nextPublishedAt() : null;
        Notice notice = toNotice(null, request, publishedAt);
        noticeMapper.insert(notice);
        return notice;
    }

    @Transactional
    public Notice updateNotice(Long id, NoticeRequest request) {
        StpUtil.checkPermission("notice:write");
        Notice current = requireNotice(id);
        if ("已撤回".equals(current.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "已撤回公告不能再次编辑");
        }
        if ("已发布".equals(current.getStatus()) && "草稿".equals(request.status())) {
            throw new BusinessException(HttpStatus.CONFLICT, "已发布公告不能改回草稿，请先撤回");
        }
        LocalDateTime publishedAt = current.getPublishedAt();
        if (!"已发布".equals(current.getStatus()) && "已发布".equals(request.status())) {
            publishedAt = nextPublishedAt();
        }
        Notice notice = toNotice(id, request, publishedAt);
        noticeMapper.updateById(notice);
        return notice;
    }

    @Transactional
    public void deleteNotice(Long id) {
        StpUtil.checkPermission("notice:write");
        Notice notice = requireNotice(id);
        if ("已撤回".equals(notice.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "已撤回公告必须保留记录");
        }
        if ("已发布".equals(notice.getStatus())) {
            notice.setStatus("已撤回");
            noticeMapper.updateById(notice);
            return;
        }
        noticeMapper.deleteById(id);
    }

    private RepairOrder requireRepairOrderForUpdate(Long id) {
        RepairOrder order = repairOrderMapper.selectOne(Wrappers.<RepairOrder>lambdaQuery()
                .eq(RepairOrder::getId, id).last("FOR UPDATE"));
        if (order == null) throw new BusinessException(HttpStatus.NOT_FOUND, "报修单不存在");
        return order;
    }

    private Payment requirePaymentForUpdate(Long id) {
        Payment payment = paymentMapper.selectOne(Wrappers.<Payment>lambdaQuery()
                .eq(Payment::getId, id).last("FOR UPDATE"));
        if (payment == null) throw new BusinessException(HttpStatus.NOT_FOUND, "缴费账单不存在");
        return payment;
    }

    private void requireHygieneCheck(Long id) {
        if (hygieneCheckMapper.selectById(id) == null) throw new BusinessException(HttpStatus.NOT_FOUND, "卫生检查不存在");
    }

    private Notice requireNotice(Long id) {
        Notice notice = noticeMapper.selectById(id);
        if (notice == null) throw new BusinessException(HttpStatus.NOT_FOUND, "公告不存在");
        return notice;
    }

    private List<RepairRecordResponse> enrichRepairRecords(List<RepairRecord> records) {
        if (records.isEmpty()) return List.of();
        Map<Long, RepairOrder> orders = repairOrderMapper.selectByIds(
                        records.stream().map(RepairRecord::getRepairOrderId).distinct().toList())
                .stream().collect(Collectors.toMap(RepairOrder::getId, Function.identity()));
        return records.stream().map(record -> {
            RepairOrder order = orders.get(record.getRepairOrderId());
            return new RepairRecordResponse(record.getId(), record.getRepairOrderId(),
                    order == null ? null : order.getLocation(), record.getHandler(), record.getContent(),
                    record.getCost(), record.getStatus(), record.getHandledAt(), record.getOperatorUserId());
        }).toList();
    }

    private List<PaymentRecordResponse> enrichPaymentRecords(List<PaymentRecord> records) {
        if (records.isEmpty()) return List.of();
        Map<Long, Payment> payments = paymentMapper.selectByIds(
                        records.stream().map(PaymentRecord::getPaymentId).distinct().toList())
                .stream().collect(Collectors.toMap(Payment::getId, Function.identity()));
        Map<Long, UserAccount> operators = loadUsers(
                records.stream().map(PaymentRecord::getOperatorUserId).toList());
        return records.stream().map(record -> {
            Payment payment = payments.get(record.getPaymentId());
            UserAccount operator = operators.get(record.getOperatorUserId());
            return new PaymentRecordResponse(record.getId(), record.getPaymentId(),
                    payment == null ? null : payment.getStudentNo(), payment == null ? null : payment.getName(),
                    payment == null ? null : payment.getType(), record.getAmount(), record.getMethod(), record.getPaidAt(),
                    record.getOperatorUserId(), operator == null ? null : operator.getDisplayName());
        }).toList();
    }

    private List<Long> assignedRepairOrderIds(Long assigneeUserId) {
        return repairOrderMapper.selectList(Wrappers.<RepairOrder>lambdaQuery()
                        .select(RepairOrder::getId)
                        .eq(RepairOrder::getAssigneeUserId, assigneeUserId))
                .stream().map(RepairOrder::getId).toList();
    }

    private void ensureRepairOrderAccess(RepairOrder order) {
        if (isRestrictedRepairer()
                && !java.util.Objects.equals(order.getAssigneeUserId(), StpUtil.getLoginIdAsLong())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "只能处理分配给自己的维修单");
        }
    }

    private boolean isRestrictedRepairer() {
        return StpUtil.hasRole("REPAIRER") && !StpUtil.hasRole("ADMIN");
    }

    private void requireAdmin() {
        if (!StpUtil.hasRole("ADMIN")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "仅系统管理员可以分配维修单");
        }
    }

    private UserAccount requireEnabledRepairer(Long userId) {
        UserAccount user = userAccountMapper.selectById(userId);
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())
                || !rbacService.roleCodesForUser(userId).contains("REPAIRER")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "指派用户必须是已启用的维修人员");
        }
        return user;
    }

    private UserAccount currentUser() {
        UserAccount user = userAccountMapper.selectById(StpUtil.getLoginIdAsLong());
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "当前用户不存在或已停用");
        }
        return user;
    }

    private Map<Long, UserAccount> loadUsers(List<Long> ids) {
        List<Long> distinctIds = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (distinctIds.isEmpty()) return Map.of();
        return userAccountMapper.selectByIds(distinctIds).stream()
                .collect(Collectors.toMap(UserAccount::getId, Function.identity()));
    }

    private String normalizeNullable(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private HygieneCheck toHygieneCheck(Long id, HygieneCheckRequest request) {
        return new HygieneCheck(id, request.dormitory().trim(), request.building().trim(), LocalDate.now(clock).toString(),
                request.inspector().trim(), request.score(), hygieneResult(request.score()),
                normalizeNullable(request.remark()));
    }

    private Notice toNotice(Long id, NoticeRequest request, LocalDateTime publishedAt) {
        return new Notice(id, request.title().trim(), request.type().trim(), LocalDate.now(clock).toString(),
                request.publisher().trim(), request.status(), normalizeNullable(request.content()), publishedAt);
    }

    private LocalDateTime nextPublishedAt() {
        LocalDateTime candidate = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        Notice latest = noticeMapper.selectOne(Wrappers.<Notice>lambdaQuery()
                .isNotNull(Notice::getPublishedAt)
                .orderByDesc(Notice::getPublishedAt)
                .last("LIMIT 1"));
        if (latest == null || latest.getPublishedAt() == null) return candidate;
        LocalDateTime previous = latest.getPublishedAt().truncatedTo(ChronoUnit.MICROS);
        return candidate.isAfter(previous) ? candidate : previous.plusNanos(1_000);
    }

    private String hygieneResult(int score) {
        if (score >= 85) return "优秀";
        if (score >= 75) return "良好";
        if (score >= 60) return "一般";
        return "不合格";
    }

    private String nextRepairCode() {
        return "WX" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")
                .format(LocalDateTime.now(clock))
                + java.util.UUID.randomUUID().toString().substring(0, 6).toUpperCase(java.util.Locale.ROOT);
    }

    private void validatePage(long page, long pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "分页参数不合法");
        }
    }
}
