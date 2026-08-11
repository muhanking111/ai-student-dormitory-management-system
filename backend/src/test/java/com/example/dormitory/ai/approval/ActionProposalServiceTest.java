package com.example.dormitory.ai.approval;

import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.BusinessExecutionActor;
import com.example.dormitory.ai.port.AiAuditPort;
import com.example.dormitory.ai.port.ApprovedBusinessActionPort;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ActionProposalServiceTest {

    @Test
    void duplicateApprovalBySameReviewerIsPureReplayAndDoesNotBlockSecondReviewer() {
        InMemoryActionProposalRepository delegate = new InMemoryActionProposalRepository();
        ActionProposalRepository repository = mock(
                ActionProposalRepository.class, delegatesTo(delegate));
        AtomicInteger snapshotReads = new AtomicInteger();
        AtomicInteger approvedAuditEvents = new AtomicInteger();
        AiAuditPort audit = new AiAuditPort() {
            @Override
            public boolean writable() {
                return true;
            }

            @Override
            public void append(AiAuditEvent event) {
                if ("PROPOSAL_APPROVED".equals(event.eventType())) {
                    approvedAuditEvents.incrementAndGet();
                }
            }
        };
        ActionProposalService service = new ActionProposalService(
                repository,
                (actor, action) -> { throw new AssertionError("未达到审批门槛时不得执行业务写"); },
                (type, payload) -> {
                    snapshotReads.incrementAndGet();
                    return "snapshot";
                },
                audit,
                () -> false);
        BusinessExecutionActor firstReviewer = BusinessExecutionActor.from(ActorDescriptor.user(8));
        BusinessExecutionActor secondReviewer = BusinessExecutionActor.from(ActorDescriptor.user(9));
        ActionProposalService.CreateProposalCommand base = command();
        ActionProposalService.CreateProposalCommand twoReviewerCommand =
                new ActionProposalService.CreateProposalCommand(
                        base.actionType(), base.targetType(), base.targetResourceId(), base.payloadJson(), base.preview(),
                        base.requiredBusinessPermission(), 2, base.riskLevel(), base.proposerUserId(),
                        base.expiresAt(), base.origin());
        Set<String> permissions = Set.of("ai:approval:review", "repair:write");

        ActionProposalService.ProposalView created = service.create(
                twoReviewerCommand, "duplicate-create", hash('1'), firstReviewer);
        ActionProposalService.ProposalView firstApproval = service.approve(
                created.publicId(), created.version(), created.payloadHash(), created.businessSnapshotHash(),
                firstReviewer, permissions, "first-approval", hash('2'));
        int snapshotReadsAfterFirstApproval = snapshotReads.get();
        clearInvocations(repository);

        assertThrows(SecurityException.class, () -> service.approve(
                created.publicId(), firstApproval.version(), created.payloadHash(), created.businessSnapshotHash(),
                firstReviewer, Set.of("ai:approval:review"), "same-reviewer-without-business-permission", hash('3')));
        assertEquals(snapshotReadsAfterFirstApproval, snapshotReads.get());

        ActionProposalService.ProposalView replay = service.approve(
                created.publicId(), firstApproval.version(), created.payloadHash(), created.businessSnapshotHash(),
                firstReviewer, permissions, "same-reviewer-new-key", hash('4'));

        assertEquals(1, replay.approvedCount());
        assertEquals(firstApproval.version(), replay.version());
        assertEquals(ProposalState.PENDING_APPROVAL, replay.state());
        assertEquals(snapshotReadsAfterFirstApproval, snapshotReads.get());
        assertEquals(1, approvedAuditEvents.get());
        verify(repository, never()).update(any());

        ActionProposalService.ProposalView approved = service.approve(
                created.publicId(), firstApproval.version(), created.payloadHash(), created.businessSnapshotHash(),
                secondReviewer, permissions, "second-reviewer", hash('5'));

        assertEquals(2, approved.approvedCount());
        assertEquals(firstApproval.version() + 1, approved.version());
        assertEquals(ProposalState.APPROVED, approved.state());
        assertEquals(2, approvedAuditEvents.get());
        verify(repository, times(1)).update(any());
    }

    @Test
    void approvedProposalExecutesExistingBusinessPortExactlyOnceOnlyWhenWriteGateIsEnabled() {
        AtomicBoolean writeEnabled = new AtomicBoolean(false);
        AtomicInteger writes = new AtomicInteger();
        ApprovedBusinessActionPort actionPort = (actor, action) -> {
            writes.incrementAndGet();
            return new ApprovedBusinessActionPort.BusinessActionResult("NOTICE", 9L, "result-hash");
        };
        ActionProposalService service = new ActionProposalService(
                new InMemoryActionProposalRepository(), actionPort,
                (type, payload) -> "snapshot-hash", audit(true), writeEnabled::get);
        BusinessExecutionActor actor = BusinessExecutionActor.from(ActorDescriptor.user(7));

        ActionProposalService.ProposalView created = service.create(new ActionProposalService.CreateProposalCommand(
                ActionType.NOTICE_CREATE_DRAFT, "NOTICE", null,
                "{\"title\":\"停水通知\",\"status\":\"草稿\"}", preview("停水通知"),
                "notice:write", 1, "MEDIUM", 7L, Instant.now().plusSeconds(300), origin(ActionType.NOTICE_CREATE_DRAFT)),
                "create-key", hash('1'), actor);

        ActionProposalService.ProposalView approved = service.approve(
                created.publicId(), created.version(), created.payloadHash(), created.businessSnapshotHash(),
                actor, Set.of("ai:approval:review", "notice:write"), "approve-key", hash('2'));
        assertEquals(ProposalState.APPROVED, approved.state());
        assertEquals(0, writes.get());

        writeEnabled.set(true);
        ActionProposalService.ProposalView executable = service.create(new ActionProposalService.CreateProposalCommand(
                ActionType.NOTICE_CREATE_DRAFT, "NOTICE", null,
                "{\"title\":\"消防演练\",\"status\":\"草稿\"}", preview("消防演练"),
                "notice:write", 1, "MEDIUM", 7L, Instant.now().plusSeconds(300), origin(ActionType.NOTICE_CREATE_DRAFT)),
                "create-key-2", hash('3'), actor);
        ActionProposalService.ProposalView executed = service.approve(
                executable.publicId(), executable.version(), executable.payloadHash(), executable.businessSnapshotHash(),
                actor, Set.of("ai:approval:review", "notice:write"), "approve-key-2", hash('4'));
        assertEquals(ProposalState.SUCCEEDED, executed.state());
        assertEquals(1, writes.get());

        ActionProposalService.ProposalView replay = service.approve(
                executable.publicId(), executable.version(), executable.payloadHash(), executable.businessSnapshotHash(),
                actor, Set.of("ai:approval:review", "notice:write"), "approve-key-2", hash('4'));
        assertEquals(ProposalState.SUCCEEDED, replay.state());
        assertEquals(1, writes.get());
    }

    @Test
    void auditFailureStaleSnapshotAndNonUserActorAllFailBeforeBusinessWrite() {
        AtomicInteger writes = new AtomicInteger();
        ApprovedBusinessActionPort actionPort = (actor, action) -> {
            writes.incrementAndGet();
            return new ApprovedBusinessActionPort.BusinessActionResult("REPAIR_ORDER", 1L, "hash");
        };
        BusinessExecutionActor user = BusinessExecutionActor.from(ActorDescriptor.user(8));

        ActionProposalService noAudit = new ActionProposalService(
                new InMemoryActionProposalRepository(), actionPort, (type, payload) -> "snapshot",
                audit(false), () -> true);
        assertThrows(IllegalStateException.class, () -> noAudit.create(command(), "key", hash('5'), user));

        AtomicBoolean changed = new AtomicBoolean(false);
        ActionProposalService stale = new ActionProposalService(
                new InMemoryActionProposalRepository(), actionPort,
                (type, payload) -> changed.get() ? "changed" : "snapshot",
                audit(true), () -> true);
        ActionProposalService.ProposalView proposal = stale.create(command(), "key", hash('5'), user);
        changed.set(true);
        assertThrows(ProposalConflictException.class, () -> stale.approve(
                proposal.publicId(), proposal.version(), proposal.payloadHash(), proposal.businessSnapshotHash(), user,
                Set.of("ai:approval:review", "repair:write"), "approve", hash('6')));
        assertEquals(0, writes.get());

        assertThrows(IllegalArgumentException.class,
                () -> BusinessExecutionActor.from(ActorDescriptor.service("worker", 8L, 8L)));
        assertTrue(proposal.payloadHash().matches("[0-9a-f]{64}"));
    }

    @Test
    void createReplaySurvivesServiceRestartWithoutRecomputingSnapshot() {
        InMemoryActionProposalRepository repository = new InMemoryActionProposalRepository();
        BusinessExecutionActor actor = BusinessExecutionActor.from(ActorDescriptor.user(8));
        ActionProposalService.CreateProposalCommand command = command();
        ActionProposalService first = new ActionProposalService(repository,
                (a, action) -> { throw new AssertionError(); }, (type, payload) -> "snapshot",
                audit(true), () -> false);
        ActionProposalService.ProposalView created = first.create(command, "stable-key", hash('7'), actor);

        ActionProposalService restarted = new ActionProposalService(repository,
                (a, action) -> { throw new AssertionError(); },
                (type, payload) -> { throw new AssertionError("幂等重放不应重新读取业务快照"); },
                audit(true), () -> false);
        ActionProposalService.ProposalView replay = restarted.create(command, "stable-key", hash('7'), actor);

        assertEquals(created.publicId(), replay.publicId());
        assertEquals(command.origin().runPublicId(), created.runId());
        assertEquals(command.origin().runPublicId(), replay.runId());
        assertEquals(1, restarted.list(null, 1, 10).total());
    }

    @Test
    void authorizedPaginationFiltersActionTypeBeforeComputingRecordsAndTotal() {
        InMemoryActionProposalRepository repository = new InMemoryActionProposalRepository();
        ActionProposalService service = new ActionProposalService(repository,
                (actor, action) -> { throw new AssertionError("list 不应执行业务写"); },
                (type, payload) -> "snapshot", audit(true), () -> false);
        BusinessExecutionActor actor = BusinessExecutionActor.from(ActorDescriptor.user(8));
        service.create(command(), "repair-filter", hash('8'), actor);
        service.create(new ActionProposalService.CreateProposalCommand(
                        ActionType.NOTICE_CREATE_DRAFT, "NOTICE", null,
                        "{\"title\":\"停水通知\",\"status\":\"草稿\"}", preview("停水通知"),
                        "notice:write", 1, "MEDIUM", 8L, Instant.now().plusSeconds(300),
                        origin(ActionType.NOTICE_CREATE_DRAFT)),
                "notice-filter", hash('9'), actor);

        ActionProposalService.PageResult repairPage = service.listAuthorized(
                null, ActionType.REPAIR_ASSIGN, 1, 1, proposal -> true);
        ActionProposalService.PageResult noticePage = service.listAuthorized(
                ProposalState.PENDING_APPROVAL, ActionType.NOTICE_CREATE_DRAFT, 1, 1, proposal -> true);
        ActionProposalService.PageResult unauthorizedRepair = service.listAuthorized(
                null, ActionType.REPAIR_ASSIGN, 1, 1, proposal -> false);

        assertEquals(1, repairPage.total());
        assertEquals(ActionType.REPAIR_ASSIGN, repairPage.records().getFirst().actionType());
        assertEquals(1, noticePage.total());
        assertEquals(ActionType.NOTICE_CREATE_DRAFT, noticePage.records().getFirst().actionType());
        assertEquals(0, unauthorizedRepair.total());
        assertTrue(unauthorizedRepair.records().isEmpty());
    }

    @Test
    void modelLowConfidenceAndMissingSourcesCannotReachBusinessExecution() {
        AtomicInteger writes = new AtomicInteger();
        ActionProposalService service = new ActionProposalService(new InMemoryActionProposalRepository(),
                (actor, action) -> {
                    writes.incrementAndGet();
                    return new ApprovedBusinessActionPort.BusinessActionResult("REPAIR_ORDER", 1L, hash('f'));
                }, (type, payload) -> "snapshot", audit(true), () -> true);
        BusinessExecutionActor actor = BusinessExecutionActor.from(ActorDescriptor.user(8));
        ActionProposalService.CreateProposalCommand base = command();
        ProposalPreview lowConfidence = new ProposalPreview("当前值", "建议值", "有限影响", Instant.now(),
                ProposalPreview.EvidenceBasis.MODEL, 0.69,
                java.util.List.of(new ProposalPreview.Citation(
                        "BUSINESS_SNAPSHOT", "REPAIR_ORDER:1", "授权快照", hash('a'))));
        ActionProposalService.CreateProposalCommand unsafe = new ActionProposalService.CreateProposalCommand(
                base.actionType(), base.targetType(), base.targetResourceId(), base.payloadJson(), lowConfidence,
                base.requiredBusinessPermission(), base.requiredApprovalCount(), base.riskLevel(),
                base.proposerUserId(), base.expiresAt(), base.origin());
        ActionProposalService.ProposalView proposal = service.create(unsafe, "low-confidence", hash('8'), actor);

        ProposalConflictException rejected = assertThrows(ProposalConflictException.class, () -> service.approve(
                proposal.publicId(), proposal.version(), proposal.payloadHash(), proposal.businessSnapshotHash(),
                actor, Set.of("ai:approval:review", "repair:write"), "approve-low", hash('9')));

        assertEquals("AI_PROPOSAL_EVIDENCE_UNVERIFIED", rejected.errorCode());
        assertEquals(0, writes.get());
    }

    @Test
    void freshRbacIsRecheckedInsideDecisionAndImmediatelyBeforeBusinessExecution() {
        AtomicInteger authorizationReads = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        ActionProposalService service = new ActionProposalService(
                new InMemoryActionProposalRepository(),
                (actor, action) -> {
                    writes.incrementAndGet();
                    return new ApprovedBusinessActionPort.BusinessActionResult("REPAIR_ORDER", 1L, hash('e'));
                },
                (type, payload) -> "snapshot", audit(true), () -> true,
                ActionProposalService.TransactionRunner.direct(),
                (currentActor, currentProposal, supplied) -> authorizationReads.incrementAndGet() == 1
                        ? Set.of("ai:approval:review", "repair:write") : Set.of("repair:write"));
        BusinessExecutionActor actor = BusinessExecutionActor.from(ActorDescriptor.user(8));
        ActionProposalService.ProposalView proposal = service.create(command(), "fresh-create", hash('b'), actor);

        assertThrows(SecurityException.class, () -> service.approve(
                proposal.publicId(), proposal.version(), proposal.payloadHash(), proposal.businessSnapshotHash(),
                actor, Set.of("ai:approval:review", "repair:write"), "fresh-approve", hash('c')));

        assertEquals(2, authorizationReads.get());
        assertEquals(0, writes.get());
        assertEquals(ProposalState.FAILED, service.get(proposal.publicId()).state());
    }

    @Test
    void freshAuthorizationMutationsRejectAnAlreadyActiveCallerTransaction() {
        ActionProposalService service = new ActionProposalService(
                new InMemoryActionProposalRepository(),
                (actor, action) -> new ApprovedBusinessActionPort.BusinessActionResult(
                        "REPAIR_ORDER", 1L, hash('e')),
                (type, payload) -> "snapshot", audit(true), () -> false);
        BusinessExecutionActor actor = BusinessExecutionActor.from(ActorDescriptor.user(8));
        ActionProposalService.ProposalView proposal = service.create(command(), "outer-create", hash('d'), actor);

        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> service.approve(
                    proposal.publicId(), proposal.version(), proposal.payloadHash(), proposal.businessSnapshotHash(),
                    actor, Set.of("ai:approval:review", "repair:write"), "outer-approve", hash('e')));
            assertTrue(failure.getMessage().contains("调用方已开启的事务"));
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private ActionProposalService.CreateProposalCommand command() {
        return new ActionProposalService.CreateProposalCommand(
                ActionType.REPAIR_ASSIGN, "REPAIR_ORDER", 1L,
                "{\"assigneeUserId\":3,\"repairOrderId\":1}", preview("维修指派"),
                "repair:write", 1, "HIGH", 8L, Instant.now().plusSeconds(300), origin(ActionType.REPAIR_ASSIGN));
    }

    private ProposalPreview preview(String proposed) {
        return new ProposalPreview("当前值", proposed, "仅执行白名单动作", Instant.now(),
                ProposalPreview.EvidenceBasis.DETERMINISTIC, null,
                java.util.List.of(new ProposalPreview.Citation(
                        "USER_COMMAND", "RUN:test", "测试命令", hash('a'))));
    }

    private ProposalOrigin origin(ActionType type) {
        return ProposalOrigin.forAction(java.util.UUID.randomUUID().toString(), type);
    }

    private String hash(char value) {
        return String.valueOf(value).repeat(64);
    }

    private AiAuditPort audit(boolean writable) {
        return new AiAuditPort() {
            @Override
            public boolean writable() {
                return writable;
            }

            @Override
            public void append(AiAuditEvent event) {
                if (!writable) throw new IllegalStateException("audit unavailable");
            }
        };
    }
}
