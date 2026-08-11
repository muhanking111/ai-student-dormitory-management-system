package com.example.dormitory.ai.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "dormitory.ai.rate-limit")
public class AiRateLimitProperties {

    private boolean enabled = true;
    private String keyPrefix = "dormitory:ai:rate-limit:v1";
    private int runsPerMinute = 10;
    private int uploadsPerHour = 20;
    private int ingestionsPerHour = 20;
    private int riskScansPerMinute = 5;
    private int approvalsPerMinute = 10;
    private int approvalsPerProposalPerMinute = 5;
    private int writesPerMinute = 60;
    private int ipMultiplier = 5;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getKeyPrefix() { return keyPrefix; }
    public void setKeyPrefix(String keyPrefix) {
        if (keyPrefix == null || !keyPrefix.matches("[a-zA-Z0-9:_-]{8,160}")) {
            throw new IllegalArgumentException("AI 限流 Redis key prefix 不合法");
        }
        this.keyPrefix = keyPrefix;
    }
    public int getRunsPerMinute() { return runsPerMinute; }
    public void setRunsPerMinute(int value) { runsPerMinute = positive(value); }
    public int getUploadsPerHour() { return uploadsPerHour; }
    public void setUploadsPerHour(int value) { uploadsPerHour = positive(value); }
    public int getIngestionsPerHour() { return ingestionsPerHour; }
    public void setIngestionsPerHour(int value) { ingestionsPerHour = positive(value); }
    public int getRiskScansPerMinute() { return riskScansPerMinute; }
    public void setRiskScansPerMinute(int value) { riskScansPerMinute = positive(value); }
    public int getApprovalsPerMinute() { return approvalsPerMinute; }
    public void setApprovalsPerMinute(int value) { approvalsPerMinute = positive(value); }
    public int getApprovalsPerProposalPerMinute() { return approvalsPerProposalPerMinute; }
    public void setApprovalsPerProposalPerMinute(int value) {
        approvalsPerProposalPerMinute = positive(value);
    }
    public int getWritesPerMinute() { return writesPerMinute; }
    public void setWritesPerMinute(int value) { writesPerMinute = positive(value); }
    public int getIpMultiplier() { return ipMultiplier; }
    public void setIpMultiplier(int value) {
        if (value < 1 || value > 100) throw new IllegalArgumentException("AI IP 限流倍数必须在 1 到 100 之间");
        ipMultiplier = value;
    }

    private int positive(int value) {
        if (value < 1 || value > 1_000_000) throw new IllegalArgumentException("AI 限流阈值不合法");
        return value;
    }
}
