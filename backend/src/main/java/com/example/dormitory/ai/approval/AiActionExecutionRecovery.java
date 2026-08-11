package com.example.dormitory.ai.approval;

import com.example.dormitory.ai.config.AiProperties;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.port.AiAuditPort;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** 将旧版本遗留的超时 EXECUTING 转为 NEEDS_REVIEW；禁止自动重放结果不确定的业务写。 */
@Component
public class AiActionExecutionRecovery implements ApplicationRunner {

    private final ActionProposalRepository repository;
    private final AiAuditPort audit;
    private final AiProperties properties;

    public AiActionExecutionRecovery(
            ActionProposalRepository repository,
            AiAuditPort audit,
            AiProperties properties) {
        this.repository = repository;
        this.audit = audit;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled()) return;
        recoverNow(Instant.now());
    }

    /**
     * 启动时租约可能尚未过期，因此必须持续扫描。仓储更新只接受 EXECUTING 状态，重复扫描幂等，
     * 且结果未知的业务动作只进入人工对账，绝不自动重放。
     */
    @Scheduled(
            initialDelayString = "${dormitory.ai.write-execution.recovery-interval:PT1M}",
            fixedDelayString = "${dormitory.ai.write-execution.recovery-interval:PT1M}")
    @Transactional
    void recoverPeriodically() {
        if (!properties.isEnabled()) return;
        recoverNow(Instant.now());
    }

    @Transactional
    public int recoverNow(Instant now) {
        if (now == null) throw new IllegalArgumentException("恢复时间不能为空");
        if (!audit.writable()) throw new IllegalStateException("AI 审计不可写，禁止执行恢复");
        var recovered = repository.markStaleExecutionsNeedsReview(
                now.minus(properties.getWriteExecution().getLeaseTimeout()));
        for (String proposalId : recovered) {
            audit.append(new AiAuditPort.AiAuditEvent(
                    "ACTION_PROPOSAL", proposalId, "EXECUTION_NEEDS_REVIEW",
                    ActorDescriptor.system("AI_EXECUTION_RECOVERY"),
                    CanonicalJsonHasher.sha256("AI_EXECUTION_OUTCOME_UNKNOWN|" + proposalId), now));
        }
        return recovered.size();
    }
}
