package com.example.dormitory.ai.api;

import com.example.dormitory.ai.risk.RiskCaseService;
import com.example.dormitory.ai.risk.RiskSignal;
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

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ai-risk-api;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "dormitory.ai.enabled=true",
        "dormitory.ai.capabilities.risk=true",
        "dormitory.ai.audit.hmac-key=0123456789abcdef0123456789abcdef",
        "dormitory.ai.tokenization.hmac-key=fedcba9876543210fedcba9876543210"
})
class AiRiskApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RiskCaseService riskCases;

    private String riskId;

    @BeforeEach
    void createDeterministicRisk() {
        jdbcTemplate.update("INSERT INTO sys_role_permission(role_id, permission_id) "
                + "SELECT r.id, p.id FROM sys_role r CROSS JOIN sys_permission p "
                + "WHERE r.code='ADMIN' AND p.code IN ('ai:risk:read','ai:risk:manage','repair:read',"
                + "'dormitory:read','checkin:read','hygiene:read','payment:read') AND NOT EXISTS ("
                + "SELECT 1 FROM sys_role_permission rp WHERE rp.role_id=r.id AND rp.permission_id=p.id)");
        jdbcTemplate.update("DELETE FROM ai_risk_case_event");
        jdbcTemplate.update("DELETE FROM ai_risk_case");
        jdbcTemplate.update("DELETE FROM ai_idempotency_record");
        riskId = riskCases.ingest(new RiskSignal(
                "repair-backlog", "REPAIR_ORDER", 41L, "risk_k1_repair_41", "HIGH",
                "repair-backlog.v1", Map.of("ageHours", 72, "ruleThreshold", 48), Instant.now()))
                .publicId();
    }

    @Test
    void listDetailAndHumanDispositionUsePermissionsVersionAndIdempotency() throws Exception {
        String token = login();
        mockMvc.perform(get("/api/ai/risk-cases").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.data.records[0].id").value(riskId))
                .andExpect(jsonPath("$.data.records[0].caseVersion").value(1))
                .andExpect(jsonPath("$.data.records[0].subjectToken").value("risk_k1_repair_41"))
                .andExpect(jsonPath("$.data.records[0].severity").value("high"))
                .andExpect(jsonPath("$.data.records[0].evidenceLayers.signal.policyVersion").value("repair-backlog.v1"))
                .andExpect(jsonPath("$.data.records[0].evidenceLayers.businessSnapshot.subjectType").value("REPAIR_ORDER"))
                .andExpect(jsonPath("$.data.records[0].evidenceLayers.explanation.basis").value("deterministic_degraded"))
                .andExpect(jsonPath("$.data.records[0].evidenceLayers.explanation.confidence").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].explanationRunId").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].assigneeUserId").doesNotExist());

        mockMvc.perform(post("/api/ai/risk-cases/{id}/resolve", riskId)
                        .header("Authorization", token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caseVersion\":1,\"detail\":\"已完成现场复核并恢复供电\","
                                + "\"dueAt\":\"2026-07-20T08:00:00Z\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/ai/risk-cases/{id}", riskId).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("resolved"))
                .andExpect(jsonPath("$.data.caseVersion").value(2))
                .andExpect(jsonPath("$.data.assigneeUserId").value(1))
                .andExpect(jsonPath("$.data.dueAt").value("2026-07-20T08:00:00Z"))
                .andExpect(jsonPath("$.data.evidenceLayers.human.events[1].type").value("RESOLVED"))
                .andExpect(jsonPath("$.data.events[1].detail").value("已完成现场复核并恢复供电"));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_risk_case_event WHERE case_id=(SELECT id FROM ai_risk_case WHERE public_id=?) "
                        + "AND event_type='RESOLVED'", Integer.class, riskId));
    }

    @Test
    void rejectsMissingManagePermission() throws Exception {
        String token = login();
        jdbcTemplate.update("DELETE FROM sys_role_permission WHERE role_id=(SELECT id FROM sys_role WHERE code='ADMIN') "
                + "AND permission_id=(SELECT id FROM sys_permission WHERE code='ai:risk:manage')");
        mockMvc.perform(post("/api/ai/risk-cases/{id}/acknowledge", riskId)
                        .header("Authorization", token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caseVersion\":1,\"detail\":\"开始人工核验\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void filtersByMappedTypeAndStateAndRejectsUnknownFilters() throws Exception {
        String token = login();
        mockMvc.perform(get("/api/ai/risk-cases")
                        .header("Authorization", token)
                        .param("type", "维修风险")
                        .param("state", "open")
                        .param("page", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].type").value("维修风险"));

        mockMvc.perform(get("/api/ai/risk-cases")
                        .header("Authorization", token).param("type", "入住风险"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/api/ai/risk-cases")
                        .header("Authorization", token).param("type", "卫生风险"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/api/ai/risk-cases")
                        .header("Authorization", token).param("type", "欠费风险"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/api/ai/risk-cases")
                        .header("Authorization", token).param("state", "unknown"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mapsAllSixInternalRulesToFourDisplayTypesAndScopesPaymentDetail() throws Exception {
        riskCases.ingest(new RiskSignal(
                "resource-checkin-inconsistency", "DORMITORY", 42L, "risk_k1_dormitory_42", "HIGH",
                "resource-checkin.v1", Map.of("configuredBeds", 4, "actualBeds", 3), Instant.now()));
        riskCases.ingest(new RiskSignal(
                "long-pending-operation", "CHECK_IN_APPLICATION", 43L, "risk_k1_checkin_43", "MEDIUM",
                "long-pending.v1", Map.of("ageHours", 72, "status", "待审核", "ruleThreshold", 48),
                Instant.now()));
        riskCases.ingest(new RiskSignal(
                "failed-hygiene-check", "DORMITORY", 44L, "risk_k1_dormitory_44", "HIGH",
                "hygiene.v1", Map.of("count", 1, "score", 58, "result", "不合格",
                        "inspectedOn", "2026-07-10"), Instant.now()));
        String paymentId = riskCases.ingest(new RiskSignal(
                "overdue-payment", "PAYMENT", 45L, "risk_k1_payment_45", "HIGH",
                "payment.v1", Map.of("status", "部分缴", "deadline", "2026-07-10", "ageDays", 10,
                        "amountDue", 1200, "amountPaid", 200), Instant.now())).publicId();
        String token = login();

        mockMvc.perform(get("/api/ai/risk-cases")
                        .header("Authorization", token).param("type", "入住风险"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records[0].type").value("入住风险"))
                .andExpect(jsonPath("$.data.records[1].type").value("入住风险"));
        mockMvc.perform(get("/api/ai/risk-cases")
                        .header("Authorization", token).param("type", "卫生风险"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].type").value("卫生风险"));
        mockMvc.perform(get("/api/ai/risk-cases")
                        .header("Authorization", token).param("type", "欠费风险"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(paymentId))
                .andExpect(jsonPath("$.data.records[0].evidenceSummary")
                        .value("账单已超过截止日 10 天，仍处于部分缴状态"))
                .andExpect(jsonPath("$.data.records[0].evidenceLayers.businessSnapshot.subjectType")
                        .value("PAYMENT"));

        jdbcTemplate.update("DELETE FROM sys_role_permission WHERE role_id=(SELECT id FROM sys_role WHERE code='ADMIN') "
                + "AND permission_id=(SELECT id FROM sys_permission WHERE code='payment:read')");
        mockMvc.perform(get("/api/ai/risk-cases/{id}", paymentId).header("Authorization", token))
                .andExpect(status().isNotFound());
    }

    @Test
    void sameIdempotencyKeyReplaysSamePayloadButRejectsDifferentPayload() throws Exception {
        String token = login();
        String key = UUID.randomUUID().toString();
        String first = "{\"caseVersion\":1,\"detail\":\"开始人工核验\"}";
        mockMvc.perform(post("/api/ai/risk-cases/{id}/acknowledge", riskId)
                        .header("Authorization", token).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/ai/risk-cases/{id}/acknowledge", riskId)
                        .header("Authorization", token).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/ai/risk-cases/{id}/acknowledge", riskId)
                        .header("Authorization", token).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caseVersion\":1,\"detail\":\"不同处置内容\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("AI_IDEMPOTENCY_PAYLOAD_MISMATCH"));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_risk_case_event WHERE case_id=(SELECT id FROM ai_risk_case WHERE public_id=?) "
                        + "AND event_type='ACKNOWLEDGED'", Integer.class, riskId));
    }

    @Test
    void rejectsStaleCaseVersionWithoutAppendingEvent() throws Exception {
        String token = login();
        mockMvc.perform(post("/api/ai/risk-cases/{id}/acknowledge", riskId)
                        .header("Authorization", token).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caseVersion\":1,\"detail\":\"开始核验\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/ai/risk-cases/{id}/resolve", riskId)
                        .header("Authorization", token).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caseVersion\":1,\"detail\":\"使用过期版本解决\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("AI_RISK_CASE_VERSION_CONFLICT"));
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_risk_case_event WHERE case_id=(SELECT id FROM ai_risk_case WHERE public_id=?)",
                Integer.class, riskId));
    }

    @Test
    void requiresLoginAndReadPermissionForEveryRead() throws Exception {
        mockMvc.perform(get("/api/ai/risk-cases"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));

        String token = login();
        jdbcTemplate.update("DELETE FROM sys_role_permission WHERE role_id=(SELECT id FROM sys_role WHERE code='ADMIN') "
                + "AND permission_id=(SELECT id FROM sys_permission WHERE code='ai:risk:read')");
        mockMvc.perform(get("/api/ai/risk-cases/{id}", riskId).header("Authorization", token))
                .andExpect(status().isForbidden());
    }

    @Test
    void currentBottomBusinessPermissionScopesDetailAndDisposition() throws Exception {
        String token = login();
        jdbcTemplate.update("DELETE FROM sys_role_permission WHERE role_id=(SELECT id FROM sys_role WHERE code='ADMIN') "
                + "AND permission_id=(SELECT id FROM sys_permission WHERE code='repair:read')");

        mockMvc.perform(get("/api/ai/risk-cases/{id}", riskId).header("Authorization", token))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/ai/risk-cases/{id}/resolve", riskId)
                        .header("Authorization", token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caseVersion\":1,\"detail\":\"不应越权处置\"}"))
                .andExpect(status().isNotFound());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_risk_case_event WHERE case_id=(SELECT id FROM ai_risk_case WHERE public_id=?)",
                Integer.class, riskId));
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
