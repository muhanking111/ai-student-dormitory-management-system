package com.example.dormitory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.dormitory.common.BusinessException;
import com.example.dormitory.security.CsrfOriginInterceptor;
import com.example.dormitory.security.CsrfTokenService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class CsrfProtectionTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CsrfTokenService csrfTokenService;

    @Test
    void cookieAuthenticatedWritesRequireBoundCsrfTokenAndAllowedOrigin() throws Exception {
        Cookie session = loginCookie();

        mockMvc.perform(post("/api/auth/logout").cookie(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        MvcResult csrfResult = mockMvc.perform(get("/api/security/csrf").cookie(session))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("private"),
                                org.hamcrest.Matchers.containsString("no-store"))))
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertTrue(
                        result.getResponse().getHeaders(HttpHeaders.VARY).stream()
                                .anyMatch(value -> value.contains("Cookie") && value.contains("Authorization"))))
                .andExpect(jsonPath("$.data.token").isString())
                .andReturn();
        JsonNode body = objectMapper.readTree(csrfResult.getResponse().getContentAsString());
        String csrf = body.path("data").path("token").asText();

        mockMvc.perform(post("/api/auth/logout")
                        .cookie(session)
                        .header("Origin", "https://evil.example")
                        .header("X-CSRF-Token", csrf))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/auth/logout")
                        .cookie(session)
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", csrf))
                .andExpect(status().isOk());
    }

    @Test
    void aiFeedbackAndRetryCookieWritesRequireEndpointCsrfAndAllowedOrigin() throws Exception {
        Cookie session = loginCookie();
        String csrf = csrfToken(session);
        String messageId = "00000000-0000-4000-8000-000000000001";
        String runId = "00000000-0000-4000-8000-000000000002";

        mockMvc.perform(post("/api/ai/messages/{id}/feedback", messageId)
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":1}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/ai/messages/{id}/feedback", messageId)
                        .cookie(session)
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header("X-CSRF-Token", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":1}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/ai/messages/{id}/feedback", messageId)
                        .cookie(session)
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header("X-CSRF-Token", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":1}"))
                .andExpect(status().isServiceUnavailable());

        mockMvc.perform(post("/api/ai/runs/{id}/retry", runId)
                        .cookie(session)
                        .header("Idempotency-Key", "csrf-retry-missing-token")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/ai/runs/{id}/retry", runId)
                        .cookie(session)
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header("X-CSRF-Token", csrf)
                        .header("Idempotency-Key", "csrf-retry-evil-origin")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/ai/runs/{id}/retry", runId)
                        .cookie(session)
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header("X-CSRF-Token", csrf)
                        .header("Idempotency-Key", "csrf-retry-valid")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void trustedLoginCanReplaceAStaleAuthenticationCookieButUntrustedOriginIsRejected() throws Exception {
        Cookie staleSession = new Cookie("Authorization", "stale-session");
        String credentials = validCredentials();

        mockMvc.perform(post("/api/auth/login")
                        .cookie(staleSession)
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("admin"));

        mockMvc.perform(post("/api/auth/login")
                        .cookie(staleSession)
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials))
                .andExpect(status().isForbidden());
    }

    @Test
    void loginWithAStaleAuthenticationCookieFailsClosedWithoutOriginOrReferer() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .cookie(new Cookie("Authorization", "stale-session"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCredentials()))
                .andExpect(status().isForbidden());
    }

    @Test
    void staleCookieLoginSpecialCaseSupportsANonEmptyContextPath() throws Exception {
        mockMvc.perform(post("/app/api/auth/login")
                        .contextPath("/app")
                        .cookie(new Cookie("Authorization", "stale-session"))
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCredentials()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("admin"));
    }

    @Test
    void staleCookieLoginUsesTrustedRefererOnlyWhenOriginIsAbsent() throws Exception {
        Cookie staleSession = new Cookie("Authorization", "stale-session");

        mockMvc.perform(post("/api/auth/login")
                        .cookie(staleSession)
                        .header(HttpHeaders.REFERER, "http://localhost:5173/login?redirect=/")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCredentials()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("admin"));

        mockMvc.perform(post("/api/auth/login")
                        .cookie(staleSession)
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.REFERER, "http://localhost:5173/login?redirect=/")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCredentials()))
                .andExpect(status().isForbidden());
    }

    @Test
    void headerOnlyNonBrowserClientKeepsSeparateContract() throws Exception {
        Cookie session = loginCookie();
        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", session.getValue()))
                .andExpect(status().isOk());
    }

    @Test
    void traceIsRejectedWithoutServletRequestEcho() throws Exception {
        Cookie session = loginCookie();

        MvcResult result = mockMvc.perform(request(HttpMethod.TRACE, "/api/auth/logout")
                        .cookie(session))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value(405))
                .andReturn();
        assertFalse(result.getResponse().getContentAsString().contains("TRACE /api/auth/logout"));
    }

    @Test
    void csrfInterceptorDoesNotClassifyTraceAsSafe() {
        CsrfOriginInterceptor interceptor = new CsrfOriginInterceptor(
                csrfTokenService, new String[]{"http://localhost:5173"});
        MockHttpServletRequest request = new MockHttpServletRequest("TRACE", "/api/auth/logout");
        request.setCookies(new Cookie("Authorization", "session"));

        assertThrows(BusinessException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void csrfInterceptorUsesConfiguredSaTokenCookieName() {
        CsrfOriginInterceptor interceptor = new CsrfOriginInterceptor(
                csrfTokenService, new String[]{"http://localhost:5173"}, "DormSession");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/notices");
        request.setCookies(new Cookie("DormSession", "session"));

        assertThrows(BusinessException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void strictLoginOriginPolicyRejectsFirstLoginWithoutBrowserOrigin() {
        CsrfOriginInterceptor interceptor = new CsrfOriginInterceptor(
                csrfTokenService, new String[]{"http://localhost:5173"}, "Authorization", true);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");

        assertThrows(BusinessException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void strictLoginOriginPolicyAcceptsTrustedOriginForFirstLogin() {
        CsrfOriginInterceptor interceptor = new CsrfOriginInterceptor(
                csrfTokenService, new String[]{"http://localhost:5173"}, "Authorization", true);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.addHeader(HttpHeaders.ORIGIN, "http://localhost:5173");

        org.junit.jupiter.api.Assertions.assertTrue(
                interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
    }

    private Cookie loginCookie() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCredentials()))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cookie = result.getResponse().getCookie("Authorization");
        assertNotNull(cookie);
        return cookie;
    }

    private String csrfToken(Cookie session) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/security/csrf").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isString())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("token").asText();
    }

    private String validCredentials() throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "username", "admin",
                "password", "test-password-123"));
    }
}
