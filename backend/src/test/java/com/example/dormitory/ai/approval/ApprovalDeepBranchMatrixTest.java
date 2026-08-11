package com.example.dormitory.ai.approval;

import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.BusinessExecutionActor;
import com.example.dormitory.ai.port.AiAuditPort;
import com.example.dormitory.ai.port.ApprovedBusinessActionPort;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.HeuristicCompletionException;
import org.springframework.transaction.TransactionSystemException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApprovalDeepBranchMatrixTest {

    private static final BusinessExecutionActor REVIEWER =
            BusinessExecutionActor.from(ActorDescriptor.user(7));
    private static final BusinessExecutionActor SECOND_REVIEWER =
            BusinessExecutionActor.from(ActorDescriptor.user(8));

    @Test
    void proposalConstructorRejectsEveryIncompleteContractField() {
        Instant expiresAt = Instant.now().plusSeconds(60);
        assertThrows(IllegalArgumentException.class, () -> ActionProposal.pending(
                null, ActionType.REPAIR_ASSIGN, repairPayload(), hash('a'), hash('b'),
                "repair:write", 1, expiresAt));
        assertThrows(IllegalArgumentException.class, () -> ActionProposal.pending(
                UUID.randomUUID().toString(), null, repairPayload(), hash('a'), hash('b'),
                "repair:write", 1, expiresAt));
        assertThrows(IllegalArgumentException.class, () -> ActionProposal.pending(
                UUID.randomUUID().toString(), ActionType.REPAIR_ASSIGN, null, hash('a'), hash('b'),
                "repair:write", 1, expiresAt));
        assertThrows(IllegalArgumentException.class, () -> ActionProposal.pending(
                UUID.randomUUID().toString(), ActionType.REPAIR_ASSIGN, repairPayload(), null, hash('b'),
                "repair:write", 1, expiresAt));
        assertThrows(IllegalArgumentException.class, () -> ActionProposal.pending(
                UUID.randomUUID().toString(), ActionType.REPAIR_ASSIGN, repairPayload(), hash('a'), null,
                "repair:write", 1, expiresAt));
        assertThrows(IllegalArgumentException.class, () -> ActionProposal.pending(
                UUID.randomUUID().toString(), ActionType.REPAIR_ASSIGN, repairPayload(), hash('a'), hash('b'),
                null, 1, expiresAt));
        assertThrows(IllegalArgumentException.class, () -> ActionProposal.pending(
                UUID.randomUUID().toString(), ActionType.REPAIR_ASSIGN, repairPayload(), hash('a'), hash('b'),
                "repair:write", 0, expiresAt));
        assertThrows(IllegalArgumentException.class, () -> ActionProposal.pending(
                UUID.randomUUID().toString(), ActionType.REPAIR_ASSIGN, repairPayload(), hash('a'), hash('b'),
                "repair:write", 1, null));
    }

    @Test
    void approvalHashStateReplayAndDuplicateReviewerBranchesRemainFailClosed() {
        assertThrows(ProposalConflictException.class, () -> proposal(1).approve(
                1, hash('a'), hash('b'), hash('b'), REVIEWER, repairPermissions(), "wrong-version", hash('1')));
        assertThrows(ProposalConflictException.class, () -> proposal(1).approve(
                0, hash('c'), hash('b'), hash('b'), REVIEWER, repairPermissions(), "wrong-payload", hash('2')));
        assertThrows(ProposalConflictException.class, () -> proposal(1).approve(
                0, hash('a'), hash('c'), hash('b'), REVIEWER, repairPermissions(), "wrong-snapshot", hash('3')));

        ActionProposal twoApprovals = proposal(2);
        twoApprovals.approve(0, hash('a'), hash('b'), hash('b'), REVIEWER, repairPermissions(),
                "first", hash('4'));
        ActionProposal.ApprovalDecision duplicateReviewer = twoApprovals.approve(
                1, hash('a'), hash('b'), hash('b'), REVIEWER, repairPermissions(),
                "same-reviewer-new-key", hash('5'));
        assertEquals(1, duplicateReviewer.approvalCount());
        assertEquals(ProposalState.PENDING_APPROVAL, duplicateReviewer.state());
        assertEquals(1, duplicateReviewer.proposalVersion());
        assertThrows(ProposalConflictException.class, () -> twoApprovals.approve(
                0, hash('a'), hash('b'), hash('b'), REVIEWER, repairPermissions(),
                "same-reviewer-stale-version", hash('6')));
        assertThrows(IdempotencyConflictException.class, () -> twoApprovals.approvalReplay(
                REVIEWER, "APPROVE", "first", hash('7')));

        twoApprovals.approve(1, hash('a'), hash('b'), hash('b'), SECOND_REVIEWER, repairPermissions(),
                "second-reviewer", hash('8'));
        assertEquals(ProposalState.APPROVED, twoApprovals.state());
        assertThrows(ProposalConflictException.class, () -> twoApprovals.approve(
                twoApprovals.version(), hash('a'), hash('b'), hash('b'), REVIEWER, repairPermissions(),
                "approved-state", hash('9')));
    }

    @Test
    void leaseTerminalPermissionReconfirmationAndRestoreBranchesRejectDrift() {
        ActionProposal succeeded = approvedProposal();
        ActionProposal.ExecutionLease succeededLease = succeeded.acquireExecutionLease(REVIEWER);
        succeeded.markSucceeded(succeededLease);
        assertThrows(IllegalStateException.class, () -> succeeded.markSucceeded(succeededLease));

        ActionProposal failed = approvedProposal();
        ActionProposal.ExecutionLease failedLease = failed.acquireExecutionLease(REVIEWER);
        failed.markFailed(failedLease);
        assertThrows(IllegalStateException.class, () -> failed.markFailed(failedLease));

        ActionProposal review = approvedProposal();
        ActionProposal.ExecutionLease reviewLease = review.acquireExecutionLease(REVIEWER);
        review.markNeedsReview(reviewLease);
        assertThrows(IllegalStateException.class, () -> review.markNeedsReview(reviewLease));
        assertThrows(SecurityException.class, () -> review.markSucceeded(null));

        ActionProposal withoutLease = proposal(1);
        ActionProposal.ExecutionLease forged = new ActionProposal.ExecutionLease(
                withoutLease.publicId(), hash('f'), REVIEWER.userId(), 1, Instant.now());
        assertThrows(SecurityException.class, () -> withoutLease.markSucceeded(forged));
        assertThrows(SecurityException.class,
                () -> withoutLease.validateReconfirmation(0, REVIEWER, null));
        assertThrows(ProposalConflictException.class,
                () -> withoutLease.validateReconfirmation(0, REVIEWER, repairPermissions()));

        ActionProposal restored = ActionProposal.restore(
                UUID.randomUUID().toString(), ActionType.REPAIR_ASSIGN, repairPayload(), hash('a'), hash('b'),
                "repair:write", 1, Instant.now().plusSeconds(60), ProposalState.NEEDS_REVIEW,
                3, null, null, null);
        assertTrue(restored.approvals().isEmpty());
        assertThrows(IllegalStateException.class,
                () -> restored.confirmRecoveredResult(3, REVIEWER, repairPermissions()));
    }

    @Test
    void createIdentityKeysAndHashesFailAtTheirOwnBoundary() {
        Harness harness = harness((type, payload) -> hash('b'), false, successAction(),
                (actor, proposal, supplied) -> supplied);
        ActionProposalService.CreateProposalCommand valid = repairCommand();

        assertThrows(IllegalArgumentException.class,
                () -> harness.service().create(null, "key", hash('1'), REVIEWER));
        assertThrows(IllegalArgumentException.class,
                () -> harness.service().create(valid, "key", hash('1'), null));
        assertThrows(IllegalArgumentException.class, () -> harness.service().create(new ActionProposalService.CreateProposalCommand(
                null, valid.targetType(), valid.targetResourceId(), valid.payloadJson(), valid.preview(),
                valid.requiredBusinessPermission(), 1, valid.riskLevel(), REVIEWER.userId(), valid.expiresAt(),
                valid.origin()), "key", hash('1'), REVIEWER));
        assertThrows(IllegalArgumentException.class, () -> harness.service().create(new ActionProposalService.CreateProposalCommand(
                valid.actionType(), valid.targetType(), valid.targetResourceId(), valid.payloadJson(), valid.preview(),
                valid.requiredBusinessPermission(), 1, valid.riskLevel(), REVIEWER.userId(), valid.expiresAt(),
                null), "key", hash('1'), REVIEWER));
        assertThrows(IllegalArgumentException.class, () -> harness.service().create(new ActionProposalService.CreateProposalCommand(
                valid.actionType(), valid.targetType(), valid.targetResourceId(), valid.payloadJson(), valid.preview(),
                valid.requiredBusinessPermission(), 1, valid.riskLevel(), REVIEWER.userId(), null,
                valid.origin()), "key", hash('1'), REVIEWER));
        assertThrows(IllegalArgumentException.class, () -> harness.service().create(new ActionProposalService.CreateProposalCommand(
                valid.actionType(), valid.targetType(), valid.targetResourceId(), valid.payloadJson(), valid.preview(),
                valid.requiredBusinessPermission(), 1, valid.riskLevel(), SECOND_REVIEWER.userId(), valid.expiresAt(),
                valid.origin()), "key", hash('1'), REVIEWER));

        assertThrows(IllegalArgumentException.class,
                () -> harness.service().create(valid, null, hash('1'), REVIEWER));
        assertThrows(IllegalArgumentException.class,
                () -> harness.service().create(valid, " ", hash('1'), REVIEWER));
        assertThrows(IllegalArgumentException.class,
                () -> harness.service().create(valid, "x".repeat(129), hash('1'), REVIEWER));
        assertThrows(IllegalArgumentException.class,
                () -> harness.service().create(valid, "key", null, REVIEWER));
        assertThrows(IllegalArgumentException.class,
                () -> harness.service().create(valid, "key", "BAD", REVIEWER));
    }

    @Test
    void createCommandAndActionSpecificContractsCoverEveryRemainingShortCircuit() {
        Harness harness = harness((type, payload) -> hash('b'), false, successAction(),
                (actor, proposal, supplied) -> supplied);
        ActionProposalService.CreateProposalCommand repair = repairCommand();

        assertInvalidCreate(harness, copy(repair, null, repair.targetResourceId(), repair.payloadJson(),
                repair.preview(), repair.requiredBusinessPermission(), repair.riskLevel(), repair.expiresAt()));
        assertInvalidCreate(harness, copy(repair, " ", repair.targetResourceId(), repair.payloadJson(),
                repair.preview(), repair.requiredBusinessPermission(), repair.riskLevel(), repair.expiresAt()));
        assertInvalidCreate(harness, copy(repair, repair.targetType(), repair.targetResourceId(), null,
                repair.preview(), repair.requiredBusinessPermission(), repair.riskLevel(), repair.expiresAt()));
        assertInvalidCreate(harness, copy(repair, repair.targetType(), repair.targetResourceId(), " ",
                repair.preview(), repair.requiredBusinessPermission(), repair.riskLevel(), repair.expiresAt()));
        assertInvalidCreate(harness, copy(repair, repair.targetType(), repair.targetResourceId(), repair.payloadJson(),
                null, repair.requiredBusinessPermission(), repair.riskLevel(), repair.expiresAt()));
        assertInvalidCreate(harness, copy(repair, repair.targetType(), repair.targetResourceId(), repair.payloadJson(),
                repair.preview(), repair.requiredBusinessPermission(), null, repair.expiresAt()));
        assertInvalidCreate(harness, copy(repair, repair.targetType(), repair.targetResourceId(), repair.payloadJson(),
                repair.preview(), repair.requiredBusinessPermission(), "CRITICAL", repair.expiresAt()));
        assertInvalidCreate(harness, copy(repair, repair.targetType(), repair.targetResourceId(), repair.payloadJson(),
                repair.preview(), repair.requiredBusinessPermission(), repair.riskLevel(), Instant.now().minusSeconds(1)));

        assertInvalidCreate(harness, copy(repair, "NOTICE", 1L, repair.payloadJson(), repair.preview(),
                "repair:write", repair.riskLevel(), repair.expiresAt()));
        assertInvalidCreate(harness, copy(repair, "REPAIR_ORDER", null, repair.payloadJson(), repair.preview(),
                "repair:write", repair.riskLevel(), repair.expiresAt()));
        assertInvalidCreate(harness, copy(repair, "REPAIR_ORDER", 0L, repair.payloadJson(), repair.preview(),
                "repair:write", repair.riskLevel(), repair.expiresAt()));
        assertInvalidCreate(harness, copy(repair, "REPAIR_ORDER", 1L, repair.payloadJson(), repair.preview(),
                "notice:write", repair.riskLevel(), repair.expiresAt()));

        ActionProposalService.CreateProposalCommand notice = noticeCommand();
        assertInvalidCreate(harness, copy(notice, "REPAIR_ORDER", null, notice.payloadJson(), notice.preview(),
                "notice:write", notice.riskLevel(), notice.expiresAt()));
        assertInvalidCreate(harness, copy(notice, "NOTICE", 1L, notice.payloadJson(), notice.preview(),
                "notice:write", notice.riskLevel(), notice.expiresAt()));
        assertInvalidCreate(harness, copy(notice, "NOTICE", null, notice.payloadJson(), notice.preview(),
                "repair:write", notice.riskLevel(), notice.expiresAt()));
        assertInvalidCreate(harness, copy(notice, "NOTICE", null,
                "{\"content\":\"published\",\"status\":\"published\"}", notice.preview(),
                "notice:write", notice.riskLevel(), notice.expiresAt()));
    }

    @Test
    void createNullSnapshotAndRepositoryRaceReplayAvoidFalseAuditEvents() {
        Harness nullSnapshot = harness((type, payload) -> null, false, successAction(),
                (actor, proposal, supplied) -> supplied);
        assertThrows(IllegalStateException.class, () -> nullSnapshot.service().create(
                repairCommand(), "null-snapshot", hash('1'), REVIEWER));

        ActionProposalRepository repository = mock(ActionProposalRepository.class);
        when(repository.findCreateReplay(any(), any())).thenReturn(Optional.empty());
        when(repository.createOrReplay(any(), any(), any())).thenAnswer(invocation ->
                new ActionProposalRepository.CreateResult(invocation.getArgument(0), true));
        AtomicInteger auditEvents = new AtomicInteger();
        ActionProposalService service = service(repository, (type, payload) -> hash('b'), false,
                successAction(), (actor, proposal, supplied) -> supplied, auditEvents);

        ActionProposalService.ProposalView replay = service.create(
                repairCommand(), "race-replay", hash('2'), REVIEWER);

        assertEquals(ProposalState.PENDING_APPROVAL, replay.state());
        assertEquals(0, auditEvents.get());
    }

    @Test
    void paginationAuthorizationPermissionNullsCommentAndUuidInputsFailClosed() {
        Harness harness = harness((type, payload) -> hash('b'), false, successAction(),
                (actor, proposal, supplied) -> supplied);
        assertThrows(IllegalArgumentException.class, () -> harness.service().list(null, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> harness.service().list(null, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> harness.service().list(null, 1, 101));
        assertThrows(IllegalArgumentException.class,
                () -> harness.service().listAuthorized(null, 1, 0, view -> true));
        assertThrows(IllegalArgumentException.class,
                () -> harness.service().listAuthorized(null, 1, 101, view -> true));
        assertThrows(IllegalArgumentException.class,
                () -> harness.service().listAuthorized(null, 1, 10, null));
        assertThrows(IllegalArgumentException.class, () -> harness.service().getByExecutionId(null));
        assertThrows(IllegalArgumentException.class, () -> harness.service().reconfirm(
                "missing", 0, hash('a'), hash('b'), ActionProposalService.ReconfirmResolution.UNKNOWN,
                REVIEWER, repairPermissions(), "comment", hash('3'), "x".repeat(501)));

        ActionProposalRepository emptyBatch = mock(ActionProposalRepository.class);
        when(emptyBatch.countByState(null)).thenReturn(1L);
        when(emptyBatch.findByState(null, 0, 1)).thenReturn(List.of());
        ActionProposalService emptyBatchService = service(
                emptyBatch, (type, payload) -> hash('b'), false, successAction(),
                (actor, proposal, supplied) -> supplied, new AtomicInteger());
        assertTrue(emptyBatchService.listAuthorized(null, 1, 10, view -> true).records().isEmpty());

        Harness nullPermissions = harness((type, payload) -> hash('b'), false, successAction(),
                (actor, proposal, supplied) -> null);
        ActionProposalService.ProposalView created = nullPermissions.service().create(
                repairCommand(), "null-permissions-create", hash('4'), REVIEWER);
        assertThrows(SecurityException.class, () -> nullPermissions.service().approve(
                created.publicId(), created.version(), created.payloadHash(), created.businessSnapshotHash(),
                REVIEWER, null, "null-permissions-approve", hash('5')));
    }

    @Test
    void reconfirmValidatesBothHashesResolutionEvidenceAndConflict() {
        UncertainRun run = uncertainRun(ActionType.REPAIR_ASSIGN, new AtomicReference<>(hash('b')),
                new TransactionSystemException("unknown outcome"));
        ActionProposalService.ProposalView review = run.review();

        assertThrows(ProposalConflictException.class, () -> run.harness().service().reconfirm(
                review.publicId(), review.version(), hash('c'), review.businessSnapshotHash(),
                ActionProposalService.ReconfirmResolution.UNKNOWN, REVIEWER, repairPermissions(),
                "wrong-payload", hash('1')));
        assertThrows(ProposalConflictException.class, () -> run.harness().service().reconfirm(
                review.publicId(), review.version(), review.payloadHash(), hash('c'),
                ActionProposalService.ReconfirmResolution.UNKNOWN, REVIEWER, repairPermissions(),
                "wrong-snapshot", hash('2')));
        assertThrows(IllegalArgumentException.class, () -> run.harness().service().reconfirm(
                review.publicId(), review.version(), review.payloadHash(), review.businessSnapshotHash(),
                null, REVIEWER, repairPermissions(), "null-resolution", hash('3')));

        ActionProposalService.ProposalView noResult = run.harness().service().reconfirm(
                review.publicId(), review.version(), review.payloadHash(), review.businessSnapshotHash(),
                ActionProposalService.ReconfirmResolution.RESULT_CONFIRMED, REVIEWER, repairPermissions(),
                "missing-result", hash('4'));
        assertEquals(ProposalState.NEEDS_REVIEW, noResult.state());

        ActionProposalService.ProposalView conflict = run.harness().service().reconfirm(
                review.publicId(), review.version(), review.payloadHash(), review.businessSnapshotHash(),
                ActionProposalService.ReconfirmResolution.CONFLICT, REVIEWER, repairPermissions(),
                "confirmed-conflict", hash('5'));
        assertEquals(ProposalState.FAILED, conflict.state());
    }

    @Test
    void provenNotExecutedNoticeAndChangedRepairSnapshotNeverReplayBusinessWrite() {
        AtomicReference<String> noticeSnapshot = new AtomicReference<>(hash('b'));
        UncertainRun notice = uncertainRun(ActionType.NOTICE_CREATE_DRAFT, noticeSnapshot,
                new TransactionSystemException("unknown notice"));
        ActionProposalService.ProposalView noticeResult = notice.harness().service().reconfirm(
                notice.review().publicId(), notice.review().version(), notice.review().payloadHash(),
                notice.review().businessSnapshotHash(), ActionProposalService.ReconfirmResolution.PROVEN_NOT_EXECUTED,
                REVIEWER, noticePermissions(), "notice-not-executed", hash('6'));
        assertEquals(ProposalState.NEEDS_REVIEW, noticeResult.state());
        assertEquals(1, notice.actionCalls().get());

        AtomicReference<String> repairSnapshot = new AtomicReference<>(hash('b'));
        UncertainRun repair = uncertainRun(ActionType.REPAIR_ASSIGN, repairSnapshot,
                new TransactionSystemException("unknown repair"));
        repairSnapshot.set(hash('c'));
        ActionProposalService.ProposalView repairResult = repair.harness().service().reconfirm(
                repair.review().publicId(), repair.review().version(), repair.review().payloadHash(),
                repair.review().businessSnapshotHash(), ActionProposalService.ReconfirmResolution.PROVEN_NOT_EXECUTED,
                REVIEWER, repairPermissions(), "repair-snapshot-changed", hash('7'));
        assertEquals(ProposalState.NEEDS_REVIEW, repairResult.state());
        assertEquals(1, repair.actionCalls().get());
    }

    @Test
    void provenNotExecutedRepairWithStableSnapshotResumesAndExecutesOnce() {
        AtomicReference<String> snapshot = new AtomicReference<>(hash('b'));
        UncertainRun run = uncertainRun(ActionType.REPAIR_ASSIGN, snapshot,
                new TransactionSystemException("unknown repair"));

        ActionProposalService.ProposalView succeeded = run.harness().service().reconfirm(
                run.review().publicId(), run.review().version(), run.review().payloadHash(),
                run.review().businessSnapshotHash(), ActionProposalService.ReconfirmResolution.PROVEN_NOT_EXECUTED,
                REVIEWER, repairPermissions(), "repair-safe-retry", hash('8'));

        assertEquals(ProposalState.SUCCEEDED, succeeded.state());
        assertEquals(2, run.actionCalls().get());
    }

    @Test
    void heuristicUnknownAndMissingBusinessPermissionUseDistinctFailurePaths() {
        AtomicInteger heuristicCalls = new AtomicInteger();
        Harness heuristic = harness((type, payload) -> hash('b'), true, (actor, action) -> {
            heuristicCalls.incrementAndGet();
            throw new HeuristicCompletionException(HeuristicCompletionException.STATE_UNKNOWN, null);
        }, (actor, proposal, supplied) -> supplied);
        ActionProposalService.ProposalView heuristicProposal = heuristic.service().create(
                repairCommand(), "heuristic-create", hash('9'), REVIEWER);
        assertThrows(HeuristicCompletionException.class, () -> heuristic.service().approve(
                heuristicProposal.publicId(), heuristicProposal.version(), heuristicProposal.payloadHash(),
                heuristicProposal.businessSnapshotHash(), REVIEWER, repairPermissions(),
                "heuristic-approve", hash('a')));
        assertEquals(ProposalState.NEEDS_REVIEW,
                heuristic.service().get(heuristicProposal.publicId()).state());
        assertEquals(1, heuristicCalls.get());

        AtomicInteger authorizationReads = new AtomicInteger();
        AtomicInteger businessWrites = new AtomicInteger();
        Harness missingBusinessPermission = harness((type, payload) -> hash('b'), true, (actor, action) -> {
            businessWrites.incrementAndGet();
            return result(action.actionType());
        }, (actor, proposal, supplied) -> authorizationReads.incrementAndGet() == 1
                ? repairPermissions() : Set.of("ai:approval:review"));
        ActionProposalService.ProposalView created = missingBusinessPermission.service().create(
                repairCommand(), "rbac-create", hash('b'), REVIEWER);
        assertThrows(SecurityException.class, () -> missingBusinessPermission.service().approve(
                created.publicId(), created.version(), created.payloadHash(), created.businessSnapshotHash(),
                REVIEWER, repairPermissions(), "rbac-approve", hash('c')));
        assertEquals(0, businessWrites.get());
        assertEquals(ProposalState.FAILED,
                missingBusinessPermission.service().get(created.publicId()).state());
    }

    private UncertainRun uncertainRun(
            ActionType actionType,
            AtomicReference<String> snapshot,
            RuntimeException firstFailure) {
        AtomicInteger actionCalls = new AtomicInteger();
        Harness harness = harness((type, payload) -> snapshot.get(), true, (actor, action) -> {
            if (actionCalls.incrementAndGet() == 1) throw firstFailure;
            return result(action.actionType());
        }, (actor, proposal, supplied) -> supplied);
        ActionProposalService.CreateProposalCommand command = actionType == ActionType.REPAIR_ASSIGN
                ? repairCommand() : noticeCommand();
        Set<String> permissions = actionType == ActionType.REPAIR_ASSIGN
                ? repairPermissions() : noticePermissions();
        ActionProposalService.ProposalView created = harness.service().create(
                command, "uncertain-create-" + UUID.randomUUID(), hash('d'), REVIEWER);
        assertThrows(firstFailure.getClass(), () -> harness.service().approve(
                created.publicId(), created.version(), created.payloadHash(), created.businessSnapshotHash(),
                REVIEWER, permissions, "uncertain-approve-" + UUID.randomUUID(), hash('e')));
        ActionProposalService.ProposalView review = harness.service().get(created.publicId());
        assertEquals(ProposalState.NEEDS_REVIEW, review.state());
        return new UncertainRun(harness, review, actionCalls);
    }

    private Harness harness(
            ActionProposalService.BusinessSnapshotProvider snapshots,
            boolean writes,
            ApprovedBusinessActionPort actions,
            ActionProposalService.FreshAuthorization authorization) {
        InMemoryActionProposalRepository repository = new InMemoryActionProposalRepository();
        AtomicInteger auditEvents = new AtomicInteger();
        return new Harness(repository,
                service(repository, snapshots, writes, actions, authorization, auditEvents), auditEvents);
    }

    private ActionProposalService service(
            ActionProposalRepository repository,
            ActionProposalService.BusinessSnapshotProvider snapshots,
            boolean writes,
            ApprovedBusinessActionPort actions,
            ActionProposalService.FreshAuthorization authorization,
            AtomicInteger auditEvents) {
        return new ActionProposalService(repository, actions, snapshots, new AiAuditPort() {
            @Override
            public boolean writable() {
                return true;
            }

            @Override
            public void append(AiAuditEvent event) {
                auditEvents.incrementAndGet();
            }
        }, () -> writes, ActionProposalService.TransactionRunner.direct(), authorization);
    }

    private void assertInvalidCreate(Harness harness, ActionProposalService.CreateProposalCommand command) {
        assertThrows(IllegalArgumentException.class, () -> harness.service().create(
                command, "invalid-" + UUID.randomUUID(), hash('f'), REVIEWER));
    }

    private ActionProposalService.CreateProposalCommand copy(
            ActionProposalService.CreateProposalCommand base,
            String targetType,
            Long targetId,
            String payload,
            ProposalPreview preview,
            String permission,
            String risk,
            Instant expiresAt) {
        return new ActionProposalService.CreateProposalCommand(
                base.actionType(), targetType, targetId, payload, preview, permission,
                base.requiredApprovalCount(), risk, base.proposerUserId(), expiresAt, base.origin());
    }

    private ActionProposalService.CreateProposalCommand repairCommand() {
        return new ActionProposalService.CreateProposalCommand(
                ActionType.REPAIR_ASSIGN, "REPAIR_ORDER", 1L, repairPayload(), preview(),
                "repair:write", 1, "HIGH", REVIEWER.userId(), Instant.now().plusSeconds(300),
                ProposalOrigin.forAction(UUID.randomUUID().toString(), ActionType.REPAIR_ASSIGN));
    }

    private ActionProposalService.CreateProposalCommand noticeCommand() {
        return new ActionProposalService.CreateProposalCommand(
                ActionType.NOTICE_CREATE_DRAFT, "NOTICE", null,
                "{\"content\":\"draft\",\"status\":\"草稿\"}", preview(),
                "notice:write", 1, "LOW", REVIEWER.userId(), Instant.now().plusSeconds(300),
                ProposalOrigin.forAction(UUID.randomUUID().toString(), ActionType.NOTICE_CREATE_DRAFT));
    }

    private ActionProposal proposal(int requiredApprovals) {
        return ActionProposal.pending(
                UUID.randomUUID().toString(), ActionType.REPAIR_ASSIGN, repairPayload(),
                hash('a'), hash('b'), "repair:write", requiredApprovals, Instant.now().plusSeconds(60));
    }

    private ActionProposal approvedProposal() {
        ActionProposal proposal = proposal(1);
        proposal.approve(0, hash('a'), hash('b'), hash('b'), REVIEWER, repairPermissions(),
                "approve-" + UUID.randomUUID(), hash('a'));
        return proposal;
    }

    private ApprovedBusinessActionPort successAction() {
        return (actor, action) -> result(action.actionType());
    }

    private static ApprovedBusinessActionPort.BusinessActionResult result(String actionType) {
        return new ApprovedBusinessActionPort.BusinessActionResult(
                ActionType.REPAIR_ASSIGN.name().equals(actionType) ? "REPAIR_ORDER" : "NOTICE",
                1L, hash('9'));
    }

    private ProposalPreview preview() {
        return new ProposalPreview("current", "proposed", "bounded", Instant.now(),
                ProposalPreview.EvidenceBasis.DETERMINISTIC, null,
                List.of(new ProposalPreview.Citation(
                        "USER_COMMAND", "RUN:test", "test command", hash('a'))));
    }

    private static Set<String> repairPermissions() {
        return Set.of("ai:approval:review", "repair:write");
    }

    private static Set<String> noticePermissions() {
        return Set.of("ai:approval:review", "notice:write");
    }

    private static String repairPayload() {
        return "{\"assigneeUserId\":3,\"repairOrderId\":1}";
    }

    private static String hash(char value) {
        return String.valueOf(value).repeat(64);
    }

    private record Harness(
            InMemoryActionProposalRepository repository,
            ActionProposalService service,
            AtomicInteger auditEvents) {
    }

    private record UncertainRun(
            Harness harness,
            ActionProposalService.ProposalView review,
            AtomicInteger actionCalls) {
    }
}
