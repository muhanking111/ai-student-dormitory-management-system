package com.example.dormitory.ai.api;

import com.example.dormitory.ai.application.run.AiActorContext;
import com.example.dormitory.ai.application.run.AiActorResolver;
import com.example.dormitory.ai.evaluation.AiEvaluationRunService;
import com.example.dormitory.ai.governance.GovernanceRequestHasher;
import com.example.dormitory.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;

@RestController
@RequestMapping("/api/ai/eval-runs")
public class AiEvaluationController {

    private final AiEvaluationRunService service;
    private final AiActorResolver actors;
    private final ObjectMapper objectMapper;

    public AiEvaluationController(
            AiEvaluationRunService service,
            AiActorResolver actors,
            ObjectMapper objectMapper) {
        this.service = service;
        this.actors = actors;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AiEvaluationRunService.EvalRunView>> create(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody CreateEvalRunRequest request,
            UriComponentsBuilder uriBuilder) {
        AiActorContext actor = actors.current("ai:eval:run");
        String requestHash = GovernanceRequestHasher.hash(objectMapper, Map.of(
                "suiteName", request.suiteName(),
                "datasetVersion", request.datasetVersion(),
                "promptId", request.promptId(),
                "modelDeploymentId", request.modelDeploymentId(),
                "codeRevision", request.codeRevision()));
        var value = service.request(actor, new AiEvaluationRunService.EvalRequest(
                request.suiteName(), request.datasetVersion(), request.promptId(),
                request.modelDeploymentId(), request.codeRevision()), idempotencyKey, requestHash);
        URI location = uriBuilder.path("/api/ai/eval-runs/{id}").buildAndExpand(value.id()).toUri();
        return ResponseEntity.accepted().location(location).headers(AiApiHeaders.privateNoStore())
                .body(ApiResponse.ok(value));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<AiEvaluationRunService.EvalRunView>> get(@PathVariable String id) {
        AiActorContext actor = actors.current("ai:eval:run");
        return ResponseEntity.ok().headers(AiApiHeaders.privateNoStore())
                .body(ApiResponse.ok(service.requireVisible(id, actor)));
    }

    public record CreateEvalRunRequest(
            @NotBlank @Size(max = 128) String suiteName,
            @NotBlank @Size(max = 64) String datasetVersion,
            @NotBlank @Pattern(regexp = "[0-9a-fA-F-]{36}") String promptId,
            @NotBlank @Pattern(regexp = "[0-9a-fA-F-]{36}") String modelDeploymentId,
            @NotBlank @Size(max = 128) String codeRevision) {
    }
}
