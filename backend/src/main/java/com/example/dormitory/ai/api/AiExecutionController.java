package com.example.dormitory.ai.api;

import com.example.dormitory.ai.application.run.AiActorContext;
import com.example.dormitory.ai.application.run.AiActorResolver;
import com.example.dormitory.ai.approval.ActionProposalService;
import com.example.dormitory.ai.approval.CanonicalJsonHasher;
import com.example.dormitory.ai.domain.model.BusinessExecutionActor;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiIdempotencyRepository;
import com.example.dormitory.ai.security.ActionAuthorizationPolicy;
import com.example.dormitory.ai.security.AuthenticatedRunContext;
import com.example.dormitory.ai.security.PiiClassificationService;
import com.example.dormitory.ai.security.RecentAuthenticationPolicy;
import com.example.dormitory.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Set;

@RestController
@RequestMapping("/api/ai/executions")
public class AiExecutionController {

    private final ActionProposalService proposals;
    private final AiActorResolver actors;
    private final ActionAuthorizationPolicy authorization;
    private final RecentAuthenticationPolicy recentAuthentication;
    private final JdbcAiIdempotencyRepository idempotency;
    private final PiiClassificationService classification;

    public AiExecutionController(ActionProposalService proposals, AiActorResolver actors,
                                 ActionAuthorizationPolicy authorization,
                                 RecentAuthenticationPolicy recentAuthentication,
                                 JdbcAiIdempotencyRepository idempotency,
                                 PiiClassificationService classification) {
        this.proposals = proposals;
        this.actors = actors;
        this.authorization = authorization;
        this.recentAuthentication = recentAuthentication;
        this.idempotency = idempotency;
        this.classification = classification;
    }

    @PostMapping("/{id}/reconfirm")
    public ResponseEntity<ApiResponse<ReconfirmResponse>> reconfirm(
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @RequestHeader("X-Step-Up-Proof") @NotBlank @Size(max = 512) String stepUpProof,
            @Valid @RequestBody ReconfirmRequest request) {
        AiActorContext actor = actors.current("ai:approval:review");
        ActionProposalService.ProposalView current = proposals.getByExecutionId(id);
        authorization.requireAccess(actor, current, "RECONFIRM");
        String requestHash = requestHash(id, request);
        JdbcAiIdempotencyRepository.Reservation reservation = idempotency.reserve(
                new JdbcAiIdempotencyRepository.Scope(actor.userId(), "AI_EXECUTION_RECONFIRM", id,
                        idempotencyKey), requestHash, Instant.now().plusSeconds(86400));
        if (reservation.status() == JdbcAiIdempotencyRepository.ReservationStatus.REPLAY) {
            var completed = idempotency.completedResponse(reservation.recordId());
            if (completed.isPresent()) return ok(proposals.get(completed.get().resourcePublicId()));
            throw new com.example.dormitory.ai.approval.ProposalConflictException(
                    "AI_RECONFIRM_IN_PROGRESS", "执行对账请求正在处理中");
        }
        try {
            recentAuthentication.consume(stepUpProof, AuthenticatedRunContext.from(actor),
                    "PROPOSAL_RECONFIRM", id, requestHash);
            ActionProposalService.ProposalView result = proposals.reconfirm(current.publicId(), request.version(),
                    request.payloadHash(), request.businessSnapshotHash(), request.resolution(),
                    BusinessExecutionActor.from(actor.actor()), Set.copyOf(actor.permissionCodes()),
                    idempotencyKey, requestHash,
                    classification.redact(request.comment().trim(), "execution-reconfirm-comment").redactedText());
            idempotency.complete(reservation.recordId(), 200, result.publicId());
            return ok(result);
        } catch (RuntimeException failure) {
            idempotency.releasePending(reservation.recordId());
            throw failure;
        }
    }

    private ResponseEntity<ApiResponse<ReconfirmResponse>> ok(ActionProposalService.ProposalView value) {
        return ResponseEntity.ok().headers(AiApiHeaders.privateNoStore()).body(ApiResponse.ok(
                new ReconfirmResponse(value.publicId(), value.executionPublicId(), value.state().name(), value.version(),
                        value.result() == null ? null : value.result().resultHash())));
    }

    public static String requestHash(String id, ReconfirmRequest request) {
        String comment = request.comment() == null ? "" : request.comment().trim();
        return CanonicalJsonHasher.sha256("execution-reconfirm.v1|"
                + framed(id) + framed(Integer.toString(request.version()))
                + framed(request.payloadHash().toLowerCase(java.util.Locale.ROOT))
                + framed(request.businessSnapshotHash().toLowerCase(java.util.Locale.ROOT))
                + framed(request.resolution().name()) + framed(comment));
    }

    private static String framed(String value) {
        return value.length() + ":" + value + "|";
    }

    public record ReconfirmRequest(
            @PositiveOrZero int version,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String payloadHash,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String businessSnapshotHash,
            @NotNull ActionProposalService.ReconfirmResolution resolution,
            @NotBlank @Size(min = 6, max = 500) String comment) { }

    public record ReconfirmResponse(
            String proposalId, String executionId, String state, int version, String resultHash) { }
}
