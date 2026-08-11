package com.example.dormitory.ai.infrastructure.business;

import cn.dev33.satoken.stp.StpUtil;
import com.example.dormitory.ai.approval.ActionProposalService;
import com.example.dormitory.ai.approval.ActionType;
import com.example.dormitory.ai.approval.CanonicalJsonHasher;
import com.example.dormitory.ai.domain.model.BusinessExecutionActor;
import com.example.dormitory.ai.port.ApprovedBusinessActionPort;
import com.example.dormitory.domain.Notice;
import com.example.dormitory.domain.RepairOrder;
import com.example.dormitory.dto.NoticeRequest;
import com.example.dormitory.service.OperationsService;
import com.example.dormitory.service.RbacService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.function.BooleanSupplier;

/** 仅在同步的真实 USER 请求内，把两个白名单动作交给现有业务 Service。 */
public final class ApprovedDormitoryBusinessActionAdapter implements ApprovedBusinessActionPort {

    private final OperationsService operationsService;
    private final RbacService rbacService;
    private final ActionProposalService.BusinessSnapshotProvider snapshotProvider;
    private final DormitoryBusinessSnapshotProvider lockedSnapshotProvider;
    private final ObjectMapper objectMapper;
    private final BooleanSupplier writeExecutionEnabled;

    public ApprovedDormitoryBusinessActionAdapter(
            OperationsService operationsService,
            RbacService rbacService,
            ActionProposalService.BusinessSnapshotProvider snapshotProvider,
            ObjectMapper objectMapper,
            BooleanSupplier writeExecutionEnabled) {
        this(operationsService, rbacService, snapshotProvider, null, objectMapper, writeExecutionEnabled);
    }

    public ApprovedDormitoryBusinessActionAdapter(
            OperationsService operationsService,
            RbacService rbacService,
            ActionProposalService.BusinessSnapshotProvider snapshotProvider,
            DormitoryBusinessSnapshotProvider lockedSnapshotProvider,
            ObjectMapper objectMapper,
            BooleanSupplier writeExecutionEnabled) {
        this.operationsService = java.util.Objects.requireNonNull(operationsService);
        this.rbacService = java.util.Objects.requireNonNull(rbacService);
        this.snapshotProvider = java.util.Objects.requireNonNull(snapshotProvider);
        this.lockedSnapshotProvider = lockedSnapshotProvider;
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper);
        this.writeExecutionEnabled = java.util.Objects.requireNonNull(writeExecutionEnabled);
    }

    @Override
    public BusinessActionResult execute(BusinessExecutionActor actor, ApprovedBusinessAction action) {
        requireCurrentUser(actor);
        if (action == null || action.actionType() == null) throw new IllegalArgumentException("业务动作不能为空");
        final ActionType actionType;
        try {
            actionType = ActionType.valueOf(action.actionType());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("动作不在固定白名单", exception);
        }
        String canonical = CanonicalJsonHasher.canonicalize(action.payloadJson());
        if (!CanonicalJsonHasher.sha256(canonical).equals(action.payloadHash())) {
            throw new SecurityException("动作 payload hash 不匹配");
        }
        JsonNode payload = parse(canonical);
        return switch (actionType) {
            case NOTICE_CREATE_DRAFT -> {
                requireCurrentSnapshot(actionType, canonical, action.snapshotHash());
                yield createNoticeDraft(actor, payload);
            }
            case REPAIR_ASSIGN -> assignRepair(actor, payload, action.snapshotHash());
        };
    }

    private void requireCurrentSnapshot(ActionType actionType, String canonical, String expectedHash) {
        String currentSnapshot = snapshotProvider.currentSnapshotHash(actionType, canonical);
        if (currentSnapshot == null || expectedHash == null
                || !currentSnapshot.matches("[0-9a-fA-F]{64}")
                || !expectedHash.matches("[0-9a-fA-F]{64}")
                || !java.security.MessageDigest.isEqual(
                currentSnapshot.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                expectedHash.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            throw new SecurityException("业务快照已变化");
        }
    }

    private BusinessActionResult createNoticeDraft(BusinessExecutionActor actor, JsonNode payload) {
        requireFields(payload, Set.of("title", "type", "status", "content"));
        if (!"草稿".equals(payload.path("status").asText())) {
            throw new IllegalArgumentException("公告 AI 只能创建草稿");
        }
        String content = payload.path("content").asText();
        if (content.length() > 10_000 || content.matches("(?s).*<[^>]+>.*")) {
            throw new IllegalArgumentException("公告正文只允许受限纯文本");
        }
        StpUtil.checkPermission("notice:write");
        RbacService.ExecutionIdentity user = requireEnabledUser(actor.userId());
        if (user.displayName() == null || user.displayName().isBlank() || user.displayName().length() > 32) {
            throw new IllegalStateException("当前执行人的发布人名称不合法");
        }
        requireWriteExecutionEnabled();
        Notice notice = operationsService.createNotice(new NoticeRequest(
                payload.path("title").asText(), payload.path("type").asText(), user.displayName(),
                "草稿", content));
        return new BusinessActionResult("NOTICE", notice.getId(), CanonicalJsonHasher.sha256(
                "NOTICE|" + notice.getId() + "|" + notice.getStatus() + "|" + actionContentHash(content)));
    }

    private BusinessActionResult assignRepair(
            BusinessExecutionActor actor, JsonNode payload, String expectedSnapshotHash) {
        requireFields(payload, Set.of("repairOrderId", "assigneeUserId"));
        long repairOrderId = payload.path("repairOrderId").asLong(-1);
        long assigneeUserId = payload.path("assigneeUserId").asLong(-1);
        if (repairOrderId < 1 || assigneeUserId < 1) throw new IllegalArgumentException("维修指派参数不合法");
        StpUtil.checkPermission("repair:write");
        if (!StpUtil.hasRole("ADMIN")) throw new SecurityException("只有系统管理员可以执行维修指派");
        requireEnabledUser(actor.userId());
        requireWriteExecutionEnabled();
        RepairOrder order;
        if (lockedSnapshotProvider == null) {
            requireCurrentSnapshot(ActionType.REPAIR_ASSIGN,
                    CanonicalJsonHasher.canonicalize(payload.toString()), expectedSnapshotHash);
            order = operationsService.assignRepairOrder(repairOrderId, assigneeUserId);
        } else {
            order = operationsService.assignRepairOrder(repairOrderId, assigneeUserId,
                    locked -> lockedSnapshotProvider.requireRepairAssignmentSnapshot(
                            locked, expectedSnapshotHash));
        }
        return new BusinessActionResult("REPAIR_ORDER", order.getId(), CanonicalJsonHasher.sha256(
                "REPAIR_ORDER|" + order.getId() + "|" + order.getStatus() + "|" + order.getAssigneeUserId()));
    }

    private void requireCurrentUser(BusinessExecutionActor actor) {
        if (actor == null || !StpUtil.isLogin() || StpUtil.getLoginIdAsLong() != actor.userId()) {
            throw new SecurityException("业务执行必须使用当前真实 USER 会话");
        }
    }

    private void requireWriteExecutionEnabled() {
        if (!writeExecutionEnabled.getAsBoolean()) {
            throw new SecurityException("AI 写执行 Kill Switch 已关闭");
        }
    }

    private RbacService.ExecutionIdentity requireEnabledUser(long userId) {
        RbacService.ExecutionIdentity user = rbacService.executionIdentityForUser(userId)
                .orElseThrow(() -> new SecurityException("执行账号已停用"));
        if (!user.enabled()) throw new SecurityException("执行账号已停用");
        return user;
    }

    private JsonNode parse(String value) {
        try {
            JsonNode node = objectMapper.readTree(value);
            if (node == null || !node.isObject()) throw new IllegalArgumentException("动作 payload 必须是对象");
            return node;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("动作 payload 不是合法 JSON", exception);
        }
    }

    private void requireFields(JsonNode payload, Set<String> expected) {
        Set<String> actual = new java.util.LinkedHashSet<>();
        payload.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw new IllegalArgumentException("动作 payload 字段不在固定合同中");
    }

    private String actionContentHash(String content) {
        return CanonicalJsonHasher.sha256(content == null ? "" : content);
    }
}
