package com.example.dormitory.ai.persistence;

import com.example.dormitory.ai.approval.ActionProposal;
import com.example.dormitory.ai.approval.ActionProposalRepository;
import com.example.dormitory.ai.approval.ActionType;
import com.example.dormitory.ai.approval.ProposalConflictException;
import com.example.dormitory.ai.approval.ProposalOrigin;
import com.example.dormitory.ai.approval.ProposalPreview;
import com.example.dormitory.ai.approval.ProposalState;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.BusinessExecutionActor;
import com.example.dormitory.ai.infrastructure.persistence.JdbcActionProposalRepository;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiOutboxRepository;
import com.example.dormitory.ai.port.ApprovedBusinessActionPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ActiveProfiles("test")
@SpringBootTest(properties =
        "dormitory.ai.audit.hmac-key=0123456789abcdef0123456789abcdef")
class JdbcActionProposalRepositoryBranchTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JdbcActionProposalRepository repository;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM ai_action_execution");
        jdbc.update("DELETE FROM ai_action_approval");
        jdbc.update("DELETE FROM ai_idempotency_record");
        jdbc.update("DELETE FROM ai_action_proposal");
        jdbc.update("DELETE FROM ai_outbox_event");
        jdbc.update("DELETE FROM ai_tool_call");
        jdbc.update("DELETE FROM ai_run");
        jdbc.update("DELETE FROM ai_message");
        jdbc.update("DELETE FROM ai_conversation");
    }

    @Test
    void createRejectsMissingOriginRunAfterReservingItsIdempotencyScope() {
        ProposalOrigin missing = ProposalOrigin.forAction(
                UUID.randomUUID().toString(), ActionType.NOTICE_CREATE_DRAFT);

        ProposalConflictException failure = assertThrows(ProposalConflictException.class,
                () -> repository.createOrReplay(
                        storedProposal(), missing, request("missing-origin", '1')));

        assertEquals("AI_PROPOSAL_ORIGIN_NOT_FOUND", failure.errorCode());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_action_proposal", Integer.class));
    }

    @Test
    void createReplayFailsClosedWhilePendingOrPointingAtMissingProposal() {
        ProposalOrigin origin = ProposalOrigin.forAction(
                UUID.randomUUID().toString(), ActionType.NOTICE_CREATE_DRAFT);
        ActionProposalRepository.CreateRequest request = request("create-replay", '2');
        insertCreateIdempotency(origin, request, "PENDING", null);

        ProposalConflictException pending = assertThrows(ProposalConflictException.class,
                () -> repository.createOrReplay(storedProposal(), origin, request));
        assertEquals("AI_PROPOSAL_CREATE_IN_PROGRESS", pending.errorCode());

        jdbc.update("UPDATE ai_idempotency_record SET state='COMPLETED',response_status=201,"
                        + "response_resource_public_id=? WHERE route_code='AI_PROPOSAL_CREATE'",
                UUID.randomUUID().toString());
        assertThrows(NoSuchElementException.class,
                () -> repository.createOrReplay(storedProposal(), origin, request));
    }

    @Test
    void executionLifecyclePersistsReviewResumeAndTerminalResultAcrossReloads() {
        ActionProposalRepository.StoredProposal stored = storedProposal();
        repository.createOrReplay(stored, originRun(), request("lifecycle-create", '3'));
        BusinessExecutionActor actor = BusinessExecutionActor.from(ActorDescriptor.user(7));
        stored.proposal().approve(0, hash('a'), hash('b'), hash('b'), actor,
                permissions(), "approve", hash('4'));
        repository.update(stored);

        ActionProposal.ExecutionLease first = stored.proposal().acquireExecutionLease(actor);
        repository.update(stored);
        stored.proposal().markNeedsReview(first);
        stored.executionFailure("AI_EXECUTION_OUTCOME_UNKNOWN", "outcome requires review");
        repository.update(stored);

        ActionProposal.ExecutionLease resumed = stored.proposal().resumeAfterReconfirmation(
                stored.proposal().version(), actor, permissions());
        stored.clearExecutionFailure();
        repository.update(stored);
        stored.result(new ApprovedBusinessActionPort.BusinessActionResult(
                "NOTICE", 99L, hash('5')));
        stored.proposal().markSucceeded(resumed);
        repository.update(stored);

        ActionProposalRepository.StoredProposal reloaded = repository.findByPublicId(
                stored.proposal().publicId()).orElseThrow();
        assertEquals(ProposalState.SUCCEEDED, reloaded.proposal().state());
        assertEquals(4, reloaded.proposal().executionLease().leaseVersion());
        assertEquals(99L, reloaded.result().resourceId());
        assertFalse(repository.findByExecutionPublicId(reloaded.executionPublicId()).isEmpty());
        assertEquals("SUCCEEDED", jdbc.queryForObject(
                "SELECT state FROM ai_action_execution", String.class));
    }

    @Test
    void repeatedUnchangedExecutionPersistenceIsRejectedAsIllegalTransition() {
        ActionProposalRepository.StoredProposal stored = storedProposal();
        repository.createOrReplay(stored, originRun(), request("no-op-create", '6'));
        BusinessExecutionActor actor = BusinessExecutionActor.from(ActorDescriptor.user(7));
        stored.proposal().approve(0, hash('a'), hash('b'), hash('b'), actor,
                permissions(), "approve-no-op", hash('7'));
        repository.update(stored);
        stored.proposal().acquireExecutionLease(actor);
        repository.update(stored);

        ProposalConflictException failure = assertThrows(ProposalConflictException.class,
                () -> repository.update(stored));

        assertEquals("AI_EXECUTION_STATE_CONFLICT", failure.errorCode());
    }

    @Test
    void emptyRepositoryQueriesAndConstructorOverloadsKeepStableSemantics() {
        JdbcActionProposalRepository oneArgument = new JdbcActionProposalRepository(jdbc);
        JdbcActionProposalRepository twoArguments = new JdbcActionProposalRepository(
                jdbc, new JdbcAiOutboxRepository(jdbc));

        assertTrue(oneArgument.findByPublicId(UUID.randomUUID().toString()).isEmpty());
        assertTrue(twoArguments.findByExecutionPublicId(UUID.randomUUID().toString()).isEmpty());
        assertEquals(0, oneArgument.countByState(null));
        assertEquals(0, oneArgument.countByState(ProposalState.PENDING_APPROVAL));
        assertTrue(oneArgument.findByState(null, 0, 10).isEmpty());
        assertTrue(oneArgument.findByState(ProposalState.PENDING_APPROVAL, 0, 10).isEmpty());
    }

    private void insertCreateIdempotency(
            ProposalOrigin origin,
            ActionProposalRepository.CreateRequest request,
            String state,
            String responsePublicId) {
        jdbc.update("INSERT INTO ai_idempotency_record "
                        + "(actor_user_id,route_code,aggregate_public_id,idempotency_key,request_hash,state,"
                        + "response_status,response_resource_public_id,expires_at,version,created_at,updated_at) "
                        + "VALUES (?,'AI_PROPOSAL_CREATE',?,?,?,?,?,?,?,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                request.actorUserId(), origin.runPublicId(), request.idempotencyKey(), request.requestHash(),
                state, responsePublicId == null ? null : 201, responsePublicId,
                Timestamp.from(request.expiresAt()));
    }

    private ActionProposalRepository.StoredProposal storedProposal() {
        ActionProposal proposal = ActionProposal.pending(
                UUID.randomUUID().toString(), ActionType.NOTICE_CREATE_DRAFT,
                "{\"content\":\"branch test\",\"status\":\"草稿\"}",
                hash('a'), hash('b'), "notice:write", 1, Instant.now().plusSeconds(600));
        return new ActionProposalRepository.StoredProposal(
                proposal, "NOTICE", null, preview(), "MEDIUM", 7L, Instant.now());
    }

    private ProposalOrigin originRun() {
        String conversation = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ai_conversation "
                        + "(public_id,owner_user_id,surface,context_type,status,created_operator_user_id,"
                        + "updated_operator_user_id,created_at,updated_at) "
                        + "VALUES (?,7,'NOTICE','COMMAND','ACTIVE',7,7,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                conversation);
        long conversationId = jdbc.queryForObject(
                "SELECT id FROM ai_conversation WHERE public_id=?", Long.class, conversation);
        String message = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ai_message "
                        + "(public_id,conversation_id,sequence_no,role,content_redacted,classification,created_at) "
                        + "VALUES (?,?,1,'USER','{}','L1',CURRENT_TIMESTAMP)",
                message, conversationId);
        long messageId = jdbc.queryForObject(
                "SELECT id FROM ai_message WHERE public_id=?", Long.class, message);
        String run = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ai_run "
                        + "(public_id,conversation_id,request_message_id,capability,state,version,actor_user_id,"
                        + "session_fingerprint_hash,session_fingerprint_key_version,permission_digest,"
                        + "prompt_version_id,tool_catalog_version_id,retrieval_policy_version,redaction_policy_version,"
                        + "reserved_tokens,reserved_cost,correlation_id,cost_status,created_operator_user_id,"
                        + "updated_operator_user_id,created_at,updated_at) "
                        + "VALUES (?,?,?,'NOTICE','RUNNING',0,7,?,1,?,1,1,'none.v1','pii-redaction-v2',0,0,?,"
                        + "'FINAL',7,7,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                run, conversationId, messageId, hash('e'), hash('f'), UUID.randomUUID().toString());
        return ProposalOrigin.forAction(run, ActionType.NOTICE_CREATE_DRAFT);
    }

    private ActionProposalRepository.CreateRequest request(String key, char requestHash) {
        return new ActionProposalRepository.CreateRequest(
                7L, key, hash(requestHash), Instant.now().plusSeconds(3600));
    }

    private ProposalPreview preview() {
        return new ProposalPreview("no draft", "create draft", "draft only", Instant.now(),
                ProposalPreview.EvidenceBasis.DETERMINISTIC, null,
                List.of(new ProposalPreview.Citation(
                        "USER_COMMAND", "RUN:test", "test command", hash('a'))));
    }

    private Set<String> permissions() {
        return Set.of("ai:approval:review", "notice:write");
    }

    private String hash(char value) {
        return String.valueOf(value).repeat(64);
    }
}
