package com.example.dormitory.ai.api;

import com.example.dormitory.ai.api.dto.AiConversationDtos.RunAcceptedResponse;
import com.example.dormitory.ai.application.run.AiRunRecords.RunCreation;
import com.example.dormitory.ai.application.run.AiRunService;
import com.example.dormitory.ai.approval.CanonicalJsonHasher;
import com.example.dormitory.ai.dashboard.DashboardQueryIntent;
import com.example.dormitory.ai.dashboard.DashboardQueryService;
import com.example.dormitory.ai.domain.model.AiCapability;
import com.example.dormitory.ai.domain.model.BusinessActorScope;
import com.example.dormitory.ai.domain.model.BusinessExecutionActor;
import com.example.dormitory.ai.knowledge.KnowledgeAssistantService;
import com.example.dormitory.ai.notice.NoticeDraftService;
import com.example.dormitory.ai.repair.RepairTriageService;
import com.example.dormitory.common.ApiResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/ai")
public class AiFeatureCommandController {

    private final AiRunService runs;
    private final DashboardQueryService dashboard;
    private final KnowledgeAssistantService knowledge;
    private final RepairTriageService repair;
    private final NoticeDraftService notice;
    private final ObjectMapper objectMapper;

    public AiFeatureCommandController(
            AiRunService runs,
            DashboardQueryService dashboard,
            KnowledgeAssistantService knowledge,
            RepairTriageService repair,
            NoticeDraftService notice,
            ObjectMapper objectMapper) {
        this.runs = runs;
        this.dashboard = dashboard;
        this.knowledge = knowledge;
        this.repair = repair;
        this.notice = notice;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/dashboard/queries")
    public ResponseEntity<ApiResponse<RunAcceptedResponse>> dashboard(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody DashboardCommand request) {
        String input = json(request);
        RunCreation creation = runs.createCommand(AiCapability.DASHBOARD, "ai:dashboard:query", "DASHBOARD",
                "DASHBOARD", null, idempotencyKey, input,
                (actor, runId) -> dashboard.query(request.question(), scope(actor)));
        return accepted(creation);
    }

    @PostMapping("/knowledge/queries")
    public ResponseEntity<ApiResponse<RunAcceptedResponse>> knowledge(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody KnowledgeCommand request) {
        String input = json(request);
        RunCreation creation = runs.createCommand(AiCapability.KNOWLEDGE, "ai:knowledge:read", "GLOBAL",
                "KNOWLEDGE", null, idempotencyKey, input, (actor, runId) -> {
                    KnowledgeAssistantService.TracedAssistantAnswer result = knowledge.answerWithTrace(
                            request.question(), Set.copyOf(actor.permissionCodes()), request.topK());
                    if (result.retrievalTrace() == null) {
                        if (!result.answer().citations().isEmpty()) {
                            throw new IllegalStateException("知识 command citation 缺少检索轨迹");
                        }
                        return result.answer();
                    }
                    java.util.List<com.example.dormitory.ai.application.run.AiRunRecords.CitationCandidate> citations =
                            java.util.stream.IntStream.range(0, result.answer().citations().size())
                                    .mapToObj(index -> {
                                        com.example.dormitory.ai.knowledge.SafeKnowledgeService.KnowledgeCitation citation =
                                                result.answer().citations().get(index);
                                        return new com.example.dormitory.ai.application.run.AiRunRecords.CitationCandidate(
                                                citation.sourceId(), citation.documentVersionId(), citation.chunkPublicId(),
                                                citation.label(), citation.locator(), citation.quote(), citation.contentHash(),
                                                index + 1, null);
                                    }).toList();
                    return new com.example.dormitory.ai.application.run.AiRunRecords.TracedCommandResult(
                            result.answer(), result.retrievalTrace(), citations);
                });
        return accepted(creation);
    }

    @PostMapping("/repairs/{repairOrderId}/triage")
    public ResponseEntity<ApiResponse<RunAcceptedResponse>> repair(
            @PathVariable long repairOrderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody(required = false) Map<String, Object> ignoredClientSnapshot) {
        String input = json(Map.of("repairOrderId", repairOrderId));
        RunCreation creation = runs.createCommand(AiCapability.REPAIR, "ai:repair:triage", "REPAIR",
                "REPAIR", repairOrderId, idempotencyKey, input,
                (actor, runId) -> repair.triage(repairOrderId, scope(actor),
                        BusinessExecutionActor.from(actor.actor()), idempotencyKey + ":proposal",
                        CanonicalJsonHasher.sha256(input), runId));
        return accepted(creation);
    }

    @PostMapping("/notices/drafts")
    public ResponseEntity<ApiResponse<RunAcceptedResponse>> notice(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody NoticeCommand request) {
        String input = json(request);
        RunCreation creation = runs.createCommand(AiCapability.NOTICE, "ai:notice:draft", "NOTICE",
                "COMMAND", null, idempotencyKey, input, (actor, runId) -> notice.draft(
                        new NoticeDraftService.DraftCommand(
                                request.points(), request.type(), request.tone(), request.audience()),
                        BusinessExecutionActor.from(actor.actor()), Set.copyOf(actor.permissionCodes()),
                        idempotencyKey + ":proposal", CanonicalJsonHasher.sha256(input), runId));
        return accepted(creation);
    }

    private BusinessActorScope scope(com.example.dormitory.ai.application.run.AiActorContext actor) {
        return new BusinessActorScope(actor.actor(), Set.copyOf(actor.permissionCodes()), Map.of());
    }

    private ResponseEntity<ApiResponse<RunAcceptedResponse>> accepted(RunCreation creation) {
        String runId = creation.run().id();
        RunAcceptedResponse response = new RunAcceptedResponse(runId, "/api/ai/runs/" + runId + "/events");
        HttpHeaders headers = AiApiHeaders.privateNoStore();
        headers.setLocation(URI.create("/api/ai/runs/" + runId));
        return new ResponseEntity<>(ApiResponse.ok(response), headers, HttpStatus.ACCEPTED);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("AI command 序列化失败", exception);
        }
    }

    public record DashboardCommand(
            @NotBlank @Size(max = 500) String question,
            @Size(max = 64) @Pattern(regexp = "DashboardQueryIntent\\.v1") String intentSchemaVersion) {
        public DashboardCommand {
            if (intentSchemaVersion == null) intentSchemaVersion = DashboardQueryIntent.SCHEMA_VERSION;
        }
    }

    public record KnowledgeCommand(
            @NotBlank @Size(max = 2_000) String question,
            @Min(1) @Max(20) int topK) {
        public KnowledgeCommand {
            if (topK == 0) topK = 5;
        }
    }

    public record NoticeCommand(
            @NotBlank @Size(max = 6_000) String points,
            @NotBlank @Size(max = 32) String type,
            @NotBlank @Size(max = 8) String tone,
            @NotBlank @Size(max = 200) String audience) { }
}
