package com.example.dormitory.ai.risk;

import com.example.dormitory.ai.outbox.AiOutboxWorker;
import com.fasterxml.jackson.databind.JsonNode;
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

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:risk-outbox;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "dormitory.ai.enabled=true",
        "dormitory.ai.capabilities.risk=true",
        "dormitory.ai.outbox.auto-process=false",
        "dormitory.ai.outbox.initial-delay=PT24H",
        "dormitory.ai.knowledge.auto-process=false",
        "dormitory.ai.provider.active=fake",
        "dormitory.ai.audit.hmac-key=0123456789abcdef0123456789abcdef",
        "dormitory.ai.tokenization.hmac-key=fedcba9876543210fedcba9876543210"
})
class RiskScanOutboxIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired AiOutboxWorker worker;

    @BeforeEach
    void reset() {
        for (String table : new String[]{"ai_outbox_event", "ai_risk_scan", "ai_risk_case_event",
                "ai_risk_case", "ai_idempotency_record", "ai_audit_event", "ai_audit_chain_head"}) {
            jdbc.update("DELETE FROM " + table);
        }
        grantAdmin("ai:risk:read", "ai:risk:manage", "repair:read", "dormitory:read", "checkin:read");
    }

    @Test
    void postOnlyQueuesAndWorkerUsesCurrentAuthorizationIntersection() throws Exception {
        jdbc.update("INSERT INTO repair_order(code,reporter,location,type,date,status,created_at,updated_at) "
                        + "VALUES ('WX-ASYNC','敏感姓名','一号楼-101','水电','2026-01-01','待处理',?,?)",
                Instant.now().minusSeconds(7 * 24 * 3600), Instant.now().minusSeconds(7 * 24 * 3600));
        String token = login();
        String key = UUID.randomUUID().toString();
        var accepted = mockMvc.perform(post("/api/ai/risk-scans")
                        .header("Authorization", token).header("Idempotency-Key", key))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.state").value("QUEUED"))
                .andReturn();
        String scanId = json.readTree(accepted.getResponse().getContentAsString()).path("data").path("id").asText();
        assertEquals(0, count("SELECT COUNT(*) FROM ai_risk_case"));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_outbox_event WHERE aggregate_public_id=? "
                + "AND event_type='RiskScanRequested.v1' AND state='PENDING'", scanId));

        // 请求后撤销底层业务权限；worker 只能取请求快照与当前授权的交集，不能沿用旧权限。
        revokeAdmin("repair:read", "dormitory:read", "checkin:read", "hygiene:read", "payment:read");
        assertEquals(1, worker.processAvailable(10), () -> outboxDiagnostics(scanId));

        mockMvc.perform(get("/api/ai/risk-scans/{id}", scanId).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.signalCount").value(0));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_risk_case"));
        assertEquals("SUCCEEDED", jdbc.queryForObject(
                "SELECT state FROM ai_outbox_event WHERE aggregate_public_id=?", String.class, scanId));

        // 模拟进程在 handler 完成后、ack 前崩溃造成的重投；终态扫描不得重复创建案例。
        jdbc.update("UPDATE ai_outbox_event SET state='RETRYABLE_FAILED',available_at=?,"
                        + "published_at=NULL WHERE aggregate_public_id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), scanId);
        assertEquals(1, worker.processAvailable(10));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_risk_case"));
    }

    @Test
    void sameIdempotencyKeyCreatesOneQueuedScanAndOneOutboxEvent() throws Exception {
        String token = login();
        String key = UUID.randomUUID().toString();
        String first = mockMvc.perform(post("/api/ai/risk-scans")
                        .header("Authorization", token).header("Idempotency-Key", key))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String scanId = json.readTree(first).path("data").path("id").asText();
        mockMvc.perform(post("/api/ai/risk-scans")
                        .header("Authorization", token).header("Idempotency-Key", key))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.id").value(scanId));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_risk_scan"));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_outbox_event WHERE aggregate_public_id=?", scanId));
    }

    private String login() throws Exception {
        var response = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
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
                            + "WHERE r.code='ADMIN' AND p.code=? AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp "
                            + "WHERE rp.role_id=r.id AND rp.permission_id=p.id)", permission);
        }
    }

    private void revokeAdmin(String... permissions) {
        for (String permission : permissions) {
            jdbc.update("DELETE FROM sys_role_permission WHERE role_id=(SELECT id FROM sys_role WHERE code='ADMIN') "
                    + "AND permission_id=(SELECT id FROM sys_permission WHERE code=?)", permission);
        }
    }

    private int count(String sql, Object... arguments) {
        Integer value = jdbc.queryForObject(sql, Integer.class, arguments);
        return value == null ? 0 : value;
    }

    private String outboxDiagnostics(String scanId) {
        return "outbox=" + jdbc.queryForMap(
                "SELECT state,event_type,available_at,locked_by,locked_at,CURRENT_TIMESTAMP AS db_now "
                        + "FROM ai_outbox_event WHERE aggregate_public_id=?",
                scanId);
    }
}
