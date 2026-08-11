package com.example.dormitory.ai.governance;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.application.run.AiActorContext;
import com.example.dormitory.ai.approval.CanonicalJsonHasher;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.security.SensitiveDataBlockedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ActiveProfiles("test")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ai-governance-edges;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "dormitory.ai.audit.hmac-key=0123456789abcdef0123456789abcdef",
        "dormitory.ai.tokenization.hmac-key=fedcba9876543210fedcba9876543210"
})
class AiConfigurationGovernanceServiceEdgeCasesTest {

    @Autowired
    private AiConfigurationGovernanceService service;

    @Autowired
    private StandardToolCatalogManifest standardCatalog;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    @BeforeEach
    void reset() {
        for (String table : new String[] {
                "ai_outbox_event", "ai_model_alias", "ai_model_deployment", "ai_prompt_version",
                "ai_tool_catalog_version", "ai_audit_anchor", "ai_audit_event", "ai_audit_chain_head"
        }) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void promptLifecycleRejectsDuplicateReplayStaleSlotTamperingAndSensitiveActivation() {
        AiActorContext actor = actor();
        assertApiCode("AI_RESOURCE_NOT_FOUND",
                () -> service.requirePrompt(UUID.randomUUID().toString()));
        assertApiCode("AI_RESOURCE_NOT_FOUND",
                () -> service.activatePrompt(actor, UUID.randomUUID().toString(), null, hash("missing")));

        AiConfigurationGovernanceService.PromptView baseline = service.createPromptDraft(
                actor, " ASSISTANT.SYSTEM ", "V101", "baseline\r\ncontent", "TEXT.V1", hash("create-1"));
        assertEquals("baseline\ncontent\n", baseline.content());
        assertApiCode("AI_PROMPT_VERSION_CONFLICT", () -> service.createPromptDraft(
                actor, "assistant.system", "v101", "other", "text.v1", hash("duplicate")));
        assertEquals(1, service.listPrompts(" ASSISTANT.SYSTEM ", " draft ", 1, 10).total());

        service.activatePrompt(actor, baseline.id(), " ", hash("activate-baseline"));
        AiConfigurationGovernanceService.PromptView active = service.requirePrompt(baseline.id());
        assertTrue(active.active());
        assertApiCode("AI_CONFIG_STATE_CONFLICT",
                () -> service.activatePrompt(actor, baseline.id(), baseline.id(), hash("active-replay")));

        AiConfigurationGovernanceService.PromptView candidate = service.createPromptDraft(
                actor, "assistant.system", "v102", "candidate", "text.v1", hash("create-2"));
        assertApiCode("AI_CONFIG_VERSION_CONFLICT", () -> service.activatePrompt(
                actor, candidate.id(), UUID.randomUUID().toString(), hash("stale-slot")));

        jdbc.update("UPDATE ai_prompt_version SET content='tampered' WHERE version='v102'");
        assertApiCode("AI_PROMPT_CONTENT_HASH_CONFLICT",
                () -> service.activatePrompt(actor, candidate.id(), baseline.id(), hash("tampered")));

        String sensitive = "联系电话 13800138000\n";
        String sensitiveHash = CanonicalJsonHasher.sha256(sensitive);
        String sensitiveId = AiConfigurationGovernanceService.promptPublicId(
                "assistant.system", "v102", sensitiveHash);
        jdbc.update("UPDATE ai_prompt_version SET content=?,content_hash=? WHERE version='v102'",
                sensitive, sensitiveHash);
        assertThrows(SensitiveDataBlockedException.class,
                () -> service.activatePrompt(actor, sensitiveId, baseline.id(), hash("sensitive")));

        String safe = "safe candidate\n";
        String safeHash = CanonicalJsonHasher.sha256(safe);
        String retiredId = AiConfigurationGovernanceService.promptPublicId(
                "assistant.system", "v102", safeHash);
        jdbc.update("UPDATE ai_prompt_version SET content=?,content_hash=?,status='RETIRED' WHERE version='v102'",
                safe, safeHash);
        assertApiCode("AI_CONFIG_STATE_CONFLICT",
                () -> service.activatePrompt(actor, retiredId, baseline.id(), hash("retired")));
    }

    @Test
    void modelAliasRequiresApprovedDeploymentAndExactCasVersion() throws Exception {
        AiActorContext actor = actor();
        assertApiCode("AI_RESOURCE_NOT_FOUND", () -> service.activateModelAlias(
                actor, "assistant-primary", UUID.randomUUID().toString(), 0, hash("missing")));

        String disabled = insertDeployment("disabled", "disabled-endpoint", approvedCapabilities(), false);
        String brokenJson = insertDeployment("broken-json", "broken-endpoint", "not-json", true);
        String contractRejected = insertDeployment("contract-rejected", "contract-endpoint",
                capabilities(false, true), true);
        String policyRejected = insertDeployment("policy-rejected", "policy-endpoint",
                capabilities(true, false), true);
        String badEndpoint = insertDeployment("bad-endpoint", "x", approvedCapabilities(), true);
        for (String deployment : List.of(disabled, brokenJson, contractRejected, policyRejected, badEndpoint)) {
            assertApiCode("AI_MODEL_DEPLOYMENT_NOT_APPROVED", () -> service.activateModelAlias(
                    actor, "alias-" + deployment.substring(0, 8), deployment, 0, hash(deployment)));
        }

        String first = insertDeployment("approved-one", "approved-one-endpoint", approvedCapabilities(), true);
        String second = insertDeployment("approved-two", "approved-two-endpoint", approvedCapabilities(), true);
        assertApiCode("AI_CONFIG_VERSION_CONFLICT", () -> service.activateModelAlias(
                actor, "assistant-primary", first, 1, hash("wrong-create-version")));
        service.activateModelAlias(actor, "assistant-primary", first, 0, hash("create-alias"));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT version FROM ai_model_alias WHERE alias_code='assistant-primary'", Long.class));
        assertApiCode("AI_CONFIG_STATE_CONFLICT", () -> service.activateModelAlias(
                actor, "assistant-primary", first, 1, hash("same-deployment")));
        assertApiCode("AI_CONFIG_VERSION_CONFLICT", () -> service.activateModelAlias(
                actor, "assistant-primary", second, 0, hash("stale-version")));
        service.activateModelAlias(actor, "assistant-primary", second, 1, hash("switch-deployment"));
        assertEquals(2L, jdbc.queryForObject(
                "SELECT version FROM ai_model_alias WHERE alias_code='assistant-primary'", Long.class));

        jdbc.update("INSERT INTO ai_model_alias(alias_code,active_deployment_id,version,created_at,updated_at) "
                + "VALUES ('empty-alias',NULL,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        service.activateModelAlias(actor, "empty-alias", first, 0, hash("fill-empty"));
        assertEquals(first, jdbc.queryForObject(
                "SELECT d.public_id FROM ai_model_alias a JOIN ai_model_deployment d "
                        + "ON d.id=a.active_deployment_id WHERE a.alias_code='empty-alias'", String.class));
    }

    @Test
    void standardToolCatalogFallbackActivationReplayAndTamperingFailClosed() {
        AiActorContext actor = actor();
        String publicId = AiConfigurationGovernanceService.toolCatalogPublicId(standardCatalog.hash());
        List<AiConfigurationGovernanceService.ToolCatalogView> fallback = service.listToolCatalogs();
        assertEquals(1, fallback.size());
        assertFalse(fallback.getFirst().active());
        assertEquals(null, fallback.getFirst().createdAt());

        service.activateToolCatalog(actor, publicId, standardCatalog.version(), standardCatalog.hash(),
                null, hash("tool-activate"));
        AiConfigurationGovernanceService.ToolCatalogView persisted = service.listToolCatalogs().getFirst();
        assertTrue(persisted.active());
        assertTrue(persisted.createdAt() != null);
        assertTrue(persisted.activatedAt() != null);

        assertApiCode("AI_CONFIG_STATE_CONFLICT", () -> service.activateToolCatalog(
                actor, publicId, standardCatalog.version(), standardCatalog.hash(), publicId, hash("tool-replay")));
        assertApiCode("AI_CONFIG_VERSION_CONFLICT", () -> service.activateToolCatalog(
                actor, publicId, standardCatalog.version(), standardCatalog.hash(), null, hash("tool-stale")));
        assertApiCode("AI_RESOURCE_NOT_FOUND", () -> service.activateToolCatalog(
                actor, UUID.randomUUID().toString(), standardCatalog.version(), standardCatalog.hash(),
                publicId, hash("tool-missing")));

        jdbc.update("UPDATE ai_tool_catalog_version SET manifest_text='{}',status='INACTIVE',active_slot_key=NULL");
        assertApiCode("AI_TOOL_CATALOG_NOT_ALLOWLISTED", () -> service.activateToolCatalog(
                actor, publicId, standardCatalog.version(), standardCatalog.hash(), null, hash("tool-tamper")));

        jdbc.update("UPDATE ai_tool_catalog_version SET manifest_text=?,status='RETIRED',active_slot_key=NULL",
                standardCatalog.manifest());
        assertApiCode("AI_CONFIG_STATE_CONFLICT", () -> service.activateToolCatalog(
                actor, publicId, standardCatalog.version(), standardCatalog.hash(), null, hash("tool-retired")));
    }

    @Test
    void promptListSupportsNullAndBlankOptionalFilters() {
        assertEquals(0, service.listPrompts(null, null, 1, 10).total());
        assertEquals(0, service.listPrompts(" ", " ", 1, 10).total());
    }

    private String insertDeployment(
            String code,
            String endpoint,
            String capabilities,
            boolean enabled) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ai_model_deployment "
                        + "(public_id,code,provider_code,model_name,endpoint_alias,capabilities_text,data_region,"
                        + "config_version,enabled,created_at,updated_at) VALUES (?,?, 'fake','model',?,?,"
                        + "'LOCAL',1,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                id, code, endpoint, capabilities, enabled);
        return id;
    }

    private String approvedCapabilities() throws Exception {
        return capabilities(true, true);
    }

    private String capabilities(boolean contract, boolean policy) throws Exception {
        return json.writeValueAsString(Map.of(
                "contractTestPassed", contract,
                "dataPolicyApproved", policy));
    }

    private AiActorContext actor() {
        return new AiActorContext(7L, "token", "a".repeat(64), 1, "b".repeat(64),
                List.of("ADMIN"), List.of("ai:config:manage"), ActorDescriptor.user(7L));
    }

    private void assertApiCode(String expected, Executable executable) {
        AiApiException exception = assertThrows(AiApiException.class, executable);
        assertEquals(expected, exception.errorCode());
    }

    private String hash(String value) {
        return CanonicalJsonHasher.sha256(value);
    }
}
