package com.example.dormitory.ai.api;

import com.example.dormitory.ai.application.run.AiActorContext;
import com.example.dormitory.ai.application.run.AiActorResolver;
import com.example.dormitory.ai.application.run.AiRuntimeGate;
import com.example.dormitory.ai.approval.CanonicalJsonHasher;
import com.example.dormitory.ai.domain.model.BusinessExecutionActor;
import com.example.dormitory.ai.domain.model.AiCapability;
import com.example.dormitory.ai.risk.RiskActorKind;
import com.example.dormitory.ai.risk.RiskCase;
import com.example.dormitory.ai.risk.RiskCaseService;
import com.example.dormitory.ai.risk.RiskCaseState;
import com.example.dormitory.ai.risk.RiskScanScope;
import com.example.dormitory.ai.risk.RiskScanScopeFactory;
import com.example.dormitory.ai.security.ActorAuthorizationFacade;
import com.example.dormitory.common.ApiResponse;
import com.example.dormitory.common.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Validated
@RestController
@RequestMapping("/api/ai/risk-cases")
@ConditionalOnProperty(prefix = "dormitory.ai.capabilities", name = "risk", havingValue = "true")
public class AiRiskController {

    private static final Map<String, Set<String>> TYPE_FILTERS = Map.of(
            "维修风险", Set.of("repair-backlog", "repeat-repair"),
            "入住风险", Set.of("resource-checkin-inconsistency", "long-pending-operation"),
            "卫生风险", Set.of("failed-hygiene-check"),
            "欠费风险", Set.of("overdue-payment"));

    private final RiskCaseService riskCases;
    private final AiActorResolver actors;
    private final AiRuntimeGate runtimeGate;
    private final RiskScanScopeFactory scopeFactory;
    private final ActorAuthorizationFacade authorization;

    public AiRiskController(
            RiskCaseService riskCases,
            AiActorResolver actors,
            AiRuntimeGate runtimeGate,
            RiskScanScopeFactory scopeFactory,
            ActorAuthorizationFacade authorization) {
        this.riskCases = riskCases;
        this.actors = actors;
        this.runtimeGate = runtimeGate;
        this.scopeFactory = scopeFactory;
        this.authorization = authorization;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<RiskCaseResponse>>> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) @Size(max = 100) String keyword) {
        runtimeGate.requireCapability(AiCapability.RISK);
        AiActorContext actor = actors.current("ai:risk:read");
        RiskCaseState stateFilter = state(state);
        Set<String> typeFilters = types(type);
        RiskCaseService.PageResult result = riskCases.listAuthorized(
                stateFilter, typeFilters, page, pageSize, keyword, scope(actor));
        List<RiskCaseResponse> records = result.records().stream().map(this::response).toList();
        return ResponseEntity.ok().headers(AiApiHeaders.privateNoStore()).body(ApiResponse.ok(
                new PageResponse<>(records, result.total(), result.page(), result.pageSize())));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<RiskCaseResponse>> get(@PathVariable @Size(max = 64) String id) {
        runtimeGate.requireCapability(AiCapability.RISK);
        AiActorContext actor = actors.current("ai:risk:read");
        return ResponseEntity.ok().headers(AiApiHeaders.privateNoStore())
                .body(ApiResponse.ok(response(requireCase(id, scope(actor)))));
    }

    @PostMapping("/{id}/acknowledge")
    public ResponseEntity<Void> acknowledge(
            @PathVariable @Size(max = 64) String id,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody DispositionRequest request) {
        return transition(id, RiskCaseState.ACKNOWLEDGED, idempotencyKey, request);
    }

    @PostMapping("/{id}/resolve")
    public ResponseEntity<Void> resolve(
            @PathVariable @Size(max = 64) String id,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody DispositionRequest request) {
        return transition(id, RiskCaseState.RESOLVED, idempotencyKey, request);
    }

    @PostMapping("/{id}/dismiss")
    public ResponseEntity<Void> dismiss(
            @PathVariable @Size(max = 64) String id,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody DispositionRequest request) {
        return transition(id, RiskCaseState.DISMISSED, idempotencyKey, request);
    }

    private ResponseEntity<Void> transition(
            String id,
            RiskCaseState target,
            String idempotencyKey,
            DispositionRequest request) {
        runtimeGate.requireCapability(AiCapability.RISK);
        AiActorContext actor = actors.current("ai:risk:manage");
        if (!actor.permissionCodes().contains("ai:risk:read")) throw new SecurityException("风险案例不可见");
        requireCase(id, scope(actor));
        String detail = request.detail().trim();
        Instant dueAtInstant = request.dueAtInstant();
        String dueAt = dueAtInstant == null ? "" : dueAtInstant.toString();
        String requestHash = CanonicalJsonHasher.sha256(
                id.length() + ":" + id + "|" + target.name() + "|" + request.caseVersion()
                        + "|" + detail.length() + ":" + detail + "|" + dueAt.length() + ":" + dueAt);
        try {
            riskCases.transition(id, target, request.caseVersion() - 1,
                    BusinessExecutionActor.from(actor.actor()), Set.copyOf(actor.permissionCodes()), detail,
                    idempotencyKey, requestHash, dueAtInstant, () -> freshAuthorization(actor.userId()));
        } catch (RiskCaseService.RiskAccessDeniedException denied) {
            throw hiddenRiskCase();
        }
        return ResponseEntity.noContent().headers(AiApiHeaders.privateNoStore()).build();
    }

    private RiskCaseService.CaseView requireCase(String id, RiskScanScope scope) {
        try {
            return riskCases.get(id, scope);
        } catch (IllegalArgumentException notFound) {
            throw hiddenRiskCase();
        }
    }

    private RiskCaseService.TransitionAuthorization freshAuthorization(long actorUserId) {
        var snapshot = authorization.snapshotForUpdate(actorUserId);
        Set<String> permissions = Set.copyOf(snapshot.permissionCodes());
        if (!snapshot.enabled()
                || !permissions.containsAll(Set.of("ai:risk:read", "ai:risk:manage"))) {
            return new RiskCaseService.TransitionAuthorization(
                    actorUserId, snapshot.enabled(), permissions, null);
        }
        RiskScanScope currentScope = scopeFactory.capture(actorUserId, Set.copyOf(snapshot.roleCodes()),
                permissions, Instant.now());
        return new RiskCaseService.TransitionAuthorization(
                actorUserId, true, permissions, currentScope);
    }

    private AiApiException hiddenRiskCase() {
        return new AiApiException(
                HttpStatus.NOT_FOUND, "AI_RISK_CASE_NOT_FOUND", "风险案例不可见", false);
    }

    private RiskScanScope scope(AiActorContext actor) {
        return scopeFactory.capture(actor.userId(), Set.copyOf(actor.roleCodes()),
                Set.copyOf(actor.permissionCodes()), java.time.Instant.now());
    }

    private RiskCaseState state(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return RiskCaseState.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new AiApiException(HttpStatus.BAD_REQUEST, "AI_RISK_STATE_INVALID", "风险状态过滤不合法", false);
        }
    }

    private Set<String> types(String value) {
        if (value == null || value.isBlank()) return Set.of();
        Set<String> mapped = TYPE_FILTERS.get(value.trim());
        if (mapped == null) {
            throw new AiApiException(HttpStatus.BAD_REQUEST, "AI_RISK_TYPE_INVALID", "风险类型过滤不合法", false);
        }
        return mapped;
    }

    private RiskCaseResponse response(RiskCaseService.CaseView value) {
        String explanation = value.explanation();
        boolean degraded = value.explanationDegraded();
        List<RiskEventResponse> events = value.events().stream()
                .map(event -> event(value.publicId(), event)).toList();
        String runPublicId = value.explanationEvidence().runPublicId();
        String dueAt = value.dueAt() == null ? null : value.dueAt().toString();
        RiskEvidenceLayersResponse evidenceLayers = new RiskEvidenceLayersResponse(
                value.signalEvidence(), value.businessSnapshot(),
                new RiskExplanationResponse(explanation,
                        value.explanationEvidence().basis().name().toLowerCase(java.util.Locale.ROOT),
                        value.explanationEvidence().policyVersion(), runPublicId,
                        value.explanationEvidence().confidence(), degraded),
                new RiskHumanEvidenceResponse(value.assigneeUserId(), dueAt, events));
        return new RiskCaseResponse(value.publicId(), value.version() + 1, value.subjectToken(),
                displayType(value.riskType()), severity(value.severity()), value.signalPolicyVersion(),
                evidenceSummary(value), explanation, null,
                value.assigneeUserId() == null ? "待人工分配" : "用户 #" + value.assigneeUserId(),
                dueAt == null ? "按运营规则人工核验" : "截止 " + dueAt,
                value.state().name().toLowerCase(java.util.Locale.ROOT), value.asOf().toString(),
                events, degraded, runPublicId, value.assigneeUserId(), dueAt, evidenceLayers);
    }

    private RiskEventResponse event(String caseId, RiskCase.RiskCaseEvent event) {
        return new RiskEventResponse(caseId + ":" + event.sequence(), event.eventType(),
                event.actorKind() == RiskActorKind.USER ? "当前用户" : "规则引擎",
                event.detail(), event.occurredAt().toString());
    }

    private String displayType(String riskType) {
        return switch (riskType) {
            case "repair-backlog", "repeat-repair" -> "维修风险";
            case "resource-checkin-inconsistency", "long-pending-operation" -> "入住风险";
            case "failed-hygiene-check" -> "卫生风险";
            case "overdue-payment" -> "欠费风险";
            default -> throw new IllegalStateException("未知风险类型");
        };
    }

    private String severity(String value) {
        return "CRITICAL".equals(value) ? "high" : value.toLowerCase(java.util.Locale.ROOT);
    }

    private String evidenceSummary(RiskCaseService.CaseView value) {
        Map<String, Object> evidence = value.evidence();
        return switch (value.riskType()) {
            case "repair-backlog" -> "维修待办已持续 " + number(evidence, "ageHours")
                    + " 小时，规则阈值为 " + number(evidence, "ruleThreshold") + " 小时";
            case "repeat-repair" -> "统计窗口内重复报修 " + number(evidence, "count")
                    + " 次，规则阈值为 " + number(evidence, "ruleThreshold") + " 次";
            case "resource-checkin-inconsistency" -> "资源与入住汇总存在确定性口径差异，请人工复核";
            case "long-pending-operation" -> "入住运营待办已持续 " + number(evidence, "ageHours")
                    + " 小时，规则阈值为 " + number(evidence, "ruleThreshold") + " 小时";
            case "failed-hygiene-check" -> "卫生检查不合格 " + number(evidence, "count")
                    + " 次，最近一次评分 " + number(evidence, "score") + " 分";
            case "overdue-payment" -> "账单已超过截止日 " + number(evidence, "ageDays")
                    + " 天，仍处于" + String.valueOf(evidence.get("status")) + "状态";
            default -> "确定性运营规则命中，请人工复核";
        };
    }

    private long number(Map<String, Object> evidence, String key) {
        Object value = evidence.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    public record DispositionRequest(
            @Min(1) int caseVersion,
            @NotBlank @Size(max = 1_000) String detail,
            String dueAt) {
        public Instant dueAtInstant() {
            if (dueAt == null || dueAt.isBlank()) return null;
            try {
                return Instant.parse(dueAt);
            } catch (DateTimeParseException invalid) {
                throw new IllegalArgumentException("风险到期时间不合法");
            }
        }
    }

    public record RiskEventResponse(String id, String type, String actor, String detail, String occurredAt) { }

    public record RiskCaseResponse(
            String id,
            int caseVersion,
            String subjectToken,
            String type,
            String severity,
            String ruleVersion,
            String evidenceSummary,
            String explanation,
            Double confidence,
            String assignee,
            String sla,
            String state,
            String asOf,
            List<RiskEventResponse> events,
            boolean degraded,
            String explanationRunId,
            Long assigneeUserId,
            String dueAt,
            RiskEvidenceLayersResponse evidenceLayers) { }

    public record RiskEvidenceLayersResponse(
            com.example.dormitory.ai.risk.RiskSignalEvidence signal,
            com.example.dormitory.ai.risk.RiskBusinessSnapshot businessSnapshot,
            RiskExplanationResponse explanation,
            RiskHumanEvidenceResponse human) { }

    public record RiskExplanationResponse(
            String text,
            String basis,
            String policyVersion,
            String runId,
            Double confidence,
            boolean degraded) { }

    public record RiskHumanEvidenceResponse(
            Long assigneeUserId,
            String dueAt,
            List<RiskEventResponse> events) { }
}
