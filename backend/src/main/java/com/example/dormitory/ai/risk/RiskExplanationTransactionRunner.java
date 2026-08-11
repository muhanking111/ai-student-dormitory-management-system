package com.example.dormitory.ai.risk;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 在确定性风险案例提交后，以独立事务执行可选解释层。
 * 解释事务即使被内部参与者标记 rollback-only，也不能污染扫描事务或 outbox 处理结果。
 */
@Component
@ConditionalOnProperty(prefix = "dormitory.ai.capabilities", name = "risk", havingValue = "true")
public class RiskExplanationTransactionRunner {

    private final RiskExplanationCoordinator coordinator;
    private final TransactionTemplate requiresNew;

    public RiskExplanationTransactionRunner(
            RiskExplanationCoordinator coordinator,
            PlatformTransactionManager transactionManager) {
        this.coordinator = java.util.Objects.requireNonNull(coordinator);
        this.requiresNew = new TransactionTemplate(java.util.Objects.requireNonNull(transactionManager));
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public RiskExplanationCoordinator.AttemptResult tryExplain(
            String riskCasePublicId,
            long initiatedByUserId) {
        try {
            RiskExplanationCoordinator.AttemptResult result = requiresNew.execute(
                    status -> coordinator.tryExplain(riskCasePublicId, initiatedByUserId));
            return result == null
                    ? RiskExplanationCoordinator.AttemptResult.of(
                            RiskExplanationCoordinator.AttemptStatus.PROVIDER_FAILED)
                    : result;
        } catch (RuntimeException failedExplanationTransaction) {
            return RiskExplanationCoordinator.AttemptResult.of(
                    RiskExplanationCoordinator.AttemptStatus.PROVIDER_FAILED);
        }
    }
}
