package com.example.dormitory.ai.api;

import com.example.dormitory.ai.application.run.AiActorContext;
import com.example.dormitory.ai.application.run.AiActorResolver;
import com.example.dormitory.ai.approval.ActionProposalService;
import com.example.dormitory.ai.approval.ActionType;
import com.example.dormitory.ai.approval.CanonicalJsonHasher;
import com.example.dormitory.ai.approval.ProposalState;
import com.example.dormitory.ai.domain.model.BusinessExecutionActor;
import com.example.dormitory.ai.port.AiAuditPort;
import com.example.dormitory.ai.security.AuthenticatedRunContext;
import com.example.dormitory.ai.security.ActionAuthorizationPolicy;
import com.example.dormitory.ai.security.PiiClassificationService;
import com.example.dormitory.ai.security.RecentAuthenticationPolicy;
import com.example.dormitory.common.ApiResponse;
import com.example.dormitory.common.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/ai/proposals")
public class AiProposalController {

    private final ActionProposalService proposals;
    private final AiActorResolver actors;
    private final AiAuditPort audit;
    private final RecentAuthenticationPolicy recentAuthentication;
    private final ActionAuthorizationPolicy authorization;
    private final PiiClassificationService classification;

    public AiProposalController(
            ActionProposalService proposals,
            AiActorResolver actors,
            AiAuditPort audit,
            RecentAuthenticationPolicy recentAuthentication,
            ActionAuthorizationPolicy authorization,
            PiiClassificationService classification) {
        this.proposals = proposals;
        this.actors = actors;
        this.audit = audit;
        this.recentAuthentication = recentAuthentication;
        this.authorization = authorization;
        this.classification = classification;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<ProposalResponse>>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String actionType) {
        AiActorContext actor = actors.current("ai:approval:review");
        ProposalState filter = parseStateFilter(state);
        ActionType actionTypeFilter = parseActionTypeFilter(actionType);
        ActionProposalService.PageResult visible = proposals.listAuthorized(
                filter, actionTypeFilter, page, pageSize,
                value -> authorization.canAccess(actor, value));
        return ResponseEntity.ok().headers(AiApiHeaders.privateNoStore()).body(ApiResponse.ok(
                new PageResponse<>(visible.records().stream().map(this::response).toList(),
                        visible.total(), page, pageSize)));
    }

    private ProposalState parseStateFilter(String value) {
        if (value == null) return null;
        if (value.isBlank()) {
            throw new AiApiException(HttpStatus.BAD_REQUEST, "AI_INVALID_PROPOSAL_STATE",
                    "提案状态筛选不合法", false);
        }
        try {
            return ProposalState.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new AiApiException(HttpStatus.BAD_REQUEST, "AI_INVALID_PROPOSAL_STATE",
                    "提案状态筛选不合法", false);
        }
    }

    private ActionType parseActionTypeFilter(String value) {
        if (value == null) return null;
        if (value.isBlank()) {
            throw new AiApiException(HttpStatus.BAD_REQUEST, "AI_INVALID_PROPOSAL_ACTION_TYPE",
                    "提案动作类型筛选不合法", false);
        }
        try {
            ActionType actionType = ActionType.valueOf(value.trim().toUpperCase(Locale.ROOT));
            if (!ActionType.allowlisted().contains(actionType)) throw new IllegalArgumentException();
            return actionType;
        } catch (IllegalArgumentException invalid) {
            throw new AiApiException(HttpStatus.BAD_REQUEST, "AI_INVALID_PROPOSAL_ACTION_TYPE",
                    "提案动作类型筛选不合法", false);
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ProposalResponse>> get(@PathVariable String id) {
        AiActorContext actor = actors.current("ai:approval:review");
        ActionProposalService.ProposalView value = proposals.get(id);
        authorization.requireAccess(actor, value, "GET");
        return ResponseEntity.ok().headers(AiApiHeaders.privateNoStore()).body(ApiResponse.ok(response(value)));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<ApiResponse<ProposalResponse>> approve(
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Step-Up-Proof") String stepUpProof,
            @Valid @RequestBody ApproveRequest request) {
        AiActorContext actor = actors.current("ai:approval:review");
        ActionProposalService.ProposalView current = proposals.get(id);
        authorization.requireAccess(actor, current, "APPROVE");
        String requestHash = approvalRequestHash(id, request);
        BusinessExecutionActor executionActor = BusinessExecutionActor.from(actor.actor());
        if (!proposals.isApprovalReplay(id, executionActor, idempotencyKey, requestHash)) {
            recentAuthentication.consume(stepUpProof, AuthenticatedRunContext.from(actor),
                    "PROPOSAL_APPROVE", id, requestHash);
        }
        ActionProposalService.ProposalView value = proposals.approve(id, request.version(), request.payloadHash(),
                request.businessSnapshotHash(), executionActor,
                Set.copyOf(actor.permissionCodes()), idempotencyKey, requestHash,
                redactComment(request.comment(), "proposal-approve-comment"));
        return ResponseEntity.ok().headers(AiApiHeaders.privateNoStore()).body(ApiResponse.ok(response(value)));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<Void> reject(
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody RejectRequest request) {
        AiActorContext actor = actors.current("ai:approval:review");
        ActionProposalService.ProposalView current = proposals.get(id);
        authorization.requireAccess(actor, current, "REJECT");
        String requestHash = CanonicalJsonHasher.sha256(
                id + "|REJECT|" + request.version() + "|" + request.comment());
        proposals.reject(id, request.version(), BusinessExecutionActor.from(actor.actor()),
                Set.copyOf(actor.permissionCodes()), idempotencyKey, requestHash,
                redactComment(request.comment(), "proposal-reject-comment"));
        return ResponseEntity.noContent().headers(AiApiHeaders.privateNoStore()).build();
    }

    private ProposalResponse response(ActionProposalService.ProposalView value) {
        var preview = value.preview();
        return new ProposalResponse(value.publicId(), value.actionType().name(),
                value.actionType().name().equals("REPAIR_ASSIGN") ? "维修指派建议" : "公告草稿",
                value.targetType() + (value.targetResourceId() == null ? "" : " #" + value.targetResourceId()),
                preview.currentValue(), preview.proposedValue(), preview.impact(),
                requiredPermissionDisplay(value), value.payloadHash(),
                value.businessSnapshotHash(), value.version(), value.expiresAt(), value.riskLevel().toLowerCase(),
                new Evidence(preview.evidenceBasis().name().toLowerCase(java.util.Locale.ROOT),
                        preview.confidence(), preview.asOf() == null ? "" : preview.asOf().toString(),
                        preview.citations().stream().map(citation -> new Citation(
                                citation.contentHash(), citation.label(), citation.sourceRef(),
                                citation.type(), "available")).toList(),
                        preview.grounded()),
                value.state().name().toLowerCase(), audit.writable(), executionState(value.state()),
                value.executionPublicId(), value.runId());
    }

    private String requiredPermissionDisplay(ActionProposalService.ProposalView value) {
        if (value.actionType().name().equals("REPAIR_ASSIGN")) {
            return "ai:approval:review + ADMIN + repair:write + 当前对象范围仍可分配";
        }
        return "ai:approval:review + notice:write";
    }

    private String redactComment(String comment, String context) {
        if (comment == null || comment.isBlank()) return null;
        return classification.redact(comment.trim(), context).redactedText();
    }

    private String executionState(ProposalState state) {
        return switch (state) {
            case EXECUTING -> "running";
            case SUCCEEDED -> "succeeded";
            case FAILED -> "failed";
            case NEEDS_REVIEW -> "needs_review";
            case APPROVED -> "pending";
            default -> null;
        };
    }

    public static String approvalRequestHash(String proposalId, ApproveRequest request) {
        if (proposalId == null || request == null) throw new IllegalArgumentException("审批请求不能为空");
        String comment = request.comment() == null ? "" : request.comment().trim();
        return CanonicalJsonHasher.sha256("proposal-approve.v1|"
                + framed(proposalId) + framed(Integer.toString(request.version()))
                + framed(request.payloadHash().toLowerCase(java.util.Locale.ROOT))
                + framed(request.businessSnapshotHash().toLowerCase(java.util.Locale.ROOT))
                + framed(comment));
    }

    private static String framed(String value) {
        return value.length() + ":" + value + "|";
    }

    public record ApproveRequest(
            @PositiveOrZero int version,
            @NotBlank @Size(min = 64, max = 64) String payloadHash,
            @NotBlank @Size(min = 64, max = 64) String businessSnapshotHash,
            @Size(max = 500) String comment) { }
    public record RejectRequest(@PositiveOrZero int version, @NotBlank @Size(max = 500) String comment) { }
    public record Evidence(String basis, Double confidence, String asOf,
                           java.util.List<Citation> citations, boolean grounded) { }
    public record Citation(String id, String label, String locator, String version, String access) { }
    public record ProposalResponse(
            String id, String actionType, String title, String target, String currentValue, String proposedValue,
            String impact, String requiredPermission, String payloadHash, String businessSnapshotHash, int version,
            java.time.Instant expiresAt, String riskLevel, Evidence evidence, String state,
            boolean auditAvailable, String executionState, String executionId, String runId) { }
}
