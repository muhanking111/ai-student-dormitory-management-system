package com.example.dormitory.ai.api;

import com.example.dormitory.ai.config.AiRuntimeControlService;
import com.example.dormitory.ai.knowledge.KnowledgeIngestionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ai-knowledge-api;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.connection-timeout=250",
        "dormitory.ai.enabled=true",
        "dormitory.ai.capabilities.knowledge=true",
        "dormitory.ai.audit.hmac-key=0123456789abcdef0123456789abcdef",
        "dormitory.ai.tokenization.hmac-key=fedcba9876543210fedcba9876543210",
        "dormitory.ai.step-up.hmac-key=abcdef0123456789abcdef0123456789"
        ,"dormitory.ai.knowledge.auto-process=false"
})
class AiKnowledgeApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired KnowledgeIngestionService ingestion;
    @Autowired AiRuntimeControlService controls;

    @BeforeEach
    void reset() {
        controls.disabledSwitches().forEach(value -> controls.clear(
                value.scope(), value.key(), "测试清理运行时开关", value.version(), null));
        for (String table : new String[]{"ai_knowledge_public_approval", "ai_ingestion_job",
                "ai_document_chunk", "ai_upload_session", "ai_document_version", "ai_document",
                "ai_knowledge_source_permission", "ai_knowledge_source", "ai_idempotency_record",
                "ai_step_up_grant"}) jdbc.update("DELETE FROM " + table);
        jdbc.update("DELETE FROM sys_user_role WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username='knowledge_reviewer')");
        jdbc.update("DELETE FROM sys_user WHERE username='knowledge_reviewer'");
        jdbc.update("INSERT INTO sys_role_permission(role_id, permission_id) "
                + "SELECT r.id,p.id FROM sys_role r CROSS JOIN sys_permission p "
                + "WHERE r.code='ADMIN' AND p.code IN ('ai:knowledge:read','ai:knowledge:manage',"
                + "'ai:knowledge:publish-public') AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp "
                + "WHERE rp.role_id=r.id AND rp.permission_id=p.id)");
    }

    @Test
    void sourceUploadVersionJobLifecycleIsPrivateAuthorizedAndPersisted() throws Exception {
        Login login = login();
        String sourceId = createSource(login.token(), "EXPLICIT_ACL", "L1", java.util.List.of("repair:read"));

        mockMvc.perform(get("/api/ai/knowledge/sources").header("Authorization", login.token()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.data.records[0].id").value(sourceId));
        mockMvc.perform(get("/api/ai/knowledge/sources/{id}", sourceId)
                        .header("Authorization", login.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.aclVersion").value(1));

        byte[] text = "维修制度正文，联系电话 13800138000。".getBytes(StandardCharsets.UTF_8);
        String sha = sha256(text);
        String uploadKey = UUID.randomUUID().toString();
        String uploadRequest = objectMapper.writeValueAsString(Map.of(
                "mimeType", "text/plain", "sizeBytes", text.length, "sha256", sha));
        MvcResult created = mockMvc.perform(post("/api/ai/knowledge/sources/{id}/uploads", sourceId)
                        .header("Authorization", login.token()).header("Idempotency-Key", uploadKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadRequest))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.data.uploadTarget").isNotEmpty()).andReturn();
        JsonNode upload = objectMapper.readTree(created.getResponse().getContentAsString()).path("data");
        String uploadId = upload.path("id").asText();
        assertEquals("fake-in-memory-versioned-v1", upload.path("storageAdapter").asText());
        assertEquals(false, upload.path("storageDurableAcrossRestart").asBoolean());
        assertEquals("CONTROLLED_PLAIN_TEXT_ONLY", upload.path("scanMode").asText());
        mockMvc.perform(post("/api/ai/knowledge/sources/{id}/uploads", sourceId)
                        .header("Authorization", login.token()).header("Idempotency-Key", uploadKey)
                        .contentType(MediaType.APPLICATION_JSON).content(uploadRequest))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").value(uploadId));
        mockMvc.perform(post("/api/ai/knowledge/sources/{id}/uploads", sourceId)
                        .header("Authorization", login.token()).header("Idempotency-Key", uploadKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mimeType\":\"text/plain\",\"sizeBytes\":2,\"sha256\":\""
                                + "a".repeat(64) + "\"}"))
                .andExpect(status().isConflict());

        mockMvc.perform(put(upload.path("uploadTarget").asText())
                        .header("Authorization", login.token())
                        .contentType(MediaType.TEXT_PLAIN).content(text))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/ai/knowledge/uploads/{id}/finalize", uploadId)
                        .header("Authorization", login.token()).header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.state").value("FINALIZED"));

        String versionKey = UUID.randomUUID().toString();
        String versionRequest = objectMapper.writeValueAsString(Map.of(
                "uploadSessionId", uploadId, "externalKey", "repair-policy",
                "title", "维修制度", "version", "v1"));
        mockMvc.perform(post("/api/ai/knowledge/sources/{id}/versions", sourceId)
                        .header("Authorization", login.token()).header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "uploadSessionId", uploadId, "externalKey", "repair-policy",
                                "title", "维修制度", "version", "v1", "objectKey", "../../unsafe"))))
                .andExpect(status().isBadRequest());
        MvcResult scheduled = mockMvc.perform(post("/api/ai/knowledge/sources/{id}/versions", sourceId)
                        .header("Authorization", login.token()).header("Idempotency-Key", versionKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(versionRequest))
                .andExpect(status().isAccepted()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.data.jobId").isNotEmpty()).andReturn();
        JsonNode data = objectMapper.readTree(scheduled.getResponse().getContentAsString()).path("data");
        String versionId = data.path("versionId").asText();
        String jobId = data.path("jobId").asText();
        mockMvc.perform(post("/api/ai/knowledge/sources/{id}/versions", sourceId)
                        .header("Authorization", login.token()).header("Idempotency-Key", versionKey)
                        .contentType(MediaType.APPLICATION_JSON).content(versionRequest))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.versionId").value(versionId))
                .andExpect(jsonPath("$.data.jobId").value(jobId));
        ingestion.processNext("api-test-worker").orElseThrow();

        mockMvc.perform(get("/api/ai/knowledge/jobs/{id}", jobId).header("Authorization", login.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("SUCCEEDED"));
        mockMvc.perform(get("/api/ai/knowledge/versions/{id}", versionId)
                        .header("Authorization", login.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("READY"));
        mockMvc.perform(post("/api/ai/knowledge/versions/{id}/activate", versionId)
                        .header("Authorization", login.token()))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/ai/knowledge/versions/{id}/retire", versionId)
                        .header("Authorization", login.token()))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/ai/knowledge/versions/{id}/rollback", versionId)
                        .header("Authorization", login.token()))
                .andExpect(status().isNoContent());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_upload_session WHERE public_id=? "
                + "AND finalized_document_version_id IS NOT NULL", Integer.class, uploadId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_upload_session WHERE public_id=? "
                + "AND quarantine_object_key LIKE 'quarantine/%'", Integer.class, uploadId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_document_version WHERE public_id=? "
                + "AND object_key=?", Integer.class, versionId, "sha256/" + sha));
    }

    @Test
    void uploadEndpointStreamsWithHardLimitAndQuarantinesBinaryWithoutPretendingMalwareScan() throws Exception {
        Login login = login();
        String sourceId = createSource(login.token(), "EXPLICIT_ACL", "L1", java.util.List.of("repair:read"));
        byte[] safe = "安全".getBytes(StandardCharsets.UTF_8);
        JsonNode tooSmall = createUpload(login.token(), sourceId, safe);
        mockMvc.perform(put(tooSmall.path("uploadTarget").asText())
                        .header("Authorization", login.token()).contentType(MediaType.TEXT_PLAIN)
                        .content("安全x".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.data.errorCode").value("AI_UPLOAD_TOO_LARGE"));

        JsonNode wrongMime = createUpload(login.token(), sourceId, safe);
        mockMvc.perform(put(wrongMime.path("uploadTarget").asText())
                        .header("Authorization", login.token()).contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(safe))
                .andExpect(status().isUnsupportedMediaType());

        byte[] binary = "%PDF-1.7\nmalicious".getBytes(StandardCharsets.UTF_8);
        JsonNode binaryUpload = createUpload(login.token(), sourceId, binary);
        mockMvc.perform(put(binaryUpload.path("uploadTarget").asText())
                        .header("Authorization", login.token()).contentType(MediaType.TEXT_PLAIN).content(binary))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/ai/knowledge/uploads/{id}/finalize", binaryUpload.path("id").asText())
                        .header("Authorization", login.token()).header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.data.errorCode").value("AI_KNOWLEDGE_QUARANTINED"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_upload_session WHERE public_id=? "
                + "AND state='QUARANTINED' AND scan_state='BINARY_SIGNATURE'", Integer.class,
                binaryUpload.path("id").asText()));
    }

    @Test
    void identicalBodiesKeepRandomQuarantineKeysAndRepeatedFinalizeIsIdempotent() throws Exception {
        Login login = login();
        String sourceId = createSource(login.token(), "EXPLICIT_ACL", "L1", java.util.List.of("repair:read"));
        byte[] content = "重复正文".getBytes(StandardCharsets.UTF_8);
        JsonNode first = createUpload(login.token(), sourceId, content);
        JsonNode second = createUpload(login.token(), sourceId, content);

        for (JsonNode upload : java.util.List.of(first, second)) {
            mockMvc.perform(put(upload.path("uploadTarget").asText()).header("Authorization", login.token())
                            .contentType(MediaType.TEXT_PLAIN).content(content))
                    .andExpect(status().isNoContent());
            mockMvc.perform(post("/api/ai/knowledge/uploads/{id}/finalize", upload.path("id").asText())
                            .header("Authorization", login.token()).header("Idempotency-Key", UUID.randomUUID()))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.data.scanMode").value("CONTROLLED_PLAIN_TEXT_ONLY"))
                    .andExpect(jsonPath("$.data.malwareScannerAvailable").value(false));
        }
        String responseLossKey = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/ai/knowledge/uploads/{id}/finalize", first.path("id").asText())
                        .header("Authorization", login.token()).header("Idempotency-Key", responseLossKey))
                .andExpect(status().isAccepted());
        jdbc.update("UPDATE ai_idempotency_record SET state='PENDING',response_status=NULL,"
                        + "response_resource_public_id=NULL WHERE route_code='AI_KNOWLEDGE_UPLOAD_FINALIZE' "
                        + "AND aggregate_public_id=? AND idempotency_key=?",
                first.path("id").asText(), responseLossKey);
        mockMvc.perform(post("/api/ai/knowledge/uploads/{id}/finalize", first.path("id").asText())
                        .header("Authorization", login.token()).header("Idempotency-Key", responseLossKey))
                .andExpect(status().isAccepted());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_idempotency_record "
                + "WHERE route_code='AI_KNOWLEDGE_UPLOAD_FINALIZE' AND aggregate_public_id=? "
                + "AND idempotency_key=? AND state='COMPLETED'", Integer.class,
                first.path("id").asText(), responseLossKey));

        assertEquals(2, jdbc.queryForObject("SELECT COUNT(DISTINCT quarantine_object_key) "
                + "FROM ai_upload_session WHERE public_id IN (?,?)", Integer.class,
                first.path("id").asText(), second.path("id").asText()));
    }

    @Test
    void publicApprovalRequiresNonOwnerStepUpAndSnapshotHash() throws Exception {
        Login admin = login();
        long reviewerId = createReviewer();
        Login reviewer = login("knowledge_reviewer", "review-password-123");
        String sourceId = createSource(admin.token(), "EXPLICIT_ACL", "L0", java.util.List.of());
        String versionId = readyVersion(admin.token(), sourceId, "公共制度正文");
        JsonNode version = objectMapper.readTree(mockMvc.perform(get("/api/ai/knowledge/versions/{id}", versionId)
                        .header("Authorization", admin.token())).andReturn().getResponse().getContentAsString())
                .path("data");
        String approvalSnapshotHash = version.path("approvalSnapshotHash").asText();
        String requestHash = AiKnowledgeController.publicApprovalRequestHash(versionId,
                version.path("contentHash").asText(), approvalSnapshotHash);

        String proof = issueProof(reviewer, versionId, requestHash);
        mockMvc.perform(post("/api/ai/knowledge/versions/{id}/approve-public", versionId)
                        .header("Authorization", reviewer.token()).header("Idempotency-Key", UUID.randomUUID())
                        .header("X-Step-Up-Proof", proof).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "contentHash", version.path("contentHash").asText(),
                                "approvalSnapshotHash", approvalSnapshotHash))))
                .andExpect(status().isNoContent());
        assertEquals(reviewerId, jdbc.queryForObject("SELECT reviewer_user_id "
                + "FROM ai_knowledge_public_approval", Long.class));
        assertEquals("USED", jdbc.queryForObject(
                "SELECT state FROM ai_step_up_grant WHERE action_code='KNOWLEDGE_PUBLIC_APPROVE'",
                String.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_idempotency_record "
                + "WHERE route_code='AI_KNOWLEDGE_PUBLIC_APPROVE' AND state='COMPLETED'", Integer.class));
    }

    @Test
    void publicApprovalSnapshotMismatchRollsBackApprovalAndPendingReservationAfterProofUse() throws Exception {
        Login admin = login();
        createReviewer();
        Login reviewer = login("knowledge_reviewer", "review-password-123");
        String sourceId = createSource(admin.token(), "EXPLICIT_ACL", "L0", java.util.List.of());
        String versionId = readyVersion(admin.token(), sourceId, "快照回滚验证正文");
        JsonNode version = objectMapper.readTree(mockMvc.perform(get("/api/ai/knowledge/versions/{id}", versionId)
                        .header("Authorization", admin.token())).andReturn().getResponse().getContentAsString())
                .path("data");
        String staleSnapshotHash = "b".repeat(64);
        String requestHash = AiKnowledgeController.publicApprovalRequestHash(
                versionId, version.path("contentHash").asText(), staleSnapshotHash);
        String proof = issueProof(reviewer, versionId, requestHash);
        String idempotencyKey = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/ai/knowledge/versions/{id}/approve-public", versionId)
                        .header("Authorization", reviewer.token()).header("Idempotency-Key", idempotencyKey)
                        .header("X-Step-Up-Proof", proof).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "contentHash", version.path("contentHash").asText(),
                                "approvalSnapshotHash", staleSnapshotHash))))
                .andExpect(status().isInternalServerError());

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_knowledge_public_approval "
                + "WHERE document_version_id=(SELECT id FROM ai_document_version WHERE public_id=?)",
                Integer.class, versionId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_document_version WHERE public_id=? "
                + "AND visibility='EXPLICIT_ACL' AND active_public_approval_id IS NULL", Integer.class, versionId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_idempotency_record "
                + "WHERE route_code='AI_KNOWLEDGE_PUBLIC_APPROVE' AND idempotency_key=?",
                Integer.class, idempotencyKey));
        assertEquals("USED", jdbc.queryForObject("SELECT state FROM ai_step_up_grant "
                + "WHERE action_code='KNOWLEDGE_PUBLIC_APPROVE' AND resource_public_id=?",
                String.class, versionId));
    }

    @Test
    void sourcePatchUsesAclCasRejectsUnknownPermissionsAndRevokesPublicVisibilityAtomically() throws Exception {
        Login admin = login();
        String sourceId = createSource(admin.token(), "EXPLICIT_ACL", "L0", java.util.List.of());
        String versionId = readyVersion(admin.token(), sourceId, "待撤销公开制度正文");
        jdbc.update("UPDATE ai_document_version SET visibility='PUBLIC_APPROVED',active_public_approval_id=999 "
                + "WHERE public_id=?", versionId);

        mockMvc.perform(patch("/api/ai/knowledge/sources/{id}", sourceId)
                        .header("Authorization", admin.token()).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedAclVersion", 1,
                                "name", "已暂停知识源",
                                "classification", "L1",
                                "matchMode", "ALL",
                                "status", "PAUSED",
                                "permissions", java.util.List.of("repair:read")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("已暂停知识源"))
                .andExpect(jsonPath("$.data.aclVersion").value(2))
                .andExpect(jsonPath("$.data.status").value("PAUSED"));

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_document_version WHERE public_id=? "
                + "AND visibility='EXPLICIT_ACL' AND active_public_approval_id IS NULL", Integer.class, versionId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_outbox_event WHERE aggregate_public_id=? "
                + "AND event_type='KnowledgeSourceChanged.v1'", Integer.class, sourceId));

        String otherSource = createSource(admin.token(), "EXPLICIT_ACL", "L1", java.util.List.of());
        mockMvc.perform(patch("/api/ai/knowledge/sources/{id}", otherSource)
                        .header("Authorization", admin.token()).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedAclVersion", 1,
                                "permissions", java.util.List.of("permission:does-not-exist")))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cookieWritesRequireOriginAndCsrfAndSourceKillSwitchBlocksNewIngestionAndRead() throws Exception {
        Login login = login();
        String request = objectMapper.writeValueAsString(Map.of("name", "知识源", "classification", "L1",
                "matchMode", "ANY", "permissions", java.util.List.of("repair:read")));
        mockMvc.perform(post("/api/ai/knowledge/sources").cookie(login.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isForbidden());
        String csrf = objectMapper.readTree(mockMvc.perform(get("/api/security/csrf").cookie(login.cookie()))
                .andReturn().getResponse().getContentAsString()).path("data").path("token").asText();
        MvcResult source = mockMvc.perform(post("/api/ai/knowledge/sources").cookie(login.cookie())
                        .header("Origin", "http://localhost:5173").header("X-CSRF-Token", csrf)
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated()).andReturn();
        String sourceId = objectMapper.readTree(source.getResponse().getContentAsString()).path("data").path("id").asText();
        controls.disable(AiRuntimeControlService.Scope.SOURCE, sourceId, "安全测试关闭知识来源");

        mockMvc.perform(get("/api/ai/knowledge/sources/{id}", sourceId).header("Authorization", login.token()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/ai/knowledge/sources/{id}/uploads", sourceId)
                        .header("Authorization", login.token()).header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mimeType\":\"text/plain\",\"sizeBytes\":1,\"sha256\":\""
                                + "a".repeat(64) + "\"}"))
                .andExpect(status().isServiceUnavailable());
    }

    private String createSource(String token, String visibility, String classification,
                                java.util.List<String> permissions) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/ai/knowledge/sources")
                        .header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "知识源", "classification",
                                classification, "matchMode", "ANY", "permissions", permissions))))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asText();
    }

    private JsonNode createUpload(String token, String sourceId, byte[] content) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/ai/knowledge/sources/{id}/uploads", sourceId)
                        .header("Authorization", token).header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of(
                                "mimeType", "text/plain", "sizeBytes", content.length,
                                "sha256", sha256(content)))))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString()).path("data");
    }

    private String readyVersion(String token, String sourceId, String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        MvcResult created = mockMvc.perform(post("/api/ai/knowledge/sources/{id}/uploads", sourceId)
                        .header("Authorization", token).header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of(
                                "mimeType", "text/plain", "sizeBytes", bytes.length, "sha256", sha256(bytes)))))
                .andReturn();
        JsonNode upload = objectMapper.readTree(created.getResponse().getContentAsString()).path("data");
        mockMvc.perform(put(upload.path("uploadTarget").asText()).header("Authorization", token)
                .contentType(MediaType.TEXT_PLAIN).content(bytes)).andExpect(status().isNoContent());
        mockMvc.perform(post("/api/ai/knowledge/uploads/{id}/finalize", upload.path("id").asText())
                .header("Authorization", token).header("Idempotency-Key", UUID.randomUUID())).andExpect(status().isAccepted());
        MvcResult version = mockMvc.perform(post("/api/ai/knowledge/sources/{id}/versions", sourceId)
                        .header("Authorization", token).header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of(
                                "uploadSessionId", upload.path("id").asText(), "externalKey", "public-policy",
                                "title", "公共制度", "version", "v1"))))
                .andExpect(status().isAccepted()).andReturn();
        JsonNode scheduled = objectMapper.readTree(version.getResponse().getContentAsString()).path("data");
        ingestion.processNext("public-api-worker").orElseThrow();
        return scheduled.path("versionId").asText();
    }

    private long createReviewer() {
        jdbc.update("INSERT INTO sys_user(username,password_hash,display_name,role_code,enabled,created_at,updated_at) "
                + "VALUES('knowledge_reviewer', ?, '知识治理员', 'ADMIN', TRUE, CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("review-password-123"));
        Long userId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username='knowledge_reviewer'", Long.class);
        Long adminRole = jdbc.queryForObject("SELECT id FROM sys_role WHERE code='ADMIN'", Long.class);
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)", userId, adminRole);
        return userId;
    }

    private String issueProof(Login login, String resource, String requestHash) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/security/step-up").header("Authorization", login.token())
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of(
                                "password", "review-password-123", "actionCode", "KNOWLEDGE_PUBLIC_APPROVE",
                                "resourcePublicId", resource, "requestHash", requestHash))))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("proof").asText();
    }

    private Login login() throws Exception { return login("admin", "test-password-123"); }
    private Login login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk()).andReturn();
        Cookie cookie = result.getResponse().getCookie("Authorization");
        assertNotNull(cookie);
        return new Login(cookie.getValue(), cookie);
    }
    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private record Login(String token, Cookie cookie) { }
}
