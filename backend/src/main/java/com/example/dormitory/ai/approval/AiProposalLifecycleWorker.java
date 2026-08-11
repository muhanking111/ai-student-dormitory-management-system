package com.example.dormitory.ai.approval;

import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.port.AiAuditPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** 以 MySQL 行锁关闭过期提案；审计不可写时整个事务回滚。 */
@Component
@ConditionalOnProperty(prefix = "dormitory.ai", name = "enabled", havingValue = "true")
public class AiProposalLifecycleWorker {

    private static final String SERVICE_PRINCIPAL = "proposal-lifecycle";

    private final ActionProposalRepository repository;
    private final AiAuditPort audit;

    public AiProposalLifecycleWorker(ActionProposalRepository repository, AiAuditPort audit) {
        this.repository = java.util.Objects.requireNonNull(repository);
        this.audit = java.util.Objects.requireNonNull(audit);
    }

    @Scheduled(
            initialDelayString = "${dormitory.ai.proposals.expiry-initial-delay:PT30S}",
            fixedDelayString = "${dormitory.ai.proposals.expiry-interval:PT30S}")
    @Transactional
    public int expireDue() {
        if (!audit.writable()) throw new IllegalStateException("AI 审计不可写，禁止迁移提案状态");
        Instant now = Instant.now();
        var expired = repository.expireDue(now);
        for (var stored : expired) {
            long initiator = stored.proposerUserId();
            audit.append(new AiAuditPort.AiAuditEvent(
                    "ACTION_PROPOSAL", stored.proposal().publicId(), "PROPOSAL_EXPIRED",
                    ActorDescriptor.service(SERVICE_PRINCIPAL, initiator, initiator),
                    CanonicalJsonHasher.sha256(stored.proposal().payloadHash()), now));
        }
        return expired.size();
    }
}
