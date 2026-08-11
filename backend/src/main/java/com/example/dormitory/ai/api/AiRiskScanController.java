package com.example.dormitory.ai.api;

import com.example.dormitory.ai.infrastructure.persistence.JdbcRiskScanRepository;
import com.example.dormitory.ai.risk.RiskScanService;
import com.example.dormitory.common.ApiResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/ai/risk-scans")
@ConditionalOnProperty(prefix = "dormitory.ai.capabilities", name = "risk", havingValue = "true")
public class AiRiskScanController {

    private final RiskScanService scans;

    public AiRiskScanController(RiskScanService scans) {
        this.scans = scans;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<JdbcRiskScanRepository.Scan>> create(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key) {
        var scan = scans.request(key);
        HttpHeaders headers = AiApiHeaders.privateNoStore();
        headers.setLocation(URI.create("/api/ai/risk-scans/" + scan.id()));
        return new ResponseEntity<>(ApiResponse.ok(scan), headers, HttpStatus.ACCEPTED);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<JdbcRiskScanRepository.Scan>> get(@PathVariable String id) {
        return ResponseEntity.ok().headers(AiApiHeaders.privateNoStore())
                .body(ApiResponse.ok(scans.get(id)));
    }
}
