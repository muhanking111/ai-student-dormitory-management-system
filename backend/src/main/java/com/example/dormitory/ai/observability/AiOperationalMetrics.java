package com.example.dormitory.ai.observability;

import com.example.dormitory.ai.domain.model.AiCapability;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * P6 低基数指标面。所有可变字符串都必须先在部署期 vocabulary 中登记；接口不接收 user/run/correlation ID。
 */
public final class AiOperationalMetrics {

    private static final Set<String> PROPOSAL_ACTIONS = Set.of("NOTICE_CREATE_DRAFT", "REPAIR_ASSIGN");
    private static final Set<String> PROPOSAL_STATES = Set.of(
            "PENDING_APPROVAL", "APPROVED", "REJECTED", "EXPIRED", "STALE", "EXECUTING",
            "SUCCEEDED", "FAILED", "NEEDS_REVIEW", "CANCELLED");
    private static final Set<String> QUOTA_SCOPES = Set.of(
            "USER", "ROLE", "CAPABILITY", "PROVIDER", "EVAL", "INGESTION");
    private static final Set<String> SECURITY_REASONS = Set.of(
            "PII_L3", "PROMPT_INJECTION", "TOOL_DENIED", "ACL_DENIED", "AUDIT_UNAVAILABLE",
            "CSRF", "IDOR", "SECRET");

    private final MeterRegistry registry;
    private final TagVocabulary vocabulary;

    public AiOperationalMetrics(MeterRegistry registry, TagVocabulary vocabulary) {
        this.registry = java.util.Objects.requireNonNull(registry, "MeterRegistry 不能为空");
        this.vocabulary = java.util.Objects.requireNonNull(vocabulary, "指标 vocabulary 不能为空");
    }

    public void recordRun(RunSample sample) {
        java.util.Objects.requireNonNull(sample, "run sample 不能为空");
        Tags base = Tags.of(
                "capability", sample.capability().name(),
                "provider", vocabulary.provider(sample.providerAlias()),
                "model", vocabulary.model(sample.modelAlias()),
                "status", sample.outcome().name(),
                "degrade_mode", sample.degradeMode().name());
        Counter.builder("dormitory.ai.run.total").tags(base).register(registry).increment();
        Timer.builder("dormitory.ai.run.duration").tags(base).register(registry).record(sample.duration());
        if (sample.firstTokenDuration() != null) {
            Timer.builder("dormitory.ai.run.first_token").tags(base).register(registry)
                    .record(sample.firstTokenDuration());
        }
        recordTokens("dormitory.ai.run.tokens", base, sample.inputTokens(), sample.outputTokens());
        recordCost("dormitory.ai.run.cost", base, sample.cost(), sample.currency());
    }

    public void recordProviderAttempt(ProviderAttemptSample sample) {
        java.util.Objects.requireNonNull(sample, "provider attempt sample 不能为空");
        Tags base = Tags.of(
                "capability", sample.capability().name(),
                "provider", vocabulary.provider(sample.providerAlias()),
                "model", vocabulary.model(sample.modelAlias()),
                "request_kind", sample.requestKind().name(),
                "outcome", sample.outcome().name(),
                "usage_source", sample.usageSource().name());
        Counter.builder("dormitory.ai.provider.attempt.total").tags(base).register(registry).increment();
        Timer.builder("dormitory.ai.provider.attempt.duration").tags(base).register(registry)
                .record(sample.duration());
        recordTokens("dormitory.ai.provider.attempt.tokens", base, sample.inputTokens(), sample.outputTokens());
        recordCost("dormitory.ai.provider.attempt.cost", base, sample.cost(), sample.currency());
    }

    public void recordTool(ToolSample sample) {
        java.util.Objects.requireNonNull(sample, "tool sample 不能为空");
        Tags tags = Tags.of(
                "capability", sample.capability().name(),
                "tool", vocabulary.tool(sample.toolId()),
                "outcome", sample.outcome().name());
        Counter.builder("dormitory.ai.tool.total").tags(tags).register(registry).increment();
        Timer.builder("dormitory.ai.tool.duration").tags(tags).register(registry).record(sample.duration());
    }

    public void recordRetrieval(RetrievalSample sample) {
        java.util.Objects.requireNonNull(sample, "retrieval sample 不能为空");
        Tags tags = Tags.of(
                "capability", sample.capability().name(),
                "index", vocabulary.index(sample.indexCode()),
                "outcome", sample.outcome().name());
        Counter.builder("dormitory.ai.retrieval.total").tags(tags).register(registry).increment();
        Timer.builder("dormitory.ai.retrieval.duration").tags(tags).register(registry).record(sample.duration());
        DistributionSummary.builder("dormitory.ai.retrieval.citations").tags(tags).register(registry)
                .record(sample.citationCount());
    }

    public void recordProposal(String actionType, String state) {
        String action = fixed(actionType, PROPOSAL_ACTIONS, "proposal action");
        String status = fixed(state, PROPOSAL_STATES, "proposal state");
        Counter.builder("dormitory.ai.proposal.total")
                .tags("action", action, "status", status).register(registry).increment();
    }

    public void recordQuotaRejection(AiCapability capability, String scope) {
        requireCapability(capability);
        Counter.builder("dormitory.ai.quota.rejection.total")
                .tags("capability", capability.name(), "scope", fixed(scope, QUOTA_SCOPES, "quota scope"))
                .register(registry).increment();
    }

    public void recordSecurityBlock(AiCapability capability, String reason) {
        requireCapability(capability);
        Counter.builder("dormitory.ai.security.block.total")
                .tags("capability", capability.name(), "reason", fixed(reason, SECURITY_REASONS, "security reason"))
                .register(registry).increment();
    }

    private void recordTokens(String name, Tags base, long input, long output) {
        DistributionSummary.builder(name).tags(base.and("direction", "input")).register(registry).record(input);
        DistributionSummary.builder(name).tags(base.and("direction", "output")).register(registry).record(output);
    }

    private void recordCost(String name, Tags base, BigDecimal amount, String currency) {
        DistributionSummary.builder(name).tags(base.and("currency", currency)).register(registry)
                .record(amount.doubleValue());
    }

    private static String fixed(String value, Set<String> allowed, String field) {
        if (value == null || !allowed.contains(value)) {
            throw new IllegalArgumentException("AI metric " + field + " 未登记");
        }
        return value;
    }

    private static AiCapability requireCapability(AiCapability capability) {
        return java.util.Objects.requireNonNull(capability, "capability 不能为空");
    }

    private static void requireDuration(Duration duration, String field) {
        if (duration == null || duration.isNegative()) throw new IllegalArgumentException(field + " 不合法");
    }

    private static void requireUsage(long input, long output, BigDecimal cost, String currency) {
        if (input < 0 || output < 0 || cost == null || cost.signum() < 0
                || currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("usage metric 不合法");
        }
    }

    public record RunSample(
            AiCapability capability,
            String providerAlias,
            String modelAlias,
            RunOutcome outcome,
            DegradeMode degradeMode,
            Duration duration,
            Duration firstTokenDuration,
            long inputTokens,
            long outputTokens,
            BigDecimal cost,
            String currency) {
        public RunSample {
            requireCapability(capability);
            java.util.Objects.requireNonNull(outcome, "run outcome 不能为空");
            java.util.Objects.requireNonNull(degradeMode, "degrade mode 不能为空");
            requireDuration(duration, "run duration");
            if (firstTokenDuration != null) requireDuration(firstTokenDuration, "first token duration");
            requireUsage(inputTokens, outputTokens, cost, currency);
        }
    }

    public record ProviderAttemptSample(
            AiCapability capability,
            String providerAlias,
            String modelAlias,
            RequestKind requestKind,
            AttemptOutcome outcome,
            UsageSource usageSource,
            Duration duration,
            long inputTokens,
            long outputTokens,
            BigDecimal cost,
            String currency) {
        public ProviderAttemptSample {
            requireCapability(capability);
            java.util.Objects.requireNonNull(requestKind, "request kind 不能为空");
            java.util.Objects.requireNonNull(outcome, "attempt outcome 不能为空");
            java.util.Objects.requireNonNull(usageSource, "usage source 不能为空");
            requireDuration(duration, "attempt duration");
            requireUsage(inputTokens, outputTokens, cost, currency);
        }
    }

    public record ToolSample(
            AiCapability capability,
            String toolId,
            AttemptOutcome outcome,
            Duration duration) {
        public ToolSample {
            requireCapability(capability);
            java.util.Objects.requireNonNull(outcome, "tool outcome 不能为空");
            requireDuration(duration, "tool duration");
        }
    }

    public record RetrievalSample(
            AiCapability capability,
            String indexCode,
            AttemptOutcome outcome,
            Duration duration,
            int citationCount) {
        public RetrievalSample {
            requireCapability(capability);
            java.util.Objects.requireNonNull(outcome, "retrieval outcome 不能为空");
            requireDuration(duration, "retrieval duration");
            if (citationCount < 0 || citationCount > 100) {
                throw new IllegalArgumentException("citation count 不合法");
            }
        }
    }

    public enum RunOutcome { SUCCEEDED, DEGRADED, FAILED, TIMED_OUT, CANCELLED }
    public enum DegradeMode { NONE, RULES, KEYWORD, PROVIDER_DISABLED, VECTOR_UNAVAILABLE }
    public enum RequestKind { MODEL_COMPLETE, MODEL_STREAM, TOOL_LOOP, STRUCTURED_REPAIR, FALLBACK, EMBEDDING }
    public enum AttemptOutcome { SUCCEEDED, FAILED, TIMED_OUT, CANCELLED, REJECTED }
    public enum UsageSource { PROVIDER, ESTIMATED }

    public record TagVocabulary(
            Set<String> providerAliases,
            Set<String> modelAliases,
            Set<String> toolIds,
            Set<String> indexCodes) {

        private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
        private static final Pattern UUID_SHAPED = Pattern.compile(
                ".*[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}.*");

        public TagVocabulary {
            providerAliases = checked(providerAliases, 16, "provider");
            modelAliases = checked(modelAliases, 64, "model");
            toolIds = checked(toolIds, 32, "tool");
            indexCodes = checked(indexCodes, 32, "index");
        }

        private static Set<String> checked(Set<String> values, int maximum, String field) {
            if (values == null || values.isEmpty() || values.size() > maximum
                    || values.stream().anyMatch(value -> value == null || !SAFE.matcher(value).matches()
                    || UUID_SHAPED.matcher(value).matches())) {
                throw new IllegalArgumentException("AI metric " + field + " vocabulary 不合法");
            }
            return Set.copyOf(values);
        }

        private String provider(String value) { return member(value, providerAliases, "provider"); }
        private String model(String value) { return member(value, modelAliases, "model"); }
        private String tool(String value) { return member(value, toolIds, "tool"); }
        private String index(String value) { return member(value, indexCodes, "index"); }

        private static String member(String value, Set<String> vocabulary, String field) {
            if (value == null || !vocabulary.contains(value)) {
                throw new IllegalArgumentException("AI metric " + field + " 未登记");
            }
            return value;
        }
    }
}
