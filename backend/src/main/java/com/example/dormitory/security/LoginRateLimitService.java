package com.example.dormitory.security;

import com.example.dormitory.ai.security.AiRateLimitStore;
import com.example.dormitory.ai.security.AiRateLimitStoreUnavailableException;
import com.example.dormitory.common.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * 登录入口的账号与来源地址共享窗口限流；key 只保存不可逆摘要，避免把账号或地址写入 Redis。
 */
@Component
public class LoginRateLimitService {

    private final AiRateLimitStore store;
    private final LoginRateLimitProperties properties;

    public LoginRateLimitService(AiRateLimitStore store, LoginRateLimitProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    public void check(HttpServletRequest request, String username) {
        if (!properties.isEnabled()) return;

        String normalizedUsername = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        String remoteAddress = request == null || request.getRemoteAddr() == null
                ? "unknown" : request.getRemoteAddr().trim();
        Duration window = properties.getWindow();
        List<AiRateLimitStore.Bucket> buckets = List.of(
                new AiRateLimitStore.Bucket(
                        properties.getKeyPrefix() + ":username:" + digest(normalizedUsername),
                        properties.getUsernameAttempts(), window),
                new AiRateLimitStore.Bucket(
                        properties.getKeyPrefix() + ":ip:" + digest(remoteAddress),
                        properties.getIpAttempts(), window));

        final AiRateLimitStore.Decision decision;
        try {
            decision = store.consume(buckets);
        } catch (AiRateLimitStoreUnavailableException unavailable) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "登录安全控制暂不可用，请稍后重试");
        }
        if (!decision.allowed()) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "登录尝试过于频繁，请稍后重试");
        }
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
