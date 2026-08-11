package com.example.dormitory.ai.api;

import com.example.dormitory.ai.application.run.AiActorResolver;
import com.example.dormitory.ai.governance.AiCostAggregationService;
import com.example.dormitory.common.ApiResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/ai/audit/costs")
public class AiGovernanceAuditController {

    private final AiCostAggregationService costs;
    private final AiActorResolver actors;

    public AiGovernanceAuditController(AiCostAggregationService costs, AiActorResolver actors) {
        this.costs = costs;
        this.actors = actors;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<AiCostAggregationService.CostAggregation>> costs(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "CAPABILITY") AiCostAggregationService.GroupBy groupBy) {
        actors.current("ai:audit:read");
        return ResponseEntity.ok().headers(AiApiHeaders.privateNoStore())
                .body(ApiResponse.ok(costs.aggregate(from, to, groupBy)));
    }
}
