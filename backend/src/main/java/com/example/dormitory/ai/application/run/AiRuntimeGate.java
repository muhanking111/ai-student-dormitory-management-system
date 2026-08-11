package com.example.dormitory.ai.application.run;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.config.AiProperties;
import com.example.dormitory.ai.config.AiRuntimeControlService;
import com.example.dormitory.ai.domain.model.AiCapability;
import com.example.dormitory.ai.rollout.AiRolloutGate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class AiRuntimeGate {

    private static final Set<String> APPROVED_PROVIDER_CODES = Set.of("fake", "spring-ai");

    private final AiProperties properties;
    private final AiRuntimeControlService runtimeControls;
    private final Environment environment;
    private final List<AiModelRuntimePort> modelRuntimes;
    private final AiRolloutGate rolloutGate;

    @Autowired
    public AiRuntimeGate(
            AiProperties properties,
            AiRuntimeControlService runtimeControls,
            Environment environment,
            List<AiModelRuntimePort> modelRuntimes,
            AiRolloutGate rolloutGate) {
        this.properties = properties;
        this.runtimeControls = runtimeControls;
        this.environment = environment;
        this.modelRuntimes = List.copyOf(modelRuntimes);
        this.rolloutGate = rolloutGate;
    }

    /** 仅供聚焦单元测试；生产构造器必须注入真实灰度门。 */
    public AiRuntimeGate(
            AiProperties properties,
            AiRuntimeControlService runtimeControls,
            Environment environment,
            List<AiModelRuntimePort> modelRuntimes) {
        this.properties = properties;
        this.runtimeControls = runtimeControls;
        this.environment = environment;
        this.modelRuntimes = List.copyOf(modelRuntimes);
        this.rolloutGate = null;
    }

    public void requireCapability(AiCapability capability) {
        if (!runtimeControls.masterEnabled()) {
            throw AiApiException.unavailable("AI_DISABLED", "AI 功能当前未启用");
        }
        if (!runtimeControls.capabilityEnabled(capability)) {
            throw AiApiException.unavailable("AI_CAPABILITY_DISABLED", "该 AI 能力当前未启用");
        }
    }

    public void requireCapability(AiCapability capability, long actorUserId) {
        requireCapability(capability);
        requireRollout(actorUserId, capability);
    }

    public void requireStreamingProvider(AiCapability capability) {
        requireStreaming(capability);
        requireProvider();
    }

    public void requireStreamingProvider(AiCapability capability, long actorUserId) {
        requireStreaming(capability, actorUserId);
        requireProvider();
    }

    public void requireStreaming(AiCapability capability) {
        requireCapability(capability);
        if (!properties.getStreaming().isEnabled()) {
            throw AiApiException.unavailable("AI_STREAMING_DISABLED", "AI 流式服务当前未启用");
        }
    }

    public void requireStreaming(AiCapability capability, long actorUserId) {
        requireCapability(capability, actorUserId);
        if (!properties.getStreaming().isEnabled()) {
            throw AiApiException.unavailable("AI_STREAMING_DISABLED", "AI 流式服务当前未启用");
        }
    }

    public boolean providerAvailable() {
        String provider = properties.getProvider().getActive();
        if (!APPROVED_PROVIDER_CODES.contains(provider) || !runtimeControls.providerEnabled(provider)
                || !adapterReady(provider)) return false;
        return !"fake".equals(provider) || environment.acceptsProfiles(Profiles.of("dev", "test"));
    }

    private boolean adapterReady(String provider) {
        return modelRuntimes.stream().anyMatch(runtime -> provider.equals(runtime.providerCode()));
    }

    private void requireProvider() {
        String provider = properties.getProvider().getActive();
        if ("none".equals(provider) || !runtimeControls.providerEnabled(provider)) {
            throw AiApiException.unavailable("AI_PROVIDER_DISABLED", "AI 模型供应商当前未启用");
        }
        if ("fake".equals(provider)
                && !environment.acceptsProfiles(Profiles.of("dev", "test"))) {
            throw AiApiException.unavailable("AI_FAKE_PROVIDER_FORBIDDEN", "fake 适配器仅允许开发和测试环境");
        }
        if (!APPROVED_PROVIDER_CODES.contains(provider) || !adapterReady(provider)) {
            throw AiApiException.unavailable("AI_PROVIDER_NOT_CONFIGURED", "激活的模型适配器尚未就绪");
        }
        if ("spring-ai".equals(provider) && properties.getBudget().getReservedCost().signum() <= 0) {
            throw AiApiException.unavailable("AI_COST_RESERVATION_UNAVAILABLE", "真实模型成本预留上限未配置");
        }
    }

    private void requireRollout(long actorUserId, AiCapability capability) {
        if (rolloutGate != null) rolloutGate.requireIncluded(actorUserId, capability);
    }
}
