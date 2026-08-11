package com.example.dormitory.ai.api;

import com.example.dormitory.ai.erasure.AiErasureService;
import com.example.dormitory.common.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/erasure-jobs")
public class AiErasureController {
    private final AiErasureService erasure;

    public AiErasureController(AiErasureService erasure) {
        this.erasure = erasure;
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<AiErasureService.JobView>> get(@PathVariable String id) {
        return ResponseEntity.ok().headers(AiApiHeaders.privateNoStore())
                .body(ApiResponse.ok(erasure.visibleJob(id)));
    }
}
