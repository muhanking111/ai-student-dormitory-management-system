package com.example.dormitory.ai.approval;

import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.BusinessExecutionActor;
import com.example.dormitory.ai.port.AiAuditPort;
import com.example.dormitory.ai.port.ApprovedBusinessActionPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalBranchCoverageTest {

    private static final BusinessExecutionActor REVIEWER =
            BusinessExecutionActor.from(ActorDescriptor.user(7));
    private static final Set<String> PERMISSIONS = Set.of("ai:approval:review", "repair:write");

    @Test
    void rejectIsVersionedReplayableAndRejectsChangedReplayPayload() {
        ActionProposal proposal = proposal("reject", Instant.now().plusSeconds(60));

        ActionProposal.ApprovalDecision rejected = proposal.reject(
                0, REVIEWER, PERMISSIONS, "reject-key", hash('1'), "not applicable");
        ActionProposal.ApprovalDecision replay = proposal.reject(
                0, REVIEWER, PERMISSIONS, "reject-key", hash('1'));

        assertEquals(ProposalState.REJECTED, rejected.state());
        assertEquals(rejected, replay);
        assertEquals("not applicable", proposal.approvals().getFirst().commentRedacted());
        assertThrows(IdempotencyConflictException.class,
                () -> proposal.reject(0, REVIEWER, PERMISSIONS, "reject-key", hash('2')));
        assertThrows(ProposalConflictException.class,
                () -> proposal.reject(1, REVIEWER, PERMISSIONS, "new-key", hash('3')));
    }

    @Test
    void executionLeaseRequiresApprovalAndMatchingToken() {
        ActionProposal pending = proposal("pending", Instant.now().plusSeconds(60));
        assertThrows(ProposalConflictException.class, () -> pending.acquireExecutionLease(REVIEWER));

        ActionProposal approved = approvedProposal("lease");
        ActionProposal.ExecutionLease lease = approved.acquireExecutionLease(REVIEWER);
        assertNotNull(lease);
        assertNull(approved.acquireExecutionLease(REVIEWER));

        ActionProposal.ExecutionLease forged = new ActionProposal.ExecutionLease(
                approved.publicId(), hash('f'), REVIEWER.userId(), 1, Instant.now());
        assertThrows(SecurityException.class, () -> approved.markSucceeded(forged));
        approved.markFailed(lease);
        assertEquals(ProposalState.FAILED, approved.state());
    }

    @Test
    void needsReviewCanResumeOnlyWithVersionPermissionAndOriginalLease() {
        ActionProposal proposal = approvedProposal("resume");
        ActionProposal.ExecutionLease first = proposal.acquireExecutionLease(REVIEWER);
        proposal.markNeedsReview(first);

        assertThrows(ProposalConflictException.class,
                () -> proposal.resumeAfterReconfirmation(proposal.version() - 1, REVIEWER, PERMISSIONS));
        assertThrows(SecurityException.class,
                () -> proposal.resumeAfterReconfirmation(proposal.version(), REVIEWER, Set.of("repair:write")));

        ActionProposal.ExecutionLease resumed = proposal.resumeAfterReconfirmation(
                proposal.version(), REVIEWER, PERMISSIONS);
        assertEquals(first.leaseVersion() + 1, resumed.leaseVersion());
        assertEquals(ProposalState.EXECUTING, proposal.state());

        ActionProposal restoredWithoutLease = ActionProposal.restore(
                UUID.randomUUID().toString(), ActionType.REPAIR_ASSIGN, payload(), hash('a'), hash('b'),
                "repair:write", 1, Instant.now().plusSeconds(60), ProposalState.NEEDS_REVIEW,
                3, List.of(), List.of(), null);
        assertThrows(IllegalStateException.class,
                () -> restoredWithoutLease.resumeAfterReconfirmation(3, REVIEWER, PERMISSIONS));
    }

    @Test
    void repositoryEnforcesCreateIdempotencyOriginAndIdentity() {
        InMemoryActionProposalRepository repository = new InMemoryActionProposalRepository();
        ProposalOrigin origin = ProposalOrigin.forAction(UUID.randomUUID().toString(), ActionType.REPAIR_ASSIGN);
        ActionProposalRepository.StoredProposal first = stored("repo-one", Instant.now().plusSeconds(60));
        ActionProposalRepository.CreateRequest request = request("create-key", hash('1'));

        assertFalse(repository.createOrReplay(first, origin, request).replayed());
        assertTrue(repository.createOrReplay(first, origin, request).replayed());
        assertTrue(repository.findCreateReplay(origin, request).isPresent());
        assertThrows(IdempotencyConflictException.class,
                () -> repository.findCreateReplay(origin, request("create-key", hash('2'))));
        assertThrows(IdempotencyConflictException.class,
                () -> repository.createOrReplay(first, origin, request("create-key", hash('2'))));

        ActionProposalRepository.StoredProposal sameOrigin = stored("repo-two", Instant.now().plusSeconds(60));
        assertTrue(repository.createOrReplay(
                sameOrigin, origin, request("second-key", hash('3'))).replayed());

        ActionProposalRepository.StoredProposal differentPayload = new ActionProposalRepository.StoredProposal(
                ActionProposal.pending(UUID.randomUUID().toString(), ActionType.REPAIR_ASSIGN,
                        "{\"repairOrderId\":2}", hash('c'), hash('b'), "repair:write", 1,
                        Instant.now().plusSeconds(60)), "REPAIR_ORDER", 2L, preview(), "HIGH", 7L, Instant.now());
        assertThrows(ProposalConflictException.class,
                () -> repository.createOrReplay(
                        differentPayload, origin, request("third-key", hash('4'))));
        assertThrows(IllegalStateException.class, () -> repository.update(sameOrigin));
    }

    @Test
    void repositoryIndexesExecutionFiltersStateAndExpiresDueItems() {
        InMemoryActionProposalRepository repository = new InMemoryActionProposalRepository();
        ActionProposalRepository.StoredProposal due = stored("due", Instant.now().minusSeconds(1));
        ProposalOrigin origin = ProposalOrigin.forAction(UUID.randomUUID().toString(), ActionType.REPAIR_ASSIGN);
        repository.createOrReplay(due, origin, request("due-key", hash('5')));

        assertEquals(1, repository.countByState(null));
        assertEquals(1, repository.findByState(ProposalState.PENDING_APPROVAL, 0, 10).size());
        assertEquals(1, repository.expireDue(Instant.now()).size());
        assertEquals(1, repository.countByState(ProposalState.EXPIRED));
        assertTrue(repository.expireDue(Instant.now()).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> repository.findByState(null, -1, 10));
        assertThrows(IllegalArgumentException.class,
                () -> repository.findByState(null, 0, 101));
        assertTrue(repository.markStaleExecutionsNeedsReview(Instant.now()).isEmpty());
    }

    @Test
    void previewRoundTripsAndMalformedStoredValuesFailClosed() {
        ProposalPreview value = preview();

        ProposalPreview restored = ProposalPreview.fromStored(value.toStoredJson());
        assertEquals(value.currentValue(), restored.currentValue());
        assertTrue(restored.confirmable());
        assertFalse(ProposalPreview.fromStored(null).confirmable());
        assertFalse(ProposalPreview.fromStored("[]").confirmable());
        assertFalse(ProposalPreview.fromStored("{broken").confirmable());
        assertEquals("trimmed", ProposalPreview.legacy("  trimmed  ").proposedValue());
    }

    @Test
    void previewRejectsUnsafeFieldsConfidenceCitationAndVolume() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProposalPreview(" ", "next", "impact", Instant.now(),
                        ProposalPreview.EvidenceBasis.DETERMINISTIC, null, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ProposalPreview("now", "next", "impact", Instant.now(),
                        ProposalPreview.EvidenceBasis.MODEL, Double.NaN, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ProposalPreview("now", "next", "impact", Instant.now(),
                        ProposalPreview.EvidenceBasis.MODEL, 1.1, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ProposalPreview("now", "next", "impact", Instant.now(),
                        ProposalPreview.EvidenceBasis.DETERMINISTIC, null,
                        java.util.Collections.nCopies(21, citation())));
        assertThrows(IllegalArgumentException.class,
                () -> new ProposalPreview.Citation("URL", "ref", "label", hash('a')));
        assertThrows(IllegalArgumentException.class,
                () -> new ProposalPreview.Citation("USER_COMMAND", "ref\n", "label", hash('a')));
        assertThrows(IllegalArgumentException.class,
                () -> new ProposalPreview.Citation("USER_COMMAND", "ref", "label", "ABC"));
    }

    @Test
    void serviceRejectsInvalidSnapshotAndSupportsRejectReplay() {
        InMemoryActionProposalRepository repository = new InMemoryActionProposalRepository();
        ActionProposalService invalidSnapshot = service(repository, (type, payload) -> " ");
        assertThrows(IllegalStateException.class,
                () -> invalidSnapshot.create(command(), "snapshot-key", hash('6'), REVIEWER));

        ActionProposalService service = service(repository, (type, payload) -> "snapshot-v1");
        ActionProposalService.ProposalView created = service.create(
                command(), "valid-key", hash('7'), REVIEWER);
        ActionProposalService.ProposalView rejected = service.reject(
                created.publicId(), created.version(), REVIEWER, PERMISSIONS,
                "reject-service", hash('8'), " reviewed ");
        ActionProposalService.ProposalView replay = service.reject(
                created.publicId(), created.version(), REVIEWER, PERMISSIONS,
                "reject-service", hash('8'));

        assertEquals(ProposalState.REJECTED, rejected.state());
        assertEquals(rejected.version(), replay.version());
        assertThrows(IllegalArgumentException.class,
                () -> service.getByExecutionId("not-a-uuid"));
        assertThrows(IllegalArgumentException.class,
                () -> service.listAuthorized(null, 0, 10, value -> true));
    }

    @Test
    void serviceValidatesNoticeRepairIdentityAndCommentsBeforeWriting() {
        ActionProposalService service = service(new InMemoryActionProposalRepository(),
                (type, payload) -> "snapshot-v1");
        ActionProposalService.CreateProposalCommand base = command();
        ActionProposalService.CreateProposalCommand badRepair = new ActionProposalService.CreateProposalCommand(
                base.actionType(), "NOTICE", null, base.payloadJson(), base.preview(),
                base.requiredBusinessPermission(), base.requiredApprovalCount(), base.riskLevel(),
                base.proposerUserId(), base.expiresAt(), base.origin());
        assertThrows(IllegalArgumentException.class,
                () -> service.create(badRepair, "bad-repair", hash('9'), REVIEWER));

        ActionProposalService.CreateProposalCommand badNotice = new ActionProposalService.CreateProposalCommand(
                ActionType.NOTICE_CREATE_DRAFT, "NOTICE", null, "{\"status\":\"published\"}", preview(),
                "notice:write", 1, "LOW", REVIEWER.userId(), Instant.now().plusSeconds(60),
                ProposalOrigin.forAction(UUID.randomUUID().toString(), ActionType.NOTICE_CREATE_DRAFT));
        assertThrows(IllegalArgumentException.class,
                () -> service.create(badNotice, "bad-notice", hash('a'), REVIEWER));

        ActionProposalService.ProposalView created = service.create(
                base, "comment-create", hash('b'), REVIEWER);
        assertThrows(IllegalArgumentException.class, () -> service.reject(
                created.publicId(), created.version(), REVIEWER, PERMISSIONS,
                "comment-reject", hash('c'), "bad\ncomment"));
    }

    private static ActionProposalService service(
            InMemoryActionProposalRepository repository,
            ActionProposalService.BusinessSnapshotProvider snapshots) {
        return new ActionProposalService(
                repository,
                (actor, action) -> new ApprovedBusinessActionPort.BusinessActionResult(
                        "REPAIR_ORDER", 1L, hash('e')),
                snapshots,
                new AiAuditPort() {
                    @Override
                    public boolean writable() {
                        return true;
                    }

                    @Override
                    public void append(AiAuditEvent event) {
                    }
                },
                () -> false);
    }

    private static ActionProposalService.CreateProposalCommand command() {
        return new ActionProposalService.CreateProposalCommand(
                ActionType.REPAIR_ASSIGN, "REPAIR_ORDER", 1L, payload(), preview(),
                "repair:write", 1, "HIGH", REVIEWER.userId(), Instant.now().plusSeconds(60),
                ProposalOrigin.forAction(UUID.randomUUID().toString(), ActionType.REPAIR_ASSIGN));
    }

    private static ActionProposal approvedProposal(String suffix) {
        ActionProposal proposal = proposal(suffix, Instant.now().plusSeconds(60));
        proposal.approve(0, hash('a'), hash('b'), hash('b'), REVIEWER, PERMISSIONS,
                "approve-" + suffix, hash('d'));
        return proposal;
    }

    private static ActionProposal proposal(String suffix, Instant expiresAt) {
        return ActionProposal.pending(UUID.randomUUID().toString(), ActionType.REPAIR_ASSIGN,
                payload(), hash('a'), hash('b'), "repair:write", 1, expiresAt);
    }

    private static ActionProposalRepository.StoredProposal stored(String suffix, Instant expiresAt) {
        return new ActionProposalRepository.StoredProposal(
                proposal(suffix, expiresAt), "REPAIR_ORDER", 1L, preview(), "HIGH", 7L, Instant.now());
    }

    private static ActionProposalRepository.CreateRequest request(String key, String requestHash) {
        return new ActionProposalRepository.CreateRequest(7L, key, requestHash, Instant.now().plusSeconds(120));
    }

    private static ProposalPreview preview() {
        return new ProposalPreview("current", "proposed", "bounded impact", Instant.now(),
                ProposalPreview.EvidenceBasis.DETERMINISTIC, null, List.of(citation()));
    }

    private static ProposalPreview.Citation citation() {
        return new ProposalPreview.Citation("USER_COMMAND", "RUN:test", "test command", hash('a'));
    }

    private static String payload() {
        return "{\"assigneeUserId\":3,\"repairOrderId\":1}";
    }

    private static String hash(char value) {
        return String.valueOf(value).repeat(64);
    }
}
