package com.example.dormitory.ai.risk;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.application.run.AiActorContext;
import com.example.dormitory.ai.application.run.AiActorResolver;
import com.example.dormitory.ai.application.run.AiRuntimeGate;
import com.example.dormitory.ai.approval.CanonicalJsonHasher;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.AiCapability;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiIdempotencyRepository;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiOutboxRepository;
import com.example.dormitory.ai.infrastructure.persistence.JdbcRiskScanRepository;
import com.example.dormitory.ai.port.AiAuditPort;
import com.example.dormitory.ai.security.ActorAuthorizationFacade;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 风险扫描命令入口与 SERVICE worker 编排；请求线程不读取业务风险事实。 */
@Service
@ConditionalOnProperty(prefix = "dormitory.ai.capabilities", name = "risk", havingValue = "true")
public class RiskScanService {

    private static final String WORKER_PRINCIPAL = "risk-scan";

    private final AiRuntimeGate gate;
    private final AiActorResolver actors;
    private final ActorAuthorizationFacade authorization;
    private final RiskScanScopeFactory scopeFactory;
    private final RiskSignalRegistry registry;
    private final RiskCaseService cases;
    private final RiskExplanationTransactionRunner explanations;
    private final JdbcRiskScanRepository scans;
    private final JdbcAiIdempotencyRepository idempotency;
    private final JdbcAiOutboxRepository outbox;
    private final AiAuditPort audit;

    public RiskScanService(
            AiRuntimeGate gate,
            AiActorResolver actors,
            ActorAuthorizationFacade authorization,
            RiskScanScopeFactory scopeFactory,
            RiskSignalRegistry registry,
            RiskCaseService cases,
            RiskExplanationTransactionRunner explanations,
            JdbcRiskScanRepository scans,
            JdbcAiIdempotencyRepository idempotency,
            JdbcAiOutboxRepository outbox,
            AiAuditPort audit) {
        this.gate = gate;
        this.actors = actors;
        this.authorization = authorization;
        this.scopeFactory = scopeFactory;
        this.registry = registry;
        this.cases = cases;
        this.explanations = explanations;
        this.scans = scans;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.audit = audit;
    }

    @Transactional
    public JdbcRiskScanRepository.Scan request(String key) {
        AiActorContext actor = actors.current("ai:risk:manage");
        gate.requireCapability(AiCapability.RISK, actor.userId());
        if (!actor.permissionCodes().contains("ai:risk:read")) {
            throw new SecurityException("缺少风险读取权限");
        }
        if (!audit.writable()) throw new IllegalStateException("AI 审计不可写，禁止风险扫描");

        String aggregate = UUID.nameUUIDFromBytes(("risk-scan|" + actor.userId())
                .getBytes(StandardCharsets.UTF_8)).toString();
        String hash = CanonicalJsonHasher.sha256("risk-scan.v1|" + actor.userId());
        var reservation = idempotency.reserve(new JdbcAiIdempotencyRepository.Scope(
                actor.userId(), "AI_RISK_SCAN", aggregate, key), hash, Instant.now().plusSeconds(86_400));
        if (reservation.status() == JdbcAiIdempotencyRepository.ReservationStatus.REPLAY) {
            var completed = idempotency.completedResponse(reservation.recordId());
            if (completed.isPresent()) {
                return scans.get(actor.userId(), completed.get().resourcePublicId()).orElseThrow();
            }
            throw new AiApiException(HttpStatus.CONFLICT, "AI_RISK_SCAN_IN_PROGRESS",
                    "风险扫描正在处理中", true);
        }

        try {
            Instant now = Instant.now();
            RiskScanScope requestedScope = scopeFactory.capture(actor.userId(),
                    Set.copyOf(actor.roleCodes()), Set.copyOf(actor.permissionCodes()), now);
            JdbcRiskScanRepository.Scan created = scans.create(actor.userId(), actor.roleCodes(),
                    actor.permissionCodes(), actor.permissionDigest(), requestedScope, now);
            outbox.enqueueOnce("risk-scan-requested|" + created.id(),
                    new JdbcAiOutboxRepository.OutboxDraft(
                            "RISK_SCAN", created.id(), "RiskScanRequested.v1",
                            "{\"schemaVersion\":\"risk-scan-request.v1\",\"scanId\":\""
                                    + created.id() + "\"}",
                            ActorDescriptor.service(WORKER_PRINCIPAL, actor.userId(), actor.userId()), now));
            idempotency.complete(reservation.recordId(), 202, created.id());
            audit.append(new AiAuditPort.AiAuditEvent("RISK_SCAN", created.id(),
                    "RISK_SCAN_REQUESTED", actor.actor(),
                    CanonicalJsonHasher.sha256(created.id() + "|QUEUED"), now));
            return created;
        } catch (RuntimeException failure) {
            idempotency.releasePending(reservation.recordId());
            throw failure;
        }
    }

    /** 由固定 outbox handler 调用；不依赖 Sa-Token ThreadLocal。 */
    @Transactional
    public void process(String scanId) {
        gate.requireCapability(AiCapability.RISK);
        if (!audit.writable()) throw new IllegalStateException("AI 审计不可写，暂停风险扫描 worker");
        JdbcRiskScanRepository.ScanWork work = scans.begin(scanId, Instant.now())
                .orElseThrow(() -> new IllegalArgumentException("风险扫描不存在"));
        if (Set.of("SUCCEEDED", "PARTIAL", "FAILED", "NEEDS_REVIEW").contains(work.state())) return;

        if (!actors.permissionDigest(work.requestedPermissionCodes()).equals(work.permissionDigest())) {
            scans.fail(scanId, "RISK_SCOPE_INTEGRITY_FAILURE", true, Instant.now());
            return;
        }
        var current = authorization.snapshot(work.initiatedByUserId());
        if (!current.enabled() || !current.permissionCodes().contains("ai:risk:read")
                || !current.permissionCodes().contains("ai:risk:manage")) {
            scans.fail(scanId, "RISK_AUTHORIZATION_REVOKED", false, Instant.now());
            audit.append(new AiAuditPort.AiAuditEvent("RISK_SCAN", scanId,
                    "RISK_SCAN_AUTHORIZATION_REVOKED",
                    ActorDescriptor.service(WORKER_PRINCIPAL, work.initiatedByUserId(), work.initiatedByUserId()),
                    CanonicalJsonHasher.sha256(scanId + "|AUTHORIZATION_REVOKED"), Instant.now()));
            return;
        }
        gate.requireCapability(AiCapability.RISK, work.initiatedByUserId());

        RiskScanScope currentScope = scopeFactory.capture(work.initiatedByUserId(),
                Set.copyOf(current.roleCodes()), Set.copyOf(current.permissionCodes()), Instant.now());
        RiskScanScope effectiveScope = work.requestedScope().intersect(currentScope);
        RiskSignalRegistry.ScanResult result = registry.scan(effectiveScope);
        int caseCount = 0;
        List<String> explanationCaseIds = new ArrayList<>();
        ActorDescriptor serviceActor = ActorDescriptor.service(
                WORKER_PRINCIPAL, work.initiatedByUserId(), work.initiatedByUserId());
        for (RiskSignal signal : result.signals()) {
            RiskCaseService.CaseView riskCase = cases.ingest(signal);
            explanationCaseIds.add(riskCase.publicId());
            outbox.enqueueOnce("risk-case-opened|" + riskCase.publicId(),
                    new JdbcAiOutboxRepository.OutboxDraft(
                            "RISK_CASE", riskCase.publicId(), "RiskCaseOpened.v1",
                            "{\"schemaVersion\":\"risk-case-opened.v1\",\"riskType\":\""
                                    + riskCase.riskType() + "\"}", serviceActor, Instant.now()));
            caseCount++;
        }
        Instant completedAt = Instant.now();
        scans.complete(scanId, result.providerVersions(), result.unavailableProviders(),
                result.signals().size(), caseCount, result.duplicateCount(), completedAt);
        audit.append(new AiAuditPort.AiAuditEvent("RISK_SCAN", scanId,
                "RISK_SCAN_COMPLETED", serviceActor,
                CanonicalJsonHasher.sha256(scanId + "|" + caseCount), completedAt));
        explainAfterCommit(explanationCaseIds, work.initiatedByUserId());
    }

    private void explainAfterCommit(List<String> riskCasePublicIds, long initiatedByUserId) {
        if (riskCasePublicIds.isEmpty()) return;
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("风险解释必须在确定性扫描事务提交后执行");
        }
        List<String> committedCaseIds = List.copyOf(riskCasePublicIds);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                committedCaseIds.forEach(caseId -> explanations.tryExplain(caseId, initiatedByUserId));
            }
        });
    }

    @Transactional
    public void markNeedsReview(String scanId, String errorCode) {
        scans.fail(scanId, errorCode, true, Instant.now());
    }

    public JdbcRiskScanRepository.Scan get(String id) {
        gate.requireCapability(AiCapability.RISK);
        AiActorContext actor = actors.current("ai:risk:read");
        return scans.get(actor.userId(), id).orElseThrow(AiApiException::notFound);
    }
}
