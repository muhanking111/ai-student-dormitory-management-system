package com.example.dormitory.ai.security;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.config.AiProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Optional;

@Component
public class SessionFingerprintService {

    private final AiProperties properties;

    public SessionFingerprintService(AiProperties properties) {
        this.properties = properties;
    }

    public Fingerprint fingerprint(String rawSessionToken) {
        if (rawSessionToken == null || rawSessionToken.isBlank()) {
            throw new IllegalArgumentException("session token 不能为空");
        }
        byte[] key = properties.getTokenization().getHmacKey().getBytes(StandardCharsets.UTF_8);
        int version = properties.getTokenization().getActiveKeyVersion();
        if (key.length < 32 || version < 1) {
            throw AiApiException.unavailable("AI_TOKENIZATION_CONTROL_UNAVAILABLE", "AI 脱敏控制面不可用");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return new Fingerprint(HexFormat.of().formatHex(mac.doFinal(
                    ("session-fingerprint-v1|" + rawSessionToken).getBytes(StandardCharsets.UTF_8))), version);
        } catch (Exception exception) {
            throw new IllegalStateException("session fingerprint HMAC 不可用", exception);
        }
    }

    public Optional<Fingerprint> fingerprintIfConfigured(String rawSessionToken) {
        byte[] key = properties.getTokenization().getHmacKey().getBytes(StandardCharsets.UTF_8);
        if (key.length < 32 || properties.getTokenization().getActiveKeyVersion() < 1) {
            return Optional.empty();
        }
        return Optional.of(fingerprint(rawSessionToken));
    }

    public record Fingerprint(String hash, int keyVersion) { }
}
