package com.example.dormitory.ai.risk;

import com.example.dormitory.ai.outbox.AiOutboxWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@ContextConfiguration(classes = RiskScanExplanationTransactionBoundaryTest.RollbackOnlyTestConfiguration.class)
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:risk-scan-explanation-boundary;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "dormitory.ai.enabled=true",
        "dormitory.ai.capabilities.risk=true",
        "dormitory.ai.risk.explanation.enabled=true",
        "dormitory.ai.outbox.auto-process=false",
        "dormitory.ai.outbox.initial-delay=PT24H",
        "dormitory.ai.knowledge.auto-process=false",
        "dormitory.ai.audit.hmac-key=risk-scan-boundary-audit-key-at-least-32-bytes",
        "dormitory.ai.tokenization.hmac-key=risk-scan-boundary-token-key-at-least-32-bytes"
})
class RiskScanExplanationTransactionBoundaryTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired AiOutboxWorker worker;
    @Autowired RollbackOnlyParticipant rollbackOnlyParticipant;

    @BeforeEach
    void setUp() {
        for (String table : new String[]{"ai_outbox_event", "ai_risk_scan", "ai_risk_case_event",
                "ai_risk_case", "ai_idempotency_record", "ai_audit_event", "ai_audit_chain_head"}) {
            jdbc.update("DELETE FROM " + table);
        }
        grantAdmin("ai:risk:read", "ai:risk:manage", "repair:read");
        rollbackOnlyParticipant.reset();
    }

    @Test
    void explanationRollbackOnlyDoesNotRollbackDeterministicScanOrRiskCase() throws Exception {
        jdbc.update("INSERT INTO repair_order(code,reporter,location,type,date,status,created_at,updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?)",
                "WX-RISK-TX-" + UUID.randomUUID(), "测试人员", "一号楼-101", "水电",
                "2026-01-01", "待处理", Instant.now().minusSeconds(7 * 24 * 3600),
                Instant.now().minusSeconds(7 * 24 * 3600));
        String token = login();
        String response = mockMvc.perform(post("/api/ai/risk-scans")
                        .header("Authorization", token)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String scanId = json.readTree(response).path("data").path("id").asText();

        assertTrue(worker.processAvailable(10) >= 1);

        assertTrue(rollbackOnlyParticipant.invocations() >= 1);
        assertEquals("SUCCEEDED", jdbc.queryForObject(
                "SELECT state FROM ai_risk_scan WHERE public_id=?", String.class, scanId));
        assertEquals("SUCCEEDED", jdbc.queryForObject(
                "SELECT state FROM ai_outbox_event WHERE aggregate_type='RISK_SCAN' AND aggregate_public_id=?",
                String.class, scanId));
        assertTrue(count("SELECT COUNT(*) FROM ai_risk_case WHERE explanation_basis='DETERMINISTIC_DEGRADED'") >= 1);
        assertEquals(0, count("SELECT COUNT(*) FROM ai_risk_case WHERE explanation_basis='MODEL'"));
    }

    private String login() throws Exception {
        var response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "username", "admin", "password", "test-password-123"))))
                .andExpect(status().isOk()).andReturn();
        var cookie = response.getResponse().getCookie("Authorization");
        assertNotNull(cookie);
        return cookie.getValue();
    }

    private void grantAdmin(String... permissions) {
        for (String permission : permissions) {
            jdbc.update("INSERT INTO sys_role_permission(role_id,permission_id) "
                            + "SELECT r.id,p.id FROM sys_role r CROSS JOIN sys_permission p "
                            + "WHERE r.code='ADMIN' AND p.code=? AND NOT EXISTS ("
                            + "SELECT 1 FROM sys_role_permission rp WHERE rp.role_id=r.id AND rp.permission_id=p.id)",
                    permission);
        }
    }

    private int count(String sql, Object... arguments) {
        Integer value = jdbc.queryForObject(sql, Integer.class, arguments);
        return value == null ? 0 : value;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RollbackOnlyTestConfiguration {

        @Bean
        RollbackOnlyParticipant rollbackOnlyParticipant() {
            return new RollbackOnlyParticipant();
        }

        @Bean
        @Primary
        RiskExplanationRunPort rollbackOnlyRiskExplanationRunPort(RollbackOnlyParticipant participant) {
            return new RiskExplanationRunPort() {
                @Override
                public RunOutcome run(RunRequest request) {
                    try {
                        participant.failInsideJoinedTransaction();
                        throw new AssertionError("测试事务参与者必须抛错");
                    } catch (RuntimeException expected) {
                        return RunOutcome.failed();
                    }
                }
            };
        }
    }

    static class RollbackOnlyParticipant {
        private final AtomicLong invocations = new AtomicLong();

        @Transactional
        public void failInsideJoinedTransaction() {
            invocations.incrementAndGet();
            throw new IllegalStateException("stable rollback-only reproduction");
        }

        long invocations() {
            return invocations.get();
        }

        void reset() {
            invocations.set(0);
        }
    }
}
