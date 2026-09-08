package com.example.dormitory.ai.api;

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
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ai-feature-command;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "dormitory.ai.capabilities.assistant=true",
        "dormitory.ai.enabled=true",
        "dormitory.ai.capabilities.dashboard=true",
        "dormitory.ai.streaming.enabled=true",
        "dormitory.ai.audit.hmac-key=0123456789abcdef0123456789abcdef",
        "dormitory.ai.tokenization.hmac-key=fedcba9876543210fedcba9876543210"
})
class AiFeatureCommandApiTest {

    private final com.example.dormitory.ai.governance.StandardToolCatalogManifest standardCatalog =
            new com.example.dormitory.ai.governance.StandardToolCatalogManifest(
                    com.example.dormitory.ai.tool.ToolCatalog.standard(), new com.fasterxml.jackson.databind.ObjectMapper());

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void activateRuntime() {
        for (String table : new String[]{"ai_run_event", "ai_run", "ai_message", "ai_conversation",
                "ai_audit_event", "ai_audit_chain_head", "ai_tool_catalog_version"}) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        jdbcTemplate.update("UPDATE ai_prompt_version SET status='DRAFT', active_slot_key=NULL, activated_at=NULL");
        jdbcTemplate.update("UPDATE ai_prompt_version SET status='ACTIVE', active_slot_key='dashboard.system', "
                + "activated_at=CURRENT_TIMESTAMP WHERE prompt_key='dashboard.system' AND version='v1'");
        jdbcTemplate.update("INSERT INTO ai_tool_catalog_version "
                        + "(version, manifest_text, manifest_hash, status, active_slot_key, activated_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'ACTIVE', 'runtime', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                standardCatalog.version(), standardCatalog.manifest(), standardCatalog.hash());
    }

    @Test
    void dashboardCommandIs202RecoverableAndIdempotentWithStructuredTerminalResult() throws Exception {
        String token = login();
        String idempotency = UUID.randomUUID().toString();
        String body = "{\"question\":\"宿舍总数是多少\"}";
        MvcResult first = mockMvc.perform(post("/api/ai/dashboard/queries")
                        .header("Authorization", token)
                        .header("Idempotency-Key", idempotency)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.data.runId").isNotEmpty())
                .andExpect(jsonPath("$.data.eventsUrl").isNotEmpty())
                .andReturn();
        JsonNode accepted = objectMapper.readTree(first.getResponse().getContentAsString()).path("data");
        String runId = accepted.path("runId").asText();

        MvcResult replay = mockMvc.perform(post("/api/ai/dashboard/queries")
                        .header("Authorization", token).header("Idempotency-Key", idempotency)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andReturn();
        assertEquals(runId, objectMapper.readTree(replay.getResponse().getContentAsString())
                .path("data").path("runId").asText());
        awaitTerminal(runId);

        String events = mockMvc.perform(get("/api/ai/runs/{id}/events", runId)
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(events.contains("event:run.completed"));
        assertTrue(events.contains("dormitory.total"));
        assertTrue(events.contains("intentSchemaVersion"));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_run WHERE public_id = ?", Integer.class, runId));

        MvcResult runResponse = mockMvc.perform(get("/api/ai/runs/{id}", runId)
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conversationId").isNotEmpty())
                .andReturn();
        String commandConversationId = objectMapper.readTree(runResponse.getResponse().getContentAsString())
                .path("data").path("conversationId").asText();
        MvcResult hiddenConversation = mockMvc.perform(
                        get("/api/ai/conversations/{id}", commandConversationId)
                                .header("Authorization", token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.errorCode").value("AI_RESOURCE_NOT_FOUND"))
                .andReturn();
        assertFalse(hiddenConversation.getResponse().getContentAsString().contains("宿舍总数是多少"));

        mockMvc.perform(post("/api/ai/dashboard/queries")
                        .header("Authorization", token).header("Idempotency-Key", idempotency)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"空余床位是多少\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("AI_IDEMPOTENCY_PAYLOAD_MISMATCH"));
    }

    @Test
    void unsupportedDashboardCommandPersistsTerminalFailureEventWithoutBudgetReservation() throws Exception {
        String token = login();
        MvcResult accepted = mockMvc.perform(post("/api/ai/dashboard/queries")
                        .header("Authorization", token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"列出学生密码字段\"}"))
                .andExpect(status().isAccepted())
                .andReturn();
        String runId = objectMapper.readTree(accepted.getResponse().getContentAsString())
                .path("data").path("runId").asText();

        awaitState(runId, "FAILED");

        String events = mockMvc.perform(get("/api/ai/runs/{id}/events", runId)
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(events.contains("event:run.failed"));
        assertTrue(events.contains("AI_DETERMINISTIC_COMMAND_FAILED"));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_budget_reservation WHERE billing_subject_public_id = ?",
                Integer.class, runId));
        assertEquals("FINAL", jdbcTemplate.queryForObject(
                "SELECT cost_status FROM ai_run WHERE public_id = ?", String.class, runId));
    }

    private void awaitTerminal(String runId) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            String state = jdbcTemplate.queryForObject("SELECT state FROM ai_run WHERE public_id = ?", String.class, runId);
            if (java.util.Set.of("SUCCEEDED", "FAILED").contains(state)) {
                assertEquals("SUCCEEDED", state);
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("command run 未终止");
    }

    private void awaitState(String runId, String expected) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            String state = jdbcTemplate.queryForObject(
                    "SELECT state FROM ai_run WHERE public_id = ?", String.class, runId);
            if (expected.equals(state)) return;
            Thread.sleep(20);
        }
        throw new AssertionError("command run 未进入状态 " + expected);
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
