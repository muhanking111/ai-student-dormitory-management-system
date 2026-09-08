package com.example.dormitory.ai.governance;

import com.example.dormitory.ai.approval.CanonicalJsonHasher;
import com.example.dormitory.ai.config.AiRuntimeControlService;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        "spring.datasource.url=jdbc:h2:mem:ai-control-plane-api;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "dormitory.ai.enabled=true",
        "dormitory.ai.capabilities.knowledge=true",
        "dormitory.ai.enabled=true",
        "dormitory.ai.capabilities.evaluation=true",
        "dormitory.ai.provider.active=fake",
        "dormitory.ai.audit.hmac-key=0123456789abcdef0123456789abcdef",
        "dormitory.ai.tokenization.hmac-key=fedcba9876543210fedcba9876543210",
        "dormitory.ai.step-up.hmac-key=abcdef0123456789abcdef0123456789"
})
class AiControlPlaneApiTest {

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
    void reset() {
        for (String table : new String[]{
                "ai_eval_result", "ai_eval_run", "ai_usage_ledger", "ai_budget_reservation",
                "ai_budget_bucket", "ai_quota_policy", "ai_citation", "ai_document_chunk",
                "ai_document_version", "ai_document", "ai_knowledge_source_permission",
                "ai_knowledge_source", "ai_model_alias", "ai_model_deployment",
                "ai_tool_catalog_version", "ai_prompt_version", "ai_outbox_event",
                "ai_idempotency_record", "ai_step_up_grant", "ai_audit_event", "ai_audit_chain_head",
                "ai_runtime_switch"
        }) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        Long viewerRole = jdbcTemplate.queryForObject("SELECT id FROM sys_role WHERE code='VIEWER'", Long.class);
        jdbcTemplate.update("DELETE FROM sys_role_permission WHERE role_id=? AND permission_id IN "
                        + "(SELECT id FROM sys_permission WHERE code IN "
                        + "('ai:knowledge:read','system:role:write','ai:eval:run','ai:dashboard:query'))",
                viewerRole);
    }

    @Test
    void promptDraftIsScopedIdempotentAndActivationUsesStepUpAndActiveSlotCas() throws Exception {
        String token = login("admin", "test-password-123");
        String key = UUID.randomUUID().toString();
        Map<String, Object> v101 = promptBody("v101", "只返回经过授权、带来源的纯文本回答。\n");

        MvcResult created = mockMvc.perform(post("/api/ai/prompts")
                        .header("Authorization", token)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(v101)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.contentHash").value(org.hamcrest.Matchers.matchesPattern("[0-9a-f]{64}")))
                .andReturn();
        JsonNode first = body(created);
        String promptId = first.path("id").asText();
        assertTrue(created.getResponse().getHeader("Location").endsWith("/api/ai/prompts/" + promptId));
        mockMvc.perform(get("/api/ai/prompts/{id}", promptId).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(promptId));

        mockMvc.perform(post("/api/ai/prompts")
                        .header("Authorization", token).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(v101)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").value(promptId));
        Map<String, Object> changed = promptBody("v101", "不同正文");
        mockMvc.perform(post("/api/ai/prompts")
                        .header("Authorization", token).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(changed)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("AI_IDEMPOTENCY_PAYLOAD_MISMATCH"));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_prompt_version"));

        String activationBody = "{\"expectedActiveId\":null}";
        String activationHash = hash(Map.of("promptId", promptId, "expectedActiveId", ""));
        String proof = issueProof(token, "PROMPT_ACTIVATE", promptId, activationHash);
        String activationKey = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/ai/prompts/{id}/activate", promptId)
                        .header("Authorization", token).header("Idempotency-Key", activationKey)
                        .header("X-Step-Up-Proof", proof)
                        .contentType(MediaType.APPLICATION_JSON).content(activationBody))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/ai/prompts/{id}/activate", promptId)
                        .header("Authorization", token).header("Idempotency-Key", activationKey)
                        .header("X-Step-Up-Proof", proof)
                        .contentType(MediaType.APPLICATION_JSON).content(activationBody))
                .andExpect(status().isNoContent());
        assertEquals("ACTIVE", jdbcTemplate.queryForObject(
                "SELECT status FROM ai_prompt_version WHERE content_hash=?", String.class,
                first.path("contentHash").asText()));
        assertEquals("assistant.system", jdbcTemplate.queryForObject(
                "SELECT active_slot_key FROM ai_prompt_version WHERE content_hash=?", String.class,
                first.path("contentHash").asText()));

        String secondId = createPrompt(token, UUID.randomUUID().toString(), "v102", "第二版受控回答。\n");
        String secondBody = objectMapper.writeValueAsString(Map.of("expectedActiveId", promptId));
        String secondHash = hash(Map.of("promptId", secondId, "expectedActiveId", promptId));
        mockMvc.perform(post("/api/ai/prompts/{id}/activate", secondId)
                        .header("Authorization", token).header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-Step-Up-Proof", issueProof(token, "PROMPT_ACTIVATE", secondId, secondHash))
                        .contentType(MediaType.APPLICATION_JSON).content(secondBody))
                .andExpect(status().isNoContent());

        String staleHash = hash(Map.of("promptId", promptId, "expectedActiveId", promptId));
        mockMvc.perform(post("/api/ai/prompts/{id}/activate", promptId)
                        .header("Authorization", token).header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-Step-Up-Proof", issueProof(token, "PROMPT_ACTIVATE", promptId, staleHash))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("expectedActiveId", promptId))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("AI_CONFIG_VERSION_CONFLICT"));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_prompt_version WHERE active_slot_key='assistant.system'"));
    }

    @Test
    void promptDraftRejectsSecretsAndKnownPersonalNamesBeforePersistence() throws Exception {
        String token = login("admin", "test-password-123");
        String knownDisplayName = jdbcTemplate.queryForObject(
                "SELECT display_name FROM sys_user WHERE username='admin'", String.class);

        for (String content : java.util.List.of(
                "数据库 password=SuperSecret!，请连接后回答。",
                "供应商 api_key=vendor-secret-value，请直接调用。",
                "请围绕" + knownDisplayName + "的个人记录生成系统提示词。")) {
            mockMvc.perform(post("/api/ai/prompts")
                            .header("Authorization", token)
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(promptBody("v701", content))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.data.errorCode").value("AI_SENSITIVE_DATA_BLOCKED"));
        }
        assertEquals(0, count("SELECT COUNT(*) FROM ai_prompt_version"));
    }

    @Test
    void promptActivationRecomputesTheLockedContentHashBeforeChangingTheActiveSlot() throws Exception {
        String token = login("admin", "test-password-123");
        String promptId = createPrompt(
                token, UUID.randomUUID().toString(), "v801", "只返回经过授权的纯文本。\n");
        jdbcTemplate.update("UPDATE ai_prompt_version SET content=? WHERE version='v801'",
                "草稿正文已在创建后被替换。\n");
        String activationHash = hash(Map.of("promptId", promptId, "expectedActiveId", ""));

        mockMvc.perform(post("/api/ai/prompts/{id}/activate", promptId)
                        .header("Authorization", token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-Step-Up-Proof", issueProof(
                                token, "PROMPT_ACTIVATE", promptId, activationHash))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedActiveId\":null}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("AI_PROMPT_CONTENT_HASH_CONFLICT"));
        assertEquals("DRAFT", jdbcTemplate.queryForObject(
                "SELECT status FROM ai_prompt_version WHERE version='v801'", String.class));
    }

    @Test
    void promptActivationReappliesTheCurrentDlpPolicyToTheLockedContent() throws Exception {
        String token = login("admin", "test-password-123");
        createPrompt(token, UUID.randomUUID().toString(), "v802", "只返回经过授权的纯文本。\n");
        String sensitive = "数据库 password=SuperSecret!，请连接后回答。\n";
        String sensitiveHash = CanonicalJsonHasher.sha256(sensitive);
        jdbcTemplate.update("UPDATE ai_prompt_version SET content=?,content_hash=? WHERE version='v802'",
                sensitive, sensitiveHash);
        String tamperedId = AiConfigurationGovernanceService.promptPublicId(
                "assistant.system", "v802", sensitiveHash);
        String activationHash = hash(Map.of("promptId", tamperedId, "expectedActiveId", ""));

        mockMvc.perform(post("/api/ai/prompts/{id}/activate", tamperedId)
                        .header("Authorization", token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-Step-Up-Proof", issueProof(
                                token, "PROMPT_ACTIVATE", tamperedId, activationHash))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedActiveId\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("AI_SENSITIVE_DATA_BLOCKED"));
        assertEquals("DRAFT", jdbcTemplate.queryForObject(
                "SELECT status FROM ai_prompt_version WHERE version='v802'", String.class));
    }

    @Test
    void modelAliasRequiresApprovedDeploymentAndNumericCas() throws Exception {
        String token = login("admin", "test-password-123");
        String deploymentA = insertDeployment("model-a", true, true);
        String deploymentB = insertDeployment("model-b", true, true);
        String body = objectMapper.writeValueAsString(Map.of("deploymentId", deploymentA, "version", 0));
        String requestHash = hash(Map.of("alias", "assistant-primary", "deploymentId", deploymentA,
                "expectedVersion", 0));
        String aliasResourceId = AiConfigurationGovernanceService.aliasPublicId("assistant-primary");
        mockMvc.perform(post("/api/ai/model-aliases/{alias}/activate", "assistant-primary")
                        .header("Authorization", token).header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-Step-Up-Proof", issueProof(token, "MODEL_ALIAS_ACTIVATE", null, requestHash))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/ai/model-aliases/{alias}/activate", "assistant-primary")
                        .header("Authorization", token).header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-Step-Up-Proof", issueProof(
                                token, "MODEL_ALIAS_ACTIVATE", aliasResourceId, requestHash))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT version FROM ai_model_alias WHERE alias_code='assistant-primary'", Long.class));
        assertEquals(deploymentA, jdbcTemplate.queryForObject(
                "SELECT d.public_id FROM ai_model_alias a JOIN ai_model_deployment d "
                        + "ON d.id=a.active_deployment_id WHERE a.alias_code='assistant-primary'", String.class));

        String staleBody = objectMapper.writeValueAsString(Map.of("deploymentId", deploymentB, "version", 0));
        String staleHash = hash(Map.of("alias", "assistant-primary", "deploymentId", deploymentB,
                "expectedVersion", 0));
        mockMvc.perform(post("/api/ai/model-aliases/{alias}/activate", "assistant-primary")
                        .header("Authorization", token).header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-Step-Up-Proof", issueProof(
                                token, "MODEL_ALIAS_ACTIVATE", aliasResourceId, staleHash))
                        .contentType(MediaType.APPLICATION_JSON).content(staleBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("AI_CONFIG_VERSION_CONFLICT"));

        String unapproved = insertDeployment("model-unapproved", false, true);
        String unapprovedBody = objectMapper.writeValueAsString(Map.of("deploymentId", unapproved, "version", 1));
        String unapprovedHash = hash(Map.of("alias", "assistant-primary", "deploymentId", unapproved,
                "expectedVersion", 1));
        mockMvc.perform(post("/api/ai/model-aliases/{alias}/activate", "assistant-primary")
                        .header("Authorization", token).header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-Step-Up-Proof", issueProof(
                                token, "MODEL_ALIAS_ACTIVATE", aliasResourceId, unapprovedHash))
                        .contentType(MediaType.APPLICATION_JSON).content(unapprovedBody))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.data.errorCode").value("AI_MODEL_DEPLOYMENT_NOT_APPROVED"));
    }

    @Test
    void oldActiveCatalogRemainsVisibleForCasUpgradeWithoutRewritingItsManifest() throws Exception {
        String token = login("admin", "test-password-123");
        String oldHash = "a".repeat(64);
        jdbcTemplate.update("INSERT INTO ai_tool_catalog_version "
                        + "(version,manifest_text,manifest_hash,status,active_slot_key,created_at,updated_at) "
                        + "VALUES ('legacy-v1','{}',?,'ACTIVE','runtime',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", oldHash);
        MvcResult listed = mockMvc.perform(get("/api/ai/tool-catalogs").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].active").value(true))
                .andExpect(jsonPath("$.data[0].toolIds.length()").value(0))
                .andExpect(jsonPath("$.data[1].version").value("v2")).andReturn();
        assertEquals(1, count("SELECT COUNT(*) FROM ai_tool_catalog_version"), "GET remains read-only");
        JsonNode target = body(listed).get(1);
        String id = target.path("id").asText();
        String manifestHash = target.path("manifestHash").asText();
        String previous = body(listed).get(0).path("id").asText();
        Map<String, String> request = Map.of("version", "v2", "manifestHash", manifestHash, "expectedActiveId", previous);
        String requestHash = hash(Map.of("catalogId", id, "version", "v2", "manifestHash", manifestHash, "expectedActiveId", previous));
        mockMvc.perform(post("/api/ai/tool-catalogs/{id}/activate", id)
                        .header("Authorization", token).header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-Step-Up-Proof", issueProof(token, "CONFIG_ACTIVATE", id, requestHash))
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());
        assertEquals("{}", jdbcTemplate.queryForObject("SELECT manifest_text FROM ai_tool_catalog_version WHERE version='legacy-v1'", String.class));
        assertEquals("INACTIVE", jdbcTemplate.queryForObject("SELECT status FROM ai_tool_catalog_version WHERE version='legacy-v1'", String.class));
        assertEquals("v2", jdbcTemplate.queryForObject("SELECT version FROM ai_tool_catalog_version WHERE active_slot_key='runtime'", String.class));
        assertEquals(1, count("SELECT COUNT(*) FROM ai_audit_event WHERE event_type='TOOL_CATALOG_ACTIVATED'"));
    }

    @Test
    void toolCatalogActivationAcceptsOnlyTheCanonicalSevenToolManifest() throws Exception {
        String token = login("admin", "test-password-123");
        MvcResult listResult = mockMvc.perform(get("/api/ai/tool-catalogs")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].version").value("v2"))
                .andExpect(jsonPath("$.data[0].toolIds.length()").value(7))
                .andExpect(jsonPath("$.data[0].manifestHash")
                        .value(org.hamcrest.Matchers.matchesPattern("[0-9a-f]{64}")))
                .andReturn();
        assertEquals(0, count("SELECT COUNT(*) FROM ai_tool_catalog_version"),
                "安全 GET 不能通过懒初始化写数据库");
        JsonNode catalog = body(listResult).get(0);
        String id = catalog.path("id").asText();
        String manifestHash = catalog.path("manifestHash").asText();
        String requestBody = "{\"version\":\"v2\",\"manifestHash\":\"" + manifestHash
                + "\",\"expectedActiveId\":null}";
        String requestHash = hash(Map.of("catalogId", id, "version", "v2",
                "manifestHash", manifestHash, "expectedActiveId", ""));

        mockMvc.perform(post("/api/ai/tool-catalogs/{id}/activate", id)
                        .header("Authorization", token).header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-Step-Up-Proof", issueProof(token, "CONFIG_ACTIVATE", null, requestHash))
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/ai/tool-catalogs/{id}/activate", id)
                        .header("Authorization", token).header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-Step-Up-Proof", issueProof(token, "CONFIG_ACTIVATE", id, requestHash))
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().isNoContent());
        assertEquals(1, count("SELECT COUNT(*) FROM ai_tool_catalog_version WHERE active_slot_key='runtime'"));
        String manifest = jdbcTemplate.queryForObject(
                "SELECT manifest_text FROM ai_tool_catalog_version WHERE active_slot_key='runtime'", String.class);
        assertNotNull(manifest);
        for (String tool : new String[]{"knowledge.search.v1", "dashboard.query_metric.v1",
                "repair.get_context.v1", "dormitory.get_capacity_summary.v1", "notice.list_published.v1",
                "repair.propose_assignment.v1", "notice.propose_draft.v1"}) {
            assertTrue(manifest.contains(tool), tool);
        }
        assertTrue(!manifest.toLowerCase().contains("shell") && !manifest.toLowerCase().contains("http://"));
    }

    @Test
    void evalRunReservesIndependentBudgetAndFinishesAsServiceActorWithoutProviderClaims() throws Exception {
        String token = login("admin", "test-password-123");
        String promptId = createPrompt(token, UUID.randomUUID().toString(), "v201", "评测提示词。\n");
        String deploymentId = insertDeployment("offline-eval-model", true, true);
        insertEvaluationBudget(20);
        String key = UUID.randomUUID().toString();
        Map<String, Object> request = Map.of(
                "suiteName", "dashboard-intent",
                "datasetVersion", "dashboard-intent-v1",
                "promptId", promptId,
                "modelDeploymentId", deploymentId,
                "codeRevision", "local-test-revision");
        String json = objectMapper.writeValueAsString(request);

        MvcResult accepted = mockMvc.perform(post("/api/ai/eval-runs")
                        .header("Authorization", token).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.data.id").isNotEmpty())
                .andReturn();
        String id = body(accepted).path("id").asText();
        assertTrue(accepted.getResponse().getHeader("Location").endsWith("/api/ai/eval-runs/" + id));

        JsonNode terminal = awaitEval(token, id);
        assertEquals("PASSED", terminal.path("state").asText());
        assertEquals("DETERMINISTIC_CONTRACT", terminal.path("summary").path("engineType").asText());
        assertEquals(3, terminal.path("summary").path("totalCases").asInt());
        assertEquals("SERVICE", jdbcTemplate.queryForObject(
                "SELECT actor_kind FROM ai_eval_run WHERE public_id=?", String.class, id));
        assertEquals("ai-eval-worker", jdbcTemplate.queryForObject(
                "SELECT service_principal_code FROM ai_eval_run WHERE public_id=?", String.class, id));
        assertEquals("COMMITTED", jdbcTemplate.queryForObject(
                "SELECT state FROM ai_budget_reservation WHERE billing_subject_kind='EVAL' "
                        + "AND billing_subject_public_id=?", String.class, id));
        assertEquals(3, count("SELECT COUNT(*) FROM ai_eval_result r JOIN ai_eval_run e ON e.id=r.eval_run_id "
                + "WHERE e.public_id=?", id));
        assertEquals(0, count("SELECT COUNT(*) FROM ai_usage_ledger WHERE billing_subject_kind='EVAL' "
                + "AND billing_subject_public_id=?", id));

        mockMvc.perform(post("/api/ai/eval-runs")
                        .header("Authorization", token).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.id").value(id));
        Map<String, Object> mismatch = new LinkedHashMap<>(request);
        mismatch.put("codeRevision", "different-revision");
        mockMvc.perform(post("/api/ai/eval-runs")
                        .header("Authorization", token).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(mismatch)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("AI_IDEMPOTENCY_PAYLOAD_MISMATCH"));
    }

    @Test
    void evalRunRequiresCurrentPermissionForItsRegisteredDataset() throws Exception {
        String admin = login("admin", "test-password-123");
        String promptId = createPrompt(admin, UUID.randomUUID().toString(), "v211", "评测权限提示词。\n");
        String deploymentId = insertDeployment("offline-eval-permission", true, true);
        insertEvaluationBudget(20);
        String viewerName = createViewer("eval-viewer", "viewer-password-123");
        grantViewer("ai:eval:run");
        String viewer = login(viewerName, "viewer-password-123");
        Map<String, Object> request = Map.of(
                "suiteName", "dashboard-intent",
                "datasetVersion", "dashboard-intent-v1",
                "promptId", promptId,
                "modelDeploymentId", deploymentId,
                "codeRevision", "dataset-permission-test");
        String json = objectMapper.writeValueAsString(request);

        mockMvc.perform(post("/api/ai/eval-runs")
                        .header("Authorization", viewer).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.errorCode").value("AI_EVAL_DATASET_FORBIDDEN"));

        grantViewer("ai:dashboard:query");
        MvcResult accepted = mockMvc.perform(post("/api/ai/eval-runs")
                        .header("Authorization", viewer).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isAccepted()).andReturn();
        String id = body(accepted).path("id").asText();
        assertEquals("PASSED", awaitEval(viewer, id).path("state").asText());

        revokeViewer("ai:dashboard:query");
        mockMvc.perform(get("/api/ai/eval-runs/{id}", id).header("Authorization", viewer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.errorCode").value("AI_EVAL_DATASET_FORBIDDEN"));
    }

    @Test
    void costAggregationHasFixedDimensionsAndCitationIsReauthorizedAgainstCurrentAcl() throws Exception {
        String admin = login("admin", "test-password-123");
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        insertUsage("ASSISTANT", "fake", "fake-chat", new BigDecimal("0.100000"), now.minusSeconds(60));
        insertUsage("ASSISTANT", "fake", "fake-chat", new BigDecimal("0.200000"), now.minusSeconds(30));
        mockMvc.perform(get("/api/ai/audit/costs")
                        .header("Authorization", admin)
                        .param("from", now.minusSeconds(3600).toString())
                        .param("to", now.plusSeconds(1).toString())
                        .param("groupBy", "CAPABILITY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groupBy").value("CAPABILITY"))
                .andExpect(jsonPath("$.data.rows[0].dimension").value("ASSISTANT"))
                .andExpect(jsonPath("$.data.rows[0].costAmount").value(0.3));
        mockMvc.perform(get("/api/ai/audit/costs")
                        .header("Authorization", admin).param("groupBy", "capability, (select 1)"))
                .andExpect(status().isBadRequest());

        String citationId = insertKnowledgeCitation("system:role:write");
        String viewerName = createViewer("citation-viewer", "viewer-password-123");
        grantViewer("ai:knowledge:read");
        String viewer = login(viewerName, "viewer-password-123");
        mockMvc.perform(get("/api/ai/citations/{id}", citationId).header("Authorization", viewer))
                .andExpect(status().isNotFound());
        assertEquals(1, count("SELECT COUNT(*) FROM ai_audit_event WHERE aggregate_public_id=? "
                + "AND event_type='CITATION_READ_DENIED'", citationId));

        grantViewer("system:role:write");
        mockMvc.perform(get("/api/ai/citations/{id}", citationId).header("Authorization", viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(citationId))
                .andExpect(jsonPath("$.data.quote").value("允许展示的脱敏片段"))
                .andExpect(jsonPath("$.data.sourceId").isNotEmpty())
                .andExpect(jsonPath("$.data.documentVersionId").isNotEmpty());
        assertEquals(1, count("SELECT COUNT(*) FROM ai_audit_event WHERE aggregate_public_id=? "
                + "AND event_type='CITATION_READ_GRANTED'", citationId));

        String sourceId = jdbcTemplate.queryForObject("SELECT s.public_id FROM ai_knowledge_source s "
                + "JOIN ai_document d ON d.source_id=s.id JOIN ai_document_version v ON v.document_id=d.id "
                + "JOIN ai_citation c ON c.document_version_id=v.id WHERE c.public_id=?", String.class, citationId);
        controls.disable(AiRuntimeControlService.Scope.SOURCE, sourceId, "citation 安全测试关闭来源");
        String killed = mockMvc.perform(get("/api/ai/citations/{id}", citationId)
                        .header("Authorization", viewer))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertTrue(!killed.contains("允许展示的脱敏片段"), "Kill Switch 拒绝不得泄露 citation 正文");
        assertEquals(2, count("SELECT COUNT(*) FROM ai_audit_event WHERE aggregate_public_id=? "
                + "AND event_type='CITATION_READ_DENIED'", citationId));

        revokeViewer("system:role:write");
        mockMvc.perform(get("/api/ai/citations/{id}", citationId).header("Authorization", viewer))
                .andExpect(status().isNotFound());
    }

    private Map<String, Object> promptBody(String version, String content) {
        return Map.of("promptKey", "assistant.system", "version", version,
                "content", content, "responseSchemaVersion", "text.v1");
    }

    private String createPrompt(String token, String key, String version, String content) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/ai/prompts")
                        .header("Authorization", token).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(promptBody(version, content))))
                .andExpect(status().isCreated()).andReturn();
        return body(result).path("id").asText();
    }

    private String insertDeployment(String code, boolean contractApproved, boolean dataPolicyApproved) throws Exception {
        String id = UUID.randomUUID().toString();
        String capabilities = objectMapper.writeValueAsString(Map.of(
                "chat", true,
                "streaming", true,
                "structuredOutput", true,
                "toolCalling", false,
                "usage", true,
                "contractTestPassed", contractApproved,
                "dataPolicyApproved", dataPolicyApproved));
        jdbcTemplate.update("INSERT INTO ai_model_deployment "
                        + "(public_id,code,provider_code,model_name,endpoint_alias,capabilities_text,data_region,"
                        + "config_version,enabled,created_at,updated_at) VALUES (?,?,?,'deterministic-contract',?,?,'LOCAL',1,TRUE,"
                        + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                id, code, "fake", code + "-endpoint", capabilities);
        return id;
    }

    private void insertEvaluationBudget(long tokenLimit) {
        Instant now = Instant.now();
        jdbcTemplate.update("INSERT INTO ai_quota_policy "
                        + "(scope_type,scope_key,capability,daily_token_limit,monthly_cost_limit,concurrent_run_limit,"
                        + "status,effective_from,created_at,updated_at) VALUES "
                        + "('SERVICE','ai-eval-worker','EVALUATION',?,0,1,'ACTIVE',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                tokenLimit, Timestamp.from(now.minusSeconds(60)));
        long policyId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM ai_quota_policy", Long.class);
        jdbcTemplate.update("INSERT INTO ai_budget_bucket "
                        + "(quota_policy_id,scope_type,scope_key,capability,provider_code,period_type,period_start,period_end,"
                        + "token_limit,cost_limit,reserved_tokens,committed_tokens,reserved_cost,committed_cost,currency,version,"
                        + "created_at,updated_at) VALUES (?,'SERVICE','ai-eval-worker','EVALUATION','offline','DAILY',?,?,?,0,0,0,0,0,"
                        + "'CNY',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                policyId, Timestamp.from(now.minusSeconds(60)), Timestamp.from(now.plusSeconds(3600)), tokenLimit);
    }

    private JsonNode awaitEval(String token, String id) throws Exception {
        JsonNode value = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            MvcResult result = mockMvc.perform(get("/api/ai/eval-runs/{id}", id)
                            .header("Authorization", token))
                    .andExpect(status().isOk()).andReturn();
            value = body(result);
            if (value.path("state").asText().matches("PASSED|FAILED|CANCELLED")) return value;
            Thread.sleep(20);
        }
        throw new AssertionError("eval run 未进入终态: " + value);
    }

    private void insertUsage(String capability, String provider, String model, BigDecimal cost, Instant occurredAt) {
        jdbcTemplate.update("INSERT INTO ai_usage_ledger "
                        + "(billing_subject_kind,billing_subject_public_id,request_sequence_no,attempt_no,request_kind,"
                        + "actor_kind,service_principal_code,capability,provider_code,model_name,input_tokens,output_tokens,"
                        + "cost_amount,currency,usage_source,occurred_at,created_at) VALUES "
                        + "('RUN',?,1,1,'CHAT','SERVICE','cost-test',?,?,?,10,5,?,'CNY','ESTIMATED',?,CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(), capability, provider, model, cost, Timestamp.from(occurredAt));
    }

    private String insertKnowledgeCitation(String permission) {
        long adminId = adminId();
        String sourceId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO ai_knowledge_source "
                        + "(public_id,name,source_type,owner_user_id,classification,permission_match_mode,object_store_code,"
                        + "acl_version,status,created_operator_user_id,updated_operator_user_id,created_at,updated_at) "
                        + "VALUES (?,'测试知识','TEXT',?,'L1','ALL','local',1,'ACTIVE',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                sourceId, adminId, adminId, adminId);
        long sourceDbId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_knowledge_source WHERE public_id=?", Long.class, sourceId);
        jdbcTemplate.update("INSERT INTO ai_knowledge_source_permission "
                        + "(source_id,permission_code,created_at,updated_at) VALUES (?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                sourceDbId, permission);
        String documentId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO ai_document "
                        + "(public_id,source_id,external_key_hmac,external_key_key_version,title,status,created_at,updated_at) "
                        + "VALUES (?,?,?,1,'测试文档','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                documentId, sourceDbId, "a".repeat(64));
        long documentDbId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_document WHERE public_id=?", Long.class, documentId);
        String versionId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO ai_document_version "
                        + "(public_id,document_id,version,content_hash,visibility,object_key,object_version_id,object_etag,"
                        + "mime_type,size_bytes,parser_version,chunk_policy_version,status,activated_at,created_at,updated_at) "
                        + "VALUES (?,?,'v1',?,'EXPLICIT_ACL','objects/test','obj-v1','etag-v1','text/plain',20,'text-v1',"
                        + "'chunk-v1','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                versionId, documentDbId, "b".repeat(64));
        long versionDbId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_document_version WHERE public_id=?", Long.class, versionId);
        jdbcTemplate.update("UPDATE ai_document SET current_version_id=? WHERE id=?", versionDbId, documentDbId);
        String chunkId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO ai_document_chunk "
                        + "(public_id,document_version_id,chunk_no,content_redacted,content_hash,locator_text,metadata_text,status,"
                        + "created_at,updated_at) VALUES (?,?,0,'允许展示的脱敏片段',?,'第 1 段','{}','READY',"
                        + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", chunkId, versionDbId, "c".repeat(64));
        long chunkDbId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_document_chunk WHERE public_id=?", Long.class, chunkId);
        String citationId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO ai_citation "
                        + "(public_id,run_id,message_id,citation_type,document_version_id,chunk_id,rank_no,score,"
                        + "quote_redacted,locator_text,content_hash,created_at) "
                        + "VALUES (?,0,0,'KNOWLEDGE',?,?,1,0.9,'允许展示的脱敏片段','第 1 段',?,CURRENT_TIMESTAMP)",
                citationId, versionDbId, chunkDbId, "c".repeat(64));
        return citationId;
    }

    private String issueProof(String token, String actionCode, String resourcePublicId, String requestHash)
            throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("password", "test-password-123");
        request.put("actionCode", actionCode);
        request.put("resourcePublicId", resourcePublicId);
        request.put("requestHash", requestHash);
        MvcResult result = mockMvc.perform(post("/api/security/step-up")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk()).andReturn();
        return body(result).path("proof").asText();
    }

    private String hash(Map<String, ?> values) throws Exception {
        return CanonicalJsonHasher.sha256(CanonicalJsonHasher.canonicalize(
                objectMapper.writeValueAsString(values)));
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private String createViewer(String prefix, String password) {
        String username = prefix + "-" + UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO sys_user "
                        + "(username,password_hash,display_name,role_code,enabled,deleted,created_at,updated_at) "
                        + "VALUES (?,?,?,'VIEWER',TRUE,FALSE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                username, passwordEncoder.encode(password), username);
        long userId = jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username=?", Long.class, username);
        long roleId = jdbcTemplate.queryForObject("SELECT id FROM sys_role WHERE code='VIEWER'", Long.class);
        jdbcTemplate.update("INSERT INTO sys_user_role(user_id,role_id,created_at,updated_at) "
                + "VALUES (?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", userId, roleId);
        return username;
    }

    private void grantViewer(String permission) {
        long roleId = jdbcTemplate.queryForObject("SELECT id FROM sys_role WHERE code='VIEWER'", Long.class);
        jdbcTemplate.update("INSERT INTO sys_role_permission(role_id,permission_id,created_at,updated_at) "
                        + "SELECT ?,id,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM sys_permission WHERE code=? "
                        + "AND NOT EXISTS (SELECT 1 FROM sys_role_permission WHERE role_id=? AND permission_id=id)",
                roleId, permission, roleId);
    }

    private void revokeViewer(String permission) {
        long roleId = jdbcTemplate.queryForObject("SELECT id FROM sys_role WHERE code='VIEWER'", Long.class);
        jdbcTemplate.update("DELETE FROM sys_role_permission WHERE role_id=? "
                + "AND permission_id=(SELECT id FROM sys_permission WHERE code=?)", roleId, permission);
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk()).andReturn();
        Cookie cookie = result.getResponse().getCookie("Authorization");
        assertNotNull(cookie);
        return cookie.getValue();
    }

    private long adminId() {
        Long value = jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username='admin'", Long.class);
        assertNotNull(value);
        return value;
    }

    private int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
}
