package com.example.dormitory.ai.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.dormitory.ai.application.run.AiRunService;
import com.example.dormitory.ai.config.AiRuntimeControlService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        "spring.datasource.url=jdbc:h2:mem:ai-conversation-api;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "dormitory.ai.enabled=true",
        "dormitory.ai.capabilities.assistant=true",
        "dormitory.ai.capabilities.knowledge=true",
        "dormitory.ai.capabilities.repair=true",
        "dormitory.ai.capabilities.notice=true",
        "dormitory.ai.provider.active=fake",
        "dormitory.ai.streaming.enabled=true",
        "dormitory.ai.audit.hmac-key=0123456789abcdef0123456789abcdef",
        "dormitory.ai.tokenization.hmac-key=fedcba9876543210fedcba9876543210",
        "dormitory.ai.step-up.hmac-key=abcdef0123456789abcdef0123456789",
        "dormitory.ai.runtime.heartbeat-interval=PT0.05S",
        "dormitory.ai.runtime.emitter-timeout=PT5S"
})
class AiConversationApiTest {

    private final com.example.dormitory.ai.governance.StandardToolCatalogManifest standardCatalog =
            new com.example.dormitory.ai.governance.StandardToolCatalogManifest(
                    com.example.dormitory.ai.tool.ToolCatalog.standard(), new com.fasterxml.jackson.databind.ObjectMapper());

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AiRuntimeControlService controls;

    @BeforeEach
    void resetAiRuntimeData() {
        for (String table : new String[]{
                "ai_action_execution", "ai_action_approval", "ai_action_proposal", "ai_idempotency_record",
                "ai_feedback", "ai_citation", "ai_retrieval_trace", "ai_tool_call", "ai_run_event",
                "ai_usage_ledger", "ai_budget_reservation",
                "ai_step_up_grant",
                "ai_run", "ai_message", "ai_conversation", "ai_budget_bucket", "ai_quota_policy",
                "ai_audit_event", "ai_audit_chain_head", "ai_tool_catalog_version",
                "ai_document_chunk", "ai_document_version", "ai_document",
                "ai_knowledge_source_permission", "ai_knowledge_source", "ai_runtime_switch"}) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        jdbcTemplate.update("UPDATE ai_prompt_version SET status = 'DRAFT', active_slot_key = NULL, activated_at = NULL");
        jdbcTemplate.update("UPDATE ai_prompt_version SET status = 'ACTIVE', active_slot_key = 'assistant.system', "
                + "activated_at = CURRENT_TIMESTAMP WHERE prompt_key = 'assistant.system' AND version = 'v1'");
        jdbcTemplate.update("INSERT INTO ai_tool_catalog_version "
                        + "(version, manifest_text, manifest_hash, status, active_slot_key, activated_at, "
                        + "created_at, updated_at) VALUES (?, ?, ?, 'ACTIVE', 'runtime', CURRENT_TIMESTAMP, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                standardCatalog.version(), standardCatalog.manifest(), standardCatalog.hash());
        jdbcTemplate.update("INSERT INTO ai_quota_policy "
                        + "(scope_type, scope_key, capability, daily_token_limit, monthly_cost_limit, "
                        + "concurrent_run_limit, status, effective_from, created_at, updated_at) "
                        + "VALUES ('GLOBAL', '*', 'ASSISTANT', 1000000, 1000, 2, 'ACTIVE', ?, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                Timestamp.from(Instant.now().minusSeconds(60)));
        Long policyId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM ai_quota_policy", Long.class);
        jdbcTemplate.update("INSERT INTO ai_budget_bucket "
                        + "(quota_policy_id, scope_type, scope_key, capability, provider_code, period_type, "
                        + "period_start, period_end, token_limit, cost_limit, reserved_tokens, committed_tokens, "
                        + "reserved_cost, committed_cost, currency, version, created_at, updated_at) "
                        + "VALUES (?, 'GLOBAL', '*', 'ASSISTANT', 'fake', 'DAY', ?, ?, 1000000, 1000, "
                        + "0, 0, 0, 0, 'CNY', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                policyId,
                Timestamp.from(Instant.now().minusSeconds(60)),
                Timestamp.from(Instant.now().plusSeconds(3600)));
    }

    @AfterEach
    void removeSensitiveAuditGrant() {
        jdbcTemplate.update("DELETE FROM sys_role_permission WHERE role_id = "
                + "(SELECT id FROM sys_role WHERE code = 'ADMIN') AND permission_id = "
                + "(SELECT id FROM sys_permission WHERE code = 'ai:audit:content:read')");
    }

    @Test
    void auditContentRequiresReasonAndSingleUseStepUpWhileMetadataRemainsReadable() throws Exception {
        grantAdminPermission("ai:audit:content:read");
        String token = login("admin", "test-password-123");
        String conversationId = createConversation(token);
        String runId = sendMessage(token, conversationId, "请说明今日宿舍运行摘要", "audit-content-run")
                .path("runId").asText();
        awaitTerminal(runId);
        String reason = "复核本次助手运行的脱敏输出";
        String requestHash = AiRunService.auditContentRequestHash(runId, reason);
        MvcResult issued = mockMvc.perform(post("/api/security/step-up")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "password", "test-password-123",
                                "actionCode", "AUDIT_CONTENT_READ",
                                "resourcePublicId", runId,
                                "requestHash", requestHash))))
                .andExpect(status().isOk()).andReturn();
        String proof = objectMapper.readTree(issued.getResponse().getContentAsString())
                .path("data").path("proof").asText();

        mockMvc.perform(get("/api/ai/audit/runs/{id}/content", runId)
                        .header("Authorization", token)
                        .header("X-Audit-Reason", reason)
                        .header("X-Step-Up-Proof", proof))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(jsonPath("$.data.runId").value(runId))
                .andExpect(jsonPath("$.data.messages[0].content").isNotEmpty());
        mockMvc.perform(get("/api/ai/audit/runs/{id}/content", runId)
                        .header("Authorization", token)
                        .header("X-Audit-Reason", reason)
                        .header("X-Step-Up-Proof", proof))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.errorCode").value("AI_STEP_UP_PROOF_INVALID"));

        String tamperReason = "确认审计链异常时正文保持封锁";
        String tamperHash = AiRunService.auditContentRequestHash(runId, tamperReason);
        MvcResult tamperProofResponse = mockMvc.perform(post("/api/security/step-up")
                        .header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "password", "test-password-123", "actionCode", "AUDIT_CONTENT_READ",
                                "resourcePublicId", runId, "requestHash", tamperHash))))
                .andExpect(status().isOk()).andReturn();
        String tamperProof = objectMapper.readTree(tamperProofResponse.getResponse().getContentAsString())
                .path("data").path("proof").asText();
        jdbcTemplate.update("UPDATE ai_audit_event SET event_hash=? WHERE chain_scope='RUN' "
                + "AND aggregate_type='RUN' AND aggregate_public_id=? AND sequence_no=1", "f".repeat(64), runId);
        mockMvc.perform(get("/api/ai/audit/runs/{id}/content", runId)
                        .header("Authorization", token).header("X-Audit-Reason", tamperReason)
                        .header("X-Step-Up-Proof", tamperProof))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.errorCode").value("AI_AUDIT_INTEGRITY_FAILURE"));

        mockMvc.perform(get("/api/ai/audit/runs/{id}", runId).header("Authorization", token))
                .andExpect(status().isOk());
        assertEquals(1, count("SELECT COUNT(*) FROM ai_audit_event "
                + "WHERE aggregate_public_id = ? AND event_type = 'AUDIT_CONTENT_READ'", runId));
    }

    @Test
    void auditContentRejectsInvalidReasonAndMissingProofWithoutWritingReadAudit() throws Exception {
        grantAdminPermission("ai:audit:content:read");
        String token = login("admin", "test-password-123");
        String conversationId = createConversation(token);
        String runId = sendMessage(token, conversationId, "请说明今日宿舍运行摘要", "audit-content-negative")
                .path("runId").asText();
        awaitTerminal(runId);
        String auditReadCountSql = "SELECT COUNT(*) FROM ai_audit_event "
                + "WHERE aggregate_public_id = ? AND event_type = 'AUDIT_CONTENT_READ'";
        int auditReadCount = count(auditReadCountSql, runId);

        mockMvc.perform(get("/api/ai/audit/runs/{id}/content", runId)
                        .header("Authorization", token)
                        .header("X-Audit-Reason", "理由过短"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("AI_AUDIT_REASON_REQUIRED"));
        assertEquals(auditReadCount, count(auditReadCountSql, runId));

        String validReason = "复核本次助手运行的脱敏输出";
        mockMvc.perform(get("/api/ai/audit/runs/{id}/content", runId)
                        .header("Authorization", token)
                        .header("X-Audit-Reason", validReason))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.errorCode").value("AI_STEP_UP_PROOF_INVALID"));
        assertEquals(auditReadCount, count(auditReadCountSql, runId));

        mockMvc.perform(get("/api/ai/audit/runs/{id}/content", runId)
                        .header("Authorization", token)
                        .header("X-Audit-Reason", validReason)
                        .header("X-Step-Up-Proof", ""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.errorCode").value("AI_STEP_UP_PROOF_INVALID"));
        assertEquals(auditReadCount, count(auditReadCountSql, runId));
    }

    @Test
    void conversationLifecycleUsesOwnerChecksAndPrivateNoStoreHeaders() throws Exception {
        String ownerToken = login("admin", "test-password-123");
        String otherToken = login(createSecondAdmin(), "other-password-123");

        MvcResult created = mockMvc.perform(post("/api/ai/conversations")
                        .header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"surface\":\"GLOBAL\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(result -> assertVaryTokens(result, "Authorization", "Cookie", "Origin"))
                .andExpect(jsonPath("$.data.id").isNotEmpty())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andReturn();
        String id = objectMapper.readTree(created.getResponse().getContentAsString()).path("data").path("id").asText();
        assertEquals("/api/ai/conversations/" + id, created.getResponse().getHeader("Location"));

        mockMvc.perform(get("/api/ai/conversations").header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].id").value(id));
        mockMvc.perform(get("/api/ai/conversations/{id}", id).header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id));
        mockMvc.perform(get("/api/ai/conversations/{id}", id).header("Authorization", otherToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.errorCode").value("AI_RESOURCE_NOT_FOUND"));

        mockMvc.perform(post("/api/ai/conversations/{id}/archive", id)
                        .header("Authorization", ownerToken))
                .andExpect(status().isNoContent());
        assertEquals("ARCHIVED", jdbcTemplate.queryForObject(
                "SELECT status FROM ai_conversation WHERE public_id = ?", String.class, id));
    }

    @Test
    void repairConversationHistoryAndWritePathsAreHiddenAfterAssigneeScopeIsRevoked() throws Exception {
        boolean hadAssistant = roleHasPermission("REPAIRER", "ai:assistant:use");
        boolean hadTriage = roleHasPermission("REPAIRER", "ai:repair:triage");
        Long repairOrderId = null;
        try {
            grantRolePermission("REPAIRER", "ai:assistant:use");
            grantRolePermission("REPAIRER", "ai:repair:triage");
            RoleUser owner = createRoleUser("REPAIRER", "Repair-owner-password-123");
            RoleUser replacement = createRoleUser("REPAIRER", "Repair-replacement-password-123");
            repairOrderId = insertAssignedRepairOrder(owner.id());

            String conversationId = createConversation(owner.token(), "REPAIR", "REPAIR", repairOrderId);
            String runId = sendMessage(owner.token(), conversationId,
                    "请说明这张维修单的当前处理情况", "repair-history-before-revoke")
                    .path("runId").asText();
            awaitTerminal(runId);
            MvcResult visible = mockMvc.perform(get("/api/ai/conversations/{id}", conversationId)
                            .header("Authorization", owner.token()))
                    .andExpect(status().isOk())
                    .andReturn();
            assertFalse(requireMessage(objectMapper.readTree(visible.getResponse().getContentAsString())
                    .path("data"), "ASSISTANT").path("text").asText().isBlank());

            jdbcTemplate.update("UPDATE repair_order SET assignee_user_id=?, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    replacement.id(), repairOrderId);

            mockMvc.perform(get("/api/ai/conversations/{id}", conversationId)
                            .header("Authorization", owner.token()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.data.errorCode").value("AI_RESOURCE_NOT_FOUND"));
            mockMvc.perform(get("/api/ai/conversations").header("Authorization", owner.token()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.total").value(0))
                    .andExpect(jsonPath("$.data.records.length()").value(0));
            assertRevokedConversationWritePathsAreHidden(
                    owner.token(), conversationId, runId, "REPAIR", "REPAIR", repairOrderId);
        } finally {
            if (repairOrderId != null) {
                jdbcTemplate.update("DELETE FROM repair_order WHERE id=?", repairOrderId);
            }
            restoreRolePermission("REPAIRER", "ai:assistant:use", hadAssistant);
            restoreRolePermission("REPAIRER", "ai:repair:triage", hadTriage);
        }
    }

    @Test
    void noticeConversationHistoryAndWritePathsAreHiddenAfterReadPermissionIsRevoked() throws Exception {
        boolean hadNoticeRead = roleHasPermission("ADMIN", "notice:read");
        Long noticeId = null;
        try {
            grantRolePermission("ADMIN", "notice:read");
            String token = login("admin", "test-password-123");
            noticeId = insertNotice();
            String conversationId = createConversation(token, "NOTICE", "NOTICE", noticeId);
            String runId = sendMessage(token, conversationId,
                    "请概括这条公告", "notice-history-before-revoke").path("runId").asText();
            awaitTerminal(runId);
            MvcResult visible = mockMvc.perform(get("/api/ai/conversations/{id}", conversationId)
                            .header("Authorization", token))
                    .andExpect(status().isOk())
                    .andReturn();
            assertFalse(requireMessage(objectMapper.readTree(visible.getResponse().getContentAsString())
                    .path("data"), "ASSISTANT").path("text").asText().isBlank());

            revokeRolePermission("ADMIN", "notice:read");

            mockMvc.perform(get("/api/ai/conversations").header("Authorization", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.total").value(0))
                    .andExpect(jsonPath("$.data.records.length()").value(0));
            mockMvc.perform(get("/api/ai/conversations/{id}", conversationId)
                            .header("Authorization", token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.data.errorCode").value("AI_RESOURCE_NOT_FOUND"));
            assertRevokedConversationWritePathsAreHidden(token, conversationId, runId,
                    "NOTICE", "NOTICE", noticeId);
        } finally {
            restoreRolePermission("ADMIN", "notice:read", hadNoticeRead);
            if (noticeId != null) {
                jdbcTemplate.update("DELETE FROM notice WHERE id=?", noticeId);
            }
        }
    }

    @Test
    void repairCommandRunAndSseReplayAreHiddenAfterAssigneeScopeIsRevoked() throws Exception {
        boolean hadTriage = roleHasPermission("REPAIRER", "ai:repair:triage");
        boolean hadWrite = roleHasPermission("REPAIRER", "repair:write");
        Long repairOrderId = null;
        try {
            grantRolePermission("REPAIRER", "ai:repair:triage");
            revokeRolePermission("REPAIRER", "repair:write");
            RoleUser owner = createRoleUser("REPAIRER", "Repair-command-owner-123");
            RoleUser replacement = createRoleUser("REPAIRER", "Repair-command-replacement-123");
            repairOrderId = insertAssignedRepairOrder(owner.id());
            jdbcTemplate.update("UPDATE ai_prompt_version SET status='ACTIVE', active_slot_key='repair.system', "
                    + "activated_at=CURRENT_TIMESTAMP WHERE prompt_key='repair.system' AND version='v1'");

            MvcResult accepted = mockMvc.perform(post("/api/ai/repairs/{id}/triage", repairOrderId)
                            .header("Authorization", owner.token())
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isAccepted())
                    .andReturn();
            String runId = objectMapper.readTree(accepted.getResponse().getContentAsString())
                    .path("data").path("runId").asText();
            awaitTerminal(runId);
            String visibleReplay = mockMvc.perform(get("/api/ai/runs/{id}/events", runId)
                            .header("Authorization", owner.token()))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertTrue(visibleReplay.contains("run.completed"));
            assertTrue(visibleReplay.contains(Long.toString(repairOrderId)));

            jdbcTemplate.update("UPDATE repair_order SET assignee_user_id=?, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    replacement.id(), repairOrderId);

            mockMvc.perform(get("/api/ai/runs/{id}", runId).header("Authorization", owner.token()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.data.errorCode").value("AI_RESOURCE_NOT_FOUND"));
            MvcResult deniedReplay = mockMvc.perform(get("/api/ai/runs/{id}/events", runId)
                            .header("Authorization", owner.token()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.data.errorCode").value("AI_RESOURCE_NOT_FOUND"))
                    .andReturn();
            assertFalse(deniedReplay.getResponse().getContentAsString().contains("run.completed"));
        } finally {
            if (repairOrderId != null) jdbcTemplate.update("DELETE FROM repair_order WHERE id=?", repairOrderId);
            restoreRolePermission("REPAIRER", "ai:repair:triage", hadTriage);
            restoreRolePermission("REPAIRER", "repair:write", hadWrite);
        }
    }

    @Test
    void noticeCommandRunAndSseReplayAreHiddenAfterBusinessReadPermissionIsRevoked() throws Exception {
        boolean hadNoticeRead = roleHasPermission("ADMIN", "notice:read");
        try {
            grantRolePermission("ADMIN", "notice:read");
            jdbcTemplate.update("UPDATE ai_prompt_version SET status='ACTIVE', active_slot_key='notice.system', "
                    + "activated_at=CURRENT_TIMESTAMP WHERE prompt_key='notice.system' AND version='v1'");
            String token = login("admin", "test-password-123");
            MvcResult accepted = mockMvc.perform(post("/api/ai/notices/drafts")
                            .header("Authorization", token)
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "points", "本周五开展宿舍安全检查",
                                    "type", "宿舍通知",
                                    "tone", "正式",
                                    "audience", "全体住宿学生"))))
                    .andExpect(status().isAccepted())
                    .andReturn();
            String runId = objectMapper.readTree(accepted.getResponse().getContentAsString())
                    .path("data").path("runId").asText();
            awaitTerminal(runId);
            String visibleReplay = mockMvc.perform(get("/api/ai/runs/{id}/events", runId)
                            .header("Authorization", token))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertTrue(visibleReplay.contains("run.completed"));
            assertTrue(visibleReplay.contains("本周五开展宿舍安全检查"));

            revokeRolePermission("ADMIN", "notice:read");

            mockMvc.perform(get("/api/ai/runs/{id}", runId).header("Authorization", token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.data.errorCode").value("AI_RESOURCE_NOT_FOUND"));
            MvcResult deniedReplay = mockMvc.perform(get("/api/ai/runs/{id}/events", runId)
                            .header("Authorization", token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.data.errorCode").value("AI_RESOURCE_NOT_FOUND"))
                    .andReturn();
            assertFalse(deniedReplay.getResponse().getContentAsString().contains("run.completed"));
        } finally {
            restoreRolePermission("ADMIN", "notice:read", hadNoticeRead);
        }
    }

    @Test
    void messageIsAcceptedOnlyAfterAuditAndBudgetAndIsConversationScopedIdempotent() throws Exception {
        String token = login("admin", "test-password-123");
        String conversationId = createConversation(token);
        String clientRequestId = UUID.randomUUID().toString();
        String request = objectMapper.writeValueAsString(Map.of(
                "text", "请说明维修申请的处理时限",
                "clientRequestId", clientRequestId));

        MvcResult first = mockMvc.perform(post("/api/ai/conversations/{id}/messages", conversationId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.data.runId").isNotEmpty())
                .andExpect(jsonPath("$.data.eventsUrl").isNotEmpty())
                .andReturn();
        JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString()).path("data");
        String runId = firstBody.path("runId").asText();
        assertEquals("/api/ai/runs/" + runId, first.getResponse().getHeader("Location"));
        assertEquals("/api/ai/runs/" + runId + "/events", firstBody.path("eventsUrl").asText());

        MvcResult replay = mockMvc.perform(post("/api/ai/conversations/{id}/messages", conversationId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isAccepted())
                .andReturn();
        assertEquals(first.getResponse().getHeader("Location"), replay.getResponse().getHeader("Location"));
        assertEquals(runId, objectMapper.readTree(replay.getResponse().getContentAsString())
                .path("data").path("runId").asText());

        mockMvc.perform(post("/api/ai/conversations/{id}/messages", conversationId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "text", "不同正文",
                                "clientRequestId", clientRequestId))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("AI_IDEMPOTENCY_PAYLOAD_MISMATCH"));

        awaitTerminal(runId);
        var lifecycle = jdbcTemplate.queryForList(
                "SELECT e.event_type FROM ai_run_event e JOIN ai_run r ON r.id=e.run_id "
                        + "WHERE r.public_id=? ORDER BY e.sequence_no", String.class, runId);
        assertTrue(lifecycle.indexOf("run.accepted") < lifecycle.indexOf("run.queued"));
        assertTrue(lifecycle.indexOf("run.queued") < lifecycle.indexOf("run.started"));
        assertTrue(lifecycle.indexOf("run.started") < lifecycle.indexOf("message.delta"));
        assertTrue(lifecycle.indexOf("message.delta") < lifecycle.indexOf("run.completed"));
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT first_token_at FROM ai_run WHERE public_id=?", Timestamp.class, runId));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_run WHERE public_id = ?", runId));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_budget_reservation WHERE billing_subject_public_id = ?", runId));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_usage_ledger WHERE billing_subject_public_id = ?", runId));
        assertEquals("RELEASED", jdbcTemplate.queryForObject(
                "SELECT state FROM ai_budget_reservation WHERE billing_subject_public_id=?", String.class, runId));
        assertTrue(count("SELECT COUNT(*) FROM ai_audit_event WHERE aggregate_public_id = ?", runId) >= 2);
    }

    @Test
    void completedStreamCanReplayMonotonicEventsAndFeedbackIsOwnerScoped() throws Exception {
        String token = login("admin", "test-password-123");
        String otherToken = login(createSecondAdmin(), "other-password-123");
        String conversationId = createConversation(token);
        JsonNode accepted = sendMessage(token, conversationId, "宿舍维修时限是什么？", UUID.randomUUID().toString());
        String runId = accepted.path("runId").asText();
        awaitTerminal(runId);

        MvcResult stream = mockMvc.perform(get("/api/ai/runs/{id}/events", runId)
                        .header("Authorization", token)
                        .header("Last-Event-ID", "1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andReturn();
        String body = stream.getResponse().getContentAsString();
        assertFalse(body.contains("id:1\n"));
        assertTrue(body.contains("event:run.started"));
        assertTrue(body.contains("event:message.delta"));
        assertTrue(body.contains("event:run.completed"));
        assertMonotonicSseIds(body);
        assertFalse(body.toLowerCase().contains("api_key"));
        assertFalse(body.toLowerCase().contains("authorization"));

        mockMvc.perform(get("/api/ai/runs/{id}/events", runId)
                        .header("Authorization", otherToken))
                .andExpect(status().isNotFound());

        String assistantMessageId = jdbcTemplate.queryForObject(
                "SELECT m.public_id FROM ai_message m JOIN ai_run r ON r.conversation_id = m.conversation_id "
                        + "WHERE r.public_id = ? AND m.role = 'ASSISTANT'", String.class, runId);
        assertNotNull(assistantMessageId);
        mockMvc.perform(post("/api/ai/messages/{id}/feedback", assistantMessageId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":1,\"tags\":[\"helpful\"],\"comment\":\"有帮助\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/ai/messages/{id}/feedback", assistantMessageId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":-1,\"tags\":[\"needs-review\"],\"comment\":\"引用不足\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/ai/messages/{id}/feedback", assistantMessageId)
                        .header("Authorization", otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":-1}"))
                .andExpect(status().isNotFound());
        assertEquals(1, count("SELECT COUNT(*) FROM ai_feedback WHERE message_id = "
                + "(SELECT id FROM ai_message WHERE public_id = ?)", assistantMessageId));
        assertEquals(-1, jdbcTemplate.queryForObject(
                "SELECT f.rating FROM ai_feedback f JOIN ai_message m ON m.id=f.message_id WHERE m.public_id=?",
                Integer.class, assistantMessageId));
        assertEquals(2, count("SELECT COUNT(*) FROM ai_outbox_event WHERE aggregate_public_id=? "
                + "AND event_type='FeedbackRecorded.v1'", assistantMessageId));
        assertEquals(2, count("SELECT COUNT(*) FROM ai_audit_event WHERE aggregate_public_id=? "
                + "AND event_type='FEEDBACK_RECORDED'", assistantMessageId));
        List<String> feedbackEvents = jdbcTemplate.queryForList(
                "SELECT payload_redacted FROM ai_outbox_event WHERE aggregate_public_id=? "
                        + "AND event_type='FeedbackRecorded.v1' ORDER BY id",
                String.class, assistantMessageId);
        assertTrue(feedbackEvents.get(0).contains("\"revision\":1"));
        assertTrue(feedbackEvents.get(1).contains("\"revision\":2"));
    }

    @Test
    void groundedGlobalAssistantPersistsCitationBeforeReplayableCompletionEvent() throws Exception {
        insertActiveKnowledge("宿舍维修处理时限为两个工作日，紧急用电故障应立即升级人工处理。");
        String token = login("admin", "test-password-123");
        String conversationId = createConversation(token);
        String clientRequestId = UUID.randomUUID().toString();
        String runId = sendMessage(token, conversationId, "宿舍维修处理时限是什么？", clientRequestId)
                .path("runId").asText();

        awaitTerminal(runId);

        assertEquals("knowledge-retrieval-policy-v1", jdbcTemplate.queryForObject(
                "SELECT retrieval_policy_version FROM ai_run WHERE public_id=?", String.class, runId));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_retrieval_trace t JOIN ai_run r ON r.id=t.run_id "
                + "WHERE r.public_id=? AND LENGTH(t.query_hash)=64 AND t.top_k=5 "
                + "AND t.acl_pre_filter_count=1 AND t.acl_post_filter_count=1 AND t.returned_count=1 "
                + "AND t.retrieval_mode='keyword-fallback-v1' AND t.index_code='knowledge-vector-index' "
                + "AND t.index_version='knowledge-index-v1' AND t.embedding_model_version='fake-embedding-v1' "
                + "AND t.retrieval_policy_version='knowledge-retrieval-policy-v1' "
                + "AND t.latency_ms>=0 AND t.state='DEGRADED'", runId));

        assertEquals(1, count("SELECT COUNT(*) FROM ai_citation c JOIN ai_run r ON r.id=c.run_id "
                + "WHERE r.public_id=?", runId));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_citation c JOIN ai_run r ON r.id=c.run_id "
                + "JOIN ai_message m ON m.id=c.message_id WHERE r.public_id=? AND m.role='ASSISTANT'", runId));
        Long citationSequence = jdbcTemplate.queryForObject(
                "SELECT e.sequence_no FROM ai_run_event e JOIN ai_run r ON r.id=e.run_id "
                        + "WHERE r.public_id=? AND e.event_type='citation.added'", Long.class, runId);
        Long completedSequence = jdbcTemplate.queryForObject(
                "SELECT e.sequence_no FROM ai_run_event e JOIN ai_run r ON r.id=e.run_id "
                        + "WHERE r.public_id=? AND e.event_type='run.completed'", Long.class, runId);
        assertNotNull(citationSequence);
        assertNotNull(completedSequence);
        assertTrue(citationSequence < completedSequence);

        String stream = mockMvc.perform(get("/api/ai/runs/{id}/events", runId)
                        .header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(stream.contains("event:citation.added"));
        assertTrue(stream.indexOf("event:citation.added") < stream.indexOf("event:run.completed"));
        String citationPayload = jdbcTemplate.queryForObject(
                "SELECT e.payload_redacted FROM ai_run_event e JOIN ai_run r ON r.id=e.run_id "
                        + "WHERE r.public_id=? AND e.event_type='citation.added'", String.class, runId);
        assertNotNull(citationPayload);
        assertFalse(citationPayload.contains("两个工作日"), "citation 事件不得回放引用正文");

        mockMvc.perform(get("/api/ai/audit/runs/{id}", runId).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.retrievals[0].retrievalPolicyVersion")
                        .value("knowledge-retrieval-policy-v1"))
                .andExpect(jsonPath("$.data.retrievals[0].aclPreFilterCount").value(1))
                .andExpect(jsonPath("$.data.retrievals[0].aclPostFilterCount").value(1));

        String replayedRunId = sendMessage(token, conversationId,
                "宿舍维修处理时限是什么？", clientRequestId).path("runId").asText();
        assertEquals(runId, replayedRunId);
        assertEquals(1, count("SELECT COUNT(*) FROM ai_retrieval_trace t JOIN ai_run r ON r.id=t.run_id "
                + "WHERE r.public_id=?", runId), "幂等重放不得再次检索或重复写 trace");
    }

    @Test
    void knowledgeCommandPersistsCitationBindingAndRechecksSourceOnReplay() throws Exception {
        boolean hadAssistant = roleHasPermission("ADMIN", "ai:assistant:use");
        boolean hadKnowledge = roleHasPermission("ADMIN", "ai:knowledge:read");
        try {
            grantRolePermission("ADMIN", "ai:assistant:use");
            grantRolePermission("ADMIN", "ai:knowledge:read");
            jdbcTemplate.update("UPDATE ai_prompt_version SET status='ACTIVE', active_slot_key='knowledge.system', "
                    + "activated_at=CURRENT_TIMESTAMP WHERE prompt_key='knowledge.system' AND version='v1'");
            insertActiveKnowledge("知识命令只允许回放当前仍有授权的来源正文。");
            String token = login("admin", "test-password-123");
            MvcResult accepted = mockMvc.perform(post("/api/ai/knowledge/queries")
                            .header("Authorization", token)
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"知识命令的授权规则是什么？\",\"topK\":5}"))
                    .andExpect(status().isAccepted())
                    .andReturn();
            String runId = objectMapper.readTree(accepted.getResponse().getContentAsString())
                    .path("data").path("runId").asText();
            awaitTerminal(runId);

            assertEquals(1, count("SELECT COUNT(*) FROM ai_citation c JOIN ai_run r ON r.id=c.run_id "
                    + "WHERE r.public_id=? AND c.citation_type='KNOWLEDGE'", runId));
            assertEquals(1, count("SELECT COUNT(*) FROM ai_citation c JOIN ai_run r ON r.id=c.run_id "
                    + "JOIN ai_message m ON m.id=c.message_id WHERE r.public_id=? AND m.role='ASSISTANT'", runId));
            assertEquals(1, count("SELECT COUNT(DISTINCT v.public_id) FROM ai_citation c JOIN ai_run r ON r.id=c.run_id "
                    + "JOIN ai_document_version v ON v.id=c.document_version_id WHERE r.public_id=?", runId));

            String events = mockMvc.perform(get("/api/ai/runs/{id}/events", runId)
                            .header("Authorization", token))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertTrue(events.contains("event:citation.added"));
            assertTrue(events.contains("event:run.completed"));
            assertTrue(events.indexOf("event:citation.added") < events.indexOf("event:run.completed"));
            String citationEventPayload = jdbcTemplate.queryForObject(
                    "SELECT e.payload_redacted FROM ai_run_event e JOIN ai_run r ON r.id=e.run_id "
                            + "WHERE r.public_id=? AND e.event_type='citation.added'", String.class, runId);
            assertNotNull(citationEventPayload);
            assertFalse(citationEventPayload.contains("知识命令只允许回放"),
                    "citation 事件不得回放引用正文");

            String sourceId = jdbcTemplate.queryForObject(
                    "SELECT s.public_id FROM ai_knowledge_source s JOIN ai_document d ON d.source_id=s.id "
                            + "JOIN ai_document_version v ON v.document_id=d.id JOIN ai_citation c "
                            + "ON c.document_version_id=v.id JOIN ai_run r ON r.id=c.run_id WHERE r.public_id=?",
                    String.class, runId);
            Long sourceDatabaseId = jdbcTemplate.queryForObject(
                    "SELECT id FROM ai_knowledge_source WHERE public_id=?", Long.class, sourceId);
            String versionId = jdbcTemplate.queryForObject(
                    "SELECT v.public_id FROM ai_document_version v JOIN ai_citation c ON c.document_version_id=v.id "
                            + "JOIN ai_run r ON r.id=c.run_id WHERE r.public_id=?",
                    String.class, runId);

            jdbcTemplate.update("DELETE FROM ai_knowledge_source_permission WHERE source_id=?", sourceDatabaseId);
            mockMvc.perform(get("/api/ai/runs/{id}/events", runId).header("Authorization", token))
                    .andExpect(status().isNotFound());
            jdbcTemplate.update("INSERT INTO ai_knowledge_source_permission "
                            + "(source_id,permission_code,created_at,updated_at) "
                            + "VALUES (?,'ai:knowledge:read',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    sourceDatabaseId);
            mockMvc.perform(get("/api/ai/runs/{id}", runId).header("Authorization", token))
                    .andExpect(status().isOk());

            jdbcTemplate.update("UPDATE ai_document_version SET status='RETIRED', "
                    + "retired_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE public_id=?", versionId);
            mockMvc.perform(get("/api/ai/runs/{id}/events", runId).header("Authorization", token))
                    .andExpect(status().isNotFound());
            jdbcTemplate.update("UPDATE ai_document_version SET status='ACTIVE', retired_at=NULL, "
                    + "updated_at=CURRENT_TIMESTAMP WHERE public_id=?", versionId);
            mockMvc.perform(get("/api/ai/runs/{id}", runId).header("Authorization", token))
                    .andExpect(status().isOk());

            controls.disable(AiRuntimeControlService.Scope.SOURCE, sourceId,
                    "知识 command 回放撤权回归");

            mockMvc.perform(get("/api/ai/runs/{id}", runId).header("Authorization", token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.data.errorCode").value("AI_RESOURCE_NOT_FOUND"));
            MvcResult revoked = mockMvc.perform(get("/api/ai/runs/{id}/events", runId)
                            .header("Authorization", token))
                    .andExpect(status().isNotFound())
                    .andReturn();
            assertFalse(revoked.getResponse().getContentAsString(StandardCharsets.UTF_8)
                    .contains("知识命令只允许回放"));
        } finally {
            restoreRolePermission("ADMIN", "ai:assistant:use", hadAssistant);
            restoreRolePermission("ADMIN", "ai:knowledge:read", hadKnowledge);
        }
    }

    @Test
    void conversationDetailRestoresPersistedAssistantEvidenceWithoutInventingConfidenceOrAsOf() throws Exception {
        insertActiveKnowledge("宿舍维修处理时限为两个工作日，紧急用电故障应立即升级人工处理。");
        String token = login("admin", "test-password-123");
        String conversationId = createConversation(token);
        String runId = sendMessage(token, conversationId,
                "宿舍维修处理时限是什么？", UUID.randomUUID().toString()).path("runId").asText();
        awaitTerminal(runId);

        String citationId = jdbcTemplate.queryForObject(
                "SELECT c.public_id FROM ai_citation c JOIN ai_run r ON r.id=c.run_id WHERE r.public_id=?",
                String.class, runId);
        int citationAuditCountBeforeDetail = count(
                "SELECT COUNT(*) FROM ai_audit_event WHERE aggregate_public_id=? "
                        + "AND event_type IN ('CITATION_READ_GRANTED','CITATION_READ_DENIED')",
                citationId);
        JsonNode detail = objectMapper.readTree(mockMvc.perform(
                        get("/api/ai/conversations/{id}", conversationId).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andReturn().getResponse().getContentAsString()).path("data");
        JsonNode assistant = requireMessage(detail, "ASSISTANT");

        assertEquals(runId, assistant.path("runId").asText());
        assertEquals("SUCCEEDED", assistant.path("runState").asText());
        assertTrue(!assistant.has("asOf") || assistant.path("asOf").isNull(),
                "run.finished_at 不是业务数据 asOf，不得伪造");
        assertTrue(assistant.path("grounded").asBoolean());
        assertFalse(assistant.has("confidence"), "持久化会话不得伪造未记录的 confidence");
        assertEquals(1, assistant.path("citations").size());
        JsonNode citation = assistant.path("citations").get(0);
        assertEquals(citationId, citation.path("id").asText());
        assertEquals("维修制度", citation.path("label").asText());
        assertEquals("第 1 段", citation.path("locator").asText());
        assertEquals("v1", citation.path("version").asText());
        assertEquals("available", citation.path("access").asText());
        assertFalse(citation.has("quote"), "历史会话引用摘要不得返回 quote");

        int citationAuditCount = count("SELECT COUNT(*) FROM ai_audit_event WHERE aggregate_public_id=? "
                + "AND event_type IN ('CITATION_READ_GRANTED','CITATION_READ_DENIED')", citationId);
        assertEquals(citationAuditCountBeforeDetail, citationAuditCount,
                "会话详情的被动引用摘要不得按 citation 数放大安全审计写入");
        String otherToken = login(createSecondAdmin(), "other-password-123");
        mockMvc.perform(get("/api/ai/conversations/{id}", conversationId)
                        .header("Authorization", otherToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.errorCode").value("AI_RESOURCE_NOT_FOUND"));
        assertEquals(citationAuditCount, count("SELECT COUNT(*) FROM ai_audit_event WHERE aggregate_public_id=? "
                + "AND event_type IN ('CITATION_READ_GRANTED','CITATION_READ_DENIED')", citationId),
                "owner 404 必须发生在 citation 重鉴权之前");
    }

    @Test
    void conversationDetailReauthorizesCitationsAndReturnsNonLeakingDeniedPlaceholders() throws Exception {
        insertActiveKnowledge("仅在授权时可见的维修制度正文。");
        String token = login("admin", "test-password-123");
        String conversationId = createConversation(token);
        String runId = sendMessage(token, conversationId,
                "维修制度是什么？", UUID.randomUUID().toString()).path("runId").asText();
        awaitTerminal(runId);
        String citationId = jdbcTemplate.queryForObject(
                "SELECT c.public_id FROM ai_citation c JOIN ai_run r ON r.id=c.run_id WHERE r.public_id=?",
                String.class, runId);

        String sourceId = jdbcTemplate.queryForObject(
                "SELECT s.public_id FROM ai_knowledge_source s JOIN ai_document d ON d.source_id=s.id "
                        + "JOIN ai_document_version v ON v.document_id=d.id JOIN ai_citation c "
                        + "ON c.document_version_id=v.id WHERE c.public_id=?",
                String.class, citationId);
        controls.disable(AiRuntimeControlService.Scope.SOURCE, sourceId, "历史引用权限回归关闭来源");
        JsonNode detail = objectMapper.readTree(mockMvc.perform(
                        get("/api/ai/conversations/{id}", conversationId).header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
        JsonNode assistant = requireMessage(detail, "ASSISTANT");
        JsonNode citation = assistant.path("citations").get(0);

        assertFalse(assistant.path("grounded").asBoolean(), "grounded 必须按当前可见引用重算");
        assertEquals("历史回答的授权来源已不可访问，请重新提问或联系管理员。",
                assistant.path("text").asText());
        assertFalse(assistant.path("text").asText().contains("仅在授权时可见"),
                "撤权后不得继续回放由受控知识生成的历史正文");
        assertEquals(citationId, citation.path("id").asText());
        assertEquals("denied", citation.path("access").asText());
        assertEquals("受限来源", citation.path("label").asText());
        assertEquals("当前不可访问", citation.path("locator").asText());
        assertEquals("", citation.path("version").asText());
        assertFalse(citation.toString().contains("仅在授权时可见"));
        assertFalse(citation.has("quote"), "历史会话引用摘要不得返回 quote");
    }

    @Test
    void explicitRetryCreatesOneTerminalChildBoundToTheOriginalInputAndCurrentOwner() throws Exception {
        String ownerToken = login("admin", "test-password-123");
        String otherToken = login(createSecondAdmin(), "other-password-123");
        String conversationId = createConversation(ownerToken);
        String parentRunId = sendMessage(ownerToken, conversationId,
                "请说明维修申请的处理时限", UUID.randomUUID().toString()).path("runId").asText();
        awaitTerminal(parentRunId);
        String retryKey = UUID.randomUUID().toString();

        MvcResult retried = mockMvc.perform(post("/api/ai/runs/{id}/retry", parentRunId)
                        .header("Authorization", ownerToken)
                        .header("Idempotency-Key", retryKey))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(jsonPath("$.data.runId").isNotEmpty())
                .andReturn();
        String childRunId = objectMapper.readTree(retried.getResponse().getContentAsString())
                .path("data").path("runId").asText();
        assertNotEquals(parentRunId, childRunId);
        assertEquals("/api/ai/runs/" + childRunId, retried.getResponse().getHeader("Location"));
        awaitTerminal(childRunId);

        assertEquals(parentRunId, jdbcTemplate.queryForObject(
                "SELECT p.public_id FROM ai_run c JOIN ai_run p ON p.id=c.parent_run_id WHERE c.public_id=?",
                String.class, childRunId));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_run child JOIN ai_run parent ON parent.id=child.parent_run_id "
                + "JOIN ai_message child_input ON child_input.id=child.request_message_id "
                + "JOIN ai_message parent_input ON parent_input.id=parent.request_message_id "
                + "WHERE child.public_id=? AND child_input.request_hash=parent_input.request_hash "
                + "AND child_input.content_redacted=parent_input.content_redacted", childRunId));

        MvcResult replay = mockMvc.perform(post("/api/ai/runs/{id}/retry", parentRunId)
                        .header("Authorization", ownerToken)
                        .header("Idempotency-Key", retryKey))
                .andExpect(status().isAccepted()).andReturn();
        assertEquals(childRunId, objectMapper.readTree(replay.getResponse().getContentAsString())
                .path("data").path("runId").asText());
        assertEquals(1, count("SELECT COUNT(*) FROM ai_run child JOIN ai_run parent ON parent.id=child.parent_run_id "
                + "WHERE parent.public_id=?", parentRunId));

        mockMvc.perform(post("/api/ai/runs/{id}/retry", parentRunId)
                        .header("Authorization", otherToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void auditMetadataAggregatesLinkedArtifactsWithoutReturningTheirBodies() throws Exception {
        insertActiveKnowledge("宿舍维修处理时限为两个工作日，紧急用电故障应立即升级人工处理。");
        String token = login("admin", "test-password-123");
        String conversationId = createConversation(token);
        String runId = sendMessage(token, conversationId,
                "宿舍维修处理时限是什么？", UUID.randomUUID().toString()).path("runId").asText();
        awaitTerminal(runId);
        insertLinkedAuditArtifacts(runId);

        String body = mockMvc.perform(get("/api/ai/audit/runs/{id}", runId)
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tools[0].toolName").value("knowledge.search.v1"))
                .andExpect(jsonPath("$.data.tools[0].authorizationDecision").value("ALLOWED"))
                .andExpect(jsonPath("$.data.tools[0].state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.tools[*].toolName", hasItem("notice.preview.v1")))
                .andExpect(jsonPath("$.data.retrievals[0].queryHash").isNotEmpty())
                .andExpect(jsonPath("$.data.citations[0].contentHash").isNotEmpty())
                .andExpect(jsonPath("$.data.proposals[0].actionType").value("NOTICE_CREATE_DRAFT"))
                .andExpect(jsonPath("$.data.approvals[0].decision").value("APPROVE"))
                .andExpect(jsonPath("$.data.executions[0].state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.usage[0].requestKind").value("CHAT"))
                .andExpect(jsonPath("$.data.hashChain[0].eventHash").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("secret-tool-request"));
        assertFalse(body.contains("secret-tool-response"));
        assertFalse(body.contains("secret-proposal-payload"));
        assertFalse(body.contains("secret-proposal-preview"));
        assertFalse(body.contains("两个工作日"), "元数据端点不得返回 citation/message 正文");
    }

    @Test
    void auditListSupportsBoundedTimeCapabilityStateAndProviderFilters() throws Exception {
        String token = login("admin", "test-password-123");
        String conversationId = createConversation(token);
        String runId = sendMessage(token, conversationId, "请给出安全帮助。", UUID.randomUUID().toString())
                .path("runId").asText();
        awaitTerminal(runId);
        insertLinkedAuditArtifacts(runId);
        insertAuditListCitation(runId);
        Instant createdAt = jdbcTemplate.queryForObject(
                "SELECT created_at FROM ai_run WHERE public_id=?", Timestamp.class, runId).toInstant();
        int expectedCitationCount = count(
                "SELECT COUNT(*) FROM ai_citation c JOIN ai_run r ON r.id=c.run_id WHERE r.public_id=?", runId);
        String expectedChainHash = jdbcTemplate.queryForObject(
                "SELECT last_event_hash FROM ai_audit_chain_head WHERE chain_scope='RUN' "
                        + "AND aggregate_type='RUN' AND aggregate_public_id=?", String.class, runId);

        mockMvc.perform(get("/api/ai/audit/runs")
                        .header("Authorization", token)
                        .param("from", createdAt.minusSeconds(1).toString())
                        .param("to", createdAt.plusSeconds(30).toString())
                        .param("capability", "assistant")
                        .param("state", "succeeded")
                        .param("provider", "fake"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(runId))
                .andExpect(jsonPath("$.data.records[0].citationCount").value(expectedCitationCount))
                .andExpect(jsonPath("$.data.records[0].chainHash").value(expectedChainHash));
        mockMvc.perform(get("/api/ai/audit/runs")
                        .header("Authorization", token).param("state", "failed"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/api/ai/audit/runs")
                        .header("Authorization", token).param("capability", "unknown"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/ai/audit/runs")
                        .header("Authorization", token)
                        .param("from", createdAt.plusSeconds(1).toString())
                        .param("to", createdAt.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void readinessIsPermissionProtectedAndReturnsOnlyCoarseAliases() throws Exception {
        String adminToken = login("admin", "test-password-123");
        String viewerUsername = createViewer();
        String viewerToken = login(viewerUsername, "viewer-password-123");

        mockMvc.perform(get("/api/ai/operations/readiness").header("Authorization", viewerToken))
                .andExpect(status().isForbidden());
        String body = mockMvc.perform(get("/api/ai/operations/readiness").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.masterEnabled").value(true))
                .andExpect(jsonPath("$.data.providerAlias").value("fake"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.toLowerCase().contains("endpoint"));
        assertFalse(body.toLowerCase().contains("password"));
        assertFalse(body.toLowerCase().contains("hmac-key"));
        assertFalse(body.toLowerCase().contains("0123456789abcdef"));
    }

    private String createConversation(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/ai/conversations")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"surface\":\"GLOBAL\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asText();
    }

    private String createConversation(
            String token,
            String surface,
            String contextType,
            long contextId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/ai/conversations")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "surface", surface,
                                "contextType", contextType,
                                "contextId", contextId))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asText();
    }

    private JsonNode sendMessage(String token, String conversationId, String text, String clientRequestId)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/ai/conversations/{id}/messages", conversationId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "text", text,
                                "clientRequestId", clientRequestId))))
                .andExpect(status().isAccepted())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private void assertRevokedConversationWritePathsAreHidden(
            String token,
            String conversationId,
            String parentRunId,
            String surface,
            String contextType,
            Long contextId) throws Exception {
        int runCount = count("SELECT COUNT(*) FROM ai_run r JOIN ai_conversation c ON c.id=r.conversation_id "
                + "WHERE c.public_id=?", conversationId);
        int conversationCount = count("SELECT COUNT(*) FROM ai_conversation WHERE surface=? AND context_type=? "
                + "AND context_resource_id=?", surface, contextType, contextId);
        mockMvc.perform(post("/api/ai/conversations")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "surface", surface,
                                "contextType", contextType,
                                "contextId", contextId))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.errorCode").value("AI_RESOURCE_NOT_FOUND"));
        mockMvc.perform(post("/api/ai/conversations/{id}/messages", conversationId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "text", "撤权后不得创建新运行",
                                "clientRequestId", UUID.randomUUID().toString()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.errorCode").value("AI_RESOURCE_NOT_FOUND"));
        mockMvc.perform(post("/api/ai/runs/{id}/retry", parentRunId)
                        .header("Authorization", token)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.errorCode").value("AI_RESOURCE_NOT_FOUND"));
        mockMvc.perform(get("/api/ai/runs/{id}", parentRunId)
                        .header("Authorization", token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.errorCode").value("AI_RESOURCE_NOT_FOUND"));
        mockMvc.perform(get("/api/ai/runs/{id}/events", parentRunId)
                        .header("Authorization", token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.errorCode").value("AI_RESOURCE_NOT_FOUND"));
        mockMvc.perform(post("/api/ai/runs/{id}/cancel", parentRunId)
                        .header("Authorization", token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.errorCode").value("AI_RESOURCE_NOT_FOUND"));
        assertEquals(runCount, count(
                "SELECT COUNT(*) FROM ai_run r JOIN ai_conversation c ON c.id=r.conversation_id "
                        + "WHERE c.public_id=?", conversationId));
        assertEquals(conversationCount, count("SELECT COUNT(*) FROM ai_conversation WHERE surface=? AND context_type=? "
                + "AND context_resource_id=?", surface, contextType, contextId));
    }

    private void awaitTerminal(String runId) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            String state = jdbcTemplate.queryForObject(
                    "SELECT state FROM ai_run WHERE public_id = ?", String.class, runId);
            if (java.util.Set.of("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT").contains(state)) {
                assertEquals("SUCCEEDED", state);
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("run 未在期限内进入终态: " + runId);
    }

    private JsonNode requireMessage(JsonNode conversation, String role) {
        for (JsonNode message : conversation.path("messages")) {
            if (role.equals(message.path("role").asText())) return message;
        }
        throw new AssertionError("会话中缺少 " + role + " 消息");
    }

    private void insertActiveKnowledge(String content) {
        Long adminId = jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username='admin'", Long.class);
        String sourcePublicId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO ai_knowledge_source "
                        + "(public_id,name,source_type,owner_user_id,classification,permission_match_mode,"
                        + "object_store_code,acl_version,status,created_at,updated_at) "
                        + "VALUES (?,?,'TEXT',?,'L1','ANY','fake',0,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                sourcePublicId, "维修制度", adminId);
        Long sourceId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_knowledge_source WHERE public_id=?", Long.class, sourcePublicId);
        jdbcTemplate.update("INSERT INTO ai_knowledge_source_permission "
                        + "(source_id,permission_code,created_at,updated_at) "
                        + "VALUES (?,'ai:knowledge:read',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", sourceId);
        String documentPublicId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO ai_document "
                        + "(public_id,source_id,external_key_hmac,external_key_key_version,title,status,created_at,updated_at) "
                        + "VALUES (?,?,?,1,'维修制度','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                documentPublicId, sourceId, "b".repeat(64));
        Long documentId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_document WHERE public_id=?", Long.class, documentPublicId);
        String versionPublicId = UUID.randomUUID().toString();
        String contentHash = com.example.dormitory.ai.approval.CanonicalJsonHasher.sha256(content);
        jdbcTemplate.update("INSERT INTO ai_document_version "
                        + "(public_id,document_id,version,content_hash,visibility,object_key,object_version_id,object_etag,"
                        + "mime_type,size_bytes,parser_version,chunk_policy_version,status,activated_at,created_at,updated_at) "
                        + "VALUES (?,?,'v1',?,'EXPLICIT_ACL',?,'v1','etag','text/plain',?,'plain-v1','chunk-v1',"
                        + "'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                versionPublicId, documentId, contentHash, "knowledge/" + contentHash, content.length());
        Long versionId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_document_version WHERE public_id=?", Long.class, versionPublicId);
        jdbcTemplate.update("UPDATE ai_document SET current_version_id=? WHERE id=?", versionId, documentId);
        jdbcTemplate.update("INSERT INTO ai_document_chunk "
                        + "(public_id,document_version_id,chunk_no,content_redacted,content_hash,locator_text,metadata_text,"
                        + "status,created_at,updated_at) VALUES (?,?,0,?,?,?,'{}','READY',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(), versionId, content, contentHash, "第 1 段");
    }

    private void insertLinkedAuditArtifacts(String runId) {
        Long runDatabaseId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_run WHERE public_id=?", Long.class, runId);
        Long actorUserId = jdbcTemplate.queryForObject(
                "SELECT actor_user_id FROM ai_run WHERE public_id=?", Long.class, runId);
        Long toolSequence = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(sequence_no),0)+1 FROM ai_tool_call WHERE run_id=?",
                Long.class, runDatabaseId);
        String toolId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO ai_tool_call "
                        + "(public_id,run_id,sequence_no,tool_name,tool_version,request_redacted,response_redacted,"
                        + "required_permissions_text,authorization_decision,state,version,started_at,finished_at,"
                        + "created_operator_user_id,updated_operator_user_id,created_at,updated_at) "
                        + "VALUES (?,?,?,'notice.preview.v1','v1',?,?,?,'ALLOWED','SUCCEEDED',0,CURRENT_TIMESTAMP,"
                        + "CURRENT_TIMESTAMP,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                toolId, runDatabaseId, toolSequence, "secret-tool-request", "secret-tool-response",
                "[\"notice:read\"]",
                actorUserId, actorUserId);
        Long toolDatabaseId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_tool_call WHERE public_id=?", Long.class, toolId);

        String proposalId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO ai_action_proposal "
                        + "(public_id,run_id,origin_tool_call_id,action_type,target_type,payload_text,preview_text,"
                        + "payload_hash,business_snapshot_hash,required_business_permission,approval_policy_version,"
                        + "required_approval_count,approved_count,risk_level,state,proposer_user_id,expires_at,version,"
                        + "created_operator_user_id,updated_operator_user_id,created_at,updated_at) "
                        + "VALUES (?,?,?,'NOTICE_CREATE_DRAFT','NOTICE',?,?,?,?,'notice:write','approval-v1',1,1,"
                        + "'LOW','SUCCEEDED',?,?,0,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                proposalId, runDatabaseId, toolDatabaseId, "secret-proposal-payload", "secret-proposal-preview",
                "c".repeat(64), "d".repeat(64), actorUserId, Timestamp.from(Instant.now().plusSeconds(600)),
                actorUserId, actorUserId);
        Long proposalDatabaseId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_action_proposal WHERE public_id=?", Long.class, proposalId);

        String retrySafeKey = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO ai_idempotency_record "
                        + "(actor_user_id,route_code,aggregate_public_id,idempotency_key,request_hash,state,response_status,"
                        + "response_resource_public_id,expires_at,version,created_operator_user_id,updated_operator_user_id,"
                        + "created_at,updated_at) VALUES (?,'AI_PROPOSAL_APPROVE',?,?,?,'COMPLETED',204,?,?,0,?,?,"
                        + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                actorUserId, proposalId, retrySafeKey, "e".repeat(64), proposalId,
                Timestamp.from(Instant.now().plusSeconds(600)), actorUserId, actorUserId);
        Long idempotencyId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_idempotency_record WHERE idempotency_key=?", Long.class, retrySafeKey);
        jdbcTemplate.update("INSERT INTO ai_action_approval "
                        + "(proposal_id,proposal_version,decision,reviewer_user_id,idempotency_record_id,payload_hash,"
                        + "business_snapshot_hash,comment_redacted,created_at) VALUES (?,0,'APPROVE',?,?,?,?,?,CURRENT_TIMESTAMP)",
                proposalDatabaseId, actorUserId, idempotencyId, "c".repeat(64), "d".repeat(64),
                "secret-approval-comment");

        jdbcTemplate.update("INSERT INTO ai_action_execution "
                        + "(public_id,proposal_id,state,version,handler_name,execution_key,lease_token_hash,"
                        + "executed_by_user_id,response_redacted,started_at,finished_at,created_operator_user_id,"
                        + "updated_operator_user_id,created_at,updated_at) VALUES (?,?, 'SUCCEEDED',0,"
                        + "'notice-create-draft-v1',?,?,?,'secret-execution-response',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,"
                        + "?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(), proposalDatabaseId, "f".repeat(64), "1".repeat(64), actorUserId,
                actorUserId, actorUserId);
        jdbcTemplate.update("INSERT INTO ai_usage_ledger "
                        + "(billing_subject_kind,billing_subject_public_id,request_sequence_no,attempt_no,request_kind,"
                        + "actor_kind,actor_user_id,capability,provider_code,model_name,input_tokens,output_tokens,"
                        + "cost_amount,currency,usage_source,occurred_at,created_at) "
                        + "VALUES ('RUN',?,7,1,'CHAT','USER',?,'ASSISTANT','fake','fake-model',11,13,0,'CNY',"
                + "'ESTIMATED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", runId, actorUserId);
    }

    private void insertAuditListCitation(String runId) {
        Long runDatabaseId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_run WHERE public_id=?", Long.class, runId);
        Long messageDatabaseId = jdbcTemplate.queryForObject(
                "SELECT m.id FROM ai_message m JOIN ai_run r ON r.conversation_id=m.conversation_id "
                        + "WHERE r.public_id=? AND m.role='ASSISTANT' ORDER BY m.sequence_no DESC LIMIT 1",
                Long.class, runId);
        jdbcTemplate.update("INSERT INTO ai_citation "
                        + "(public_id,run_id,message_id,citation_type,metric_id,rank_no,content_hash,created_at) "
                        + "VALUES (?,?,?,'METRIC','audit-list-count',1,?,CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(), runDatabaseId, messageDatabaseId, "9".repeat(64));
    }

    private void assertMonotonicSseIds(String body) {
        long previous = 1;
        for (String line : body.replace("\r\n", "\n").split("\n")) {
            if (!line.startsWith("id:")) continue;
            long current = Long.parseLong(line.substring(3).trim());
            assertTrue(current > previous, () -> "SSE id 非单调: " + body);
            previous = current;
        }
        assertNotEquals(1, previous);
    }

    private int count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    private void assertVaryTokens(MvcResult result, String... expected) {
        java.util.Set<String> tokens = result.getResponse().getHeaders("Vary").stream()
                .flatMap(value -> java.util.Arrays.stream(value.split(",")))
                .map(String::trim)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(tokens.containsAll(java.util.Set.of(expected)), () -> "Vary tokens: " + tokens);
    }

    private String createSecondAdmin() {
        String username = "ai-admin-" + UUID.randomUUID();
        long userId = insertUser(username, "other-password-123", "ADMIN");
        Long roleId = jdbcTemplate.queryForObject("SELECT id FROM sys_role WHERE code = 'ADMIN'", Long.class);
        jdbcTemplate.update("INSERT INTO sys_user_role (user_id, role_id, created_at, updated_at) "
                + "VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", userId, roleId);
        return username;
    }

    private void grantAdminPermission(String code) {
        grantRolePermission("ADMIN", code);
    }

    private RoleUser createRoleUser(String roleCode, String password) throws Exception {
        String username = "ai-" + roleCode.toLowerCase() + "-" + UUID.randomUUID();
        long userId = insertUser(username, password, roleCode);
        Long roleId = jdbcTemplate.queryForObject("SELECT id FROM sys_role WHERE code = ?", Long.class, roleCode);
        jdbcTemplate.update("INSERT INTO sys_user_role (user_id, role_id, created_at, updated_at) "
                + "VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", userId, roleId);
        return new RoleUser(userId, login(username, password));
    }

    private long insertAssignedRepairOrder(long assigneeUserId) {
        String code = "AI-REPAIR-" + UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO repair_order "
                        + "(code,reporter,location,type,date,status,description,assignee_user_id,created_at,updated_at) "
                        + "VALUES (?, '测试报修人', 'A栋-101', '水电维修', '2026-07-22', '待处理', "
                        + "'宿舍照明故障', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                code, assigneeUserId);
        return jdbcTemplate.queryForObject("SELECT id FROM repair_order WHERE code=?", Long.class, code);
    }

    private long insertNotice() {
        String title = "AI 公告上下文 " + UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO notice "
                        + "(title,type,date,publisher,status,content,published_at,deleted,created_at,updated_at) "
                        + "VALUES (?, '宿舍通知', '2026-07-22', '测试管理员', '已发布', "
                        + "'本周五开展宿舍安全检查。', CURRENT_TIMESTAMP, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                title);
        return jdbcTemplate.queryForObject("SELECT id FROM notice WHERE title=?", Long.class, title);
    }

    private boolean roleHasPermission(String roleCode, String permissionCode) {
        return count("SELECT COUNT(*) FROM sys_role_permission rp "
                + "JOIN sys_role r ON r.id=rp.role_id JOIN sys_permission p ON p.id=rp.permission_id "
                + "WHERE r.code=? AND p.code=?", roleCode, permissionCode) > 0;
    }

    private void grantRolePermission(String roleCode, String permissionCode) {
        Long roleId = jdbcTemplate.queryForObject("SELECT id FROM sys_role WHERE code = ?", Long.class, roleCode);
        Long permissionId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_permission WHERE code = ?", Long.class, permissionCode);
        jdbcTemplate.update("INSERT INTO sys_role_permission "
                        + "(role_id, permission_id, created_at, updated_at) "
                        + "SELECT ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP WHERE NOT EXISTS "
                        + "(SELECT 1 FROM sys_role_permission WHERE role_id = ? AND permission_id = ?)",
                roleId, permissionId, roleId, permissionId);
    }

    private void revokeRolePermission(String roleCode, String permissionCode) {
        jdbcTemplate.update("DELETE FROM sys_role_permission WHERE role_id = "
                        + "(SELECT id FROM sys_role WHERE code = ?) AND permission_id = "
                        + "(SELECT id FROM sys_permission WHERE code = ?)",
                roleCode, permissionCode);
    }

    private void restoreRolePermission(String roleCode, String permissionCode, boolean existed) {
        if (existed) grantRolePermission(roleCode, permissionCode);
        else revokeRolePermission(roleCode, permissionCode);
    }

    private String createViewer() {
        String username = "ai-viewer-" + UUID.randomUUID();
        long userId = insertUser(username, "viewer-password-123", "VIEWER");
        Long roleId = jdbcTemplate.queryForObject("SELECT id FROM sys_role WHERE code = 'VIEWER'", Long.class);
        jdbcTemplate.update("INSERT INTO sys_user_role (user_id, role_id, created_at, updated_at) "
                + "VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", userId, roleId);
        return username;
    }

    private long insertUser(String username, String password, String roleCode) {
        jdbcTemplate.update("INSERT INTO sys_user "
                        + "(username, password_hash, display_name, role_code, enabled, deleted, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, TRUE, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                username, passwordEncoder.encode(password), username, roleCode);
        return jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        var cookie = result.getResponse().getCookie("Authorization");
        assertNotNull(cookie);
        return cookie.getValue();
    }

    private record RoleUser(long id, String token) {
    }
}
