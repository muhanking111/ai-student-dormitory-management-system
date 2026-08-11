package com.example.dormitory.ai.risk;

import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.security.ActorAuthorizationFacade;
import com.example.dormitory.ai.security.PiiClassificationService;
import com.example.dormitory.ai.security.PromptInjectionGuard;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * 风险解释的 fail-safe 协调器。它只把当前授权的规则层与去标识业务快照交给受控 run 端口；
 * 不读取或传递人工事件，也不修改风险信号、责任人、截止时间和状态。
 */
@Service
@ConditionalOnProperty(prefix = "dormitory.ai.capabilities", name = "risk", havingValue = "true")
public class RiskExplanationCoordinator {

    private static final Set<String> REQUIRED_PERMISSIONS = Set.of("ai:risk:read", "ai:risk:manage");

    private final RiskExplanationProperties properties;
    private final RiskExplanationRunPort runs;
    private final RiskCaseService cases;
    private final ActorAuthorizationFacade authorization;
    private final RiskScanScopeFactory scopeFactory;
    private final PromptInjectionGuard injectionGuard;
    private final PiiClassificationService classification;

    public RiskExplanationCoordinator(
            RiskExplanationProperties properties,
            RiskExplanationRunPort runs,
            RiskCaseService cases,
            ActorAuthorizationFacade authorization,
            RiskScanScopeFactory scopeFactory,
            PromptInjectionGuard injectionGuard,
            PiiClassificationService classification) {
        this.properties = java.util.Objects.requireNonNull(properties);
        this.runs = java.util.Objects.requireNonNull(runs);
        this.cases = java.util.Objects.requireNonNull(cases);
        this.authorization = java.util.Objects.requireNonNull(authorization);
        this.scopeFactory = java.util.Objects.requireNonNull(scopeFactory);
        this.injectionGuard = java.util.Objects.requireNonNull(injectionGuard);
        this.classification = java.util.Objects.requireNonNull(classification);
    }

    public AttemptResult tryExplain(String riskCasePublicId, long initiatedByUserId) {
        if (!properties.isEnabled()) return AttemptResult.of(AttemptStatus.DISABLED);
        if (riskCasePublicId == null || initiatedByUserId < 1) {
            return AttemptResult.of(AttemptStatus.AUTHORIZATION_REVOKED);
        }

        RiskCaseService.CaseView current;
        try {
            var snapshot = authorization.snapshot(initiatedByUserId);
            if (snapshot == null || !snapshot.enabled()
                    || !snapshot.permissionCodes().containsAll(REQUIRED_PERMISSIONS)) {
                return AttemptResult.of(AttemptStatus.AUTHORIZATION_REVOKED);
            }
            RiskScanScope currentScope = scopeFactory.capture(initiatedByUserId,
                    Set.copyOf(snapshot.roleCodes()), Set.copyOf(snapshot.permissionCodes()), Instant.now());
            current = cases.get(riskCasePublicId, currentScope);
        } catch (RuntimeException revokedOrOutOfScope) {
            return AttemptResult.of(AttemptStatus.AUTHORIZATION_REVOKED);
        }
        if (current.explanationEvidence().basis() == RiskExplanationBasis.MODEL) {
            return AttemptResult.of(AttemptStatus.ALREADY_EXPLAINED);
        }
        if (!safeInput(current.signalEvidence(), current.businessSnapshot())) {
            return AttemptResult.of(AttemptStatus.INPUT_BLOCKED);
        }

        RiskExplanationRunPort.RunOutcome outcome;
        try {
            outcome = runs.run(new RiskExplanationRunPort.RunRequest(
                    current.publicId(), initiatedByUserId, current.signalEvidence(), current.businessSnapshot()));
        } catch (RuntimeException providerFailure) {
            return AttemptResult.of(AttemptStatus.PROVIDER_FAILED);
        }
        if (outcome == null || outcome.status() == RiskExplanationRunPort.OutcomeStatus.FAILED) {
            return AttemptResult.of(AttemptStatus.PROVIDER_FAILED);
        }
        if (outcome.status() == RiskExplanationRunPort.OutcomeStatus.DISABLED) {
            return AttemptResult.of(AttemptStatus.DISABLED);
        }

        try {
            RiskExplanationRunPort.RunReceipt receipt = outcome.receipt();
            RiskCaseService.CaseView explained = cases.recordModelExplanation(
                    current.publicId(), receipt.explanation(),
                    ActorDescriptor.model("risk-explanation-run", initiatedByUserId),
                    receipt.runDatabaseId(), receipt.runPublicId());
            return new AttemptResult(AttemptStatus.SUCCEEDED, explained.explanationEvidence().runPublicId());
        } catch (RuntimeException invalidReceiptOrPersistenceFailure) {
            return AttemptResult.of(AttemptStatus.PROVIDER_FAILED);
        }
    }

    private boolean safeInput(RiskSignalEvidence signal, RiskBusinessSnapshot business) {
        if (!safeText(signal.riskType()) || !safeText(signal.policyVersion())
                || !safeText(business.subjectType()) || !safeText(business.subjectToken())) {
            return false;
        }
        return safeFacts(signal.facts()) && safeFacts(business.facts());
    }

    private boolean safeFacts(Map<String, Object> facts) {
        for (Map.Entry<String, Object> entry : facts.entrySet()) {
            if (!safeText(entry.getKey())) return false;
            if (entry.getValue() instanceof String text && !safeText(text)) return false;
        }
        return true;
    }

    private boolean safeText(String value) {
        try {
            if (value == null || injectionGuard.inspect(value).blocked()) return false;
            return classification.redact(value, "risk-explanation-input").redactedText().equals(value);
        } catch (RuntimeException sensitiveOrInvalid) {
            return false;
        }
    }

    public enum AttemptStatus {
        SUCCEEDED,
        DISABLED,
        ALREADY_EXPLAINED,
        INPUT_BLOCKED,
        AUTHORIZATION_REVOKED,
        PROVIDER_FAILED
    }

    public record AttemptResult(AttemptStatus status, String runPublicId) {
        public AttemptResult {
            if (status == null || (status == AttemptStatus.SUCCEEDED) != (runPublicId != null)) {
                throw new IllegalArgumentException("风险解释尝试结果不合法");
            }
        }

        public static AttemptResult of(AttemptStatus status) {
            return new AttemptResult(status, null);
        }
    }
}
