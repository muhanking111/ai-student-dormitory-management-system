package com.example.dormitory.controller;

import com.example.dormitory.common.ApiResponse;
import com.example.dormitory.security.CsrfTokenService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/security")
public class SecurityController {

    private final CsrfTokenService csrfTokenService;

    public SecurityController(CsrfTokenService csrfTokenService) {
        this.csrfTokenService = csrfTokenService;
    }

    @GetMapping("/csrf")
    public ResponseEntity<ApiResponse<CsrfTokenResponse>> csrf() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header("Vary", "Authorization, Cookie, Origin")
                .body(ApiResponse.ok(new CsrfTokenResponse(csrfTokenService.issueForCurrentSession())));
    }

    public record CsrfTokenResponse(String token) {
    }
}
