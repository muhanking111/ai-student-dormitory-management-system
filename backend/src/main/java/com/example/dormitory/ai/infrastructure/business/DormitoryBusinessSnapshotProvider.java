package com.example.dormitory.ai.infrastructure.business;

import com.example.dormitory.ai.approval.ActionProposalService;
import com.example.dormitory.ai.approval.ActionType;
import com.example.dormitory.ai.approval.CanonicalJsonHasher;
import com.example.dormitory.service.OperationsService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class DormitoryBusinessSnapshotProvider implements ActionProposalService.BusinessSnapshotProvider {

    private final OperationsService operationsService;
    private final ObjectMapper objectMapper;

    public DormitoryBusinessSnapshotProvider(OperationsService operationsService, ObjectMapper objectMapper) {
        this.operationsService = java.util.Objects.requireNonNull(operationsService);
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper);
    }

    @Override
    public String currentSnapshotHash(ActionType actionType, String canonicalPayload) {
        JsonNode payload = parse(canonicalPayload);
        if (actionType == ActionType.NOTICE_CREATE_DRAFT) {
            requireExactFields(payload, Set.of("title", "type", "status", "content"));
            if (!"草稿".equals(payload.path("status").asText())
                    || payload.path("content").asText().length() > 10_000
                    || payload.path("content").asText().matches("(?s).*<[^>]+>.*")) {
                throw new IllegalArgumentException("公告草稿业务快照参数不合法");
            }
            return CanonicalJsonHasher.sha256("NOTICE_DRAFT_CREATE|schema-v1");
        }
        if (actionType != ActionType.REPAIR_ASSIGN) throw new IllegalArgumentException("动作不在业务快照白名单");
        requireExactFields(payload, Set.of("repairOrderId", "assigneeUserId"));
        long repairOrderId = payload.path("repairOrderId").asLong(-1);
        long assigneeUserId = payload.path("assigneeUserId").asLong(-1);
        if (repairOrderId < 1 || assigneeUserId < 1) throw new IllegalArgumentException("维修指派参数不合法");
        OperationsService.RepairAssignmentSnapshot order = operationsService
                .repairAssignmentSnapshot(repairOrderId)
                .orElseThrow(() -> new IllegalArgumentException("维修单不存在"));
        return repairAssignmentSnapshotHash(order);
    }

    public void requireRepairAssignmentSnapshot(
            OperationsService.RepairAssignmentSnapshot lockedSnapshot,
            String expectedHash) {
        if (lockedSnapshot == null || expectedHash == null
                || !expectedHash.matches("[0-9a-fA-F]{64}")) {
            throw new SecurityException("维修业务快照参数不合法");
        }
        String actual = repairAssignmentSnapshotHash(lockedSnapshot);
        if (!java.security.MessageDigest.isEqual(
                actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                expectedHash.toLowerCase(java.util.Locale.ROOT)
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            throw new SecurityException("业务快照已变化");
        }
    }

    public String repairAssignmentSnapshotHash(
            OperationsService.RepairAssignmentSnapshot order) {
        if (order == null) throw new IllegalArgumentException("维修业务快照不能为空");
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schemaVersion", "repair-assignment-snapshot.v1");
        snapshot.put("repairOrderId", order.repairOrderId());
        snapshot.put("status", order.status());
        snapshot.put("assigneeUserId", order.assigneeUserId());
        snapshot.put("updatedAt", order.updatedAt() == null ? null : order.updatedAt().toString());
        return CanonicalJsonHasher.sha256(json(snapshot));
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

    private void requireExactFields(JsonNode payload, Set<String> fields) {
        Set<String> actual = new java.util.LinkedHashSet<>();
        payload.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(fields)) throw new IllegalArgumentException("动作 payload 字段不在固定合同中");
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("业务快照序列化失败", exception);
        }
    }
}
