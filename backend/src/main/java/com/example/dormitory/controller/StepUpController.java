package com.example.dormitory.controller;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.api.AiApiHeaders;
import com.example.dormitory.ai.api.AiErrorDetail;
import com.example.dormitory.ai.application.run.AiActorContext;
import com.example.dormitory.ai.application.run.AiActorResolver;
import com.example.dormitory.ai.security.AuthenticatedRunContext;
import com.example.dormitory.ai.security.RecentAuthenticationPolicy;
import com.example.dormitory.ai.security.StepUpAuthenticationService;
import com.example.dormitory.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/security")
public class StepUpController {

    private final AiActorResolver actorResolver;
    private final StepUpAuthenticationService authenticationService;

    public StepUpController(AiActorResolver actorResolver, StepUpAuthenticationService authenticationService) {
        this.actorResolver = actorResolver;
        this.authenticationService = authenticationService;
    }

    @PostMapping("/step-up")
    public ResponseEntity<ApiResponse<StepUpResponse>> stepUp(@Valid @RequestBody StepUpRequest request) {
        AiActorContext actor = actorResolver.current(null);
        RecentAuthenticationPolicy.IssuedProof proof = authenticationService.authenticate(
                AuthenticatedRunContext.from(actor), request.password(), request.actionCode(),
                request.resourcePublicId(), request.requestHash());
        return ResponseEntity.ok().headers(AiApiHeaders.privateNoStore())
                .body(ApiResponse.ok(new StepUpResponse(
                        proof.proof(), proof.authenticatedAt(), proof.expiresAt(), proof.authMethod())));
    }

    @ExceptionHandler(AiApiException.class)
    public ResponseEntity<ApiResponse<AiErrorDetail>> handleAi(AiApiException exception) {
        var builder = ResponseEntity.status(exception.status()).headers(AiApiHeaders.privateNoStore());
        if (exception.retryAfterSeconds() != null) {
            builder.header("Retry-After", Integer.toString(exception.retryAfterSeconds()));
        }
        return builder.body(new ApiResponse<>(exception.status().value(), exception.getMessage(),
                new AiErrorDetail(exception.errorCode(), exception.retryable(), exception.fieldErrors(),
                        exception.runId(), exception.metadata())));
    }

    public record StepUpRequest(
            @NotBlank @Size(max = 200) String password,
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{2,63}") String actionCode,
            @Pattern(regexp = "[0-9a-fA-F-]{36}") String resourcePublicId,
            @NotBlank @Pattern(regexp = "[0-9a-fA-F]{64}") String requestHash) { }

    public record StepUpResponse(String proof, Instant authenticatedAt, Instant expiresAt, String authMethod) { }
}
