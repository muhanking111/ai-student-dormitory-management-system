package com.example.dormitory.ai.security;

import com.example.dormitory.ai.application.run.AiActorResolver;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.service.RbacService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "dormitory.ai.audit.hmac-key=0123456789abcdef0123456789abcdef",
        "dormitory.ai.tokenization.hmac-key=fedcba9876543210fedcba9876543210",
        "dormitory.ai.step-up.hmac-key=abcdef0123456789abcdef0123456789",
        "dormitory.ai.step-up.proof-ttl=PT5M",
        "dormitory.ai.step-up.max-failures=5"
})
class StepUpControllerTest {

    private static final String RESOURCE = "11111111-1111-1111-1111-111111111111";
    private static final String REQUEST_HASH = "c".repeat(64);

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RecentAuthenticationPolicy policy;
    @Autowired SessionFingerprintService fingerprints;
    @Autowired AiActorResolver actorResolver;
    @Autowired RbacService rbacService;

    @AfterEach
    void cleanSecurityRecords() {
        jdbcTemplate.update("DELETE FROM ai_audit_event");
        jdbcTemplate.update("DELETE FROM ai_audit_chain_head");
        jdbcTemplate.update("DELETE FROM ai_step_up_grant");
        jdbcTemplate.update("DELETE FROM ai_step_up_failure_window");
        jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username LIKE 'stepup_%')");
        jdbcTemplate.update("DELETE FROM sys_user WHERE username LIKE 'stepup_%'");
    }

    @Test
    void issuesOpaquePrivateProofAndEnforcesAllBindingsAndSingleUse() throws Exception {
        Login login = login();
        MvcResult result = issueWithHeader(login.token(), "test-password-123", "AUDIT_CONTENT_READ", RESOURCE)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.data.authMethod").value("PASSWORD"))
                .andReturn();
        String vary = String.join(",", result.getResponse().getHeaders("Vary"));
        assertTrue(vary.contains("Authorization"));
        assertTrue(vary.contains("Cookie"));
        assertTrue(vary.contains("Origin"));
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        String proof = data.path("proof").asText();
        assertTrue(proof.startsWith("sup1.1."));
        assertTrue(data.path("expiresAt").asText().compareTo(data.path("authenticatedAt").asText()) > 0);
        String persisted = jdbcTemplate.queryForObject(
                "SELECT grant_token_hmac FROM ai_step_up_grant", String.class);
        assertNotNull(persisted);
        assertNotEquals(proof, persisted);
        assertFalse(jdbcTemplate.queryForList("SELECT * FROM ai_step_up_grant").toString()
                .contains("test-password-123"));

        AuthenticatedRunContext context = context(login.token());
        assertThrows(com.example.dormitory.ai.api.AiApiException.class, () -> policy.consume(
                proof, context, "AUDIT_CONTENT_READ",
                "22222222-2222-2222-2222-222222222222", REQUEST_HASH));
        policy.consume(proof, context, "AUDIT_CONTENT_READ", RESOURCE, REQUEST_HASH);
        assertThrows(com.example.dormitory.ai.api.AiApiException.class, () -> policy.consume(
                proof, context, "AUDIT_CONTENT_READ", RESOURCE, REQUEST_HASH));
    }

    @Test
    void cookieAuthenticatedIssuanceRequiresAllowedOriginAndCsrf() throws Exception {
        Login login = login();
        mockMvc.perform(post("/api/security/step-up")
                        .cookie(login.cookie())
                        .header("Origin", "http://localhost:5173")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("test-password-123", "CONFIG_ACTIVATE", null)))
                .andExpect(status().isForbidden());

        MvcResult csrfResult = mockMvc.perform(get("/api/security/csrf").cookie(login.cookie()))
                .andExpect(status().isOk()).andReturn();
        String csrf = objectMapper.readTree(csrfResult.getResponse().getContentAsString())
                .path("data").path("token").asText();
        mockMvc.perform(post("/api/security/step-up")
                        .cookie(login.cookie())
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("test-password-123", "CONFIG_ACTIVATE", null)))
                .andExpect(status().isOk());
    }

    @Test
    void rateLimitsPasswordBruteForcePerUserAndAuditsFailuresWithoutPassword() throws Exception {
        Login login = login();
        for (int attempt = 0; attempt < 5; attempt++) {
            issueWithHeader(login.token(), "wrong-password", "CONFIG_ACTIVATE", null)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.data.errorCode").value("AI_STEP_UP_REAUTH_FAILED"));
        }
        issueWithHeader(login.token(), "test-password-123", "CONFIG_ACTIVATE", null)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "900"))
                .andExpect(jsonPath("$.data.errorCode").value("AI_STEP_UP_RATE_LIMITED"));

        Integer failures = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_audit_event WHERE event_type = 'STEP_UP_AUTH_FAILED'",
                Integer.class);
        assertNotNull(failures);
        assertTrue(failures >= 5);
        assertFalse(jdbcTemplate.queryForList("SELECT payload_redacted_hash FROM ai_audit_event").toString()
                .contains("wrong-password"));
    }

    @Test
    void logoutRevokesUnusedProofForCurrentSession() throws Exception {
        Login login = login();
        issueWithHeader(login.token(), "test-password-123", "CONFIG_ACTIVATE", null)
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/logout").header("Authorization", login.token()))
                .andExpect(status().isOk());
        String state = jdbcTemplate.queryForObject("SELECT state FROM ai_step_up_grant", String.class);
        org.junit.jupiter.api.Assertions.assertEquals("REVOKED", state);
    }

    @Test
    void passwordChangeDisableAndDeleteEachRevokeOutstandingProofs() throws Exception {
        Login admin = login();
        Long roleId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_role WHERE code = 'REPAIRER'", Long.class);
        MvcResult created = mockMvc.perform(post("/api/users")
                        .header("Authorization", admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "stepup_target",
                                "displayName", "step-up 测试用户",
                                "password", "target-password-123",
                                "enabled", true,
                                "roleIds", java.util.List.of(roleId)))))
                .andExpect(status().isCreated()).andReturn();
        long targetId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        Login target = login("stepup_target", "target-password-123");
        issueWithHeader(target.token(), "target-password-123", "CONFIG_ACTIVATE", null)
                .andExpect(status().isOk());
        updateUser(admin.token(), targetId, "changed-password-456", true, roleId)
                .andExpect(status().isOk());
        assertNoActiveGrant(targetId);

        target = login("stepup_target", "changed-password-456");
        issueWithHeader(target.token(), "changed-password-456", "CONFIG_ACTIVATE", null)
                .andExpect(status().isOk());
        updateUser(admin.token(), targetId, null, false, roleId).andExpect(status().isOk());
        assertNoActiveGrant(targetId);

        updateUser(admin.token(), targetId, null, true, roleId).andExpect(status().isOk());
        target = login("stepup_target", "changed-password-456");
        issueWithHeader(target.token(), "changed-password-456", "CONFIG_ACTIVATE", null)
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/users/{id}", targetId).header("Authorization", admin.token()))
                .andExpect(status().isNoContent());
        assertNoActiveGrant(targetId);
    }

    private org.springframework.test.web.servlet.ResultActions issueWithHeader(
            String token, String password, String action, String resource) throws Exception {
        return mockMvc.perform(post("/api/security/step-up")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request(password, action, resource)));
    }

    private String request(String password, String action, String resource) throws Exception {
        java.util.HashMap<String, String> values = new java.util.HashMap<>();
        values.put("password", password);
        values.put("actionCode", action);
        values.put("requestHash", REQUEST_HASH);
        if (resource != null) values.put("resourcePublicId", resource);
        return objectMapper.writeValueAsString(values);
    }

    private Login login() throws Exception {
        return login("admin", "test-password-123");
    }

    private Login login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username, "password", password))))
                .andExpect(status().isOk()).andReturn();
        Cookie cookie = result.getResponse().getCookie("Authorization");
        assertNotNull(cookie);
        return new Login(cookie.getValue(), cookie);
    }

    private org.springframework.test.web.servlet.ResultActions updateUser(
            String adminToken, long userId, String password, boolean enabled, long roleId) throws Exception {
        java.util.HashMap<String, Object> values = new java.util.HashMap<>();
        values.put("displayName", "step-up 测试用户");
        values.put("enabled", enabled);
        values.put("roleIds", java.util.List.of(roleId));
        if (password != null) values.put("password", password);
        return mockMvc.perform(patch("/api/users/{id}", userId)
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(values)));
    }

    private void assertNoActiveGrant(long userId) {
        Integer active = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_step_up_grant WHERE actor_user_id = ? AND state = 'ACTIVE'",
                Integer.class, userId);
        org.junit.jupiter.api.Assertions.assertEquals(0, active);
    }

    private AuthenticatedRunContext context(String token) {
        long userId = 1L;
        RbacService.AuthorizationSnapshot snapshot = rbacService.authorizationSnapshotForUser(userId);
        SessionFingerprintService.Fingerprint fingerprint = fingerprints.fingerprint(token);
        return new AuthenticatedRunContext(ActorDescriptor.user(userId), fingerprint.hash(),
                fingerprint.keyVersion(), actorResolver.permissionDigest(snapshot.permissionCodes()));
    }

    private record Login(String token, Cookie cookie) { }
}
