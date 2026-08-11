package com.example.dormitory.ai.erasure;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.application.run.AiActorContext;
import com.example.dormitory.ai.application.run.AiActorResolver;
import com.example.dormitory.ai.approval.CanonicalJsonHasher;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiIdempotencyRepository;
import com.example.dormitory.ai.port.AiAuditPort;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

@Service
public class AiErasureService {

    private final AiActorResolver actors;
    private final ErasureJobRepository jobs;
    private final ErasureRetentionPolicy retention;
    private final ErasureProofPort proof;
    private final AiAuditPort audit;
    private final JdbcAiIdempotencyRepository idempotency;
    private final TransactionTemplate transactions;

    public AiErasureService(
            AiActorResolver actors,
            ErasureJobRepository jobs,
            ErasureRetentionPolicy retention,
            ErasureProofPort proof,
            AiAuditPort audit,
            JdbcAiIdempotencyRepository idempotency,
            PlatformTransactionManager transactionManager) {
        this.actors = actors;
        this.jobs = jobs;
        this.retention = retention;
        this.proof = proof;
        this.audit = audit;
        this.idempotency = idempotency;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public JobView requestConversationErasure(String conversationId, String idempotencyKey) {
        // 清除控制面必须独立于助手能力与助手权限；所有权在事务内由 repository 重新校验。
        AiActorContext actor = actors.current(null);
        if (!audit.writable()) throw new IllegalStateException("AI 审计不可写，禁止创建清除任务");

        Instant now = Instant.now();
        String requestHash = CanonicalJsonHasher.sha256(
                "conversation-erasure.v1|" + actor.userId() + "|" + conversationId);
        JdbcAiIdempotencyRepository.Reservation reservation = idempotency.reserve(
                new JdbcAiIdempotencyRepository.Scope(actor.userId(), "AI_CONVERSATION_ERASURE",
                        conversationId, idempotencyKey), requestHash, now.plusSeconds(86_400));
        if (reservation.status() == JdbcAiIdempotencyRepository.ReservationStatus.REPLAY) {
            var completed = idempotency.completedResponse(reservation.recordId());
            if (completed.isPresent()) {
                return view(jobs.findOwned(actor.userId(), completed.get().resourcePublicId())
                        .orElseThrow(AiApiException::notFound));
            }
            throw new AiApiException(HttpStatus.CONFLICT, "AI_ERASURE_IN_PROGRESS",
                    "会话清除请求正在处理中", true);
        }

        try {
            var active = jobs.findActive(actor.userId(), conversationId);
            ErasureJobRepository.Job job = active.orElseGet(() -> jobs.create(
                    actor.userId(), conversationId,
                    retention.retentionHold(actor.userId(), conversationId), now));
            if (active.isEmpty()) {
                audit.append(new AiAuditPort.AiAuditEvent("CONVERSATION", conversationId,
                        "ERASURE_REQUESTED", actor.actor(),
                        CanonicalJsonHasher.sha256(job.publicId()), now));
            }
            idempotency.complete(reservation.recordId(), 202, job.publicId());
            return view(job);
        } catch (RuntimeException failure) {
            idempotency.releasePending(reservation.recordId());
            throw failure;
        }
    }

    public JobView visibleJob(String jobId) {
        AiActorContext actor = actors.current(null);
        var owned = jobs.findOwned(actor.userId(), jobId);
        if (owned.isPresent()) {
            return view(owned.get());
        }
        if (actor.permissionCodes().contains("ai:audit:read")) {
            return view(jobs.findByPublicId(jobId).orElseThrow(AiApiException::notFound));
        }
        throw AiApiException.notFound();
    }

    @Scheduled(initialDelayString = "${dormitory.ai.erasure.initial-delay:PT10S}",
            fixedDelayString = "${dormitory.ai.erasure.interval:PT30S}")
    public void processAvailable() {
        if (!audit.writable()) return;
        for (int index = 0; index < 10; index++) {
            Boolean processed = transactions.execute(status -> {
                var job = jobs.claimNext(Instant.now());
                if (job.isEmpty()) return false;
                processClaimed(job.get());
                return true;
            });
            if (!Boolean.TRUE.equals(processed)) return;
        }
    }

    @Transactional
    public void process(ErasureJobRepository.Job job) {
        if (!audit.writable()) throw new IllegalStateException("AI 审计不可写，禁止执行清除任务");
        processClaimed(job);
    }

    private void processClaimed(ErasureJobRepository.Job job) {
        Instant now = Instant.now();
        boolean retryable = false;
        boolean needsReview = false;
        boolean retained = false;
        for (ErasureJobRepository.Target target : jobs.targets(job.databaseId())) {
            if ("VERIFIED".equals(target.state())) {
                if (!validProofHash(target.proofRefHash())) needsReview = true;
                continue;
            }
            if ("RETAINED".equals(target.state())) {
                retained |= "RETAINED".equals(target.state());
                continue;
            }
            if (job.retentionHold()) {
                jobs.markTargetRetained(target.id(), now);
                retained = true;
                continue;
            }
            if ("MYSQL_CONTENT".equals(target.kind())) {
                String mysqlProof = jobs.redactConversationContent(
                        job.ownerUserId(), job.conversationId());
                if (validProofHash(mysqlProof)) {
                    jobs.markTargetVerified(target.id(), mysqlProof, now);
                } else {
                    jobs.markTargetFailure(target.id(), "NEEDS_REVIEW",
                            "ERASURE_PROOF_INVALID", now);
                    needsReview = true;
                }
                continue;
            }
            ErasureProofPort.ProofResult result = proof.eraseAndVerify(
                    target.kind(), target.targetRefHash(), target.providerCode());
            switch (result.status()) {
                case VERIFIED -> {
                    if (validProofHash(result.proofHash())) {
                        jobs.markTargetVerified(target.id(), result.proofHash(), now);
                    } else {
                        jobs.markTargetFailure(target.id(), "NEEDS_REVIEW",
                                "ERASURE_PROOF_INVALID", now);
                        needsReview = true;
                    }
                }
                case RETRYABLE_FAILURE -> {
                    jobs.markTargetFailure(target.id(), "RETRYABLE_FAILED", result.errorCode(), now);
                    retryable = true;
                }
                case NEEDS_REVIEW -> {
                    jobs.markTargetFailure(target.id(), "NEEDS_REVIEW", result.errorCode(), now);
                    needsReview = true;
                }
            }
        }

        String state;
        String error;
        Instant next = now;
        if (needsReview) {
            state = "NEEDS_REVIEW";
            error = "ERASURE_PROOF_UNAVAILABLE";
        } else if (retryable) {
            state = job.attempts() >= 3 ? "NEEDS_REVIEW" : "RETRYABLE_FAILED";
            error = "ERASURE_RETRY_REQUIRED";
            next = now.plusSeconds(30L * job.attempts());
        } else if (retained) {
            state = "PARTIAL";
            error = "LEGAL_HOLD";
        } else {
            state = "SUCCEEDED";
            error = null;
        }
        List<ErasureJobRepository.Target> evidenceTargets = List.of();
        if ("SUCCEEDED".equals(state)) {
            evidenceTargets = jobs.targets(job.databaseId());
            if (evidenceTargets.isEmpty()
                    || evidenceTargets.stream().anyMatch(target -> !validTargetEvidence(target))) {
                state = "NEEDS_REVIEW";
                error = "ERASURE_PROOF_UNAVAILABLE";
            }
        }
        jobs.finish(job.databaseId(), job.version(), state, error, next, now);
        if ("SUCCEEDED".equals(state)) {
            ActorDescriptor worker = ActorDescriptor.system("AI_ERASURE_WORKER");
            audit.append(new AiAuditPort.AiAuditEvent("CONVERSATION", job.conversationId(),
                    "ERASURE_TOMBSTONE", worker,
                    ErasureEvidenceHasher.hash("ERASURE_TOMBSTONE", job, evidenceTargets), now));
            audit.append(new AiAuditPort.AiAuditEvent("CONVERSATION", job.conversationId(),
                    "ERASURE_CHECKPOINT", worker,
                    ErasureEvidenceHasher.hash("ERASURE_CHECKPOINT", job, evidenceTargets), now));
        }
    }

    private boolean validProofHash(String value) {
        return value != null && value.matches("[0-9a-fA-F]{64}");
    }

    private boolean validTargetEvidence(ErasureJobRepository.Target target) {
        return target.kind() != null && target.kind().matches("[A-Z0-9_]{1,32}")
                && target.targetRefHash() != null
                && target.targetRefHash().matches("[0-9a-fA-F]{64}")
                && "VERIFIED".equals(target.state())
                && validProofHash(target.proofRefHash());
    }

    private JobView view(ErasureJobRepository.Job job) {
        List<TargetView> targets = jobs.targets(job.databaseId()).stream()
                .map(target -> new TargetView(target.kind(), target.state(), target.attempts(),
                        target.lastErrorCode()))
                .toList();
        return new JobView(job.publicId(), job.conversationId(), job.state(),
                job.retentionHold(), job.attempts(), targets);
    }

    public record JobView(String id, String conversationId, String state, boolean retentionHold,
                          int attempts, List<TargetView> targets) {
        public JobView {
            targets = List.copyOf(targets);
        }
    }

    public record TargetView(String kind, String state, int attempts, String errorCode) { }
}
