package com.example.dormitory.ai.approval;

import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.BusinessExecutionActor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionProposalStateMachineTest {

    @Test
    void validatesVersionHashesSnapshotExpiryAndOnlyCreatesOneExecutionLease() throws Exception {
        ActionProposal proposal = ActionProposal.pending(
                "proposal-1", ActionType.REPAIR_ASSIGN, "{\"repairOrderId\":1}",
                "payload-hash", "snapshot-hash", "repair:write", 1,
                Instant.now().plusSeconds(60));

        BusinessExecutionActor actor = BusinessExecutionActor.from(ActorDescriptor.user(7));
        proposal.approve(0, "payload-hash", "snapshot-hash", "snapshot-hash", actor,
                Set.of("ai:approval:review", "repair:write"), "approve-key", "request-hash");

        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<ActionProposal.ExecutionLease> acquire = () -> proposal.acquireExecutionLease(actor);
            var results = executor.invokeAll(java.util.List.of(acquire, acquire));
            long leases = results.stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    return null;
                }
            }).filter(java.util.Objects::nonNull).count();
            assertEquals(1, leases);
            assertEquals(ProposalState.EXECUTING, proposal.state());
            assertNotNull(proposal.executionLease());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void staleExpiredUnauthorizedAndNonUserExecutionFailClosed() {
        ActionProposal stale = proposal("stale", Instant.now().plusSeconds(60));
        assertThrows(ProposalConflictException.class,
                () -> stale.approve(0, "payload-hash", "snapshot-hash", "changed", actor(),
                        permissions(), "k1", "r1"));
        assertEquals(ProposalState.STALE, stale.state());

        ActionProposal expired = proposal("expired", Instant.now().minusSeconds(1));
        assertThrows(ProposalConflictException.class,
                () -> expired.approve(0, "payload-hash", "snapshot-hash", "snapshot-hash", actor(),
                        permissions(), "k2", "r2"));
        assertEquals(ProposalState.EXPIRED, expired.state());

        ActionProposal unauthorized = proposal("unauthorized", Instant.now().plusSeconds(60));
        assertThrows(SecurityException.class,
                () -> unauthorized.approve(0, "payload-hash", "snapshot-hash", "snapshot-hash", actor(),
                        Set.of("ai:approval:review"), "k3", "r3"));

        assertThrows(IllegalArgumentException.class,
                () -> BusinessExecutionActor.from(ActorDescriptor.service("outbox", 7L, 7L)));
    }

    @Test
    void scopedIdempotencyReturnsSameDecisionAndRejectsDifferentPayload() {
        ActionProposal proposal = proposal("idem", Instant.now().plusSeconds(60));
        ActionProposal.ApprovalDecision first = proposal.approve(0, "payload-hash", "snapshot-hash",
                "snapshot-hash", actor(), permissions(), "same-key", "request-a");
        ActionProposal.ApprovalDecision replay = proposal.approve(0, "payload-hash", "snapshot-hash",
                "snapshot-hash", actor(), permissions(), "same-key", "request-a");
        assertEquals(first, replay);
        assertEquals(1, proposal.approvals().size());
        assertThrows(IdempotencyConflictException.class,
                () -> proposal.approve(0, "payload-hash", "snapshot-hash", "snapshot-hash", actor(),
                        permissions(), "same-key", "request-b"));
    }

    @Test
    void scheduledExpiryOnlyClosesUnexecutedDueProposals() {
        ActionProposal pending = proposal("pending-expiry", Instant.now().minusSeconds(1));
        assertTrue(pending.expireIfDue(Instant.now()));
        assertEquals(ProposalState.EXPIRED, pending.state());
        assertTrue(!pending.expireIfDue(Instant.now()));

        ActionProposal executing = proposal("executing-expiry", Instant.now().plusSeconds(1));
        executing.approve(0, "payload-hash", "snapshot-hash", "snapshot-hash",
                actor(), permissions(), "execute", "execute-request");
        executing.acquireExecutionLease(actor());
        assertTrue(!executing.expireIfDue(Instant.now().plusSeconds(60)));
        assertEquals(ProposalState.EXECUTING, executing.state());
    }

    @Test
    void onlyAllowlistedActionsAndCanonicalPayloadAreAccepted() {
        String canonical = CanonicalJsonHasher.canonicalize("{\"b\":2,\"a\":1}");
        assertEquals("{\"a\":1,\"b\":2}", canonical);
        assertEquals(64, CanonicalJsonHasher.sha256(canonical).length());
        assertTrue(ActionType.allowlisted().containsAll(Set.of(ActionType.NOTICE_CREATE_DRAFT, ActionType.REPAIR_ASSIGN)));
        assertEquals(2, ActionType.allowlisted().size());
        assertThrows(IllegalArgumentException.class,
                () -> ActionProposal.pending("bad", ActionType.REPAIR_ASSIGN, "{\"url\":\"http://evil\"}",
                        "hash", "snapshot", "repair:write", 1, Instant.now().plusSeconds(10)));
    }

    private ActionProposal proposal(String id, Instant expiresAt) {
        return ActionProposal.pending(id, ActionType.NOTICE_CREATE_DRAFT, "{\"status\":\"草稿\"}",
                "payload-hash", "snapshot-hash", "notice:write", 1, expiresAt);
    }

    private BusinessExecutionActor actor() {
        return BusinessExecutionActor.from(ActorDescriptor.user(7));
    }

    private Set<String> permissions() {
        return Set.of("ai:approval:review", "notice:write", "repair:write");
    }
}
