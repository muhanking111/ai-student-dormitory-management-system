package com.example.dormitory.ai.api;

import com.example.dormitory.ai.application.run.AiActorContext;
import com.example.dormitory.ai.application.run.AiActorResolver;
import com.example.dormitory.ai.governance.AiConfigurationGovernanceService;
import com.example.dormitory.ai.governance.GovernanceRequestHasher;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiIdempotencyRepository;
import com.example.dormitory.ai.security.AuthenticatedRunContext;
import com.example.dormitory.ai.security.RecentAuthenticationPolicy;
import com.example.dormitory.common.ApiResponse;
import com.example.dormitory.common.PageResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/ai")
public class AiConfigurationGovernanceController {

    private static final String CONFIG_PERMISSION = "ai:config:manage";

    private final AiConfigurationGovernanceService service;
    private final AiActorResolver actors;
    private final JdbcAiIdempotencyRepository idempotency;
    private final RecentAuthenticationPolicy recentAuthentication;
    private final ObjectMapper objectMapper;

    public AiConfigurationGovernanceController(
            AiConfigurationGovernanceService service,
            AiActorResolver actors,
            JdbcAiIdempotencyRepository idempotency,
            RecentAuthenticationPolicy recentAuthentication,
            ObjectMapper objectMapper) {
        this.service = service;
        this.actors = actors;
        this.idempotency = idempotency;
        this.recentAuthentication = recentAuthentication;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/prompts")
    public ResponseEntity<ApiResponse<PageResponse<AiConfigurationGovernanceService.PromptView>>> prompts(
            @RequestParam(required = false) String promptKey,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) long pageSize) {
        actors.current(CONFIG_PERMISSION);
        return ok(service.listPrompts(promptKey, status, page, pageSize));
    }

    @PostMapping("/prompts")
    public ResponseEntity<ApiResponse<AiConfigurationGovernanceService.PromptView>> createPrompt(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody CreatePromptRequest request,
            UriComponentsBuilder uriBuilder) {
        AiActorContext actor = actors.current(CONFIG_PERMISSION);
        String requestHash = GovernanceRequestHasher.hash(objectMapper, Map.of(
                "promptKey", request.promptKey(),
                "version", request.version(),
                "content", request.content(),
                "responseSchemaVersion", request.responseSchemaVersion()));
        String aggregate = AiConfigurationGovernanceService.promptKeyAggregateId(request.promptKey());
        JdbcAiIdempotencyRepository.Reservation reservation = reserve(
                actor, "AI_PROMPT_CREATE", aggregate, idempotencyKey, requestHash);
        if (reservation.status() == JdbcAiIdempotencyRepository.ReservationStatus.REPLAY) {
            String existingId = completedResource(reservation);
            var existing = service.requirePrompt(existingId);
            return created(existing, uriBuilder);
        }
        try {
            var value = service.createPromptDraft(actor, request.promptKey(), request.version(),
                    request.content(), request.responseSchemaVersion(), requestHash);
            idempotency.complete(reservation.recordId(), HttpStatus.CREATED.value(), value.id());
            return created(value, uriBuilder);
        } catch (RuntimeException failure) {
            idempotency.releasePending(reservation.recordId());
            throw failure;
        }
    }

    @GetMapping("/prompts/{id}")
    public ResponseEntity<ApiResponse<AiConfigurationGovernanceService.PromptView>> prompt(
            @PathVariable String id) {
        actors.current(CONFIG_PERMISSION);
        return ok(service.requirePrompt(id));
    }

    @PostMapping("/prompts/{id}/activate")
    public ResponseEntity<Void> activatePrompt(
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @RequestHeader("X-Step-Up-Proof") @NotBlank @Size(max = 512) String proof,
            @Valid @RequestBody ActivateSlotRequest request) {
        AiActorContext actor = actors.current(CONFIG_PERMISSION);
        String requestHash = GovernanceRequestHasher.hash(objectMapper, Map.of(
                "promptId", id,
                "expectedActiveId", GovernanceRequestHasher.value(request.expectedActiveId())));
        JdbcAiIdempotencyRepository.Reservation reservation = reserve(
                actor, "AI_PROMPT_ACTIVATE", id, idempotencyKey, requestHash);
        if (replayedCompleted(reservation)) return noContent();
        try {
            recentAuthentication.consume(proof, AuthenticatedRunContext.from(actor),
                    "PROMPT_ACTIVATE", id, requestHash);
            service.activatePrompt(actor, id, request.expectedActiveId(), requestHash);
            idempotency.complete(reservation.recordId(), HttpStatus.NO_CONTENT.value(), id);
            return noContent();
        } catch (RuntimeException failure) {
            idempotency.releasePending(reservation.recordId());
            throw failure;
        }
    }

    @PostMapping("/model-aliases/{alias}/activate")
    public ResponseEntity<Void> activateModelAlias(
            @PathVariable String alias,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @RequestHeader("X-Step-Up-Proof") @NotBlank @Size(max = 512) String proof,
            @Valid @RequestBody ActivateModelAliasRequest request) {
        AiActorContext actor = actors.current(CONFIG_PERMISSION);
        String requestHash = GovernanceRequestHasher.hash(objectMapper, Map.of(
                "alias", alias,
                "deploymentId", request.deploymentId(),
                "expectedVersion", request.version()));
        String aggregate = AiConfigurationGovernanceService.aliasPublicId(alias);
        JdbcAiIdempotencyRepository.Reservation reservation = reserve(
                actor, "AI_MODEL_ALIAS_ACTIVATE", aggregate, idempotencyKey, requestHash);
        if (replayedCompleted(reservation)) return noContent();
        try {
            recentAuthentication.consume(proof, AuthenticatedRunContext.from(actor),
                    "MODEL_ALIAS_ACTIVATE", aggregate, requestHash);
            service.activateModelAlias(actor, alias, request.deploymentId(), request.version(), requestHash);
            idempotency.complete(reservation.recordId(), HttpStatus.NO_CONTENT.value(), aggregate);
            return noContent();
        } catch (RuntimeException failure) {
            idempotency.releasePending(reservation.recordId());
            throw failure;
        }
    }

    @GetMapping("/tool-catalogs")
    public ResponseEntity<ApiResponse<java.util.List<AiConfigurationGovernanceService.ToolCatalogView>>>
            toolCatalogs() {
        actors.current(CONFIG_PERMISSION);
        return ok(service.listToolCatalogs());
    }

    @PostMapping("/tool-catalogs/{id}/activate")
    public ResponseEntity<Void> activateToolCatalog(
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @RequestHeader("X-Step-Up-Proof") @NotBlank @Size(max = 512) String proof,
            @Valid @RequestBody ActivateToolCatalogRequest request) {
        AiActorContext actor = actors.current(CONFIG_PERMISSION);
        String requestHash = GovernanceRequestHasher.hash(objectMapper, Map.of(
                "catalogId", id,
                "version", request.version(),
                "manifestHash", request.manifestHash(),
                "expectedActiveId", GovernanceRequestHasher.value(request.expectedActiveId())));
        JdbcAiIdempotencyRepository.Reservation reservation = reserve(
                actor, "AI_TOOL_CATALOG_ACTIVATE", id, idempotencyKey, requestHash);
        if (replayedCompleted(reservation)) return noContent();
        try {
            recentAuthentication.consume(proof, AuthenticatedRunContext.from(actor),
                    "CONFIG_ACTIVATE", id, requestHash);
            service.activateToolCatalog(actor, id, request.version(), request.manifestHash(),
                    request.expectedActiveId(), requestHash);
            idempotency.complete(reservation.recordId(), HttpStatus.NO_CONTENT.value(), id);
            return noContent();
        } catch (RuntimeException failure) {
            idempotency.releasePending(reservation.recordId());
            throw failure;
        }
    }

    private JdbcAiIdempotencyRepository.Reservation reserve(
            AiActorContext actor,
            String route,
            String aggregate,
            String key,
            String requestHash) {
        JdbcAiIdempotencyRepository.Reservation reservation = idempotency.reserve(
                new JdbcAiIdempotencyRepository.Scope(actor.userId(), route, aggregate, key),
                requestHash, Instant.now().plusSeconds(86_400));
        if (reservation.status() == JdbcAiIdempotencyRepository.ReservationStatus.REPLAY
                && !"COMPLETED".equals(reservation.state())) {
            throw new AiApiException(HttpStatus.CONFLICT, "AI_CONFIG_REQUEST_IN_PROGRESS",
                    "相同治理请求仍在处理中", true);
        }
        return reservation;
    }

    private boolean replayedCompleted(JdbcAiIdempotencyRepository.Reservation reservation) {
        if (reservation.status() != JdbcAiIdempotencyRepository.ReservationStatus.REPLAY) return false;
        completedResource(reservation);
        return true;
    }

    private String completedResource(JdbcAiIdempotencyRepository.Reservation reservation) {
        return idempotency.completedResponse(reservation.recordId())
                .map(JdbcAiIdempotencyRepository.CompletedResponse::resourcePublicId)
                .orElseThrow(() -> new AiApiException(HttpStatus.CONFLICT,
                        "AI_CONFIG_REQUEST_IN_PROGRESS", "相同治理请求仍在处理中", true));
    }

    private ResponseEntity<ApiResponse<AiConfigurationGovernanceService.PromptView>> created(
            AiConfigurationGovernanceService.PromptView value,
            UriComponentsBuilder uriBuilder) {
        URI location = uriBuilder.path("/api/ai/prompts/{id}").buildAndExpand(value.id()).toUri();
        return ResponseEntity.created(location).headers(AiApiHeaders.privateNoStore())
                .body(ApiResponse.ok(value));
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(T value) {
        return ResponseEntity.ok().headers(AiApiHeaders.privateNoStore()).body(ApiResponse.ok(value));
    }

    private ResponseEntity<Void> noContent() {
        return ResponseEntity.noContent().headers(AiApiHeaders.privateNoStore()).build();
    }

    public record CreatePromptRequest(
            @NotBlank @Size(max = 128) String promptKey,
            @NotBlank @Size(max = 32) String version,
            @NotBlank @Size(max = 100_000) String content,
            @NotBlank @Size(max = 64) String responseSchemaVersion) {
    }

    public record ActivateSlotRequest(
            @Pattern(regexp = "(?:|[0-9a-fA-F-]{36})") String expectedActiveId) {
    }

    public record ActivateModelAliasRequest(
            @NotBlank @Pattern(regexp = "[0-9a-fA-F-]{36}") String deploymentId,
            @Min(0) long version) {
    }

    public record ActivateToolCatalogRequest(
            @NotBlank @Size(max = 32) String version,
            @NotBlank @Pattern(regexp = "[0-9a-fA-F]{64}") String manifestHash,
            @Pattern(regexp = "(?:|[0-9a-fA-F-]{36})") String expectedActiveId) {
    }
}
