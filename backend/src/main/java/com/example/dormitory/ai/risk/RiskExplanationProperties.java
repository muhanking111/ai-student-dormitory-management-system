package com.example.dormitory.ai.risk;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 独立风险模型解释开关；即使风险规则能力启用，模型解释仍默认关闭。 */
@Component
@ConfigurationProperties(prefix = "dormitory.ai.risk.explanation")
public class RiskExplanationProperties {

    private boolean enabled;
    private String adapter = "none";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAdapter() {
        return adapter;
    }

    public void setAdapter(String adapter) {
        String normalized = adapter == null ? "none" : adapter.trim().toLowerCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("none", "fake", "spring-ai").contains(normalized)) {
            throw new IllegalArgumentException("风险解释 adapter 不合法");
        }
        this.adapter = normalized;
    }
}
