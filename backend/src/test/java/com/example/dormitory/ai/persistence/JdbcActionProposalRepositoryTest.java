package com.example.dormitory.ai.persistence;

import com.example.dormitory.ai.approval.ActionProposal;
import com.example.dormitory.ai.approval.ActionProposalRepository;
import com.example.dormitory.ai.approval.ActionType;
import com.example.dormitory.ai.infrastructure.persistence.JdbcActionProposalRepository;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiOutboxRepository;
import com.example.dormitory.ai.approval.ProposalConflictException;
import com.example.dormitory.ai.approval.ProposalOrigin;
import com.example.dormitory.ai.approval.ActionProposalService;
import com.example.dormitory.ai.approval.SpringActionProposalTransactionRunner;
import com.example.dormitory.ai.approval.ProposalPreview;
import com.example.dormitory.ai.approval.ProposalState;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.BusinessExecutionActor;
import com.example.dormitory.ai.port.ApprovedBusinessActionPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ActiveProfiles("test")
@SpringBootTest(properties =
        "dormitory.ai.audit.hmac-key=0123456789abcdef0123456789abcdef")
class JdbcActionProposalRepositoryTest {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired JdbcActionProposalRepository repository;
    @Autowired JdbcAiOutboxRepository outbox;
    @Autowired SpringActionProposalTransactionRunner transactions;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM ai_action_execution");
        jdbcTemplate.update("DELETE FROM ai_action_approval");
        jdbcTemplate.update("DELETE FROM ai_idempotency_record");
        jdbcTemplate.update("DELETE FROM ai_action_proposal");
        jdbcTemplate.update("DELETE FROM ai_outbox_event");
        jdbcTemplate.update("DELETE FROM ai_tool_call");
        jdbcTemplate.update("DELETE FROM ai_run");
        jdbcTemplate.update("DELETE FROM ai_message");
        jdbcTemplate.update("DELETE FROM ai_conversation");
    }

    @Test
    void survivesRestartWithApprovalIdempotencyAndUniqueExecutionLease() {
        ActionProposalRepository.StoredProposal stored = storedProposal();
        ProposalOrigin origin = originRun("NOTICE", 7L);
        repository.createOrReplay(stored, origin, createRequest(7L, "create-key", '0'));
        BusinessExecutionActor actor = BusinessExecutionActor.from(ActorDescriptor.user(7));
        stored.proposal().approve(0, hash('a'), hash('b'), hash('b'), actor,
                Set.of("ai:approval:review", "notice:write"), "approval-key", hash('c'));
        repository.update(stored);

        JdbcActionProposalRepository restarted = new JdbcActionProposalRepository(jdbcTemplate, outbox);
        ActionProposalRepository.StoredProposal loaded = restarted.findByPublicId(stored.proposal().publicId()).orElseThrow();
        assertEquals(ProposalState.APPROVED, loaded.proposal().state());
        assertEquals(1, loaded.proposal().approvals().size());
        assertEquals(loaded.proposal().state(), loaded.proposal().approve(
                0, hash('a'), hash('b'), hash('b'), actor,
                Set.of("ai:approval:review", "notice:write"), "approval-key", hash('c')).state());

        ActionProposal.ExecutionLease lease = loaded.proposal().acquireExecutionLease(actor);
        assertNotNull(lease);
        restarted.update(loaded);
        loaded.result(new ApprovedBusinessActionPort.BusinessActionResult("NOTICE", 99L, hash('d')));
        loaded.proposal().markSucceeded(lease);
        restarted.update(loaded);

        ActionProposalRepository.StoredProposal completed = new JdbcActionProposalRepository(jdbcTemplate, outbox)
                .findByPublicId(stored.proposal().publicId()).orElseThrow();
        assertEquals(ProposalState.SUCCEEDED, completed.proposal().state());
        assertEquals(99L, completed.result().resourceId());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_action_approval", Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_action_execution", Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_tool_call tc "
                + "JOIN ai_action_proposal p ON p.origin_tool_call_id=tc.id "
                + "JOIN ai_run r ON r.id=p.run_id WHERE r.public_id=? AND tc.tool_name=?", Integer.class,
                origin.runPublicId(), origin.toolName()));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_action_proposal WHERE run_id=0", Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_outbox_event "
                + "WHERE aggregate_public_id=? AND event_type='ActionProposalCreated.v1'", Integer.class,
                stored.proposal().publicId()));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_outbox_event "
                + "WHERE aggregate_public_id=? AND event_type='ActionExecutionSucceeded.v1'", Integer.class,
                stored.proposal().publicId()));
    }

    @Test
    void createIdempotencyAndOriginIdentitySurviveRestartAndRejectPayloadMismatch() {
        ProposalOrigin origin = originRun("NOTICE", 7L);
        ActionProposalRepository.StoredProposal first = storedProposal();
        ActionProposalRepository.CreateRequest request = createRequest(7L, "stable-create-key", '1');
        ActionProposalRepository.CreateResult created = repository.createOrReplay(first, origin, request);

        JdbcActionProposalRepository restarted = new JdbcActionProposalRepository(jdbcTemplate, outbox);
        ActionProposalRepository.CreateResult replay = restarted.createOrReplay(storedProposal(), origin, request);

        assertEquals(created.proposal().proposal().publicId(), replay.proposal().proposal().publicId());
        assertEquals(origin.runPublicId(), replay.proposal().runId());
        assertEquals(origin.runPublicId(), restarted.findByPublicId(
                created.proposal().proposal().publicId()).orElseThrow().runId());
        assertEquals(true, replay.replayed());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_action_proposal", Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_tool_call", Integer.class));
        assertThrows(RuntimeException.class, () -> restarted.createOrReplay(
                storedProposal(), origin, createRequest(7L, "stable-create-key", '2')));
    }

    @Test
    void optimisticVersionPreventsTwoRepositoryInstancesFromApprovingSameVersion() {
        ActionProposalRepository.StoredProposal created = storedProposal();
        repository.createOrReplay(created, originRun("NOTICE", 7L), createRequest(7L, "create-key", '0'));
        JdbcActionProposalRepository second = new JdbcActionProposalRepository(jdbcTemplate, outbox);
        ActionProposalRepository.StoredProposal left = repository.findByPublicId(created.proposal().publicId()).orElseThrow();
        ActionProposalRepository.StoredProposal right = second.findByPublicId(created.proposal().publicId()).orElseThrow();
        BusinessExecutionActor actor = BusinessExecutionActor.from(ActorDescriptor.user(7));
        left.proposal().approve(0, hash('a'), hash('b'), hash('b'), actor,
                Set.of("ai:approval:review", "notice:write"), "left", hash('c'));
        right.proposal().approve(0, hash('a'), hash('b'), hash('b'), actor,
                Set.of("ai:approval:review", "notice:write"), "right", hash('d'));

        repository.update(left);
        assertThrows(ProposalConflictException.class, () -> second.update(right));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_action_approval", Integer.class));
    }

    @Test
    void staleExecutingLeaseMovesToNeedsReviewAndIsNeverAutomaticallyReplayed() {
        ActionProposalRepository.StoredProposal stored = storedProposal();
        repository.createOrReplay(stored, originRun("NOTICE", 7L), createRequest(7L, "create-key", '0'));
        BusinessExecutionActor actor = BusinessExecutionActor.from(ActorDescriptor.user(7));
        stored.proposal().approve(0, hash('a'), hash('b'), hash('b'), actor,
                Set.of("ai:approval:review", "notice:write"), "approval-key", hash('c'));
        repository.update(stored);
        assertNotNull(stored.proposal().acquireExecutionLease(actor));
        repository.update(stored);
        jdbcTemplate.update("UPDATE ai_action_execution SET started_at=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(600)));

        var recovered = repository.markStaleExecutionsNeedsReview(Instant.now().minusSeconds(300));

        assertEquals(java.util.List.of(stored.proposal().publicId()), recovered);
        assertEquals("NEEDS_REVIEW", jdbcTemplate.queryForObject(
                "SELECT state FROM ai_action_proposal WHERE public_id=?", String.class,
                stored.proposal().publicId()));
        assertEquals("NEEDS_REVIEW", jdbcTemplate.queryForObject(
                "SELECT state FROM ai_action_execution", String.class));
        assertEquals(0, repository.markStaleExecutionsNeedsReview(Instant.now()).size());
    }

    @Test
    void expiredAndStaleDecisionPathsAreCommittedBeforeConflictIsReturned() {
        BusinessExecutionActor actor = BusinessExecutionActor.from(ActorDescriptor.user(7));
        ActionProposal expiredProposal = ActionProposal.pending(UUID.randomUUID().toString(),
                ActionType.NOTICE_CREATE_DRAFT, "{\"content\":\"test\",\"status\":\"草稿\"}",
                hash('a'), hash('b'), "notice:write", 1, Instant.now().minusSeconds(1));
        ActionProposalRepository.StoredProposal expired = new ActionProposalRepository.StoredProposal(
                expiredProposal, "NOTICE", null, preview(), "MEDIUM", 7L, Instant.now());
        repository.createOrReplay(expired, originRun("NOTICE", 7L), createRequest(7L, "expired-create", '3'));
        ActionProposalService service = service((type, payload) -> hash('b'), (a, action) -> {
            throw new AssertionError("冲突路径不得执行业务写");
        }, false);

        assertThrows(ProposalConflictException.class, () -> service.approve(
                expiredProposal.publicId(), 0, hash('a'), hash('b'), actor,
                Set.of("ai:approval:review", "notice:write"), "approve-expired", hash('4')));
        assertEquals("EXPIRED", jdbcTemplate.queryForObject(
                "SELECT state FROM ai_action_proposal WHERE public_id=?", String.class, expiredProposal.publicId()));

        ActionProposalRepository.StoredProposal stale = storedProposal();
        repository.createOrReplay(stale, originRun("NOTICE", 7L), createRequest(7L, "stale-create", '5'));
        ActionProposalService staleService = service((type, payload) -> hash('c'), (a, action) -> {
            throw new AssertionError("冲突路径不得执行业务写");
        }, false);
        assertThrows(ProposalConflictException.class, () -> staleService.approve(
                stale.proposal().publicId(), 0, hash('a'), hash('b'), actor,
                Set.of("ai:approval:review", "notice:write"), "approve-stale", hash('6')));
        assertEquals("STALE", jdbcTemplate.queryForObject(
                "SELECT state FROM ai_action_proposal WHERE public_id=?", String.class,
                stale.proposal().publicId()));
    }

    @Test
    void businessFailureAndUnknownCommitOutcomePersistIndependentTerminalFacts() {
        BusinessExecutionActor actor = BusinessExecutionActor.from(ActorDescriptor.user(7));
        ActionProposalRepository.StoredProposal failed = storedProposal();
        repository.createOrReplay(failed, originRun("NOTICE", 7L), createRequest(7L, "failed-create", '7'));
        ActionProposalService failedService = service((type, payload) -> hash('b'),
                (a, action) -> { throw new IllegalStateException("simulated business failure"); }, true);
        assertThrows(IllegalStateException.class, () -> failedService.approve(
                failed.proposal().publicId(), 0, hash('a'), hash('b'), actor,
                Set.of("ai:approval:review", "notice:write"), "failed-approve", hash('8')));
        assertEquals("FAILED", proposalState(failed.proposal().publicId()));
        assertEquals("AI_BUSINESS_EXECUTION_FAILED", executionError(failed.proposal().publicId()));

        ActionProposalRepository.StoredProposal uncertain = storedProposal();
        repository.createOrReplay(uncertain, originRun("NOTICE", 7L), createRequest(7L, "unknown-create", '9'));
        ActionProposalService uncertainService = service((type, payload) -> hash('b'),
                (a, action) -> { throw new org.springframework.transaction.TransactionSystemException("unknown"); },
                true);
        assertThrows(org.springframework.transaction.TransactionSystemException.class, () -> uncertainService.approve(
                uncertain.proposal().publicId(), 0, hash('a'), hash('b'), actor,
                Set.of("ai:approval:review", "notice:write"), "unknown-approve", hash('0')));
        assertEquals("NEEDS_REVIEW", proposalState(uncertain.proposal().publicId()));
        assertEquals("AI_EXECUTION_OUTCOME_UNKNOWN", executionError(uncertain.proposal().publicId()));
    }

    @Test
    void publicQueriesCreateReplayAndScheduledExpiryCoverAllBoundaryModes() {
        JdbcActionProposalRepository oneArgument = new JdbcActionProposalRepository(jdbcTemplate);
        ProposalOrigin origin = originRun("NOTICE", 7L);
        ActionProposalRepository.CreateRequest request = createRequest(7L, "query-create", '1');
        assertTrue(repository.findCreateReplay(origin, request).isEmpty());
        ActionProposalRepository.StoredProposal stored = storedProposal();
        repository.createOrReplay(stored, origin, request);

        assertEquals(stored.proposal().publicId(),
                repository.findCreateReplay(origin, request).orElseThrow().proposal().publicId());
        ProposalConflictException mismatch = assertThrows(ProposalConflictException.class,
                () -> repository.findCreateReplay(origin, createRequest(7L, "query-create", '2')));
        assertEquals("AI_IDEMPOTENCY_PAYLOAD_MISMATCH", mismatch.errorCode());
        jdbcTemplate.update("UPDATE ai_idempotency_record SET state='PENDING',"
                        + "response_status=NULL,response_resource_public_id=NULL WHERE route_code='AI_PROPOSAL_CREATE' "
                        + "AND idempotency_key='query-create'");
        ProposalConflictException pending = assertThrows(ProposalConflictException.class,
                () -> repository.findCreateReplay(origin, request));
        assertEquals("AI_PROPOSAL_CREATE_IN_PROGRESS", pending.errorCode());

        assertThrows(IllegalArgumentException.class, () -> repository.findByState(null, -1, 10));
        assertThrows(IllegalArgumentException.class, () -> repository.findByState(null, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> repository.findByState(null, 0, 101));
        assertThrows(IllegalArgumentException.class, () -> repository.expireDue(null));
        assertThrows(IllegalArgumentException.class, () -> repository.markStaleExecutionsNeedsReview(null));
        assertEquals(1, repository.findByState(null, 0, 100).size());
        assertEquals(1, repository.findByState(ProposalState.PENDING_APPROVAL, 0, 100).size());
        assertEquals(1, repository.countByState(null));
        assertEquals(1, repository.countByState(ProposalState.PENDING_APPROVAL));
        assertTrue(oneArgument.findByExecutionPublicId(UUID.randomUUID().toString()).isEmpty());

        ActionProposal expiredProposal = ActionProposal.pending(UUID.randomUUID().toString(),
                ActionType.NOTICE_CREATE_DRAFT, "{\"content\":\"expired\",\"status\":\"草稿\"}",
                hash('3'), hash('4'), "notice:write", 1, Instant.now().minusSeconds(1));
        ActionProposalRepository.StoredProposal expired = new ActionProposalRepository.StoredProposal(
                expiredProposal, "NOTICE", null, preview(), "MEDIUM", 7L, Instant.now());
        repository.createOrReplay(expired, originRun("NOTICE", 7L), createRequest(7L, "expired-scan", '5'));
        assertEquals(List.of(expiredProposal.publicId()), repository.expireDue(Instant.now()).stream()
                .map(value -> value.proposal().publicId()).toList());
        assertEquals(ProposalState.EXPIRED,
                repository.findByPublicId(expiredProposal.publicId()).orElseThrow().proposal().state());
    }

    @Test
    void actionTypeCriteriaFiltersJdbcRecordsAndCountWithTheSamePredicate() {
        ActionProposalRepository.StoredProposal notice = storedProposal();
        repository.createOrReplay(notice, originRun("NOTICE", 7L),
                createRequest(7L, "notice-query-filter", '1'));
        ActionProposal repairProposal = ActionProposal.pending(UUID.randomUUID().toString(),
                ActionType.REPAIR_ASSIGN, "{\"assigneeUserId\":9,\"repairOrderId\":1}",
                hash('2'), hash('3'), "repair:write", 1, Instant.now().plusSeconds(600));
        ActionProposalRepository.StoredProposal repair = new ActionProposalRepository.StoredProposal(
                repairProposal, "REPAIR_ORDER", 1L, preview(), "HIGH", 7L, Instant.now());
        repository.createOrReplay(repair, originRun("REPAIR", 7L, ActionType.REPAIR_ASSIGN),
                createRequest(7L, "repair-query-filter", '4'));

        assertEquals(1, repository.countByStateAndActionType(
                null, ActionType.REPAIR_ASSIGN));
        assertEquals(1, repository.countByStateAndActionType(
                ProposalState.PENDING_APPROVAL, ActionType.NOTICE_CREATE_DRAFT));
        assertEquals(ActionType.REPAIR_ASSIGN,
                repository.findByStateAndActionType(null, ActionType.REPAIR_ASSIGN, 0, 1)
                        .getFirst().proposal().actionType());
        assertEquals(ActionType.NOTICE_CREATE_DRAFT,
                repository.findByStateAndActionType(ProposalState.PENDING_APPROVAL,
                                ActionType.NOTICE_CREATE_DRAFT, 0, 1)
                        .getFirst().proposal().actionType());
    }

    @Test
    void originRunActorCapabilityStateAndRepairPermissionAreBoundToTheProposal() {
        ProposalOrigin wrongActor = originRun("NOTICE", 7L);
        assertThrows(SecurityException.class, () -> repository.createOrReplay(
                storedProposal(), wrongActor, createRequest(8L, "wrong-actor", '1')));

        ProposalOrigin wrongCapability = originRun("REPAIR", 7L);
        assertThrows(SecurityException.class, () -> repository.createOrReplay(
                storedProposal(), wrongCapability, createRequest(7L, "wrong-capability", '2')));

        ProposalOrigin wrongState = originRun("NOTICE", 7L);
        jdbcTemplate.update("UPDATE ai_run SET state='FAILED' WHERE public_id=?", wrongState.runPublicId());
        assertThrows(SecurityException.class, () -> repository.createOrReplay(
                storedProposal(), wrongState, createRequest(7L, "wrong-state", '3')));

        ActionProposal repair = ActionProposal.pending(UUID.randomUUID().toString(), ActionType.REPAIR_ASSIGN,
                "{\"assigneeUserId\":9,\"repairOrderId\":1}", hash('4'), hash('5'),
                "repair:write", 1, Instant.now().plusSeconds(600));
        ActionProposalRepository.StoredProposal repairStored = new ActionProposalRepository.StoredProposal(
                repair, "REPAIR_ORDER", 1L, preview(), "HIGH", 7L, Instant.now());
        ProposalOrigin repairOrigin = originRun("REPAIR", 7L, ActionType.REPAIR_ASSIGN);
        assertFalse(repository.createOrReplay(
                repairStored, repairOrigin, createRequest(7L, "repair-create", '6')).replayed());
        assertEquals("[\"ai:repair:triage\",\"repair:read\"]", jdbcTemplate.queryForObject(
                "SELECT required_permissions_text FROM ai_tool_call WHERE public_id=(SELECT tc.public_id "
                        + "FROM ai_tool_call tc JOIN ai_action_proposal p ON p.origin_tool_call_id=tc.id "
                        + "WHERE p.public_id=?)", String.class, repair.publicId()));
    }

    @Test
    void existingOriginToolCanOnlyReplayTheSameCompleteProposal() {
        ProposalOrigin origin = originRun("NOTICE", 7L);
        ActionProposalRepository.StoredProposal first = storedProposal();
        repository.createOrReplay(first, origin, createRequest(7L, "origin-first", '1'));

        ActionProposalRepository.CreateResult replay = repository.createOrReplay(
                storedProposal(), origin, createRequest(7L, "origin-replay", '2'));
        assertTrue(replay.replayed());
        assertEquals(first.proposal().publicId(), replay.proposal().proposal().publicId());

        ActionProposal different = ActionProposal.pending(UUID.randomUUID().toString(),
                ActionType.NOTICE_CREATE_DRAFT, "{\"content\":\"different\",\"status\":\"草稿\"}",
                hash('9'), hash('b'), "notice:write", 1, Instant.now().plusSeconds(600));
        ActionProposalRepository.StoredProposal differentStored = new ActionProposalRepository.StoredProposal(
                different, "NOTICE", null, preview(), "MEDIUM", 7L, Instant.now());
        ProposalConflictException conflict = assertThrows(ProposalConflictException.class,
                () -> repository.createOrReplay(
                        differentStored, origin, createRequest(7L, "origin-conflict", '3')));
        assertEquals("AI_PROPOSAL_ORIGIN_CONFLICT", conflict.errorCode());

        jdbcTemplate.update("DELETE FROM ai_action_proposal WHERE public_id=?", first.proposal().publicId());
        ProposalConflictException incomplete = assertThrows(ProposalConflictException.class,
                () -> repository.createOrReplay(
                        storedProposal(), origin, createRequest(7L, "origin-incomplete", '4')));
        assertEquals("AI_PROPOSAL_ORIGIN_INCOMPLETE", incomplete.errorCode());
    }

    @Test
    void partialApprovalAndRejectionRestoreTheirDistinctResponses() {
        BusinessExecutionActor actor = BusinessExecutionActor.from(ActorDescriptor.user(7));
        ActionProposal partialProposal = ActionProposal.pending(UUID.randomUUID().toString(),
                ActionType.NOTICE_CREATE_DRAFT, "{\"content\":\"partial\",\"status\":\"草稿\"}",
                hash('a'), hash('b'), "notice:write", 2, Instant.now().plusSeconds(600));
        ActionProposalRepository.StoredProposal partial = new ActionProposalRepository.StoredProposal(
                partialProposal, "NOTICE", null, preview(), "MEDIUM", 7L, Instant.now());
        repository.createOrReplay(partial, originRun("NOTICE", 7L), createRequest(7L, "partial-create", '1'));
        partialProposal.approve(0, hash('a'), hash('b'), hash('b'), actor,
                Set.of("ai:approval:review", "notice:write"), "partial-approval", hash('2'));
        repository.update(partial);
        repository.update(partial);
        ActionProposalRepository.StoredProposal partialReloaded =
                repository.findByPublicId(partialProposal.publicId()).orElseThrow();
        assertEquals(ProposalState.PENDING_APPROVAL, partialReloaded.proposal().state());
        assertEquals(1, partialReloaded.proposal().approvals().size());

        ActionProposalRepository.StoredProposal rejected = storedProposal();
        repository.createOrReplay(rejected, originRun("NOTICE", 7L), createRequest(7L, "reject-create", '3'));
        rejected.proposal().reject(0, actor, Set.of("ai:approval:review", "notice:write"),
                "reject-key", hash('4'), "不批准");
        repository.update(rejected);
        ActionProposalRepository.StoredProposal rejectedReloaded =
                repository.findByPublicId(rejected.proposal().publicId()).orElseThrow();
        assertEquals(ProposalState.REJECTED, rejectedReloaded.proposal().state());
        assertEquals("REJECT", rejectedReloaded.proposal().approvals().getFirst().decision());
    }

    private ActionProposalRepository.StoredProposal storedProposal() {
        String publicId = UUID.randomUUID().toString();
        ActionProposal proposal = ActionProposal.pending(publicId, ActionType.NOTICE_CREATE_DRAFT,
                "{\"content\":\"test\",\"status\":\"草稿\"}", hash('a'), hash('b'),
                "notice:write", 1, Instant.now().plusSeconds(600));
        return new ActionProposalRepository.StoredProposal(
                proposal, "NOTICE", null, preview(), "MEDIUM", 7L, Instant.now());
    }

    private ProposalPreview preview() {
        return new ProposalPreview("当前无草稿", "创建测试草稿", "仅创建不发布", Instant.now(),
                ProposalPreview.EvidenceBasis.DETERMINISTIC, null,
                java.util.List.of(new ProposalPreview.Citation(
                        "USER_COMMAND", "RUN:test", "测试命令", hash('a'))));
    }

    private ActionProposalService service(
            ActionProposalService.BusinessSnapshotProvider snapshots,
            com.example.dormitory.ai.port.ApprovedBusinessActionPort actions,
            boolean writes) {
        return new ActionProposalService(repository, actions, snapshots,
                new com.example.dormitory.ai.port.AiAuditPort() {
                    @Override public boolean writable() { return true; }
                    @Override public void append(com.example.dormitory.ai.port.AiAuditPort.AiAuditEvent event) { }
                }, () -> writes, transactions);
    }

    private String proposalState(String publicId) {
        return jdbcTemplate.queryForObject(
                "SELECT state FROM ai_action_proposal WHERE public_id=?", String.class, publicId);
    }

    private String executionError(String publicId) {
        return jdbcTemplate.queryForObject("SELECT e.error_code FROM ai_action_execution e "
                + "JOIN ai_action_proposal p ON p.id=e.proposal_id WHERE p.public_id=?", String.class, publicId);
    }

    private ActionProposalRepository.CreateRequest createRequest(long actor, String key, char hash) {
        return new ActionProposalRepository.CreateRequest(actor, key, hash(hash), Instant.now().plusSeconds(3600));
    }

    private ProposalOrigin originRun(String capability, long actor) {
        return originRun(capability, actor, ActionType.NOTICE_CREATE_DRAFT);
    }

    private ProposalOrigin originRun(String capability, long actor, ActionType actionType) {
        String conversation = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO ai_conversation "
                        + "(public_id,owner_user_id,surface,context_type,status,created_operator_user_id,"
                        + "updated_operator_user_id,created_at,updated_at) "
                        + "VALUES (?,?,'NOTICE','COMMAND','ACTIVE',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                conversation, actor, actor, actor);
        long conversationId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_conversation WHERE public_id=?", Long.class, conversation);
        String message = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO ai_message "
                        + "(public_id,conversation_id,sequence_no,role,content_redacted,classification,created_at) "
                        + "VALUES (?,?,1,'USER','{}','L1',CURRENT_TIMESTAMP)", message, conversationId);
        long messageId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_message WHERE public_id=?", Long.class, message);
        String run = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO ai_run "
                        + "(public_id,conversation_id,request_message_id,capability,state,version,actor_user_id,"
                        + "session_fingerprint_hash,session_fingerprint_key_version,permission_digest,"
                        + "prompt_version_id,tool_catalog_version_id,retrieval_policy_version,redaction_policy_version,"
                        + "reserved_tokens,reserved_cost,correlation_id,cost_status,created_operator_user_id,"
                        + "updated_operator_user_id,created_at,updated_at) "
                        + "VALUES (?,?,?,?,'RUNNING',0,?,?,1,?,1,1,'none.v1','pii-redaction-v2',0,0,?,"
                        + "'FINAL',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                run, conversationId, messageId, capability, actor, hash('e'), hash('f'),
                UUID.randomUUID().toString(), actor, actor);
        return ProposalOrigin.forAction(run, actionType);
    }

    private String hash(char value) {
        return String.valueOf(value).repeat(64);
    }
}
