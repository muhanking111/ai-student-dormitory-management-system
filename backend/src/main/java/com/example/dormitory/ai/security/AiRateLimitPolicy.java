package com.example.dormitory.ai.security;

import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AiRateLimitPolicy {

    private static final Pattern CONVERSATION_RUN = Pattern.compile("^/api/ai/conversations/[^/]+/messages$");
    private static final Pattern FEATURE_RUN = Pattern.compile(
            "^/api/ai/(dashboard/queries|knowledge/queries|repairs/[^/]+/triage|notices/drafts)$");
    private static final Pattern UPLOAD_CREATE = Pattern.compile("^/api/ai/knowledge/sources/[^/]+/uploads$");
    private static final Pattern UPLOAD_CONTENT = Pattern.compile("^/api/ai/knowledge/uploads/[^/]+/content$");
    private static final Pattern UPLOAD_FINALIZE = Pattern.compile("^/api/ai/knowledge/uploads/[^/]+/finalize$");
    private static final Pattern INGESTION = Pattern.compile("^/api/ai/knowledge/sources/[^/]+/versions$");
    private static final Pattern APPROVAL = Pattern.compile(
            "^/api/ai/(?:proposals|executions)/([^/]+)/(?:approve|reject|reconfirm)$");

    private final AiRateLimitProperties properties;

    public AiRateLimitPolicy(AiRateLimitProperties properties) {
        this.properties = properties;
    }

    public Optional<Rule> resolve(String method, String requestPath) {
        String verb = method == null ? "" : method.toUpperCase(Locale.ROOT);
        String path = requestPath == null ? "" : requestPath.split("\\?", 2)[0];
        if (!path.startsWith("/api/ai/") || HttpMethod.GET.matches(verb)
                || HttpMethod.HEAD.matches(verb) || HttpMethod.OPTIONS.matches(verb)) {
            return Optional.empty();
        }
        if (HttpMethod.POST.matches(verb) && (CONVERSATION_RUN.matcher(path).matches()
                || FEATURE_RUN.matcher(path).matches() || "/api/ai/eval-runs".equals(path))) {
            return Optional.of(rule("RUN", properties.getRunsPerMinute(), Duration.ofMinutes(1)));
        }
        if (HttpMethod.POST.matches(verb) && UPLOAD_CREATE.matcher(path).matches()) {
            return Optional.of(rule("UPLOAD_CREATE", properties.getUploadsPerHour(), Duration.ofHours(1)));
        }
        if (HttpMethod.PUT.matches(verb) && UPLOAD_CONTENT.matcher(path).matches()) {
            return Optional.of(rule("UPLOAD_CONTENT", properties.getUploadsPerHour(), Duration.ofHours(1)));
        }
        if (HttpMethod.POST.matches(verb) && UPLOAD_FINALIZE.matcher(path).matches()) {
            return Optional.of(rule("UPLOAD_FINALIZE", properties.getUploadsPerHour(), Duration.ofHours(1)));
        }
        if (HttpMethod.POST.matches(verb) && INGESTION.matcher(path).matches()) {
            return Optional.of(rule("INGESTION", properties.getIngestionsPerHour(), Duration.ofHours(1)));
        }
        if (HttpMethod.POST.matches(verb) && "/api/ai/risk-scans".equals(path)) {
            return Optional.of(rule("RISK_SCAN", properties.getRiskScansPerMinute(), Duration.ofMinutes(1)));
        }
        if (HttpMethod.POST.matches(verb)) {
            Matcher approval = APPROVAL.matcher(path);
            if (approval.matches()) {
                return Optional.of(rule("APPROVAL", properties.getApprovalsPerMinute(), Duration.ofMinutes(1),
                        approval.group(1), properties.getApprovalsPerProposalPerMinute()));
            }
        }
        return Optional.of(rule("WRITE", properties.getWritesPerMinute(), Duration.ofMinutes(1)));
    }

    private Rule rule(String code, int actorLimit, Duration window) {
        return rule(code, actorLimit, window, null, 0);
    }

    private Rule rule(String code, int actorLimit, Duration window, String resourceKey, int resourceLimit) {
        long multiplied = (long) actorLimit * properties.getIpMultiplier();
        int ipLimit = multiplied > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) multiplied;
        return new Rule(code, actorLimit, ipLimit, window, Optional.ofNullable(resourceKey), resourceLimit);
    }

    public record Rule(String policyCode, int actorLimit, int ipLimit, Duration window,
                       Optional<String> resourceKey, int resourceLimit) { }
}
