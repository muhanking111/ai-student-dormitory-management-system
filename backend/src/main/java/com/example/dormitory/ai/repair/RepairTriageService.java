package com.example.dormitory.ai.repair;

import com.example.dormitory.ai.approval.ActionProposalService;
import com.example.dormitory.ai.approval.ActionType;
import com.example.dormitory.ai.approval.CanonicalJsonHasher;
import com.example.dormitory.ai.approval.ProposalOrigin;
import com.example.dormitory.ai.approval.ProposalPreview;
import com.example.dormitory.ai.domain.model.BusinessActorScope;
import com.example.dormitory.ai.domain.model.BusinessExecutionActor;
import com.example.dormitory.ai.port.BusinessReadFacade;
import com.example.dormitory.ai.security.PromptInjectionGuard;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 维修分诊只读取服务端重取的快照，客户端描述和候选人不会进入决策。 */
public final class RepairTriageService {

    private final BusinessReadFacade businessReads;
    private final ActionProposalService proposalService;
    private final PromptInjectionGuard injectionGuard;
    private final ObjectMapper objectMapper;

    public RepairTriageService(
            BusinessReadFacade businessReads,
            ActionProposalService proposalService,
            PromptInjectionGuard injectionGuard,
            ObjectMapper objectMapper) {
        this.businessReads = java.util.Objects.requireNonNull(businessReads);
        this.proposalService = java.util.Objects.requireNonNull(proposalService);
        this.injectionGuard = java.util.Objects.requireNonNull(injectionGuard);
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper);
    }

    public TriageResult triage(
            long repairOrderId,
            BusinessActorScope actorScope,
            BusinessExecutionActor actor,
            String idempotencyKey,
            String requestHash,
            String runPublicId) {
        if (repairOrderId < 1 || actorScope == null || actor == null
                || !actorScope.permissionCodes().containsAll(Set.of("ai:repair:triage", "repair:read"))) {
            throw new SecurityException("缺少维修分诊读取权限或维修单 ID 不合法");
        }
        BusinessReadFacade.BusinessReadResult rawContext = businessReads.read(actorScope,
                new BusinessReadFacade.BusinessReadRequest(
                        "repair.context.v1", Map.of("repairOrderId", String.valueOf(repairOrderId))));
        JsonNode context = parse(rawContext.payloadJson());
        if (context.path("repairOrderId").asLong(-1) != repairOrderId) {
            throw new SecurityException("服务端维修上下文与请求资源不匹配");
        }
        String status = text(context, "status");
        String description = text(context, "description");
        PromptInjectionGuard.Inspection inspection = injectionGuard.inspect(description);
        boolean degraded = inspection.blocked();
        String category = category(description, context.path("type").asText(""));
        String urgency = urgency(description);
        List<String> missing = new ArrayList<>();
        if (description.isBlank()) missing.add("故障现象与安全影响");
        if (degraded) missing.add("请人工核验原始描述");
        if ("HIGH".equals(urgency)) missing.add("是否已切断相关电源或水源");

        Candidate candidate = null;
        ActionProposalService.ProposalView proposal = null;
        if (!degraded && !"已完成".equals(status)
                && actorScope.permissionCodes().contains("repair:write")) {
            candidate = firstCandidate(actorScope, repairOrderId);
            if (candidate != null) {
                String payload = json(Map.of(
                        "repairOrderId", repairOrderId,
                        "assigneeUserId", candidate.userId()));
                Long currentAssignee = context.path("assigneeUserId").isNull()
                        || context.path("assigneeUserId").isMissingNode()
                        ? null : context.path("assigneeUserId").asLong();
                ProposalPreview preview = new ProposalPreview(
                        currentAssignee == null ? "当前维修负责人：未指派" : "当前维修负责人：用户 #" + currentAssignee,
                        "建议维修负责人：" + candidate.displayName(),
                        "仅变更维修负责人，不改变维修状态",
                        rawContext.asOf(), ProposalPreview.EvidenceBasis.DETERMINISTIC, null,
                        List.of(
                                new ProposalPreview.Citation(
                                        "BUSINESS_SNAPSHOT", "REPAIR_ORDER:" + repairOrderId,
                                        "维修单当前授权快照",
                                        CanonicalJsonHasher.sha256(rawContext.payloadJson())),
                                new ProposalPreview.Citation(
                                        "BUSINESS_SNAPSHOT",
                                        "REPAIR_ASSIGNMENT_CANDIDATES:" + repairOrderId,
                                        "维修候选人当前授权快照", candidate.snapshotContentHash())));
                proposal = proposalService.create(new ActionProposalService.CreateProposalCommand(
                                ActionType.REPAIR_ASSIGN, "REPAIR_ORDER", repairOrderId, payload,
                                preview, "repair:write", 1,
                                "HIGH".equals(urgency) ? "HIGH" : "MEDIUM", actor.userId(),
                                Instant.now().plusSeconds(600),
                                ProposalOrigin.forAction(runPublicId, ActionType.REPAIR_ASSIGN)),
                        idempotencyKey, requestHash, actor);
            }
        }

        String summary = degraded
                ? "检测到不受信任指令，已降级为人工核验且未创建指派提案。"
                : "依据服务端维修快照和固定规则生成分诊建议；未改变维修状态。";
        return new TriageResult(repairOrderId, category, urgency, team(category), List.copyOf(missing),
                summary, candidate == null ? null : candidate.userId(),
                candidate == null ? null : candidate.displayName(),
                "HIGH".equals(urgency) ? "建议 2 小时内人工响应，待真实数据校准" : "建议 2 个工作日内处理，待真实数据校准",
                rawContext.asOf(), degraded, proposal);
    }

    private Candidate firstCandidate(BusinessActorScope scope, long repairOrderId) {
        BusinessReadFacade.BusinessReadResult result = businessReads.read(scope,
                new BusinessReadFacade.BusinessReadRequest(
                        "repair.assignment-candidates.v1", Map.of("repairOrderId", String.valueOf(repairOrderId))));
        JsonNode candidates = parse(result.payloadJson()).path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) return null;
        JsonNode first = candidates.get(0);
        long id = first.path("userId").asLong(-1);
        String name = first.path("displayName").asText("").trim();
        if (id < 1 || name.isBlank()) throw new IllegalStateException("维修候选人合同不合法");
        return new Candidate(id, name, CanonicalJsonHasher.sha256(result.payloadJson()));
    }

    private String category(String description, String existingType) {
        String value = description + " " + existingType;
        if (containsAny(value, "插座", "电", "火花", "冒烟", "断路")) return "水电维修";
        if (containsAny(value, "漏水", "水管", "下水", "龙头")) return "给排水维修";
        if (containsAny(value, "门", "窗", "锁", "床", "柜")) return "设施维修";
        return "综合维修";
    }

    private String urgency(String value) {
        return containsAny(value, "冒烟", "火花", "漏电", "起火", "大量漏水", "无法关闭")
                ? "HIGH" : containsAny(value, "漏水", "断电", "无法使用") ? "MEDIUM" : "LOW";
    }

    private String team(String category) {
        return switch (category) {
            case "水电维修", "给排水维修" -> "水电维修组";
            case "设施维修" -> "综合设施组";
            default -> "后勤维修组";
        };
    }

    private boolean containsAny(String value, String... terms) {
        return java.util.Arrays.stream(terms).anyMatch(value::contains);
    }

    private String text(JsonNode node, String field) {
        return node.path(field).isMissingNode() || node.path(field).isNull()
                ? "" : node.path(field).asText("").trim();
    }

    private JsonNode parse(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("业务读取返回非法 JSON", exception);
        }
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("维修提案序列化失败", exception);
        }
    }

    private record Candidate(long userId, String displayName, String snapshotContentHash) {
    }

    public record TriageResult(
            long repairOrderId,
            String category,
            String urgency,
            String recommendedTeam,
            List<String> missingInformation,
            String reasoningSummary,
            Long assignmentCandidateUserId,
            String assignmentCandidateName,
            String slaSuggestion,
            Instant asOf,
            boolean degraded,
            ActionProposalService.ProposalView proposal) {
        public TriageResult {
            missingInformation = List.copyOf(missingInformation);
        }
    }
}
