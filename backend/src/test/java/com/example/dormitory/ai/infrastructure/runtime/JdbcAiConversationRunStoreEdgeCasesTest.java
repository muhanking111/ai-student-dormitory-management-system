package com.example.dormitory.ai.infrastructure.runtime;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.application.control.BudgetExceededException;
import com.example.dormitory.ai.application.control.IdempotencyPayloadMismatchException;
import com.example.dormitory.ai.application.run.AiActorContext;
import com.example.dormitory.ai.application.run.AiRunRecords.CitationCandidate;
import com.example.dormitory.ai.application.run.AiRunRecords.RetrievalTrace;
import com.example.dormitory.ai.application.run.AiRunRecords.RunCreation;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.AiCapability;
import com.example.dormitory.ai.domain.model.ModelUsage;
import com.example.dormitory.ai.port.AiToolCallAuditPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ActiveProfiles("test")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:run-store-edges;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "dormitory.ai.enabled=true",
        "dormitory.ai.capabilities.assistant=true",
        "dormitory.ai.provider.active=fake",
        "dormitory.ai.audit.hmac-key=0123456789abcdef0123456789abcdef",
        "dormitory.ai.tokenization.hmac-key=fedcba9876543210fedcba9876543210"
})
class JdbcAiConversationRunStoreEdgeCasesTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JdbcAiConversationRunStore store;

    @Autowired
    private AiToolCallAuditPort toolCallAudit;

    @Autowired
    private AiRuntimeAuditWriter auditWriter;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetFacts() {
        for (String table : new String[] {
                "ai_feedback", "ai_citation", "ai_tool_call", "ai_retrieval_trace", "ai_run_event",
                "ai_usage_ledger", "ai_provider_attempt", "ai_budget_reservation",
                "ai_outbox_event", "ai_run", "ai_message", "ai_conversation",
                "ai_document_chunk", "ai_document_version", "ai_document",
                "ai_knowledge_source_permission", "ai_knowledge_source",
                "ai_budget_bucket", "ai_quota_policy", "ai_audit_event", "ai_audit_chain_head",
                "ai_tool_catalog_version"
        }) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("UPDATE ai_prompt_version SET status='ACTIVE',active_slot_key=prompt_key,"
                + "activated_at=CURRENT_TIMESTAMP");
        jdbc.update("INSERT INTO ai_tool_catalog_version "
                        + "(version,manifest_text,manifest_hash,status,active_slot_key,activated_at,created_at,updated_at) "
                        + "VALUES ('edge-v1','{\"tools\":[]}',?,'ACTIVE','runtime',CURRENT_TIMESTAMP,"
                        + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                "a".repeat(64));
        long policy = insertPolicy(10, "ACTIVE", "GLOBAL", "*");
        insertBucket(policy, "GLOBAL", "*", "fake");
    }

    @Test
    void toolCallAuditAssignsStableRunSequencesAndIsVisibleFromRunAudit() {
        AiActorContext actor = actor(7L, List.of("ADMIN"), hash('b'), hash('c'));
        String conversationId = store.createConversation(actor, "GLOBAL", "GLOBAL", null).id();
        String runId = createAssistantRun(actor, conversationId, "tool-audit").run().id();
        Instant started = Instant.parse("2026-07-16T01:00:00Z");

        toolCallAudit.append(new AiToolCallAuditPort.ToolCallAudit(
                runId, actor.userId(), "knowledge.search.v1", "v1",
                "{\"query\":\"已脱敏查询\"}", "{\"grounded\":false}",
                Set.of("ai:knowledge:read"), AiToolCallAuditPort.AuthorizationDecision.ALLOWED,
                AiToolCallAuditPort.State.SUCCEEDED, null, started, started.plusMillis(5)));
        toolCallAudit.append(new AiToolCallAuditPort.ToolCallAudit(
                runId, actor.userId(), "notice.list_published.v1", "v1",
                "{\"surface\":\"GLOBAL\"}", null,
                Set.of("ai:assistant:use", "notice:read"), AiToolCallAuditPort.AuthorizationDecision.DENIED,
                AiToolCallAuditPort.State.DENIED, "AI_TOOL_DENIED", started.plusMillis(6),
                started.plusMillis(7)));

        List<java.util.Map<String, Object>> rows = jdbc.queryForList(
                "SELECT sequence_no,tool_name,request_redacted,response_redacted,"
                        + "required_permissions_text,authorization_decision,state,error_code "
                        + "FROM ai_tool_call WHERE run_id=(SELECT id FROM ai_run WHERE public_id=?) "
                        + "ORDER BY sequence_no", runId);
        assertEquals(List.of(1L, 2L), rows.stream()
                .map(row -> ((Number) row.get("SEQUENCE_NO")).longValue()).toList());
        assertEquals("knowledge.search.v1", rows.getFirst().get("TOOL_NAME"));
        assertTrue(rows.getFirst().get("REQUEST_REDACTED").toString().contains("已脱敏查询"));
        assertFalse(rows.getFirst().get("REQUIRED_PERMISSIONS_TEXT").toString().contains("*"));
        assertEquals("AI_TOOL_DENIED", rows.getLast().get("ERROR_CODE"));

        var detail = store.auditRun(runId);
        assertEquals(List.of("knowledge.search.v1", "notice.list_published.v1"),
                detail.tools().stream().map(tool -> tool.toolName()).toList());
        assertEquals(List.of(1L, 2L), detail.tools().stream().map(tool -> tool.sequence()).toList());
        assertEquals("DENIED", detail.tools().getLast().state());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_audit_event WHERE chain_scope='RUN' AND aggregate_type='RUN' "
                        + "AND aggregate_public_id=? AND event_type='TOOL_CALL_RECORDED'",
                Integer.class, runId));
        assertFalse(jdbc.queryForList(
                "SELECT payload_redacted_hash,correlation_id FROM ai_audit_event "
                        + "WHERE aggregate_public_id=? AND event_type='TOOL_CALL_RECORDED'", runId)
                .toString().contains("已脱敏查询"));
        assertDoesNotThrow(() -> auditWriter.requireValidChain("RUN", "RUN", runId));
        assertTrue(auditWriter.startupReady());
    }

    @Test
    void toolFactsAreReconciledAgainstTheRunHmacChainAndTamperingFailsClosed() {
        AiActorContext actor = actor(7L, List.of("ADMIN"), hash('b'), hash('c'));
        String conversationId = store.createConversation(actor, "GLOBAL", "GLOBAL", null).id();
        String runId = createAssistantRun(actor, conversationId, "tool-integrity").run().id();
        appendTool(runId, actor.userId(), "knowledge.search.v1", "{\"query\":\"脱敏事实\"}");
        assertTrue(auditWriter.startupReady());

        jdbc.update("UPDATE ai_tool_call SET request_redacted=? WHERE run_id="
                        + "(SELECT id FROM ai_run WHERE public_id=?)",
                "{\"query\":\"篡改后事实\"}", runId);

        assertFalse(auditWriter.startupReady());
        assertApiCode("AI_AUDIT_INTEGRITY_FAILURE",
                () -> auditWriter.requireValidChain("RUN", "RUN", runId));
    }

    @Test
    void deletingToolFactLeavesAnOrphanedSignedEventAndFailsStartup() {
        AiActorContext actor = actor(7L, List.of("ADMIN"), hash('b'), hash('c'));
        String conversationId = store.createConversation(actor, "GLOBAL", "GLOBAL", null).id();
        String runId = createAssistantRun(actor, conversationId, "tool-delete").run().id();
        appendTool(runId, actor.userId(), "knowledge.search.v1", "{\"query\":\"脱敏事实\"}");
        jdbc.update("DELETE FROM ai_tool_call WHERE run_id=(SELECT id FROM ai_run WHERE public_id=?)", runId);

        assertFalse(auditWriter.startupReady());
    }

    @Test
    void auditAppendFailureRollsBackTheToolFactInsertedInTheSameTransaction() {
        AiActorContext actor = actor(7L, List.of("ADMIN"), hash('b'), hash('c'));
        String conversationId = store.createConversation(actor, "GLOBAL", "GLOBAL", null).id();
        String runId = createAssistantRun(actor, conversationId, "tool-rollback").run().id();
        appendTool(runId, actor.userId(), "knowledge.search.v1", "{\"query\":\"第一次\"}");
        int before = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_tool_call WHERE run_id=(SELECT id FROM ai_run WHERE public_id=?)",
                Integer.class, runId);
        jdbc.update("UPDATE ai_audit_event SET payload_redacted_hash=? "
                        + "WHERE aggregate_public_id=? AND sequence_no=1",
                hash('f'), runId);

        assertThrows(IllegalStateException.class, () -> appendTool(
                runId, actor.userId(), "notice.list_published.v1", "{\"surface\":\"GLOBAL\"}"));
        assertEquals(before, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_tool_call WHERE run_id=(SELECT id FROM ai_run WHERE public_id=?)",
                Integer.class, runId));
    }

    @Test
    void activeRunBlocksArchiveAndStateTransitionsAreIdempotent() {
        AiActorContext actor = actor(7L, List.of("ADMIN"), hash('b'), hash('c'));
        String conversationId = store.createConversation(actor, "GLOBAL", "GLOBAL", null).id();
        String runId = createAssistantRun(actor, conversationId, "state-edges").run().id();

        assertApiCode("AI_CONVERSATION_HAS_ACTIVE_RUN",
                () -> store.archiveConversation(actor, conversationId));
        assertFalse(store.markStarted(runId, "fake").changed());
        assertTrue(store.markQueued(runId).changed());
        assertFalse(store.markQueued(runId).changed());
        assertTrue(store.markStarted(runId, "fake").changed());
        assertFalse(store.markStarted(runId, "fake").changed());
        assertTrue(store.appendDelta(runId, "first").changed());
        assertTrue(store.appendDelta(runId, " second").changed());

        RetrievalTrace trace = trace();
        assertThrows(IllegalArgumentException.class, () -> store.recordRetrievalTrace(runId, null, trace));
        assertThrows(IllegalArgumentException.class, () -> store.recordRetrievalTrace(runId, actor, null));
        assertThrows(SecurityException.class,
                () -> store.recordRetrievalTrace(runId, actor(8L, List.of(), hash('b'), hash('c')), trace));
        assertThrows(SecurityException.class,
                () -> store.recordRetrievalTrace(runId, actor(7L, List.of(), hash('d'), hash('c')), trace));
        assertThrows(SecurityException.class,
                () -> store.recordRetrievalTrace(runId, actor(7L, List.of(), hash('b'), hash('d')), trace));
        store.recordRetrievalTrace(runId, actor, trace);

        ModelUsage estimated = new ModelUsage(3, 2, ModelUsage.Source.ESTIMATED);
        assertThrows(IllegalArgumentException.class,
                () -> store.completeRun(runId, "answer", null, "fake", "fake-v1",
                        false, false, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> store.completeRun(runId, "answer", estimated, null, "fake-v1",
                        false, false, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> store.completeRun(runId, "answer", estimated, " ", "fake-v1",
                        false, false, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> store.completeRun(runId, "answer", estimated, "fake", null,
                        false, false, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> store.completeRun(runId, "answer", estimated, "fake", " ",
                        false, false, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> store.completeRun(runId, "answer", estimated, "fake", "fake-v1",
                        false, true, null, null));
        CitationCandidate unbound = citation(null);
        assertThrows(IllegalArgumentException.class,
                () -> store.completeRun(runId, "answer", estimated, "fake", "fake-v1",
                        false, false, List.of(unbound), Set.of()));

        assertTrue(store.completeRun(runId, "answer", estimated, "fake", "fake-v1",
                false, false, null, null).changed());
        assertFalse(store.completeRun(runId, "ignored", null, null, null,
                false, false, null, null).changed());
        assertThrows(IllegalStateException.class, () -> store.recordRetrievalTrace(runId, actor, trace));

        store.archiveConversation(actor, conversationId);
        store.archiveConversation(actor, conversationId);
        assertEquals("ARCHIVED", store.conversationDetail(7L, conversationId).conversation().status());
        assertTrue(store.eventsAfter(7L, runId, 0).size() >= 6);
    }

    @Test
    void groundedCompletionSerializesSourceRevocationAndRejectsPostRevocationWrites() throws Exception {
        AiActorContext actor = knowledgeActor();
        CitationCandidate citation = insertActiveCitation(actor.userId(), "只允许在当前 ACL 下输出");
        String conversationId = store.createConversation(actor, "GLOBAL", "NONE", null).id();
        String runId = createAssistantRun(actor, conversationId, "citation-lock").run().id();
        assertTrue(store.markQueued(runId).changed());
        assertTrue(store.markStarted(runId, "knowledge-grounding-policy-v1").changed());

        CountDownLatch completionStored = new CountDownLatch(1);
        CountDownLatch releaseCommit = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var completion = executor.submit(() -> transaction.execute(status -> {
                boolean changed = store.completeRunWithFinalDelta(
                        runId, citation.quoteRedacted(),
                        new ModelUsage(0, 0, ModelUsage.Source.ESTIMATED),
                        "deterministic", "knowledge-grounding-policy-v1", false, true,
                        List.of(citation), Set.copyOf(actor.permissionCodes())).changed();
                completionStored.countDown();
                await(releaseCommit);
                return changed;
            }));
            assertTrue(completionStored.await(5, TimeUnit.SECONDS));
            var revocation = executor.submit(() -> jdbc.update(
                    "UPDATE ai_knowledge_source SET status='PAUSED',acl_version=acl_version+1 "
                            + "WHERE public_id=?",
                    citation.sourcePublicId()));

            Thread.sleep(300);
            assertFalse(revocation.isDone(),
                    "知识撤权事务不得越过正文与完成事件事务持有的 source 行锁");
            releaseCommit.countDown();

            assertTrue(Boolean.TRUE.equals(completion.get(5, TimeUnit.SECONDS)));
            assertEquals(1, revocation.get(5, TimeUnit.SECONDS));
        } finally {
            releaseCommit.countDown();
        }

        assertEquals(1, eventCount(runId, "message.delta"));
        assertEquals(1, eventCount(runId, "run.completed"));
        String deniedConversation = store.createConversation(actor, "GLOBAL", "NONE", null).id();
        String deniedRun = createAssistantRun(actor, deniedConversation, "citation-lock-revoked").run().id();
        assertTrue(store.markQueued(deniedRun).changed());
        assertTrue(store.markStarted(deniedRun, "knowledge-grounding-policy-v1").changed());
        AiApiException denied = assertThrows(AiApiException.class,
                () -> store.completeRunWithFinalDelta(
                        deniedRun, "撤权后不得输出",
                        new ModelUsage(0, 0, ModelUsage.Source.ESTIMATED),
                        "deterministic", "knowledge-grounding-policy-v1", false, true,
                        List.of(citation), Set.copyOf(actor.permissionCodes())));
        assertEquals("AI_CONTEXT_ACCESS_REVOKED", denied.errorCode());
        assertEquals(0, eventCount(deniedRun, "message.delta"));
        assertEquals(0, eventCount(deniedRun, "run.completed"));
        assertEquals("RUNNING", jdbc.queryForObject(
                "SELECT state FROM ai_run WHERE public_id=?", String.class, deniedRun));
    }

    @Test
    void fixedPromptAndNotFoundBoundariesFailClosed() {
        AiActorContext actor = actor(7L, List.of(), hash('b'), hash('c'));
        String conversationId = store.createConversation(actor, "GLOBAL", "GLOBAL", 99L).id();
        String runId = createAssistantRun(actor, conversationId, "prompt-edges").run().id();
        var authorizationScope = store.auditAuthorizationScope(runId);
        assertEquals(7L, authorizationScope.ownerUserId());
        assertEquals("GLOBAL", authorizationScope.surface());
        assertEquals("GLOBAL", authorizationScope.contextType());
        assertEquals(99L, authorizationScope.contextId());
        String content = jdbc.queryForObject(
                "SELECT content FROM ai_prompt_version WHERE prompt_key='assistant.system'", String.class);
        String contentHash = jdbc.queryForObject(
                "SELECT content_hash FROM ai_prompt_version WHERE prompt_key='assistant.system'", String.class);
        try {
            assertEquals(content, store.pinnedSystemPrompt(runId));
            jdbc.update("UPDATE ai_prompt_version SET content='' WHERE prompt_key='assistant.system'");
            assertThrows(IllegalStateException.class, () -> store.pinnedSystemPrompt(runId));
            jdbc.update("UPDATE ai_prompt_version SET content=?,content_hash=? WHERE prompt_key='assistant.system'",
                    content, "f".repeat(64));
            assertThrows(IllegalStateException.class, () -> store.pinnedSystemPrompt(runId));
        } finally {
            jdbc.update("UPDATE ai_prompt_version SET content=?,content_hash=? WHERE prompt_key='assistant.system'",
                    content, contentHash);
        }

        String missing = UUID.randomUUID().toString();
        assertApiCode("AI_RESOURCE_NOT_FOUND", () -> store.ownedRun(7L, missing));
        assertApiCode("AI_RESOURCE_NOT_FOUND", () -> store.ownedRun(8L, runId));
        assertApiCode("AI_RESOURCE_NOT_FOUND", () -> store.conversationDetail(8L, conversationId));
        assertApiCode("AI_RESOURCE_NOT_FOUND", () -> store.archiveConversation(actor, missing));
        assertApiCode("AI_RESOURCE_NOT_FOUND", () -> store.markQueued(missing));
        assertApiCode("AI_RESOURCE_NOT_FOUND", () -> store.pinnedSystemPrompt(missing));
        assertApiCode("AI_RESOURCE_NOT_FOUND", () -> store.auditRun(missing));
        assertApiCode("AI_RESOURCE_NOT_FOUND", () -> store.auditRunContent(missing));
        assertApiCode("AI_RESOURCE_NOT_FOUND", () -> store.auditAuthorizationScope(missing));
        assertEquals(1, store.listConversations(7L, 1, 10).total());
    }

    @Test
    void retryRequiresTerminalAssistantInputAndDetectsReplayTampering() {
        AiActorContext actor = actor(7L, List.of("ADMIN"), hash('b'), hash('c'));
        String conversationId = store.createConversation(actor, "GLOBAL", "GLOBAL", null).id();
        String parentRunId = createAssistantRun(actor, conversationId, "retry-parent").run().id();

        assertApiCode("AI_RUN_RETRY_REQUIRES_TERMINAL", () -> store.retrySource(7L, parentRunId));
        assertApiCode("AI_RUN_RETRY_REQUIRES_TERMINAL",
                () -> store.createRetryRun(actor, parentRunId, "retry-key", "fake"));
        store.forceFailWithoutEvent(parentRunId, "AI_TEST_FAILURE");

        jdbc.update("UPDATE ai_message SET classification='' WHERE id="
                + "(SELECT request_message_id FROM ai_run WHERE public_id=?)", parentRunId);
        assertThrows(IllegalArgumentException.class, () -> store.retrySource(7L, parentRunId));
        jdbc.update("UPDATE ai_message SET classification='L1' WHERE id="
                + "(SELECT request_message_id FROM ai_run WHERE public_id=?)", parentRunId);
        assertEquals(hash('e'), store.retrySource(7L, parentRunId).requestHash());

        RunCreation created = store.createRetryRun(actor, parentRunId, "retry-key", "fake");
        RunCreation replay = store.createRetryRun(actor, parentRunId, "retry-key", "fake");
        assertFalse(created.replayed());
        assertTrue(replay.replayed());
        assertEquals(created.run().id(), replay.run().id());

        jdbc.update("UPDATE ai_message SET request_hash=? WHERE id="
                + "(SELECT request_message_id FROM ai_run WHERE public_id=?)", hash('f'), parentRunId);
        assertThrows(IdempotencyPayloadMismatchException.class,
                () -> store.createRetryRun(actor, parentRunId, "retry-key", "fake"));
    }

    @Test
    void retryRejectsUnsupportedArchivedAndUnbudgetedParents() {
        AiActorContext actor = actor(7L, List.of(), hash('b'), hash('c'));
        String commandRun = store.createCommandRun(actor, AiCapability.RISK, null, null, null,
                "risk-command", hash('e'), "{}", "L1", "fake").run().id();
        store.forceFailWithoutEvent(commandRun, "AI_TEST_FAILURE");
        assertApiCode("AI_RUN_RETRY_UNSUPPORTED",
                () -> store.createRetryRun(actor, commandRun, "retry-risk", "fake"));

        String archivedConversation = store.createConversation(actor, "GLOBAL", "GLOBAL", null).id();
        String archivedParent = createAssistantRun(actor, archivedConversation, "archived-parent").run().id();
        store.forceFailWithoutEvent(archivedParent, "AI_TEST_FAILURE");
        store.archiveConversation(actor, archivedConversation);
        assertApiCode("AI_CONVERSATION_ARCHIVED",
                () -> store.createRetryRun(actor, archivedParent, "retry-archived", "fake"));

        String noBudgetConversation = store.createConversation(actor, "GLOBAL", "GLOBAL", null).id();
        String noBudgetParent = createAssistantRun(actor, noBudgetConversation, "no-budget-parent").run().id();
        store.forceFailWithoutEvent(noBudgetParent, "AI_TEST_FAILURE");
        jdbc.update("DELETE FROM ai_budget_reservation");
        jdbc.update("DELETE FROM ai_budget_bucket");
        jdbc.update("DELETE FROM ai_quota_policy");
        assertApiCode("AI_QUOTA_CONTROL_UNAVAILABLE",
                () -> store.createRetryRun(actor, noBudgetParent, "retry-no-budget", "fake"));
    }

    @Test
    void createRunRejectsArchivedMissingAndExhaustedBudgetScopes() {
        AiActorContext actor = actor(7L, List.of(), hash('b'), hash('c'));
        String archived = store.createConversation(actor, "GLOBAL", "GLOBAL", null).id();
        store.archiveConversation(actor, archived);
        assertApiCode("AI_CONVERSATION_ARCHIVED",
                () -> createAssistantRun(actor, archived, "archived-create"));

        String noBudget = store.createConversation(actor, "GLOBAL", "GLOBAL", null).id();
        jdbc.update("DELETE FROM ai_budget_bucket");
        jdbc.update("DELETE FROM ai_quota_policy");
        assertApiCode("AI_QUOTA_CONTROL_UNAVAILABLE",
                () -> createAssistantRun(actor, noBudget, "missing-budget"));

        long zeroPolicy = insertPolicy(0, "ACTIVE", "GLOBAL", "zero");
        insertBucket(zeroPolicy, "GLOBAL", "*", "fake");
        assertThrows(BudgetExceededException.class,
                () -> createAssistantRun(actor, noBudget, "zero-limit"));

        jdbc.update("UPDATE ai_quota_policy SET concurrent_run_limit=1");
        String firstConversation = store.createConversation(actor, "GLOBAL", "GLOBAL", null).id();
        createAssistantRun(actor, firstConversation, "limit-first");
        String secondConversation = store.createConversation(actor, "GLOBAL", "GLOBAL", null).id();
        assertThrows(BudgetExceededException.class,
                () -> createAssistantRun(actor, secondConversation, "limit-second"));
    }

    @Test
    void allBudgetScopeKindsAreFilteredAgainstTrustedActorFacts() {
        jdbc.update("DELETE FROM ai_budget_bucket");
        jdbc.update("DELETE FROM ai_quota_policy");
        long policy = insertPolicy(20, "ACTIVE", "MIXED", "scopes");
        for (Object[] scope : new Object[][] {
                {"GLOBAL", "*"}, {"GLOBAL", "not-global"},
                {"USER", "7"}, {"USER", "8"},
                {"ROLE", "ADMIN"}, {"ROLE", "OTHER"},
                {"CAPABILITY", "ASSISTANT"}, {"CAPABILITY", "RISK"},
                {"PROVIDER", "fake"}, {"PROVIDER", "other"},
                {"UNKNOWN", "*"}
        }) {
            insertBucket(policy, (String) scope[0], (String) scope[1], "fake");
        }
        AiActorContext actor = actor(7L, List.of("ADMIN"), hash('b'), hash('c'));
        String conversation = store.createConversation(actor, "GLOBAL", "GLOBAL", null).id();

        String runId = createAssistantRun(actor, conversation, "scope-run").run().id();

        assertEquals(5, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_budget_reservation WHERE billing_subject_public_id=?",
                Integer.class, runId));
        assertTrue(store.readiness(7L, "fake").budgetConfigured());
    }

    @Test
    void commandFailureCancellationAndNullResultKeepStableTerminalFacts() {
        AiActorContext actor = actor(7L, List.of(), hash('b'), hash('c'));
        String command = store.createCommandRun(actor, AiCapability.RISK, "RISK", "RISK", 42L,
                "command-null", hash('e'), "{}", "L1", "fake").run().id();
        assertTrue(store.markQueued(command).changed());
        assertTrue(store.markStarted(command, "fake").changed());
        assertTrue(store.completeCommandRun(command, null).changed());
        assertFalse(store.completeCommandRun(command, null).changed());

        String timeout = store.createCommandRun(actor, AiCapability.NOTICE, "NOTICE", "NOTICE", null,
                "command-timeout", hash('f'), "{}", "L1", "fake").run().id();
        assertTrue(store.failRun(timeout, "AI_PROVIDER_TIMEOUT", true).changed());
        assertEquals("TIMED_OUT", store.ownedRun(7L, timeout).state());
        assertFalse(store.failRun(timeout, "AI_PROVIDER_TIMEOUT", true).changed());

        String cancelled = store.createCommandRun(actor, AiCapability.REPAIR, "REPAIR", "REPAIR", null,
                "command-cancel", hash('a'), "{}", "L1", "fake").run().id();
        assertTrue(store.cancelRun(actor, cancelled).changed());
        assertEquals("CANCELLED", store.ownedRun(7L, cancelled).state());
        assertFalse(store.cancelRun(actor, cancelled).changed());
    }

    @Test
    void groundedKnowledgeCommandCannotCompleteWithoutPersistedCitationBindings() {
        AiActorContext actor = actor(7L, List.of("ADMIN"), hash('b'), hash('c'));
        String command = store.createCommandRun(actor, AiCapability.KNOWLEDGE,
                "GLOBAL", "KNOWLEDGE", null, "knowledge-unbound", hash('e'),
                "{}", "L1", "fake").run().id();
        assertTrue(store.markQueued(command).changed());
        assertTrue(store.markStarted(command, "deterministic-rules-v1").changed());
        Map<String, Object> unboundGroundedResult = Map.of(
                "grounded", true,
                "answerText", "不应回放的知识正文",
                "citations", List.of(Map.of(
                        "sourceId", "source-1",
                        "documentVersionId", "version-1",
                        "chunkPublicId", "chunk-1",
                        "label", "制度",
                        "locator", "第 1 段",
                        "quote", "不应回放的知识正文",
                        "contentHash", hash('a'))));

        assertThrows(IllegalArgumentException.class,
                () -> store.completeCommandRun(command, unboundGroundedResult));
        assertEquals("RUNNING", store.ownedRun(actor.userId(), command).state());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_citation c JOIN ai_run r ON r.id=c.run_id WHERE r.public_id=?",
                Integer.class, command));

        assertTrue(store.completeCommandRun(command, Map.of(
                "grounded", false,
                "answerText", "暂无可靠来源",
                "citations", List.of())).changed());
    }

    @Test
    void listConversationsHidesPureCommandConversationsButKeepsOwnerAssistantAndEmptySessions() {
        AiActorContext owner = actor(7L, List.of("ADMIN"), hash('b'), hash('c'));
        AiActorContext other = actor(8L, List.of("ADMIN"), hash('d'), hash('e'));

        String emptyAssistant = store.createConversation(owner, "GLOBAL", "GLOBAL", null).id();
        String assistantConversation = store.createConversation(owner, "GLOBAL", "GLOBAL", 99L).id();
        createAssistantRun(owner, assistantConversation, "assistant-visible");

        store.createCommandRun(owner, AiCapability.DASHBOARD, "DASHBOARD", "DASHBOARD", null,
                "dashboard-command", hash('a'), "{\"question\":\"入住率\"}", "L1", "fake");
        store.createCommandRun(owner, AiCapability.REPAIR, "REPAIR", "REPAIR", 42L,
                "repair-command", hash('f'), "{\"repairOrderId\":42}", "L1", "fake");
        store.createCommandRun(owner, AiCapability.NOTICE, "NOTICE", "NOTICE", null,
                "notice-command", hash('c'), "{\"noticeId\":1}", "L1", "fake");
        store.createCommandRun(owner, AiCapability.KNOWLEDGE, "GLOBAL", "KNOWLEDGE", null,
                "knowledge-command", hash('d'), "{\"question\":\"宿舍制度\"}", "L1", "fake");
        store.createCommandRun(owner, AiCapability.RISK, "RISK", "COMMAND", null,
                "risk-command", hash('g'), "{\"question\":\"风险摘要\"}", "L1", "fake");
        store.createCommandRun(other, AiCapability.DASHBOARD, "DASHBOARD", "DASHBOARD", null,
                "other-dashboard-command", hash('h'), "{\"question\":\"other\"}", "L1", "fake");

        var page = store.listConversations(owner.userId(), 1, 20);

        assertEquals(2, page.total());
        assertEquals(List.of(assistantConversation, emptyAssistant),
                page.records().stream().map(conversation -> conversation.id()).toList());
        assertTrue(page.records().stream().allMatch(conversation ->
                "GLOBAL".equals(conversation.surface())));
    }

    @Test
    void listConversationsHidesArchivedAssistantSessions() {
        AiActorContext owner = actor(7L, List.of("ADMIN"), hash('b'), hash('c'));
        String activeConversation = store.createConversation(owner, "GLOBAL", "GLOBAL", null).id();
        String archivedConversation = store.createConversation(owner, "GLOBAL", "GLOBAL", 99L).id();
        String archivedRun = createAssistantRun(owner, archivedConversation, "assistant-archived").run().id();
        store.forceFailWithoutEvent(archivedRun, "AI_TEST_FAILURE");
        store.archiveConversation(owner, archivedConversation);

        var page = store.listConversations(owner.userId(), 1, 20);

        assertEquals(1, page.total());
        assertEquals(List.of(activeConversation),
                page.records().stream().map(conversation -> conversation.id()).toList());
    }

    @Test
    void conversationDetailHidesPureCommandButKeepsEmptyAndAssistantSessions() {
        AiActorContext owner = actor(7L, List.of("ADMIN"), hash('b'), hash('c'));
        String emptyAssistant = store.createConversation(owner, "GLOBAL", "GLOBAL", null).id();
        String assistantConversation = store.createConversation(owner, "GLOBAL", "GLOBAL", 99L).id();
        createAssistantRun(owner, assistantConversation, "assistant-detail-visible");
        String commandConversation = store.createCommandRun(owner, AiCapability.DASHBOARD,
                "DASHBOARD", "DASHBOARD", null, "dashboard-detail-hidden", hash('a'),
                "{\"question\":\"入住率\"}", "L1", "fake").run().conversationId();

        assertApiCode("AI_RESOURCE_NOT_FOUND",
                () -> store.conversationDetail(owner.userId(), commandConversation));
        assertEquals(emptyAssistant, store.conversationDetail(owner.userId(), emptyAssistant).conversation().id());
        assertEquals(assistantConversation,
                store.conversationDetail(owner.userId(), assistantConversation).conversation().id());
    }

    private RunCreation createAssistantRun(AiActorContext actor, String conversationId, String key) {
        return store.createRun(actor, conversationId, key, hash('e'), "input", "L1", "fake");
    }

    private void appendTool(String runId, long actorUserId, String toolName, String request) {
        Instant started = Instant.parse("2026-07-16T01:00:00Z");
        toolCallAudit.append(new AiToolCallAuditPort.ToolCallAudit(
                runId, actorUserId, toolName, "v1", request, "{\"ok\":true}",
                Set.of("ai:assistant:use"), AiToolCallAuditPort.AuthorizationDecision.ALLOWED,
                AiToolCallAuditPort.State.SUCCEEDED, null, started, started.plusMillis(5)));
    }

    private AiActorContext actor(long userId, List<String> roles, String sessionHash, String permissionHash) {
        return new AiActorContext(userId, "session", sessionHash, 1, permissionHash,
                roles, List.of("ai:assistant:use"), ActorDescriptor.user(userId));
    }

    private AiActorContext knowledgeActor() {
        return new AiActorContext(7L, "session", hash('b'), 1, hash('c'), List.of("ADMIN"),
                List.of("ai:assistant:use", "ai:knowledge:read"), ActorDescriptor.user(7L));
    }

    private CitationCandidate insertActiveCitation(long actorUserId, String quote) {
        String sourceId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ai_knowledge_source "
                        + "(public_id,name,source_type,owner_user_id,classification,permission_match_mode,"
                        + "object_store_code,acl_version,status,created_operator_user_id,updated_operator_user_id,"
                        + "created_at,updated_at) VALUES(?,?,'TEXT',?,'L1','ANY','local',1,'ACTIVE',?,?,"
                        + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                sourceId, "并发授权知识", actorUserId, actorUserId, actorUserId);
        long sourceDatabaseId = jdbc.queryForObject(
                "SELECT id FROM ai_knowledge_source WHERE public_id=?", Long.class, sourceId);
        jdbc.update("INSERT INTO ai_knowledge_source_permission "
                        + "(source_id,permission_code,created_operator_user_id,updated_operator_user_id,"
                        + "created_at,updated_at) VALUES(?,'ai:knowledge:read',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                sourceDatabaseId, actorUserId, actorUserId);

        String documentId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ai_document "
                        + "(public_id,source_id,external_key_hmac,external_key_key_version,title,status,"
                        + "created_operator_user_id,updated_operator_user_id,created_at,updated_at) "
                        + "VALUES(?,?,?,1,?,'ACTIVE',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                documentId, sourceDatabaseId, hash('d'), "并发授权文档", actorUserId, actorUserId);
        long documentDatabaseId = jdbc.queryForObject(
                "SELECT id FROM ai_document WHERE public_id=?", Long.class, documentId);

        String versionId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ai_document_version "
                        + "(public_id,document_id,version,content_hash,visibility,object_key,object_version_id,"
                        + "object_etag,mime_type,size_bytes,parser_version,chunk_policy_version,status,"
                        + "created_operator_user_id,updated_operator_user_id,created_at,updated_at) "
                        + "VALUES(?,?,'v1',?,'EXPLICIT_ACL','knowledge/test','object-v1','etag-v1','text/plain',"
                        + "?, 'parser-v1','chunk-v1','ACTIVE',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                versionId, documentDatabaseId, hash('a'), quote.length(), actorUserId, actorUserId);
        long versionDatabaseId = jdbc.queryForObject(
                "SELECT id FROM ai_document_version WHERE public_id=?", Long.class, versionId);
        jdbc.update("UPDATE ai_document SET current_version_id=? WHERE id=?",
                versionDatabaseId, documentDatabaseId);

        String chunkId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ai_document_chunk "
                        + "(public_id,document_version_id,chunk_no,content_redacted,content_hash,locator_text,"
                        + "metadata_text,status,created_operator_user_id,updated_operator_user_id,created_at,updated_at) "
                        + "VALUES(?,?,0,?,?,?,'{}','READY',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                chunkId, versionDatabaseId, quote, hash('a'), "第 1 段", actorUserId, actorUserId);
        return new CitationCandidate(sourceId, versionId, chunkId, "制度", "第 1 段", quote,
                hash('a'), 1, new BigDecimal("0.9"));
    }

    private long eventCount(String runId, String eventType) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ai_run_event e JOIN ai_run r ON r.id=e.run_id "
                        + "WHERE r.public_id=? AND e.event_type=?",
                Long.class, runId, eventType);
    }

    private void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待并发测试信号时被中断", interrupted);
        }
    }

    private RetrievalTrace trace() {
        return new RetrievalTrace(hash('a'), "retrieval-v1", "HYBRID", "knowledge", "index-v1",
                "embed-v1", "{}", 5, 5, 3, 2, 10, "SUCCEEDED");
    }

    private CitationCandidate citation(String chunkId) {
        return new CitationCandidate(UUID.randomUUID().toString(), UUID.randomUUID().toString(), chunkId,
                "source", "line:1", "quote", hash('a'), 1, new BigDecimal("0.9"));
    }

    private long insertPolicy(int concurrentLimit, String status, String scopeType, String scopeKey) {
        Instant effective = Instant.now().minusSeconds(60);
        jdbc.update("INSERT INTO ai_quota_policy "
                        + "(scope_type,scope_key,capability,daily_token_limit,monthly_cost_limit,"
                        + "concurrent_run_limit,status,effective_from,created_at,updated_at) "
                        + "VALUES (?,?, 'ASSISTANT',1000000,1000,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                scopeType, scopeKey, concurrentLimit, status, Timestamp.from(effective));
        return jdbc.queryForObject("SELECT MAX(id) FROM ai_quota_policy", Long.class);
    }

    private void insertBucket(long policyId, String scopeType, String scopeKey, String provider) {
        Instant now = Instant.now();
        jdbc.update("INSERT INTO ai_budget_bucket "
                        + "(quota_policy_id,scope_type,scope_key,capability,provider_code,period_type,"
                        + "period_start,period_end,token_limit,cost_limit,reserved_tokens,committed_tokens,"
                        + "reserved_cost,committed_cost,currency,version,created_at,updated_at) "
                        + "VALUES (?,?,?,'ASSISTANT',?,'DAY',?,?,1000000,1000,0,0,0,0,'CNY',0,"
                        + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                policyId, scopeType, scopeKey, provider, Timestamp.from(now.minusSeconds(30)),
                Timestamp.from(now.plusSeconds(3600)));
    }

    private void assertApiCode(String expected, Executable executable) {
        AiApiException exception = assertThrows(AiApiException.class, executable);
        assertEquals(expected, exception.errorCode());
    }

    private String hash(char value) {
        return String.valueOf(value).repeat(64);
    }

}
