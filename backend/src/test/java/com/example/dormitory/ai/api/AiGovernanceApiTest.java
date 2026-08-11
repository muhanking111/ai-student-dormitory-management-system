package com.example.dormitory.ai.api;

import com.example.dormitory.ai.approval.ActionProposalService;
import com.example.dormitory.ai.approval.CanonicalJsonHasher;
import com.example.dormitory.ai.erasure.AiErasureService;
import com.example.dormitory.ai.infrastructure.runtime.AiRuntimeAuditWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ai-governance-api;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "dormitory.ai.enabled=true",
        "dormitory.ai.capabilities.assistant=true",
        "dormitory.ai.capabilities.risk=true",
        "dormitory.ai.provider.active=fake",
        "dormitory.ai.audit.hmac-key=0123456789abcdef0123456789abcdef",
        "dormitory.ai.tokenization.hmac-key=fedcba9876543210fedcba9876543210",
        "dormitory.ai.step-up.hmac-key=abcdef0123456789abcdef0123456789",
        "dormitory.ai.erasure.initial-delay=PT1H",
        "dormitory.ai.erasure.interval=PT1H"
})
class AiGovernanceApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AiErasureService erasureService;

    @Autowired
    private AiRuntimeAuditWriter auditWriter;

    private long viewerRoleId;

    @BeforeEach
    void resetGovernanceData() {
        for (String table : new String[]{
                "ai_erasure_target", "ai_erasure_job", "ai_outbox_event", "ai_risk_scan", "ai_risk_case_event",
                "ai_risk_case", "ai_action_approval", "ai_action_execution", "ai_action_proposal",
                "ai_idempotency_record", "ai_step_up_grant", "ai_step_up_failure_window",
                "ai_run_event", "ai_run", "ai_message", "ai_conversation",
                "ai_runtime_switch",
                "ai_audit_event", "ai_audit_chain_head"
        }) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        viewerRoleId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_role WHERE code='VIEWER'", Long.class);
        jdbcTemplate.update("DELETE FROM sys_role_permission WHERE role_id=? AND permission_id IN "
                + "(SELECT id FROM sys_permission WHERE code LIKE 'ai:%')", viewerRoleId);
        revokeViewer("notice:write");
    }

    @AfterEach
    void removeViewerAiPermissions() {
        jdbcTemplate.update("DELETE FROM sys_role_permission WHERE role_id=? AND permission_id IN "
                + "(SELECT id FROM sys_permission WHERE code LIKE 'ai:%')", viewerRoleId);
        revokeViewer("notice:write");
    }

    @Test
    void conversationDeleteIsOwnerScopedIdempotentAndExposesProofStateToOwnerOrAuditor() throws Exception {
        String ownerToken = login("admin", "test-password-123");
        String observer = createViewer("erasure-observer", "observer-password-123");
        String observerToken = login(observer, "observer-password-123");
        grantViewer("ai:assistant:use");
        String conversationId = createConversation(ownerToken);

        mockMvc.perform(delete("/api/ai/conversations/{id}", conversationId)
                        .header("Authorization", ownerToken))
                .andExpect(status().isBadRequest());

        String key = UUID.randomUUID().toString();
        MvcResult accepted = mockMvc.perform(delete("/api/ai/conversations/{id}", conversationId)
                        .header("Authorization", ownerToken)
                        .header("Idempotency-Key", key))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.data.state").value("PENDING"))
                .andExpect(jsonPath("$.data.targets.length()").value(5))
                .andReturn();
        JsonNode job = objectMapper.readTree(accepted.getResponse().getContentAsString()).path("data");
        String jobId = job.path("id").asText();
        assertEquals("/api/ai/erasure-jobs/" + jobId,
                accepted.getResponse().getHeader("Location"));
        assertEquals("ARCHIVED", jdbcTemplate.queryForObject(
                "SELECT status FROM ai_conversation WHERE public_id=?", String.class, conversationId));

        mockMvc.perform(delete("/api/ai/conversations/{id}", conversationId)
                        .header("Authorization", observerToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/ai/erasure-jobs/{id}", jobId)
                        .header("Authorization", observerToken))
                .andExpect(status().isNotFound());

        erasureService.processAvailable();
        mockMvc.perform(get("/api/ai/erasure-jobs/{id}", jobId)
                        .header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.data.targets[0].state").value("VERIFIED"))
                .andExpect(jsonPath("$.data.targets[1].state").value("NEEDS_REVIEW"));

        MvcResult replay = mockMvc.perform(delete("/api/ai/conversations/{id}", conversationId)
                        .header("Authorization", ownerToken)
                        .header("Idempotency-Key", key))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.id").value(jobId))
                .andReturn();
        assertEquals(accepted.getResponse().getHeader("Location"), replay.getResponse().getHeader("Location"));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_erasure_job WHERE scope_public_id=?", conversationId));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_audit_event WHERE aggregate_public_id=? "
                + "AND event_type='ERASURE_REQUESTED'", conversationId));

        revokeViewer("ai:assistant:use");
        grantViewer("ai:audit:read");
        mockMvc.perform(get("/api/ai/erasure-jobs/{id}", jobId)
                        .header("Authorization", observerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(jobId))
                .andExpect(jsonPath("$.data.targets.length()").value(5));
    }

    @Test
    void conversationErasureRejectsActiveRunWithConflictWithoutArchivingOrCreatingJob() throws Exception {
        String token = login("admin", "test-password-123");
        String conversationId = createConversation(token);
        insertActiveRun(conversationId, currentUserId(), "RUNNING");

        mockMvc.perform(delete("/api/ai/conversations/{id}", conversationId)
                        .header("Authorization", token)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("AI_CONVERSATION_HAS_ACTIVE_RUN"));

        assertEquals("ACTIVE", jdbcTemplate.queryForObject(
                "SELECT status FROM ai_conversation WHERE public_id=?", String.class, conversationId));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_erasure_job WHERE scope_public_id=?", conversationId));
        jdbcTemplate.update("UPDATE ai_run SET state='SUCCEEDED',finished_at=CURRENT_TIMESTAMP "
                + "WHERE conversation_id=(SELECT id FROM ai_conversation WHERE public_id=?)", conversationId);
        mockMvc.perform(delete("/api/ai/conversations/{id}", conversationId)
                        .header("Authorization", token)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isAccepted());
    }

    @Test
    void assistantKillSwitchDoesNotDisableOwnerErasureControlPlane() throws Exception {
        String username = createViewer("erasure-owner", "owner-password-123");
        String token = login(username, "owner-password-123");
        long owner = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username=?", Long.class, username);
        String conversationId = insertOwnedConversation(owner);
        jdbcTemplate.update("INSERT INTO ai_runtime_switch "
                        + "(scope_type,scope_key,resource_public_id,disabled,reason_redacted,version,"
                        + "updated_by_user_id,created_at,updated_at) "
                        + "VALUES ('CAPABILITY','ASSISTANT',?,TRUE,'安全演练关闭助手',1,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(), currentUserId());

        mockMvc.perform(post("/api/ai/conversations")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"surface\":\"GLOBAL\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.errorCode").value("AI_CAPABILITY_DISABLED"));
        MvcResult accepted = mockMvc.perform(delete("/api/ai/conversations/{id}", conversationId)
                        .header("Authorization", token)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isAccepted())
                .andReturn();
        String jobId = objectMapper.readTree(accepted.getResponse().getContentAsString())
                .path("data").path("id").asText();
        mockMvc.perform(get("/api/ai/erasure-jobs/{id}", jobId)
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(jobId));
    }

    @Test
    void riskScanUsesPermissionsActorScopeStatusResourceAndScopedIdempotency() throws Exception {
        String ownerToken = login("admin", "test-password-123");
        String viewer = createViewer("risk-viewer", "viewer-password-123");
        String viewerToken = login(viewer, "viewer-password-123");

        mockMvc.perform(post("/api/ai/risk-scans").header("Authorization", ownerToken))
                .andExpect(status().isBadRequest());

        String key = UUID.randomUUID().toString();
        MvcResult first = mockMvc.perform(post("/api/ai/risk-scans")
                        .header("Authorization", ownerToken)
                        .header("Idempotency-Key", key))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.data.id").isNotEmpty())
                .andExpect(jsonPath("$.data.state").value("QUEUED"))
                .andReturn();
        String scanId = objectMapper.readTree(first.getResponse().getContentAsString())
                .path("data").path("id").asText();
        assertEquals("/api/ai/risk-scans/" + scanId, first.getResponse().getHeader("Location"));

        mockMvc.perform(post("/api/ai/risk-scans")
                        .header("Authorization", ownerToken)
                        .header("Idempotency-Key", key))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.id").value(scanId));
        mockMvc.perform(get("/api/ai/risk-scans/{id}", scanId)
                        .header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(scanId));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_risk_scan"));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_idempotency_record "
                + "WHERE route_code='AI_RISK_SCAN' AND response_resource_public_id=?", scanId));

        grantViewer("ai:risk:read");
        mockMvc.perform(post("/api/ai/risk-scans")
                        .header("Authorization", viewerToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/ai/risk-scans/{id}", scanId)
                        .header("Authorization", viewerToken))
                .andExpect(status().isNotFound());
        grantViewer("ai:risk:manage");
        mockMvc.perform(post("/api/ai/risk-scans")
                        .header("Authorization", viewerToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isAccepted());
    }

    @Test
    void cookieAuthenticatedGovernanceWriteRequiresBothBoundCsrfAndAllowedOrigin() throws Exception {
        Cookie session = loginCookie("admin", "test-password-123");
        String conversationId = createConversation(session.getValue());

        mockMvc.perform(delete("/api/ai/conversations/{id}", conversationId)
                        .cookie(session)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/ai/risk-scans")
                        .cookie(session)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());

        MvcResult csrfResponse = mockMvc.perform(get("/api/security/csrf").cookie(session))
                .andExpect(status().isOk()).andReturn();
        String csrf = objectMapper.readTree(csrfResponse.getResponse().getContentAsString())
                .path("data").path("token").asText();
        mockMvc.perform(delete("/api/ai/conversations/{id}", conversationId)
                        .cookie(session)
                        .header("Origin", "https://evil.example")
                        .header("X-CSRF-Token", csrf)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/ai/conversations/{id}", conversationId)
                        .cookie(session)
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", csrf)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isAccepted());
    }

    @Test
    void executionReconfirmHasThreeSafeOutcomesAndReplayDoesNotCreateAnotherExecution() throws Exception {
        String token = login("admin", "test-password-123");

        ProposalFixture recovered = insertNeedsReviewNotice(true);
        String recoveredKey = UUID.randomUUID().toString();
        String recoveredBody = reconfirmBody(recovered, "RESULT_CONFIRMED");
        String recoveredProof = issueReconfirmProof(token, recovered, recoveredBody);
        mockMvc.perform(post("/api/ai/executions/{id}/reconfirm", recovered.id())
                        .header("Authorization", token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-Step-Up-Proof", "not-consumed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(recoveredBody))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/ai/executions/{id}/reconfirm", recovered.executionId())
                        .header("Authorization", token)
                        .header("Idempotency-Key", recoveredKey)
                        .header("X-Step-Up-Proof", recoveredProof)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(recoveredBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.proposalId").value(recovered.id()))
                .andExpect(jsonPath("$.data.executionId").value(recovered.executionId()))
                .andExpect(jsonPath("$.data.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.resultHash").value(recovered.resultHash()));
        mockMvc.perform(post("/api/ai/executions/{id}/reconfirm", recovered.executionId())
                        .header("Authorization", token)
                        .header("Idempotency-Key", recoveredKey)
                        .header("X-Step-Up-Proof", recoveredProof)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(recoveredBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("SUCCEEDED"));
        assertEquals(1, executionCount(recovered.id()));
        assertEquals(currentUserId(), jdbcTemplate.queryForObject(
                "SELECT e.reconfirmed_by_user_id FROM ai_action_execution e "
                        + "JOIN ai_action_proposal p ON p.id=e.proposal_id WHERE p.public_id=?",
                Long.class, recovered.id()));

        ProposalFixture failed = insertNeedsReviewNotice(false);
        String failedBody = reconfirmBody(failed, "CONFLICT");
        mockMvc.perform(post("/api/ai/executions/{id}/reconfirm", failed.executionId())
                        .header("Authorization", token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-Step-Up-Proof", issueReconfirmProof(token, failed, failedBody))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(failedBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("FAILED"));
        assertEquals(1, executionCount(failed.id()));

        ProposalFixture uncertain = insertNeedsReviewNotice(false);
        String uncertainBody = reconfirmBody(uncertain, "UNKNOWN");
        mockMvc.perform(post("/api/ai/executions/{id}/reconfirm", uncertain.executionId())
                        .header("Authorization", token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-Step-Up-Proof", issueReconfirmProof(token, uncertain, uncertainBody))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uncertainBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("NEEDS_REVIEW"));
        assertEquals(1, executionCount(uncertain.id()));
    }

    @Test
    void executionReconfirmRejectsStaleCasIdempotencyMismatchAndMissingActionScope() throws Exception {
        String token = login("admin", "test-password-123");
        ProposalFixture stale = insertNeedsReviewNotice(false);
        String staleBody = objectMapper.writeValueAsString(Map.of(
                "version", stale.version() - 1,
                "payloadHash", stale.payloadHash(),
                "businessSnapshotHash", stale.snapshotHash(),
                "resolution", "UNKNOWN",
                "comment", "已核对原业务事实，保持人工复核"));
        mockMvc.perform(post("/api/ai/executions/{id}/reconfirm", stale.executionId())
                        .header("Authorization", token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-Step-Up-Proof", issueReconfirmProof(token, stale.executionId(), staleBody))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(staleBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("AI_EXECUTION_RECONFIRM_CONFLICT"));
        assertEquals("NEEDS_REVIEW", proposalState(stale.id()));

        ProposalFixture mismatch = insertNeedsReviewNotice(false);
        String key = UUID.randomUUID().toString();
        String original = reconfirmBody(mismatch, "UNKNOWN");
        String proof = issueReconfirmProof(token, mismatch, original);
        mockMvc.perform(post("/api/ai/executions/{id}/reconfirm", mismatch.executionId())
                        .header("Authorization", token).header("Idempotency-Key", key)
                        .header("X-Step-Up-Proof", proof).contentType(MediaType.APPLICATION_JSON).content(original))
                .andExpect(status().isOk());
        String changed = reconfirmBody(mismatch, "CONFLICT");
        mockMvc.perform(post("/api/ai/executions/{id}/reconfirm", mismatch.executionId())
                        .header("Authorization", token).header("Idempotency-Key", key)
                        .header("X-Step-Up-Proof", proof).contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("AI_IDEMPOTENCY_PAYLOAD_MISMATCH"));
        assertEquals("NEEDS_REVIEW", proposalState(mismatch.id()));

        String viewer = createViewer("approval-viewer", "approval-password-123");
        grantViewer("ai:approval:review");
        String viewerToken = login(viewer, "approval-password-123");
        ProposalFixture unauthorized = insertNeedsReviewNotice(false);
        String unauthorizedBody = reconfirmBody(unauthorized, "UNKNOWN");
        mockMvc.perform(post("/api/ai/executions/{id}/reconfirm", unauthorized.executionId())
                        .header("Authorization", viewerToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-Step-Up-Proof", "unused")
                        .contentType(MediaType.APPLICATION_JSON).content(unauthorizedBody))
                .andExpect(status().isNotFound());

        ProposalFixture invisibleRepair = insertNeedsReviewRepair(9_999_999L);
        String invisibleBody = reconfirmBody(invisibleRepair, "UNKNOWN");
        mockMvc.perform(post("/api/ai/executions/{id}/reconfirm", invisibleRepair.executionId())
                        .header("Authorization", token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-Step-Up-Proof", "unused")
                        .contentType(MediaType.APPLICATION_JSON).content(invisibleBody))
                .andExpect(status().isNotFound());
    }

    @Test
    void proposalPreviewListPaginationAndApprovalCommentFailClosedWithoutInventedEvidence() throws Exception {
        String adminToken = login("admin", "test-password-123");
        ProposalFixture pending = insertPendingNotice();
        AiProposalController.ApproveRequest request = new AiProposalController.ApproveRequest(
                pending.version(), pending.payloadHash(), pending.snapshotHash(), "联系 13800138000 后同意");
        String body = objectMapper.writeValueAsString(request);
        String requestHash = AiProposalController.approvalRequestHash(pending.id(), request);
        MvcResult proofResult = mockMvc.perform(post("/api/security/step-up")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "password", "test-password-123",
                                "actionCode", "PROPOSAL_APPROVE",
                                "resourcePublicId", pending.id(),
                                "requestHash", requestHash))))
                .andExpect(status().isOk()).andReturn();
        String proof = objectMapper.readTree(proofResult.getResponse().getContentAsString())
                .path("data").path("proof").asText();

        mockMvc.perform(get("/api/ai/proposals/{id}", pending.id())
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentValue").value("当前不存在此 AI 公告草稿"))
                .andExpect(jsonPath("$.data.proposedValue").value("创建可编辑纯文本公告草稿《停水通知》"))
                .andExpect(jsonPath("$.data.impact").value("仅创建草稿，不发布公告"))
                .andExpect(jsonPath("$.data.evidence.basis").value("deterministic"))
                .andExpect(jsonPath("$.data.evidence.confidence").doesNotExist())
                .andExpect(jsonPath("$.data.evidence.grounded").value(true));
        String approvalKey = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/ai/proposals/{id}/approve", pending.id())
                        .header("Authorization", adminToken)
                        .header("Idempotency-Key", approvalKey)
                        .header("X-Step-Up-Proof", proof)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("approved"));
        mockMvc.perform(post("/api/ai/proposals/{id}/approve", pending.id())
                        .header("Authorization", adminToken)
                        .header("Idempotency-Key", approvalKey)
                        .header("X-Step-Up-Proof", proof)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("approved"));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_action_approval WHERE proposal_id="
                + "(SELECT id FROM ai_action_proposal WHERE public_id=?)", pending.id()));
        String storedComment = jdbcTemplate.queryForObject(
                "SELECT comment_redacted FROM ai_action_approval WHERE proposal_id="
                        + "(SELECT id FROM ai_action_proposal WHERE public_id=?)",
                String.class, pending.id());
        assertTrue(storedComment != null && !storedComment.contains("13800138000"));

        String viewer = createViewer("proposal-page-viewer", "viewer-password-123");
        grantViewer("ai:approval:review");
        grantViewer("notice:write");
        String viewerToken = login(viewer, "viewer-password-123");
        insertPendingNotice();
        insertNeedsReviewRepair(9_999_999L);
        mockMvc.perform(get("/api/ai/proposals?page=1&pageSize=1")
                        .header("Authorization", viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.total").value(2));
        mockMvc.perform(get("/api/ai/proposals?page=2&pageSize=1")
                        .header("Authorization", viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.total").value(2));
        mockMvc.perform(get("/api/ai/proposals?page=1&pageSize=1&actionType=NOTICE_CREATE_DRAFT")
                        .header("Authorization", viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].actionType").value("NOTICE_CREATE_DRAFT"))
                .andExpect(jsonPath("$.data.total").value(2));
        mockMvc.perform(get("/api/ai/proposals?page=1&pageSize=20&state=pending_approval"
                                + "&actionType=NOTICE_CREATE_DRAFT")
                        .header("Authorization", viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/ai/proposals?page=1&pageSize=20&actionType=REPAIR_ASSIGN")
                        .header("Authorization", viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(0))
                .andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/api/ai/proposals?actionType=DELETE_ALL")
                        .header("Authorization", viewerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("AI_INVALID_PROPOSAL_ACTION_TYPE"));
        mockMvc.perform(get("/api/ai/proposals?state=unknown")
                        .header("Authorization", viewerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("AI_INVALID_PROPOSAL_STATE"));
        mockMvc.perform(get("/api/ai/proposals").param("actionType", " ")
                        .header("Authorization", viewerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("AI_INVALID_PROPOSAL_ACTION_TYPE"));
        mockMvc.perform(get("/api/ai/proposals").param("state", " ")
                        .header("Authorization", viewerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("AI_INVALID_PROPOSAL_STATE"));
    }

    @Test
    void directProposalObjectDenialsAcrossAllFourRoutesArePersistedInValidHmacChains() throws Exception {
        ProposalFixture pending = insertPendingNotice();
        ProposalFixture review = insertNeedsReviewNotice(false);
        String viewer = createViewer("proposal-denied-viewer", "viewer-password-123");
        grantViewer("ai:approval:review");
        String viewerToken = login(viewer, "viewer-password-123");

        mockMvc.perform(get("/api/ai/proposals/{id}", pending.id())
                        .header("Authorization", viewerToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/ai/proposals/{id}/approve", pending.id())
                        .header("Authorization", viewerToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-Step-Up-Proof", "must-not-be-consumed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "version", pending.version(),
                                "payloadHash", pending.payloadHash(),
                                "businessSnapshotHash", pending.snapshotHash(),
                                "comment", "无权审批"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/ai/proposals/{id}/reject", pending.id())
                        .header("Authorization", viewerToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "version", pending.version(), "comment", "无权拒绝"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/ai/executions/{id}/reconfirm", review.executionId())
                        .header("Authorization", viewerToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-Step-Up-Proof", "must-not-be-consumed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reconfirmBody(review, "UNKNOWN")))
                .andExpect(status().isNotFound());

        assertEquals(3, count("SELECT COUNT(*) FROM ai_audit_event WHERE aggregate_public_id=? "
                + "AND event_type='PROPOSAL_OBJECT_ACCESS_DENIED'", pending.id()));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_audit_event WHERE aggregate_public_id=? "
                + "AND event_type='PROPOSAL_OBJECT_ACCESS_DENIED'", review.id()));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_step_up_grant WHERE actor_user_id="
                + "(SELECT id FROM sys_user WHERE username=?)", viewer));
        auditWriter.requireValidChain("PROPOSAL", "ACTION_PROPOSAL", pending.id());
        auditWriter.requireValidChain("PROPOSAL", "ACTION_PROPOSAL", review.id());
    }

    private String issueReconfirmProof(String token, ProposalFixture proposal, String body) throws Exception {
        return issueReconfirmProof(token, proposal.executionId(), body);
    }

    private String issueReconfirmProof(String token, String proposalId, String body) throws Exception {
        AiExecutionController.ReconfirmRequest request = objectMapper.readValue(
                body, AiExecutionController.ReconfirmRequest.class);
        String requestHash = AiExecutionController.requestHash(proposalId, request);
        MvcResult result = mockMvc.perform(post("/api/security/step-up")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "password", "test-password-123",
                                "actionCode", "PROPOSAL_RECONFIRM",
                                "resourcePublicId", proposalId,
                                "requestHash", requestHash))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("proof").asText();
    }

    private String reconfirmBody(ProposalFixture proposal, String resolution) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "version", proposal.version(),
                "payloadHash", proposal.payloadHash(),
                "businessSnapshotHash", proposal.snapshotHash(),
                "resolution", resolution,
                "comment", "已核对原业务事实并记录对账结论"));
    }

    private ProposalFixture insertNeedsReviewNotice(boolean withResult) {
        String payload = CanonicalJsonHasher.canonicalize(
                "{\"title\":\"停水通知\",\"type\":\"后勤\",\"status\":\"草稿\",\"content\":\"测试正文\"}");
        return insertNeedsReview("NOTICE_CREATE_DRAFT", "NOTICE", null, payload,
                "notice:write", CanonicalJsonHasher.sha256("NOTICE_DRAFT_CREATE|schema-v1"), withResult);
    }

    private ProposalFixture insertPendingNotice() throws Exception {
        String payload = CanonicalJsonHasher.canonicalize(
                "{\"title\":\"停水通知\",\"type\":\"后勤\",\"status\":\"草稿\",\"content\":\"测试正文\"}");
        String preview = objectMapper.writeValueAsString(Map.of(
                "currentValue", "当前不存在此 AI 公告草稿",
                "proposedValue", "创建可编辑纯文本公告草稿《停水通知》",
                "impact", "仅创建草稿，不发布公告",
                "asOf", Instant.now().toString(),
                "evidenceBasis", "DETERMINISTIC",
                "citations", java.util.List.of(Map.of(
                        "type", "USER_COMMAND", "sourceRef", "RUN:test",
                        "label", "当前用户提交的公告起草命令", "contentHash", CanonicalJsonHasher.sha256(payload)))));
        String id = UUID.randomUUID().toString();
        String payloadHash = CanonicalJsonHasher.sha256(payload);
        String snapshotHash = CanonicalJsonHasher.sha256("NOTICE_DRAFT_CREATE|schema-v1");
        long proposer = currentUserId();
        jdbcTemplate.update("INSERT INTO ai_action_proposal "
                        + "(public_id,run_id,origin_tool_call_id,action_type,target_type,target_resource_id,payload_text,"
                        + "preview_text,payload_hash,business_snapshot_hash,required_business_permission,"
                        + "approval_policy_version,required_approval_count,approved_count,risk_level,state,"
                        + "proposer_user_id,expires_at,version,created_operator_user_id,updated_operator_user_id,"
                        + "created_at,updated_at) VALUES (?,1,?,'NOTICE_CREATE_DRAFT','NOTICE',NULL,?,?,?,?,?,"
                        + "'approval-policy-v1',1,0,'MEDIUM','PENDING_APPROVAL',?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                id, Math.max(1L, Math.abs(UUID.randomUUID().getLeastSignificantBits())), payload, preview,
                payloadHash, snapshotHash, "notice:write", proposer,
                Timestamp.from(Instant.now().plusSeconds(600)), 0, proposer, proposer);
        return new ProposalFixture(id, payloadHash, snapshotHash, 0, null, null);
    }

    private ProposalFixture insertNeedsReviewRepair(long repairOrderId) {
        String payload = CanonicalJsonHasher.canonicalize(
                "{\"repairOrderId\":" + repairOrderId + ",\"assigneeUserId\":1}");
        return insertNeedsReview("REPAIR_ASSIGN", "REPAIR_ORDER", repairOrderId, payload,
                "repair:write", "b".repeat(64), false);
    }

    private ProposalFixture insertNeedsReview(String actionType, String targetType, Long targetResourceId,
                                              String payload, String permission, String snapshotHash,
                                              boolean withResult) {
        String id = UUID.randomUUID().toString();
        String payloadHash = CanonicalJsonHasher.sha256(payload);
        long proposer = currentUserId();
        int version = 4;
        jdbcTemplate.update("INSERT INTO ai_action_proposal "
                        + "(public_id,run_id,origin_tool_call_id,action_type,target_type,target_resource_id,payload_text,"
                        + "preview_text,payload_hash,business_snapshot_hash,required_business_permission,"
                        + "approval_policy_version,required_approval_count,approved_count,risk_level,state,"
                        + "proposer_user_id,expires_at,version,created_operator_user_id,updated_operator_user_id,"
                        + "created_at,updated_at) VALUES (?,0,?,?,?,?,?,'人工对账',?,?,?,'approval-policy-v1',1,1,"
                        + "'HIGH','NEEDS_REVIEW',?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                id, Math.abs(UUID.randomUUID().getLeastSignificantBits()), actionType, targetType, targetResourceId,
                payload, payloadHash, snapshotHash, permission, proposer,
                Timestamp.from(Instant.now().plusSeconds(600)), version, proposer, proposer);
        Long proposalDbId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_action_proposal WHERE public_id=?", Long.class, id);
        String resultHash = withResult ? CanonicalJsonHasher.sha256("existing-result|" + id) : null;
        String executionId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO ai_action_execution "
                        + "(public_id,proposal_id,state,version,handler_name,execution_key,lease_token_hash,"
                        + "executed_by_user_id,result_resource_type,result_resource_id,response_redacted,started_at,"
                        + "error_code,error_summary,created_operator_user_id,updated_operator_user_id,created_at,updated_at) "
                        + "VALUES (?,?,'NEEDS_REVIEW',1,?,?,?,?,?,?,?,?,"
                        + "'AI_EXECUTION_OUTCOME_UNKNOWN','等待人工对账',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                executionId, proposalDbId, actionType,
                CanonicalJsonHasher.sha256("execution|" + id), CanonicalJsonHasher.sha256("lease|" + id), proposer,
                withResult ? "NOTICE" : null, withResult ? 777L : null, resultHash,
                Timestamp.from(Instant.now().minusSeconds(300)), proposer, proposer);
        return new ProposalFixture(id, payloadHash, snapshotHash, version, resultHash, executionId);
    }

    private String createConversation(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/ai/conversations")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"surface\":\"GLOBAL\"}"))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asText();
    }

    private void insertActiveRun(String conversationPublicId, long owner, String state) {
        long conversationId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_conversation WHERE public_id=?", Long.class, conversationPublicId);
        String messageId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO ai_message "
                        + "(public_id,conversation_id,sequence_no,role,content_redacted,classification,created_at) "
                        + "VALUES (?,?,1,'USER','[REDACTED]','L1',CURRENT_TIMESTAMP)",
                messageId, conversationId);
        long requestMessageId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_message WHERE public_id=?", Long.class, messageId);
        jdbcTemplate.update("INSERT INTO ai_run "
                        + "(public_id,conversation_id,request_message_id,capability,state,version,actor_user_id,"
                        + "session_fingerprint_hash,session_fingerprint_key_version,permission_digest,"
                        + "prompt_version_id,tool_catalog_version_id,retrieval_policy_version,redaction_policy_version,"
                        + "reserved_tokens,reserved_cost,correlation_id,cost_status,created_at,updated_at) "
                        + "VALUES (?,?,?,'ASSISTANT',?,0,?,?,1,?,1,1,'none.v1','pii-redaction-v2',"
                        + "0,0,?,'FINAL',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(), conversationId, requestMessageId, state, owner,
                "a".repeat(64), "b".repeat(64), UUID.randomUUID().toString());
    }

    private String insertOwnedConversation(long owner) {
        String publicId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO ai_conversation "
                        + "(public_id,owner_user_id,surface,context_type,status,title_redacted,"
                        + "created_operator_user_id,updated_operator_user_id,created_at,updated_at) "
                        + "VALUES (?,?,'GLOBAL','NONE','ACTIVE','待清除会话',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                publicId, owner, owner, owner);
        return publicId;
    }

    private String createViewer(String prefix, String password) {
        String username = prefix + "-" + UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO sys_user "
                        + "(username,password_hash,display_name,role_code,enabled,deleted,created_at,updated_at) "
                        + "VALUES (?,?,?,'VIEWER',TRUE,FALSE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                username, passwordEncoder.encode(password), username);
        long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username=?", Long.class, username);
        jdbcTemplate.update("INSERT INTO sys_user_role(user_id,role_id,created_at,updated_at) "
                + "VALUES (?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", userId, viewerRoleId);
        return username;
    }

    private void grantViewer(String permission) {
        jdbcTemplate.update("INSERT INTO sys_role_permission(role_id,permission_id,created_at,updated_at) "
                        + "SELECT ?,id,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM sys_permission WHERE code=? "
                        + "AND NOT EXISTS (SELECT 1 FROM sys_role_permission WHERE role_id=? AND permission_id=id)",
                viewerRoleId, permission, viewerRoleId);
    }

    private void revokeViewer(String permission) {
        jdbcTemplate.update("DELETE FROM sys_role_permission WHERE role_id=? "
                        + "AND permission_id=(SELECT id FROM sys_permission WHERE code=?)",
                viewerRoleId, permission);
    }

    private String login(String username, String password) throws Exception {
        return loginCookie(username, password).getValue();
    }

    private Cookie loginCookie(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk()).andReturn();
        var cookie = result.getResponse().getCookie("Authorization");
        assertNotNull(cookie);
        return cookie;
    }

    private long currentUserId() {
        Long value = jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username='admin'", Long.class);
        assertNotNull(value);
        return value;
    }

    private int executionCount(String proposalId) {
        return count("SELECT COUNT(*) FROM ai_action_execution WHERE proposal_id="
                + "(SELECT id FROM ai_action_proposal WHERE public_id=?)", proposalId);
    }

    private String proposalState(String proposalId) {
        return jdbcTemplate.queryForObject(
                "SELECT state FROM ai_action_proposal WHERE public_id=?", String.class, proposalId);
    }

    private int count(String sql, Object... arguments) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, arguments);
        assertNotNull(value);
        return value;
    }

    private record ProposalFixture(String id, String payloadHash, String snapshotHash,
                                   int version, String resultHash, String executionId) { }
}
