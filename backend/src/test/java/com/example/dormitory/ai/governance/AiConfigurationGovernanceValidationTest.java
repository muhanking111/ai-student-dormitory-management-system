package com.example.dormitory.ai.governance;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.application.run.AiActorContext;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiOutboxRepository;
import com.example.dormitory.ai.infrastructure.runtime.AiRuntimeAuditWriter;
import com.example.dormitory.ai.security.PiiClassificationService;
import com.example.dormitory.ai.security.PiiRedactionService;
import com.example.dormitory.ai.security.SensitiveDataBlockedException;
import com.example.dormitory.ai.tool.ToolCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class AiConfigurationGovernanceValidationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiConfigurationGovernanceService service = new AiConfigurationGovernanceService(
            mock(JdbcTemplate.class), objectMapper,
            new StandardToolCatalogManifest(ToolCatalog.standard(), objectMapper),
            mock(JdbcAiOutboxRepository.class), mock(AiRuntimeAuditWriter.class), classification());

    @Test
    void promptListAndResourceLookupRejectEveryInvalidPublicArgument() {
        for (long[] page : List.of(new long[]{0, 10}, new long[]{1, 0}, new long[]{1, 101})) {
            assertThrows(IllegalArgumentException.class,
                    () -> service.listPrompts(null, null, page[0], page[1]));
        }
        assertThrows(IllegalArgumentException.class,
                () -> service.listPrompts("dynamic.system", null, 1, 10));
        assertThrows(IllegalArgumentException.class,
                () -> service.listPrompts(null, "DELETED", 1, 10));
        for (String id : Arrays.asList(null, "", "not-a-uuid")) {
            assertThrows(IllegalArgumentException.class, () -> service.requirePrompt(id));
            assertThrows(IllegalArgumentException.class,
                    () -> service.activatePrompt(actor(), id, null, "a".repeat(64)));
        }
    }

    @Test
    void promptDraftRejectsUnregisteredKeysVersionsSchemasContentAndSecrets() {
        for (String key : Arrays.asList(null, "", "dynamic.system")) {
            assertThrows(IllegalArgumentException.class,
                    () -> service.createPromptDraft(actor(), key, "v1", "safe prompt",
                            "text.v1", "a".repeat(64)));
        }
        for (String version : Arrays.asList(null, "", "1", "v0", "v1234567890")) {
            assertThrows(IllegalArgumentException.class,
                    () -> service.createPromptDraft(actor(), "assistant.system", version, "safe prompt",
                            "text.v1", "a".repeat(64)));
        }
        for (String content : Arrays.asList(null, "", "x".repeat(100_001), "safe\u0000prompt",
                "use sk-abcdefghijklmnop", "bearer abcdefghijklmnopqrst")) {
            assertThrows(IllegalArgumentException.class,
                    () -> service.createPromptDraft(actor(), "assistant.system", "v1", content,
                            "text.v1", "a".repeat(64)));
        }
        for (String schema : Arrays.asList(null, "", "v1", "1text.v1", "text.v0")) {
            assertThrows(IllegalArgumentException.class,
                    () -> service.createPromptDraft(actor(), "assistant.system", "v1", "safe prompt",
                            schema, "a".repeat(64)));
        }
        assertThrows(SensitiveDataBlockedException.class,
                () -> service.createPromptDraft(actor(), "assistant.system", "v1",
                        "联系电话 13800138000", "text.v1", "a".repeat(64)));
    }

    @Test
    void aliasToolCatalogAndDeterministicIdsNormalizeOrRejectUnsafeValues() {
        assertEquals(AiConfigurationGovernanceService.promptKeyAggregateId("assistant.system"),
                AiConfigurationGovernanceService.promptKeyAggregateId(" ASSISTANT.SYSTEM "));
        assertEquals(AiConfigurationGovernanceService.aliasPublicId("approved-chat"),
                AiConfigurationGovernanceService.aliasPublicId(" APPROVED-CHAT "));
        for (String key : Arrays.asList(null, "", "unknown.system")) {
            assertThrows(IllegalArgumentException.class,
                    () -> AiConfigurationGovernanceService.promptKeyAggregateId(key));
        }
        for (String alias : Arrays.asList(null, "", "x", "bad alias", "x".repeat(65))) {
            assertThrows(IllegalArgumentException.class,
                    () -> AiConfigurationGovernanceService.aliasPublicId(alias));
            assertThrows(IllegalArgumentException.class,
                    () -> service.activateModelAlias(actor(), alias, java.util.UUID.randomUUID().toString(),
                            0, "a".repeat(64)));
        }
        assertThrows(IllegalArgumentException.class, () -> service.activateModelAlias(
                actor(), "approved-chat", java.util.UUID.randomUUID().toString(), -1, "a".repeat(64)));

        StandardToolCatalogManifest standard =
                new StandardToolCatalogManifest(ToolCatalog.standard(), objectMapper);
        AiApiException wrongVersion = assertThrows(AiApiException.class,
                () -> service.activateToolCatalog(actor(),
                        AiConfigurationGovernanceService.toolCatalogPublicId(standard.hash()),
                        "v2", standard.hash(), null, "a".repeat(64)));
        assertEquals("AI_TOOL_CATALOG_NOT_ALLOWLISTED", wrongVersion.errorCode());
        AiApiException wrongHash = assertThrows(AiApiException.class,
                () -> service.activateToolCatalog(actor(),
                        AiConfigurationGovernanceService.toolCatalogPublicId("0".repeat(64)),
                        standard.version(), "0".repeat(64), null, "a".repeat(64)));
        assertEquals("AI_TOOL_CATALOG_NOT_ALLOWLISTED", wrongHash.errorCode());
    }

    private AiActorContext actor() {
        return new AiActorContext(7L, "token", "fingerprint", 1, "digest",
                List.of("ADMIN"), List.of("ai:config:manage"), ActorDescriptor.user(7L));
    }

    private PiiClassificationService classification() {
        PiiRedactionService redaction = new PiiRedactionService(
                "governance-validation-key-32-bytes".getBytes(StandardCharsets.UTF_8), "v1");
        return new PiiClassificationService(redaction, List::of);
    }
}
