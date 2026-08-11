package com.example.dormitory.ai.approval;

import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.port.AiAuditPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiProposalLifecycleWorkerTest {

    @Test
    void expiresDueProposalAndWritesServiceAudit() {
        InMemoryActionProposalRepository repository = repositoryWithExpiredProposal();
        List<AiAuditPort.AiAuditEvent> events = new ArrayList<>();
        AiProposalLifecycleWorker worker = new AiProposalLifecycleWorker(repository, audit(true, events));

        assertEquals(1, worker.expireDue());
        assertEquals(ProposalState.EXPIRED, repository.findByPublicId("expired-proposal").orElseThrow()
                .proposal().state());
        assertEquals(1, events.size());
        assertEquals("PROPOSAL_EXPIRED", events.getFirst().eventType());
        assertEquals("proposal-lifecycle", events.getFirst().actor().servicePrincipalCode());
    }

    @Test
    void auditOutageStopsBeforeAnyLifecycleMutation() {
        InMemoryActionProposalRepository repository = repositoryWithExpiredProposal();
        AiProposalLifecycleWorker worker = new AiProposalLifecycleWorker(repository, audit(false, new ArrayList<>()));

        assertThrows(IllegalStateException.class, worker::expireDue);
        assertEquals(ProposalState.PENDING_APPROVAL, repository.findByPublicId("expired-proposal")
                .orElseThrow().proposal().state());
    }

    private InMemoryActionProposalRepository repositoryWithExpiredProposal() {
        InMemoryActionProposalRepository repository = new InMemoryActionProposalRepository();
        ActionProposal proposal = ActionProposal.pending("expired-proposal", ActionType.NOTICE_CREATE_DRAFT,
                "{\"status\":\"草稿\"}", "a".repeat(64), "b".repeat(64),
                "notice:write", 1, Instant.now().minusSeconds(60));
        var stored = new ActionProposalRepository.StoredProposal(proposal, "NOTICE", null,
                ProposalPreview.legacy("过期公告草稿"), "MEDIUM", 7L, Instant.now().minusSeconds(120));
        repository.createOrReplay(stored,
                ProposalOrigin.forAction(java.util.UUID.randomUUID().toString(), ActionType.NOTICE_CREATE_DRAFT),
                new ActionProposalRepository.CreateRequest(7L, "expiry-create", "c".repeat(64),
                        Instant.now().plusSeconds(60)));
        return repository;
    }

    private AiAuditPort audit(boolean writable, List<AiAuditPort.AiAuditEvent> events) {
        return new AiAuditPort() {
            @Override
            public boolean writable() { return writable; }

            @Override
            public void append(AiAuditEvent event) {
                if (!writable) throw new IllegalStateException("audit unavailable");
                events.add(event);
            }
        };
    }
}
