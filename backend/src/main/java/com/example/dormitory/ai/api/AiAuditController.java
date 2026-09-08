package com.example.dormitory.ai.api;

import com.example.dormitory.ai.application.run.AiRunRecords.AuditRun;
import com.example.dormitory.ai.application.run.AiRunRecords.AuditRunDetail;
import com.example.dormitory.ai.application.run.AiRunService;
import com.example.dormitory.common.ApiResponse;
import com.example.dormitory.common.PageResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/audit/runs")
public class AiAuditController {

    private final AiRunService service;

    public AiAuditController(AiRunService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<AuditRun>>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) java.time.Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) java.time.Instant to,
            @RequestParam(required = false) String capability,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String costStatus) {
        var filter = new com.example.dormitory.ai.application.run.AiRunRecords.AuditRunFilter(
                from, to, capability, state, provider, costStatus);
        return ResponseEntity.ok().headers(AiApiHeaders.privateNoStore())
                .body(ApiResponse.ok(service.auditRuns(filter, page, pageSize)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<AuditRunDetail>> detail(@PathVariable String id) {
        return ResponseEntity.ok().headers(AiApiHeaders.privateNoStore())
                .body(ApiResponse.ok(service.auditRun(id)));
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<ApiResponse<com.example.dormitory.ai.application.run.AiRunRecords.AuditRunContent>> content(
            @PathVariable String id,
            @RequestHeader(name = "X-Audit-Reason", required = false) String reason,
            @RequestHeader(name = "X-Step-Up-Proof", required = false) String proof) {
        return ResponseEntity.ok().headers(AiApiHeaders.privateNoStore())
                .body(ApiResponse.ok(service.auditRunContent(id, reason, proof)));
    }
}
