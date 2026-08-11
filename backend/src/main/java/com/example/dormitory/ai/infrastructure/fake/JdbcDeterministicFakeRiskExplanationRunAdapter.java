package com.example.dormitory.ai.infrastructure.fake;

import com.example.dormitory.ai.application.run.AiActorContext;
import com.example.dormitory.ai.application.run.AiActorResolver;
import com.example.dormitory.ai.application.run.AiConversationRunStore;
import com.example.dormitory.ai.application.run.AiRunRecords;
import com.example.dormitory.ai.approval.CanonicalJsonHasher;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.AiCapability;
import com.example.dormitory.ai.risk.RiskExplanationRunPort;
import com.example.dormitory.ai.security.ActorAuthorizationFacade;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 无供应商凭证时的持久化 Fake 合同。只在 dev/test 且显式 adapter=fake 时装配，
 * 仍生成真实 RISK run、状态事件和审计链；不伪装成外部模型调用或 usage。
 */
@Component
@Profile({"dev", "test"})
@ConditionalOnProperty(prefix = "dormitory.ai.risk.explanation", name = "adapter", havingValue = "fake")
public final class JdbcDeterministicFakeRiskExplanationRunAdapter implements RiskExplanationRunPort {

    private final AiConversationRunStore runs;
    private final AiActorResolver actors;
    private final ActorAuthorizationFacade authorization;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AtomicLong invocations = new AtomicLong();

    public JdbcDeterministicFakeRiskExplanationRunAdapter(
            AiConversationRunStore runs,
            AiActorResolver actors,
            ActorAuthorizationFacade authorization,
            JdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.runs = runs;
        this.actors = actors;
        this.authorization = authorization;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public RunOutcome run(RunRequest request) {
        invocations.incrementAndGet();
        try {
            var snapshot = authorization.snapshot(request.initiatedByUserId());
            if (!snapshot.enabled() || !snapshot.permissionCodes().contains("ai:risk:read")
                    || !snapshot.permissionCodes().contains("ai:risk:manage")) return RunOutcome.failed();
            String explanation = explanation(request);
            String requestJson = json(Map.of(
                    "riskType", request.signal().riskType(),
                    "signalPolicyVersion", request.signal().policyVersion(),
                    "signalFacts", request.signal().facts(),
                    "businessSubjectType", request.businessSnapshot().subjectType(),
                    "businessSubjectToken", request.businessSnapshot().subjectToken(),
                    "businessFacts", request.businessSnapshot().facts()));
            String requestHash = CanonicalJsonHasher.sha256("risk-explanation-fake.v1|" + requestJson);
            String fingerprint = CanonicalJsonHasher.sha256(
                    "risk-explanation-model-actor.v1|" + request.initiatedByUserId());
            AiActorContext actor = new AiActorContext(
                    request.initiatedByUserId(), null, fingerprint, 1,
                    actors.permissionDigest(List.copyOf(snapshot.permissionCodes())),
                    List.copyOf(snapshot.roleCodes()), List.copyOf(snapshot.permissionCodes()),
                    ActorDescriptor.model("risk-explanation-fake", request.initiatedByUserId()));
            AiRunRecords.RunCreation creation = runs.createCommandRun(
                    actor, AiCapability.RISK, "RISK", "RISK_CASE", null,
                    "risk-explanation:" + request.riskCasePublicId(), requestHash,
                    requestJson, "L1", "fake");
            if (!creation.replayed()) {
                requireChanged(runs.markQueued(creation.run().id()), "RISK fake run 无法排队");
                requireChanged(runs.markStarted(creation.run().id(), "deterministic-risk-explanation-v1"),
                        "RISK fake run 无法启动");
                requireChanged(runs.completeCommandRun(creation.run().id(), Map.of(
                        "schemaVersion", "risk-explanation-fake.v1",
                        "explanation", explanation,
                        "externalProviderInvoked", false)), "RISK fake run 无法完成");
            }
            AiRunRecords.Run completed = runs.ownedRun(request.initiatedByUserId(), creation.run().id());
            if (!"RISK".equals(completed.capability()) || !"SUCCEEDED".equals(completed.state())) {
                return RunOutcome.failed();
            }
            Long databaseId = jdbc.queryForObject(
                    "SELECT id FROM ai_run WHERE public_id=? AND capability='RISK' AND state='SUCCEEDED'",
                    Long.class, completed.id());
            if (databaseId == null) return RunOutcome.failed();
            return RunOutcome.succeeded(new RunReceipt(
                    databaseId, completed.id(), completed.capability(), completed.state(), explanation));
        } catch (RuntimeException failure) {
            return RunOutcome.failed();
        }
    }

    @Override
    public long invocationCount() {
        return invocations.get();
    }

    private static String explanation(RunRequest request) {
        return "检测到“" + request.signal().riskType() + "”规则信号（策略 "
                + request.signal().policyVersion() + "），共命中 " + request.signal().facts().size()
                + " 项确定性事实。此说明仅用于辅助人工核验，不会自动确认、指派或关闭风险。";
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("无法序列化风险解释 Fake 输入", failure);
        }
    }

    private static void requireChanged(AiRunRecords.RunMutation mutation, String message) {
        if (mutation == null || !mutation.changed()) throw new IllegalStateException(message);
    }
}
