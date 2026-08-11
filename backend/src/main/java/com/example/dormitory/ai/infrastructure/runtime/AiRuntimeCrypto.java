package com.example.dormitory.ai.infrastructure.runtime;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.config.AiProperties;
import com.example.dormitory.ai.security.PiiClassificationService;
import com.example.dormitory.ai.security.PiiRedactionService;
import com.example.dormitory.ai.security.PiiStreamingRedactor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Component
public class AiRuntimeCrypto {

    private final AiProperties properties;
    private final PiiClassificationService classificationService;

    public AiRuntimeCrypto(AiProperties properties, PiiClassificationService classificationService) {
        this.properties = properties;
        this.classificationService = java.util.Objects.requireNonNull(classificationService);
    }

    public PiiRedactionService.RedactionResult redact(String value, String purpose) {
        return classificationService.redact(value, purpose);
    }

    public PiiStreamingRedactor streamingRedactor(String purpose, int holdbackCharacters) {
        return new PiiStreamingRedactor(classificationService, purpose, holdbackCharacters);
    }

    public String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    public String hmacHex(String purpose, String value) {
        if (purpose == null || purpose.isBlank() || value == null) {
            throw new IllegalArgumentException("AI HMAC 用途和值不能为空");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(tokenizationKey(), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((purpose + "|" + value)
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 不可用", exception);
        }
    }

    public boolean tokenizationReady() {
        return properties.getTokenization().getHmacKey().getBytes(StandardCharsets.UTF_8).length >= 32
                && properties.getTokenization().getActiveKeyVersion() > 0;
    }

    private byte[] tokenizationKey() {
        byte[] key = properties.getTokenization().getHmacKey().getBytes(StandardCharsets.UTF_8);
        if (key.length < 32 || properties.getTokenization().getActiveKeyVersion() < 1) {
            throw AiApiException.unavailable("AI_TOKENIZATION_CONTROL_UNAVAILABLE", "AI 脱敏控制面不可用");
        }
        return key;
    }

}
