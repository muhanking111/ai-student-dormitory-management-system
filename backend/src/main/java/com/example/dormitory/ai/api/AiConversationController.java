package com.example.dormitory.ai.api;

import com.example.dormitory.ai.api.dto.AiConversationDtos.ConversationDetailResponse;
import com.example.dormitory.ai.api.dto.AiConversationDtos.ConversationResponse;
import com.example.dormitory.ai.api.dto.AiConversationDtos.CitationSummaryResponse;
import com.example.dormitory.ai.api.dto.AiConversationDtos.CreateConversationRequest;
import com.example.dormitory.ai.api.dto.AiConversationDtos.CreateMessageRequest;
import com.example.dormitory.ai.api.dto.AiConversationDtos.RunAcceptedResponse;
import com.example.dormitory.ai.application.run.AiActorContext;
import com.example.dormitory.ai.application.run.AiActorResolver;
import com.example.dormitory.ai.application.run.AiRunRecords.RunCreation;
import com.example.dormitory.ai.application.run.AiRunService;
import com.example.dormitory.ai.governance.AiCitationQueryService;
import com.example.dormitory.common.ApiResponse;
import com.example.dormitory.common.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/ai/conversations")
public class AiConversationController {

    private final AiRunService service;
    private final com.example.dormitory.ai.erasure.AiErasureService erasure;
    private final AiActorResolver actors;
    private final AiCitationQueryService citations;

    public AiConversationController(
            AiRunService service,
            com.example.dormitory.ai.erasure.AiErasureService erasure,
            AiActorResolver actors,
            AiCitationQueryService citations) {
        this.service = service;
        this.erasure = erasure;
        this.actors = actors;
        this.citations = citations;
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<com.example.dormitory.ai.erasure.AiErasureService.JobView>> erase(
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey) {
        var job = erasure.requestConversationErasure(id, idempotencyKey);
        HttpHeaders headers = AiApiHeaders.privateNoStore();
        headers.setLocation(URI.create("/api/ai/erasure-jobs/" + job.id()));
        return new ResponseEntity<>(ApiResponse.ok(job), headers, HttpStatus.ACCEPTED);
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ConversationResponse>> create(
            @Valid @RequestBody CreateConversationRequest request) {
        ConversationResponse response = ConversationResponse.from(service.createConversation(
                request.surface(), request.contextType(), request.contextId()));
        HttpHeaders headers = AiApiHeaders.privateNoStore();
        headers.setLocation(URI.create("/api/ai/conversations/" + response.id()));
        return new ResponseEntity<>(ApiResponse.ok(response), headers, HttpStatus.CREATED);
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<ConversationResponse>>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize) {
        var result = service.listConversations(page, pageSize);
        PageResponse<ConversationResponse> response = new PageResponse<>(
                result.records().stream().map(ConversationResponse::from).toList(),
                result.total(), result.page(), result.pageSize());
        return ResponseEntity.ok().headers(AiApiHeaders.privateNoStore()).body(ApiResponse.ok(response));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ConversationDetailResponse>> detail(@PathVariable String id) {
        var conversation = service.conversation(id);
        AiActorContext actor = actors.current("ai:assistant:use");
        return ResponseEntity.ok().headers(AiApiHeaders.privateNoStore())
                .body(ApiResponse.ok(ConversationDetailResponse.from(conversation, citationId -> {
                    var citation = citations.summarizeCurrentAccess(citationId, actor);
                    return new CitationSummaryResponse(citation.id(), citation.label(), citation.locator(),
                            citation.version(), citation.access());
                })));
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<Void> archive(@PathVariable String id) {
        service.archiveConversation(id);
        return ResponseEntity.noContent().headers(AiApiHeaders.privateNoStore()).build();
    }

    @PostMapping("/{id}/messages")
    public ResponseEntity<ApiResponse<RunAcceptedResponse>> message(
            @PathVariable String id,
            @Valid @RequestBody CreateMessageRequest request) {
        RunCreation creation = service.createMessage(id, request.text(), request.clientRequestId());
        String runId = creation.run().id();
        RunAcceptedResponse response = new RunAcceptedResponse(runId, "/api/ai/runs/" + runId + "/events");
        HttpHeaders headers = AiApiHeaders.privateNoStore();
        headers.setLocation(URI.create("/api/ai/runs/" + runId));
        return new ResponseEntity<>(ApiResponse.ok(response), headers, HttpStatus.ACCEPTED);
    }
}
