package com.example.dormitory.ai.api;

import com.example.dormitory.ai.api.dto.AiRunDtos.FeedbackRequest;
import com.example.dormitory.ai.api.dto.AiRunDtos.RunResponse;
import com.example.dormitory.ai.api.dto.AiConversationDtos.RunAcceptedResponse;
import com.example.dormitory.ai.application.run.AiRunRecords.RunCreation;
import com.example.dormitory.ai.application.run.AiActorContext;
import com.example.dormitory.ai.application.run.AiRunService;
import com.example.dormitory.ai.infrastructure.runtime.AiSseEmitterBroker;
import com.example.dormitory.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;

@RestController
@RequestMapping("/api/ai")
public class AiRunStreamController {

    private final AiRunService service;
    private final AiSseEmitterBroker broker;

    public AiRunStreamController(AiRunService service, AiSseEmitterBroker broker) {
        this.service = service;
        this.broker = broker;
    }

    @GetMapping("/runs/{id}")
    public ResponseEntity<ApiResponse<RunResponse>> run(@PathVariable String id) {
        return ResponseEntity.ok().headers(AiApiHeaders.privateNoStore())
                .body(ApiResponse.ok(RunResponse.from(service.run(id))));
    }

    @PostMapping("/runs/{id}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable String id) {
        service.cancel(id);
        return ResponseEntity.noContent().headers(AiApiHeaders.privateNoStore()).build();
    }

    @PostMapping("/runs/{id}/retry")
    public ResponseEntity<ApiResponse<RunAcceptedResponse>> retry(
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey) {
        RunCreation creation = service.retry(id, idempotencyKey);
        String runId = creation.run().id();
        RunAcceptedResponse response = new RunAcceptedResponse(runId, "/api/ai/runs/" + runId + "/events");
        HttpHeaders headers = AiApiHeaders.privateNoStore();
        headers.setLocation(URI.create("/api/ai/runs/" + runId));
        return new ResponseEntity<>(ApiResponse.ok(response), headers, HttpStatus.ACCEPTED);
    }

    @GetMapping(value = "/runs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(
            @PathVariable String id,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        long sequence = parseLastEventId(lastEventId);
        AiActorContext actor = service.streamActor(id);
        SseEmitter emitter = broker.subscribe(actor, id, sequence);
        HttpHeaders headers = AiApiHeaders.privateNoStore();
        headers.setContentType(MediaType.TEXT_EVENT_STREAM);
        headers.set("X-Accel-Buffering", "no");
        return ResponseEntity.ok().headers(headers).body(emitter);
    }

    @PostMapping("/messages/{id}/feedback")
    public ResponseEntity<Void> feedback(
            @PathVariable String id,
            @Valid @RequestBody FeedbackRequest request) {
        service.feedback(id, request.rating(), request.tags(), request.comment());
        return ResponseEntity.noContent().headers(AiApiHeaders.privateNoStore()).build();
    }

    private long parseLastEventId(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw new AiApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "AI_INVALID_LAST_EVENT_ID", "Last-Event-ID 必须是非负整数", false);
        }
    }
}
