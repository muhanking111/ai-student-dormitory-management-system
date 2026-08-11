package com.example.dormitory.ai.security;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.config.AiProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Component
public class AiRateLimitInterceptor implements HandlerInterceptor {

    private final AiRateLimitPolicy policy;
    private final AiRateLimitStore store;
    private final AiRateLimitIdentityResolver identities;
    private final AiRateLimitProperties properties;
    private final AiProperties aiProperties;

    public AiRateLimitInterceptor(AiRateLimitPolicy policy, AiRateLimitStore store,
                                  AiRateLimitIdentityResolver identities,
                                  AiRateLimitProperties properties, AiProperties aiProperties) {
        this.policy = policy;
        this.store = store;
        this.identities = identities;
        this.properties = properties;
        this.aiProperties = aiProperties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!properties.isEnabled() || !aiProperties.isEnabled()) return true;
        AiRateLimitPolicy.Rule rule = policy.resolve(request.getMethod(), request.getRequestURI()).orElse(null);
        if (rule == null) return true;
        AiRateLimitIdentityResolver.Identity identity = identities.resolve(request);
        String base = properties.getKeyPrefix() + ":" + rule.policyCode().toLowerCase(java.util.Locale.ROOT);
        List<AiRateLimitStore.Bucket> buckets = new ArrayList<>();
        buckets.add(new AiRateLimitStore.Bucket(base + ":actor:" + identity.actorKey(),
                rule.actorLimit(), rule.window()));
        buckets.add(new AiRateLimitStore.Bucket(base + ":ip:" + identity.ipKey(),
                rule.ipLimit(), rule.window()));
        rule.resourceKey().ifPresent(resource -> buckets.add(new AiRateLimitStore.Bucket(
                base + ":resource:" + sha256(resource), rule.resourceLimit(), rule.window())));

        AiRateLimitStore.Decision decision;
        try {
            decision = store.consume(List.copyOf(buckets));
        } catch (AiRateLimitStoreUnavailableException exception) {
            throw AiApiException.unavailable("AI_RATE_LIMIT_CONTROL_UNAVAILABLE", "AI 限流控制面不可用");
        }
        response.setHeader("X-RateLimit-Limit", Integer.toString(rule.actorLimit()));
        response.setHeader("X-RateLimit-Remaining", Integer.toString(decision.remaining()));
        response.setHeader("X-RateLimit-Reset", Long.toString(
                Instant.now().getEpochSecond() + decision.resetAfterSeconds()));
        response.setHeader("X-RateLimit-Policy", rule.policyCode());
        if (!decision.allowed()) {
            throw new AiApiException(HttpStatus.TOO_MANY_REQUESTS, "AI_RATE_LIMIT_EXCEEDED",
                    "请求过于频繁，请稍后重试", true, decision.resetAfterSeconds(), null, List.of(),
                    Map.of("policy", rule.policyCode()));
        }
        return true;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
