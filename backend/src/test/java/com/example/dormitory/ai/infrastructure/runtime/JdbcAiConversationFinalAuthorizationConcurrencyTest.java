package com.example.dormitory.ai.infrastructure.runtime;

import cn.dev33.satoken.stp.StpUtil;
import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.application.run.AiActorContext;
import com.example.dormitory.ai.application.run.AiRunRecords.CitationCandidate;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.AiCapability;
import com.example.dormitory.ai.domain.model.ModelUsage;
import com.example.dormitory.ai.governance.StandardToolCatalogManifest;
import com.example.dormitory.ai.security.ActorAuthorizationFacade;
import com.example.dormitory.dto.RoleUpdateRequest;
import com.example.dormitory.service.RbacService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:final-citation-auth;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "dormitory.ai.enabled=true",
        "dormitory.ai.capabilities.assistant=true",
        "dormitory.ai.capabilities.knowledge=true",
        "dormitory.ai.provider.active=fake",
        "dormitory.ai.audit.hmac-key=final-citation-auth-audit-key-32-bytes",
        "dormitory.ai.tokenization.hmac-key=final-citation-auth-token-key-32-bytes"
})
class JdbcAiConversationFinalAuthorizationConcurrencyTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private JdbcAiConversationRunStore store;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ActorAuthorizationFacade authorization;
    @Autowired private RbacService rbacService;
    @Autowired private StandardToolCatalogManifest standardCatalog;

    @BeforeAll
    void prepareControlPlane() {
        jdbc.update("UPDATE ai_prompt_version SET status='ACTIVE',active_slot_key=prompt_key,"
                + "activated_at=CURRENT_TIMESTAMP");
        jdbc.update("INSERT INTO ai_tool_catalog_version "
                        + "(version,manifest_text,manifest_hash,status,active_slot_key,activated_at,created_at,updated_at) "
                        + "VALUES (?,?,?,'ACTIVE','runtime',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                standardCatalog.version(), standardCatalog.manifest(), standardCatalog.hash());
        jdbc.update("INSERT INTO ai_quota_policy "
                        + "(scope_type,scope_key,capability,daily_token_limit,monthly_cost_limit,"
                        + "concurrent_run_limit,status,effective_from,created_at,updated_at) "
                        + "VALUES ('GLOBAL','*','ASSISTANT',1000000,1000,100,'ACTIVE',?,"
                        + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                Timestamp.from(Instant.now().minusSeconds(60)));
        Long policyId = jdbc.queryForObject("SELECT MAX(id) FROM ai_quota_policy", Long.class);
        assertNotNull(policyId);
        jdbc.update("INSERT INTO ai_budget_bucket "
                        + "(quota_policy_id,scope_type,scope_key,capability,provider_code,period_type,"
                        + "period_start,period_end,token_limit,cost_limit,reserved_tokens,committed_tokens,"
                        + "reserved_cost,committed_cost,currency,version,created_at,updated_at) "
                        + "VALUES (?,'GLOBAL','*','ASSISTANT','fake','DAY',?,?,1000000,1000,0,0,0,0,"
                        + "'CNY',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                policyId, Timestamp.from(Instant.now().minusSeconds(30)),
                Timestamp.from(Instant.now().plusSeconds(3600)));
    }

    @Test
    void groundedCompletionLocksFreshRoleFactsBeforeCitationSources() throws Exception {
        Fixture fixture = fixture("并发撤权前允许输出");
        String runId = assistantRun(fixture.actor(), "fresh-role-lock");
        CountDownLatch sourceLocked = new CountDownLatch(1);
        CountDownLatch releaseSource = new CountDownLatch(1);
        CountDownLatch completionStarted = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        try (var executor = Executors.newFixedThreadPool(3)) {
            var sourceBlocker = executor.submit(() -> transaction.execute(status -> {
                jdbc.queryForObject("SELECT status FROM ai_knowledge_source WHERE public_id=? FOR UPDATE",
                        String.class, fixture.citation().sourcePublicId());
                sourceLocked.countDown();
                await(releaseSource);
                return true;
            }));
            assertTrue(sourceLocked.await(5, TimeUnit.SECONDS));

            var completion = executor.submit(() -> {
                completionStarted.countDown();
                return store.completeRunWithFinalDelta(
                        runId, fixture.citation().quoteRedacted(),
                        new ModelUsage(0, 0, ModelUsage.Source.ESTIMATED),
                        "deterministic", "knowledge-grounding-policy-v1", false, true,
                        List.of(fixture.citation()), Set.copyOf(fixture.actor().permissionCodes())).changed();
            });
            assertTrue(completionStarted.await(5, TimeUnit.SECONDS));
            Thread.sleep(300);
            assertFalse(completion.isDone(), "completion 应阻塞在已锁定的 citation source");

            var revocation = executor.submit(() -> {
                revokeKnowledgePermission(fixture);
                return true;
            });
            boolean revocationCrossedCompletion;
            try {
                revocation.get(500, TimeUnit.MILLISECONDS);
                revocationCrossedCompletion = true;
            } catch (TimeoutException expected) {
                revocationCrossedCompletion = false;
            } finally {
                releaseSource.countDown();
            }

            assertTrue(Boolean.TRUE.equals(sourceBlocker.get(5, TimeUnit.SECONDS)));
            assertTrue(completion.get(5, TimeUnit.SECONDS));
            assertTrue(revocation.get(5, TimeUnit.SECONDS));
            assertFalse(revocationCrossedCompletion,
                    "RBAC 撤权不得越过已进入最终 grounded completion 的 fresh role 行锁");
        } finally {
            releaseSource.countDown();
        }
    }

    @Test
    void groundedCompletionRejectsPermissionsRevokedBeforeFinalTransactionWithoutWritingFacts() {
        Fixture fixture = fixture("撤权后不得落盘");
        String runId = assistantRun(fixture.actor(), "revoked-before-final");
        revokeKnowledgePermission(fixture);

        AiApiException denied = assertThrows(AiApiException.class, () -> store.completeRunWithFinalDelta(
                runId, fixture.citation().quoteRedacted(),
                new ModelUsage(0, 0, ModelUsage.Source.ESTIMATED),
                "deterministic", "knowledge-grounding-policy-v1", false, true,
                List.of(fixture.citation()), Set.copyOf(fixture.actor().permissionCodes())));

        assertEquals("AI_CONTEXT_ACCESS_REVOKED", denied.errorCode());
        assertNoGroundedCompletionFacts(runId);
    }

    @Test
    void knowledgeCommandCompletionRejectsPermissionsRevokedBeforeFinalTransaction() {
        Fixture fixture = fixture("命令撤权后不得落盘");
        var creation = store.createCommandRun(fixture.actor(), AiCapability.KNOWLEDGE,
                "GLOBAL", "KNOWLEDGE", null, "knowledge-command-" + UUID.randomUUID(),
                hash('e'), "{\"question\":\"制度\"}", "L1", "deterministic");
        String runId = creation.run().id();
        assertTrue(store.markQueued(runId).changed());
        assertTrue(store.markStarted(runId, "knowledge-grounding-policy-v1").changed());
        revokeKnowledgePermission(fixture);

        AiApiException denied = assertThrows(AiApiException.class, () -> store.completeCommandRun(
                runId, knowledgeResult(fixture.citation()), List.of(fixture.citation()),
                Set.copyOf(fixture.actor().permissionCodes())));

        assertEquals("AI_CONTEXT_ACCESS_REVOKED", denied.errorCode());
        assertNoGroundedCompletionFacts(runId);
    }

    private String assistantRun(AiActorContext actor, String keyPrefix) {
        String conversationId = store.createConversation(actor, "GLOBAL", "NONE", null).id();
        String runId = store.createRun(actor, conversationId, keyPrefix + "-" + UUID.randomUUID(),
                hash('d'), "input", "L1", "fake").run().id();
        assertTrue(store.markQueued(runId).changed());
        assertTrue(store.markStarted(runId, "knowledge-grounding-policy-v1").changed());
        return runId;
    }

    private Fixture fixture(String quote) {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String roleCode = "KNOW_" + suffix;
        jdbc.update("INSERT INTO sys_role(code,name,description,enabled,built_in) VALUES(?,?,?,TRUE,FALSE)",
                roleCode, "知识并发角色", "最终 citation 授权测试");
        Long roleId = jdbc.queryForObject("SELECT id FROM sys_role WHERE code=?", Long.class, roleCode);
        Long assistantPermissionId = permissionId("ai:assistant:use");
        Long knowledgePermissionId = permissionId("ai:knowledge:read");
        assertNotNull(roleId);
        jdbc.update("INSERT INTO sys_role_permission(role_id,permission_id) VALUES(?,?)",
                roleId, assistantPermissionId);
        jdbc.update("INSERT INTO sys_role_permission(role_id,permission_id) VALUES(?,?)",
                roleId, knowledgePermissionId);

        String username = "citation-auth-" + suffix.toLowerCase();
        jdbc.update("INSERT INTO sys_user(username,password_hash,display_name,role_code,enabled,deleted) "
                        + "VALUES(?,?,'引用授权用户',?,TRUE,FALSE)",
                username, "test-password-hash", roleCode);
        Long userId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username=?", Long.class, username);
        assertNotNull(userId);
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)", userId, roleId);

        var snapshot = authorization.snapshot(userId);
        assertTrue(snapshot.enabled());
        AiActorContext actor = new AiActorContext(userId, "session", hash('a'), 1, hash('b'),
                snapshot.roleCodes(), snapshot.permissionCodes(), ActorDescriptor.user(userId));
        return new Fixture(actor, insertActiveCitation(userId, quote), roleId, assistantPermissionId);
    }

    private CitationCandidate insertActiveCitation(long ownerUserId, String quote) {
        String sourceId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ai_knowledge_source "
                        + "(public_id,name,source_type,owner_user_id,classification,permission_match_mode,"
                        + "object_store_code,acl_version,status,created_operator_user_id,updated_operator_user_id,"
                        + "created_at,updated_at) VALUES(?,?,'TEXT',?,'L1','ANY','local',1,'ACTIVE',?,?,"
                        + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                sourceId, "最终授权知识", ownerUserId, ownerUserId, ownerUserId);
        Long sourceDatabaseId = jdbc.queryForObject(
                "SELECT id FROM ai_knowledge_source WHERE public_id=?", Long.class, sourceId);
        assertNotNull(sourceDatabaseId);
        jdbc.update("INSERT INTO ai_knowledge_source_permission "
                        + "(source_id,permission_code,created_operator_user_id,updated_operator_user_id,"
                        + "created_at,updated_at) VALUES(?,'ai:knowledge:read',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                sourceDatabaseId, ownerUserId, ownerUserId);

        String documentId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ai_document "
                        + "(public_id,source_id,external_key_hmac,external_key_key_version,title,status,"
                        + "created_operator_user_id,updated_operator_user_id,created_at,updated_at) "
                        + "VALUES(?,?,?,1,'最终授权文档','ACTIVE',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                documentId, sourceDatabaseId, hash('c'), ownerUserId, ownerUserId);
        Long documentDatabaseId = jdbc.queryForObject(
                "SELECT id FROM ai_document WHERE public_id=?", Long.class, documentId);
        assertNotNull(documentDatabaseId);

        String versionId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ai_document_version "
                        + "(public_id,document_id,version,content_hash,visibility,object_key,object_version_id,"
                        + "object_etag,mime_type,size_bytes,parser_version,chunk_policy_version,status,"
                        + "created_operator_user_id,updated_operator_user_id,created_at,updated_at) "
                        + "VALUES(?,?,'v1',?,'EXPLICIT_ACL','knowledge/test','object-v1','etag-v1','text/plain',"
                        + "?,'parser-v1','chunk-v1','ACTIVE',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                versionId, documentDatabaseId, hash('d'), quote.length(), ownerUserId, ownerUserId);
        Long versionDatabaseId = jdbc.queryForObject(
                "SELECT id FROM ai_document_version WHERE public_id=?", Long.class, versionId);
        assertNotNull(versionDatabaseId);
        jdbc.update("UPDATE ai_document SET current_version_id=? WHERE id=?",
                versionDatabaseId, documentDatabaseId);

        String chunkId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ai_document_chunk "
                        + "(public_id,document_version_id,chunk_no,content_redacted,content_hash,locator_text,"
                        + "metadata_text,status,created_operator_user_id,updated_operator_user_id,created_at,updated_at) "
                        + "VALUES(?,?,0,?,?,?,'{}','READY',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                chunkId, versionDatabaseId, quote, hash('d'), "第 1 段", ownerUserId, ownerUserId);
        return new CitationCandidate(sourceId, versionId, chunkId, "制度", "第 1 段", quote,
                hash('d'), 1, new BigDecimal("0.9"));
    }

    private Map<String, Object> knowledgeResult(CitationCandidate citation) {
        return Map.of(
                "grounded", true,
                "answerText", citation.quoteRedacted(),
                "citations", List.of(Map.of(
                        "sourceId", citation.sourcePublicId(),
                        "documentVersionId", citation.documentVersionPublicId(),
                        "chunkPublicId", citation.chunkPublicId(),
                        "label", citation.label(),
                        "locator", citation.locator(),
                        "quote", citation.quoteRedacted(),
                        "contentHash", citation.contentHash())));
    }

    private void revokeKnowledgePermission(Fixture fixture) {
        try (MockedStatic<StpUtil> ignored = org.mockito.Mockito.mockStatic(StpUtil.class)) {
            rbacService.updateRole(fixture.roleId(), new RoleUpdateRequest(
                    "知识并发角色", "已撤销知识读取", true, List.of(fixture.assistantPermissionId())));
        }
    }

    private void assertNoGroundedCompletionFacts(String runId) {
        assertEquals(0L, count("SELECT COUNT(*) FROM ai_run_event e JOIN ai_run r ON r.id=e.run_id "
                + "WHERE r.public_id=? AND e.event_type IN ('message.delta','citation.added','run.completed')", runId));
        assertEquals(0L, count("SELECT COUNT(*) FROM ai_citation c JOIN ai_run r ON r.id=c.run_id "
                + "WHERE r.public_id=?", runId));
        assertEquals(0L, count("SELECT COUNT(*) FROM ai_message m JOIN ai_run r "
                + "ON r.conversation_id=m.conversation_id WHERE r.public_id=? "
                + "AND m.role='ASSISTANT' AND m.parent_message_id=r.request_message_id", runId));
    }

    private long count(String sql, Object... arguments) {
        Long value = jdbc.queryForObject(sql, Long.class, arguments);
        return value == null ? 0 : value;
    }

    private Long permissionId(String code) {
        Long value = jdbc.queryForObject("SELECT id FROM sys_permission WHERE code=?", Long.class, code);
        assertNotNull(value);
        return value;
    }

    private void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("并发授权测试等待被中断", interrupted);
        }
    }

    private String hash(char value) {
        return String.valueOf(value).repeat(64);
    }

    private record Fixture(
            AiActorContext actor,
            CitationCandidate citation,
            long roleId,
            long assistantPermissionId) { }
}
