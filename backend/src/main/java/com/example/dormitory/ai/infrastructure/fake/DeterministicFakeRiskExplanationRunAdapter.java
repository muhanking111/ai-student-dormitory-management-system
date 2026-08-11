package com.example.dormitory.ai.infrastructure.fake;

import com.example.dormitory.ai.risk.RiskExplanationRunPort;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** 仅供合同/协调器测试的确定性 fake；不会访问模型、网络或数据库。 */
public final class DeterministicFakeRiskExplanationRunAdapter implements RiskExplanationRunPort {

    private final RunOutcome outcome;
    private final AtomicLong invocations = new AtomicLong();
    private final CopyOnWriteArrayList<RunRequest> requests = new CopyOnWriteArrayList<>();

    public DeterministicFakeRiskExplanationRunAdapter(RunOutcome outcome) {
        this.outcome = java.util.Objects.requireNonNull(outcome);
    }

    public static DeterministicFakeRiskExplanationRunAdapter succeeded(
            long runDatabaseId,
            String runPublicId,
            String explanation) {
        return new DeterministicFakeRiskExplanationRunAdapter(RunOutcome.succeeded(
                new RunReceipt(runDatabaseId, runPublicId, "RISK", "SUCCEEDED", explanation)));
    }

    public static DeterministicFakeRiskExplanationRunAdapter failed() {
        return new DeterministicFakeRiskExplanationRunAdapter(RunOutcome.failed());
    }

    @Override
    public RunOutcome run(RunRequest request) {
        requests.add(java.util.Objects.requireNonNull(request));
        invocations.incrementAndGet();
        return outcome;
    }

    @Override
    public long invocationCount() {
        return invocations.get();
    }

    public List<RunRequest> requests() {
        return List.copyOf(requests);
    }
}
