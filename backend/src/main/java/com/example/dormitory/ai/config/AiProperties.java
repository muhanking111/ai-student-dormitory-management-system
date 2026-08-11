package com.example.dormitory.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
@ConfigurationProperties(prefix = "dormitory.ai")
public class AiProperties {

    private boolean enabled;
    private final Capabilities capabilities = new Capabilities();
    private final Provider provider = new Provider();
    private final WriteExecution writeExecution = new WriteExecution();
    private final Streaming streaming = new Streaming();
    private final Audit audit = new Audit();
    private final Tokenization tokenization = new Tokenization();
    private final Budget budget = new Budget();
    private final Runtime runtime = new Runtime();
    private final StepUp stepUp = new StepUp();
    private final Rollout rollout = new Rollout();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Capabilities getCapabilities() {
        return capabilities;
    }

    public Provider getProvider() {
        return provider;
    }

    public WriteExecution getWriteExecution() {
        return writeExecution;
    }

    public Streaming getStreaming() {
        return streaming;
    }

    public Audit getAudit() {
        return audit;
    }

    public Tokenization getTokenization() {
        return tokenization;
    }

    public Budget getBudget() {
        return budget;
    }

    public Runtime getRuntime() {
        return runtime;
    }

    public StepUp getStepUp() {
        return stepUp;
    }

    public Rollout getRollout() {
        return rollout;
    }

    /** Compatibility accessor retained for the P0 contract tests. */
    public String getProviderActive() {
        return provider.getActive();
    }

    public void setProviderActive(String providerActive) {
        provider.setActive(providerActive);
    }

    /** Compatibility accessor retained for the P0 contract tests. */
    public boolean isWriteExecutionEnabled() {
        return writeExecution.isEnabled();
    }

    public void setWriteExecutionEnabled(boolean writeExecutionEnabled) {
        writeExecution.setEnabled(writeExecutionEnabled);
    }

    public static final class Capabilities {
        private boolean assistant;
        private boolean knowledge;
        private boolean dashboard;
        private boolean repair;
        private boolean notice;
        private boolean risk;
        private boolean evaluation;

        public boolean isAssistant() { return assistant; }
        public void setAssistant(boolean assistant) { this.assistant = assistant; }
        public boolean isKnowledge() { return knowledge; }
        public void setKnowledge(boolean knowledge) { this.knowledge = knowledge; }
        public boolean isDashboard() { return dashboard; }
        public void setDashboard(boolean dashboard) { this.dashboard = dashboard; }
        public boolean isRepair() { return repair; }
        public void setRepair(boolean repair) { this.repair = repair; }
        public boolean isNotice() { return notice; }
        public void setNotice(boolean notice) { this.notice = notice; }
        public boolean isRisk() { return risk; }
        public void setRisk(boolean risk) { this.risk = risk; }
        public boolean isEvaluation() { return evaluation; }
        public void setEvaluation(boolean evaluation) { this.evaluation = evaluation; }
    }

    public static final class Provider {
        private static final Set<String> ALLOWED = Set.of("none", "fake", "spring-ai", "langchain4j");
        private String active = "none";
        private String modelAlias = "";

        public String getActive() { return active; }

        public void setActive(String active) {
            String normalized = active == null ? "none" : active.trim().toLowerCase(Locale.ROOT);
            if (!ALLOWED.contains(normalized)) {
                throw new IllegalArgumentException("不支持的 AI provider adapter code");
            }
            this.active = normalized;
        }

        public String getModelAlias() { return modelAlias; }

        public void setModelAlias(String modelAlias) {
            String normalized = modelAlias == null ? "" : modelAlias.trim();
            if (!normalized.isEmpty() && !normalized.matches("[A-Za-z0-9][A-Za-z0-9._-]{1,127}")) {
                throw new IllegalArgumentException("AI provider model alias 不合法");
            }
            this.modelAlias = normalized;
        }
    }

    public static final class WriteExecution {
        private boolean enabled;
        private Duration leaseTimeout = Duration.ofMinutes(5);
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Duration getLeaseTimeout() { return leaseTimeout; }
        public void setLeaseTimeout(Duration leaseTimeout) {
            if (leaseTimeout == null || leaseTimeout.isZero() || leaseTimeout.isNegative()
                    || leaseTimeout.compareTo(Duration.ofHours(1)) > 0) {
                throw new IllegalArgumentException("执行租约超时必须在 0 到 1 小时内");
            }
            this.leaseTimeout = leaseTimeout;
        }
    }

    public static final class Streaming {
        private boolean enabled;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static final class Audit {
        private String hmacKey = "";
        private int activeKeyVersion = 1;
        private String previousHmacKey = "";
        private int previousKeyVersion;
        private Map<Integer, String> hmacKeyring = new LinkedHashMap<>();

        public String getHmacKey() { return hmacKey; }
        public void setHmacKey(String hmacKey) { this.hmacKey = hmacKey == null ? "" : hmacKey; }
        public int getActiveKeyVersion() { return activeKeyVersion; }
        public void setActiveKeyVersion(int activeKeyVersion) { this.activeKeyVersion = activeKeyVersion; }
        public String getPreviousHmacKey() { return previousHmacKey; }
        public void setPreviousHmacKey(String previousHmacKey) {
            this.previousHmacKey = previousHmacKey == null ? "" : previousHmacKey;
        }
        public int getPreviousKeyVersion() { return previousKeyVersion; }
        public void setPreviousKeyVersion(int previousKeyVersion) {
            this.previousKeyVersion = previousKeyVersion;
        }
        public Map<Integer, String> getHmacKeyring() { return Map.copyOf(hmacKeyring); }
        public void setHmacKeyring(Map<Integer, String> hmacKeyring) {
            this.hmacKeyring = hmacKeyring == null ? new LinkedHashMap<>() : new LinkedHashMap<>(hmacKeyring);
        }
    }

    public static final class Tokenization {
        private String hmacKey = "";
        private int activeKeyVersion = 1;

        public String getHmacKey() { return hmacKey; }
        public void setHmacKey(String hmacKey) { this.hmacKey = hmacKey == null ? "" : hmacKey; }
        public int getActiveKeyVersion() { return activeKeyVersion; }
        public void setActiveKeyVersion(int activeKeyVersion) { this.activeKeyVersion = activeKeyVersion; }
    }

    public static final class Budget {
        private long reservedTokens = 20_000;
        private BigDecimal reservedCost = BigDecimal.ZERO;
        private Duration reservationTtl = Duration.ofMinutes(10);

        public long getReservedTokens() { return reservedTokens; }
        public void setReservedTokens(long reservedTokens) { this.reservedTokens = reservedTokens; }
        public BigDecimal getReservedCost() { return reservedCost; }
        public void setReservedCost(BigDecimal reservedCost) { this.reservedCost = reservedCost; }
        public Duration getReservationTtl() { return reservationTtl; }
        public void setReservationTtl(Duration reservationTtl) { this.reservationTtl = reservationTtl; }
    }

    public static final class Rollout {
        private boolean enabled;
        private int basisPoints;
        private String policyVersion = "rollout-disabled-v1";
        private String hmacKey = "";
        private Set<Long> internalUserIds = Set.of();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getBasisPoints() { return basisPoints; }
        public void setBasisPoints(int basisPoints) {
            if (basisPoints < 0 || basisPoints > 10_000) {
                throw new IllegalArgumentException("AI 灰度比例必须在 0 到 10000 basis points");
            }
            this.basisPoints = basisPoints;
        }
        public String getPolicyVersion() { return policyVersion; }
        public void setPolicyVersion(String policyVersion) {
            String normalized = policyVersion == null ? "" : policyVersion.trim();
            if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._-]{2,63}")) {
                throw new IllegalArgumentException("AI 灰度策略版本不合法");
            }
            this.policyVersion = normalized;
        }
        public String getHmacKey() { return hmacKey; }
        public void setHmacKey(String hmacKey) { this.hmacKey = hmacKey == null ? "" : hmacKey; }
        public Set<Long> getInternalUserIds() { return internalUserIds; }
        public void setInternalUserIds(Set<Long> internalUserIds) {
            Set<Long> normalized = internalUserIds == null ? Set.of() : Set.copyOf(internalUserIds);
            if (normalized.size() > 10_000 || normalized.stream().anyMatch(id -> id == null || id < 1)) {
                throw new IllegalArgumentException("AI 内部灰度白名单不合法");
            }
            this.internalUserIds = normalized;
        }
    }

    public static final class Runtime {
        private int executorCoreSize = 2;
        private int executorMaxSize = 4;
        private int executorQueueCapacity = 256;
        private int maxSseConnectionsPerUser = 3;
        private Duration heartbeatInterval = Duration.ofSeconds(15);
        private Duration emitterTimeout = Duration.ofMinutes(5);

        public int getExecutorCoreSize() { return executorCoreSize; }
        public void setExecutorCoreSize(int executorCoreSize) { this.executorCoreSize = executorCoreSize; }
        public int getExecutorMaxSize() { return executorMaxSize; }
        public void setExecutorMaxSize(int executorMaxSize) { this.executorMaxSize = executorMaxSize; }
        public int getExecutorQueueCapacity() { return executorQueueCapacity; }
        public void setExecutorQueueCapacity(int executorQueueCapacity) {
            this.executorQueueCapacity = executorQueueCapacity;
        }
        public int getMaxSseConnectionsPerUser() { return maxSseConnectionsPerUser; }
        public void setMaxSseConnectionsPerUser(int maxSseConnectionsPerUser) {
            this.maxSseConnectionsPerUser = maxSseConnectionsPerUser;
        }
        public Duration getHeartbeatInterval() { return heartbeatInterval; }
        public void setHeartbeatInterval(Duration heartbeatInterval) { this.heartbeatInterval = heartbeatInterval; }
        public Duration getEmitterTimeout() { return emitterTimeout; }
        public void setEmitterTimeout(Duration emitterTimeout) { this.emitterTimeout = emitterTimeout; }
    }

    public static final class StepUp {
        private String hmacKey = "";
        private int activeKeyVersion = 1;
        private String previousHmacKey = "";
        private int previousKeyVersion;
        private Duration proofTtl = Duration.ofMinutes(5);
        private Duration failureWindow = Duration.ofMinutes(15);
        private int maxFailures = 5;

        public String getHmacKey() { return hmacKey; }
        public void setHmacKey(String hmacKey) { this.hmacKey = hmacKey == null ? "" : hmacKey; }
        public int getActiveKeyVersion() { return activeKeyVersion; }
        public void setActiveKeyVersion(int activeKeyVersion) { this.activeKeyVersion = activeKeyVersion; }
        public String getPreviousHmacKey() { return previousHmacKey; }
        public void setPreviousHmacKey(String previousHmacKey) {
            this.previousHmacKey = previousHmacKey == null ? "" : previousHmacKey;
        }
        public int getPreviousKeyVersion() { return previousKeyVersion; }
        public void setPreviousKeyVersion(int previousKeyVersion) { this.previousKeyVersion = previousKeyVersion; }
        public Duration getProofTtl() { return proofTtl; }
        public void setProofTtl(Duration proofTtl) { this.proofTtl = proofTtl; }
        public Duration getFailureWindow() { return failureWindow; }
        public void setFailureWindow(Duration failureWindow) { this.failureWindow = failureWindow; }
        public int getMaxFailures() { return maxFailures; }
        public void setMaxFailures(int maxFailures) { this.maxFailures = maxFailures; }
    }
}
