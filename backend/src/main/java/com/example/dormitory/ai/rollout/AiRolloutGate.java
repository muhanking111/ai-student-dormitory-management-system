package com.example.dormitory.ai.rollout;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.config.AiProperties;
import com.example.dormitory.ai.domain.model.AiCapability;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/** 新运行的稳定灰度门；不替代 RBAC、Feature Flag、provider Kill Switch 或历史资源授权。 */
@Component
public final class AiRolloutGate {

    private final boolean enabled;
    private final Set<Long> internalUserIds;
    private final DeterministicCohortPolicy cohort;

    public AiRolloutGate(AiProperties properties) {
        AiProperties.Rollout rollout = properties.getRollout();
        this.enabled = rollout.isEnabled();
        this.internalUserIds = Set.copyOf(rollout.getInternalUserIds());
        if (!enabled) {
            if (rollout.getBasisPoints() != 0 || !internalUserIds.isEmpty()) {
                throw new IllegalStateException("关闭灰度时比例和内部白名单必须为空");
            }
            this.cohort = new DeterministicCohortPolicy();
            return;
        }
        byte[] key = rollout.getHmacKey().getBytes(StandardCharsets.UTF_8);
        if (rollout.getBasisPoints() > 0 && key.length < 32) {
            throw new IllegalStateException("启用百分比灰度时必须配置至少 32 字节 HMAC key");
        }
        this.cohort = new DeterministicCohortPolicy(
                DeterministicCohortPolicy.Configuration.enabled(
                        rollout.getBasisPoints(), rollout.getPolicyVersion(), key));
    }

    public Decision requireIncluded(long actorUserId, AiCapability capability) {
        if (actorUserId < 1 || capability == null) {
            throw new IllegalArgumentException("灰度门只接受服务端可信 actor 与 capability");
        }
        if (!enabled) throw notIncluded();
        if (internalUserIds.contains(actorUserId)) {
            return new Decision(true, true, -1, 0, "INTERNAL_ALLOWLIST");
        }
        DeterministicCohortPolicy.Decision decision = cohort.decide(actorUserId, capability);
        if (!decision.included()) throw notIncluded();
        return new Decision(true, false, decision.bucket(), decision.basisPoints(), decision.reasonCode());
    }

    private static AiApiException notIncluded() {
        return AiApiException.unavailable("AI_ROLLOUT_NOT_INCLUDED", "该 AI 能力尚未对当前账号灰度开放");
    }

    public record Decision(
            boolean included,
            boolean internalAllowlist,
            int bucket,
            int basisPoints,
            String reasonCode) { }
}
