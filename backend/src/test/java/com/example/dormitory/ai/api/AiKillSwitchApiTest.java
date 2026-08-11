package com.example.dormitory.ai.api;

import com.example.dormitory.ai.config.AiRuntimeControlService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ai-kill-switch;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.connection-timeout=250",
        "dormitory.ai.enabled=true",
        "dormitory.ai.capabilities.assistant=true",
        "dormitory.ai.capabilities.dashboard=true",
        "dormitory.ai.streaming.enabled=true",
        "dormitory.ai.audit.hmac-key=0123456789abcdef0123456789abcdef",
        "dormitory.ai.tokenization.hmac-key=fedcba9876543210fedcba9876543210",
        "dormitory.ai.step-up.hmac-key=abcdef0123456789abcdef0123456789"
})
class AiKillSwitchApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AiRuntimeControlService controls;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetOverride() {
        jdbcTemplate.update("DELETE FROM ai_step_up_grant WHERE action_code='KILL_SWITCH_CLEAR'");
        jdbcTemplate.update("DELETE FROM ai_runtime_switch WHERE scope_type='CAPABILITY' "
                + "AND scope_key IN ('DASHBOARD','ASSISTANT')");
        jdbcTemplate.update("DELETE FROM ai_audit_event WHERE chain_scope='CONFIG' "
                + "AND aggregate_type='AI_KILL_SWITCH'");
        jdbcTemplate.update("DELETE FROM ai_audit_chain_head WHERE chain_scope='CONFIG' "
                + "AND aggregate_type='AI_KILL_SWITCH'");
    }

    @Test
    void configManagerCanDisableCapabilityAndGateImmediatelyRejectsNewRuns() throws Exception {
        String token = login();
        mockMvc.perform(post("/api/ai/operations/kill-switches/CAPABILITY/DASHBOARD/disable")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Dashboard 上游异常率超过演练阈值\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.disabled").value(true))
                .andExpect(jsonPath("$.data.scope").value("CAPABILITY"));

        mockMvc.perform(post("/api/ai/dashboard/queries")
                        .header("Authorization", token)
                        .header("Idempotency-Key", "kill-switch-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"宿舍总数是多少\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.errorCode").value("AI_CAPABILITY_DISABLED"));

        mockMvc.perform(get("/api/ai/operations/kill-switches").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].key").value("DASHBOARD"));
    }

    @Test
    void clearRequiresTargetBoundSingleUseProofAndRemovesPersistedOverride() throws Exception {
        String token = login();
        String reason = "上游恢复且已完成人工复核";
        mockMvc.perform(post("/api/ai/operations/kill-switches/CAPABILITY/DASHBOARD/disable")
                        .header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"故障演练先关闭能力\"}"))
                .andExpect(status().isOk());
        String requestHash = AiOperationsController.switchHash(
                AiRuntimeControlService.Scope.CAPABILITY, "DASHBOARD", false, reason, 1L);
        String resourceId = AiRuntimeControlService.resourcePublicId(
                AiRuntimeControlService.Scope.CAPABILITY, "DASHBOARD");
        MvcResult proofResponse = mockMvc.perform(post("/api/security/step-up")
                        .header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "password", "test-password-123", "actionCode", "KILL_SWITCH_CLEAR",
                                "resourcePublicId", resourceId, "requestHash", requestHash))))
                .andExpect(status().isOk()).andReturn();
        String proof = objectMapper.readTree(proofResponse.getResponse().getContentAsString())
                .path("data").path("proof").asText();

        mockMvc.perform(post("/api/ai/operations/kill-switches/CAPABILITY/DASHBOARD/clear")
                        .header("Authorization", token).header("X-Step-Up-Proof", proof)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", reason, "expectedVersion", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.disabled").value(false));
        mockMvc.perform(get("/api/ai/operations/kill-switches").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        assertEquals("USED", jdbcTemplate.queryForObject(
                "SELECT state FROM ai_step_up_grant WHERE action_code='KILL_SWITCH_CLEAR'",
                String.class));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_audit_event WHERE event_type='AI_KILL_SWITCH_CLEARED'",
                Integer.class));
    }

    @Test
    void clearRollsBackSwitchMutationWhenAuditAppendFailsAfterProofWasConsumed() throws Exception {
        String token = login();
        String reason = "上游恢复且已完成人工复核";
        mockMvc.perform(post("/api/ai/operations/kill-switches/CAPABILITY/DASHBOARD/disable")
                        .header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"故障演练先关闭能力\"}"))
                .andExpect(status().isOk());
        String requestHash = AiOperationsController.switchHash(
                AiRuntimeControlService.Scope.CAPABILITY, "DASHBOARD", false, reason, 1L);
        String resourceId = AiRuntimeControlService.resourcePublicId(
                AiRuntimeControlService.Scope.CAPABILITY, "DASHBOARD");
        MvcResult proofResponse = mockMvc.perform(post("/api/security/step-up")
                        .header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "password", "test-password-123", "actionCode", "KILL_SWITCH_CLEAR",
                                "resourcePublicId", resourceId, "requestHash", requestHash))))
                .andExpect(status().isOk()).andReturn();
        String proof = objectMapper.readTree(proofResponse.getResponse().getContentAsString())
                .path("data").path("proof").asText();
        jdbcTemplate.update("UPDATE ai_audit_chain_head SET last_event_hash=? "
                        + "WHERE chain_scope='CONFIG' AND aggregate_type='AI_KILL_SWITCH' "
                        + "AND aggregate_public_id=?",
                "0".repeat(64), resourceId);

        mockMvc.perform(post("/api/ai/operations/kill-switches/CAPABILITY/DASHBOARD/clear")
                        .header("Authorization", token).header("X-Step-Up-Proof", proof)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reason", reason, "expectedVersion", 1))))
                .andExpect(status().isInternalServerError());

        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_runtime_switch WHERE scope_type='CAPABILITY' "
                        + "AND scope_key='DASHBOARD' AND disabled=TRUE AND version=1",
                Integer.class));
        assertEquals("USED", jdbcTemplate.queryForObject(
                "SELECT state FROM ai_step_up_grant WHERE action_code='KILL_SWITCH_CLEAR'",
                String.class));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_audit_event WHERE event_type='AI_KILL_SWITCH_CLEARED'",
                Integer.class));
    }

    @Test
    void clearUsesVersionCasSoAStaleOperatorCannotEraseANewerIncidentOverride() throws Exception {
        String token = login();
        String reason = "上游恢复且已完成人工复核";
        mockMvc.perform(post("/api/ai/operations/kill-switches/CAPABILITY/DASHBOARD/disable")
                        .header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"第一次故障处置关闭能力\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        String requestHash = AiOperationsController.switchHash(
                AiRuntimeControlService.Scope.CAPABILITY, "DASHBOARD", false, reason, 1L);
        String resourceId = AiRuntimeControlService.resourcePublicId(
                AiRuntimeControlService.Scope.CAPABILITY, "DASHBOARD");
        MvcResult proofResponse = mockMvc.perform(post("/api/security/step-up")
                        .header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "password", "test-password-123", "actionCode", "KILL_SWITCH_CLEAR",
                                "resourcePublicId", resourceId, "requestHash", requestHash))))
                .andExpect(status().isOk()).andReturn();
        String proof = objectMapper.readTree(proofResponse.getResponse().getContentAsString())
                .path("data").path("proof").asText();

        mockMvc.perform(post("/api/ai/operations/kill-switches/CAPABILITY/DASHBOARD/disable")
                        .header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"并发发现新的故障证据并更新关闭原因\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(2));

        mockMvc.perform(post("/api/ai/operations/kill-switches/CAPABILITY/DASHBOARD/clear")
                        .header("Authorization", token).header("X-Step-Up-Proof", proof)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reason", reason, "expectedVersion", 1))))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/ai/operations/kill-switches").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].disabled").value(true))
                .andExpect(jsonPath("$.data[0].version").value(2));
    }

    @Test
    void clearStepUpHashBindsTheReviewedSwitchVersion() {
        String reason = "上游恢复且已完成人工复核";

        assertNotEquals(
                AiOperationsController.switchHash(
                        AiRuntimeControlService.Scope.CAPABILITY, "DASHBOARD", false, reason, 1L),
                AiOperationsController.switchHash(
                        AiRuntimeControlService.Scope.CAPABILITY, "DASHBOARD", false, reason, 2L));
    }

    @Test
    void assistantIncidentKillSwitchDoesNotDisableForensicAuditReads() throws Exception {
        String token = login();
        controls.disable(AiRuntimeControlService.Scope.CAPABILITY, "ASSISTANT",
                "助手发生安全事件，保留审计取证入口");

        mockMvc.perform(get("/api/ai/audit/runs").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records").isArray());
    }

    private String login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "admin", "password", "test-password-123"))))
                .andExpect(status().isOk()).andReturn();
        var cookie = result.getResponse().getCookie("Authorization");
        assertNotNull(cookie);
        return cookie.getValue();
    }
}
