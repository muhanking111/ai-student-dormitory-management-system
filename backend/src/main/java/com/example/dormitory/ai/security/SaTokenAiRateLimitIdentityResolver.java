package com.example.dormitory.ai.security;

import cn.dev33.satoken.stp.StpUtil;
import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.config.AiProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

@Component
public class SaTokenAiRateLimitIdentityResolver implements AiRateLimitIdentityResolver {

    private final AiProperties properties;

    public SaTokenAiRateLimitIdentityResolver(AiProperties properties) {
        this.properties = properties;
    }

    @Override
    public Identity resolve(HttpServletRequest request) {
        if (!StpUtil.isLogin()) {
            throw new AiApiException(HttpStatus.UNAUTHORIZED, "AI_AUTHENTICATION_REQUIRED",
                    "未登录或登录已过期", false);
        }
        String remoteAddress = request.getRemoteAddr();
        if (remoteAddress == null || remoteAddress.isBlank()) {
            throw AiApiException.unavailable("AI_RATE_LIMIT_IDENTITY_UNAVAILABLE", "AI 限流身份控制不可用");
        }
        return new Identity(
                hmac("rate-limit-user-v1", StpUtil.getLoginId().toString()),
                hmac("rate-limit-ip-v1", remoteAddress));
    }

    private String hmac(String domain, String value) {
        byte[] key = properties.getTokenization().getHmacKey().getBytes(StandardCharsets.UTF_8);
        if (key.length < 32 || properties.getTokenization().getActiveKeyVersion() < 1) {
            throw AiApiException.unavailable("AI_TOKENIZATION_CONTROL_UNAVAILABLE", "AI 脱敏控制面不可用");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(
                    (domain + "|" + value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("AI 限流身份 HMAC 不可用", exception);
        }
    }
}
