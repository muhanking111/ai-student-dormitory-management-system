package com.example.dormitory.ai.api;

import com.example.dormitory.ai.application.run.AiActorContext;
import com.example.dormitory.ai.application.run.AiActorResolver;
import com.example.dormitory.ai.governance.AiCitationQueryService;
import com.example.dormitory.common.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/citations")
public class AiCitationController {

    private final AiCitationQueryService citations;
    private final AiActorResolver actors;

    public AiCitationController(AiCitationQueryService citations, AiActorResolver actors) {
        this.citations = citations;
        this.actors = actors;
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<AiCitationQueryService.CitationView>> get(@PathVariable String id) {
        AiActorContext actor = actors.current(null);
        return ResponseEntity.ok().headers(AiApiHeaders.privateNoStore())
                .body(ApiResponse.ok(citations.requireVisible(id, actor)));
    }
}
