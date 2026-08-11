package com.example.dormitory.ai.api;

import com.example.dormitory.ai.application.run.AiRunRecords.Readiness;
import com.example.dormitory.ai.application.run.AiRunService;
import com.example.dormitory.ai.application.run.AiActorContext;
import com.example.dormitory.ai.application.run.AiActorResolver;
import com.example.dormitory.ai.approval.CanonicalJsonHasher;
import com.example.dormitory.ai.approval.ActionProposalService;
import com.example.dormitory.ai.config.AiProperties;
import com.example.dormitory.ai.config.AiRuntimeControlService;
import com.example.dormitory.ai.infrastructure.runtime.AiRuntimeAuditWriter;
import com.example.dormitory.ai.security.AuthenticatedRunContext;
import com.example.dormitory.ai.security.RecentAuthenticationPolicy;
import com.example.dormitory.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/ai/operations")
public class AiOperationsController {

    private final AiRunService service;
    private final AiProperties properties;
    private final AiRuntimeControlService runtimeControls;
    private final AiActorResolver actors;
    private final RecentAuthenticationPolicy recentAuthentication;
    private final AiRuntimeAuditWriter auditWriter;
    private final ActionProposalService.TransactionRunner transactions;

    public AiOperationsController(
            AiRunService service,
            AiProperties properties,
            AiRuntimeControlService runtimeControls,
            AiActorResolver actors,
            RecentAuthenticationPolicy recentAuthentication,
            AiRuntimeAuditWriter auditWriter,
            ActionProposalService.TransactionRunner transactions) {
        this.service = service;
        this.properties = properties;
        this.runtimeControls = runtimeControls;
        this.actors = actors;
        this.recentAuthentication = recentAuthentication;
        this.auditWriter = auditWriter;
        this.transactions = java.util.Objects.requireNonNull(transactions);
    }

    @GetMapping("/readiness")
    public ResponseEntity<ApiResponse<ReadinessResponse>> readiness() {
        Readiness readiness = service.readiness();
        Map<String, String> controls = new LinkedHashMap<>();
        controls.put("audit", readiness.auditWritable() ? "READY" : "AI_AUDIT_UNAVAILABLE");
        controls.put("prompt", readiness.promptActive() ? "READY" : "AI_PROMPT_INACTIVE");
        controls.put("toolCatalog", readiness.toolCatalogActive() ? "READY" : "AI_TOOL_CATALOG_INACTIVE");
        controls.put("budget", readiness.budgetConfigured() ? "READY" : "AI_QUOTA_CONTROL_UNAVAILABLE");
        ReadinessResponse response = new ReadinessResponse(
                runtimeControls.masterEnabled(),
                properties.getProvider().getActive(),
                properties.getStreaming().isEnabled(),
                runtimeControls.writeExecutionEnabled(),
                Map.copyOf(controls));
        return ResponseEntity.ok().headers(AiApiHeaders.privateNoStore()).body(ApiResponse.ok(response));
    }

    @GetMapping("/kill-switches")
    public ResponseEntity<ApiResponse<java.util.List<AiRuntimeControlService.SwitchRecord>>> killSwitches() {
        actors.current("ai:config:manage");
        return ResponseEntity.ok().headers(AiApiHeaders.privateNoStore())
                .body(ApiResponse.ok(runtimeControls.disabledSwitches()));
    }

    @PostMapping("/kill-switches/{scope}/{key}/disable")
    @Transactional
    public ResponseEntity<ApiResponse<AiRuntimeControlService.SwitchRecord>> disable(
            @PathVariable String scope,
            @PathVariable String key,
            @Valid @RequestBody SwitchRequest request) {
        AiActorContext actor = actors.current("ai:config:manage");
        AiRuntimeControlService.Scope parsedScope = scope(scope);
        String canonicalKey = AiRuntimeControlService.canonicalKey(parsedScope, key);
        String payloadHash = switchHash(parsedScope, canonicalKey, true, request.reason(), null);
        auditWriter.requireWritable();
        AiRuntimeControlService.SwitchRecord result = runtimeControls.disable(
                parsedScope, canonicalKey, request.reason(), actor.userId());
        auditWriter.append("CONFIG", "AI_KILL_SWITCH", switchAggregateId(parsedScope, canonicalKey),
                "AI_KILL_SWITCH_DISABLED", actor, payloadHash, java.util.UUID.randomUUID().toString());
        return ResponseEntity.ok().headers(AiApiHeaders.privateNoStore()).body(ApiResponse.ok(result));
    }

    @PostMapping("/kill-switches/{scope}/{key}/clear")
    public ResponseEntity<ApiResponse<AiRuntimeControlService.SwitchRecord>> clear(
            @PathVariable String scope,
            @PathVariable String key,
            @RequestHeader("X-Step-Up-Proof") String proof,
            @Valid @RequestBody SwitchRequest request) {
        AiActorContext actor = actors.current("ai:config:manage");
        AiRuntimeControlService.Scope parsedScope = scope(scope);
        String canonicalKey = AiRuntimeControlService.canonicalKey(parsedScope, key);
        long expectedVersion = request.requiredExpectedVersion();
        String payloadHash = switchHash(
                parsedScope, canonicalKey, false, request.reason(), expectedVersion);
        auditWriter.requireWritable();
        recentAuthentication.consume(proof, AuthenticatedRunContext.from(actor),
                "KILL_SWITCH_CLEAR", switchAggregateId(parsedScope, canonicalKey), payloadHash);
        AiRuntimeControlService.SwitchRecord result = transactions.required(() -> {
            AiRuntimeControlService.SwitchRecord cleared = runtimeControls.clear(
                    parsedScope, canonicalKey, request.reason(), expectedVersion, actor.userId());
            auditWriter.append("CONFIG", "AI_KILL_SWITCH", switchAggregateId(parsedScope, canonicalKey),
                    "AI_KILL_SWITCH_CLEARED", actor, payloadHash, java.util.UUID.randomUUID().toString());
            return cleared;
        });
        return ResponseEntity.ok().headers(AiApiHeaders.privateNoStore()).body(ApiResponse.ok(result));
    }

    private AiRuntimeControlService.Scope scope(String value) {
        try {
            return AiRuntimeControlService.Scope.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Kill Switch scope 不合法", exception);
        }
    }

    public static String switchHash(
            AiRuntimeControlService.Scope scope,
            String key,
            boolean disabled,
            String reason,
            Long expectedVersion) {
        if (!disabled && (expectedVersion == null || expectedVersion < 1)) {
            throw new IllegalArgumentException("清除 Kill Switch 必须绑定当前版本");
        }
        if (disabled && expectedVersion != null) {
            throw new IllegalArgumentException("关闭 Kill Switch 不接受预期版本");
        }
        return CanonicalJsonHasher.sha256("kill-switch.v2|" + scope.name() + "|" + key + "|"
                + disabled + "|" + (reason == null ? "" : reason.trim()) + "|"
                + (expectedVersion == null ? "none" : expectedVersion));
    }

    private static String switchAggregateId(AiRuntimeControlService.Scope scope, String key) {
        return AiRuntimeControlService.resourcePublicId(scope, key);
    }

    public record SwitchRequest(
            @NotBlank @Size(min = 6, max = 500) String reason,
            @Positive Long expectedVersion) {
        private long requiredExpectedVersion() {
            if (expectedVersion == null) throw new IllegalArgumentException("清除 Kill Switch 缺少预期版本");
            return expectedVersion;
        }
    }

    public record ReadinessResponse(
            boolean masterEnabled,
            String providerAlias,
            boolean streamingEnabled,
            boolean writeExecutionEnabled,
            Map<String, String> controls) {
    }
}
