package com.example.dormitory.ai.knowledge;

import com.example.dormitory.ai.config.AiProperties;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

public class KnowledgeExternalKeyHasher {
    private final byte[] key;
    private final int keyVersion;
    public KnowledgeExternalKeyHasher(AiProperties properties) {
        this.key = properties.getTokenization().getHmacKey().getBytes(StandardCharsets.UTF_8);
        this.keyVersion = properties.getTokenization().getActiveKeyVersion();
    }
    public HashedKey hash(String sourcePublicId, String externalKey) {
        if (key.length < 32 || keyVersion < 1) {
            throw new IllegalStateException("知识外部键 HMAC 不可用");
        }
        if (sourcePublicId == null || sourcePublicId.isBlank() || externalKey == null || externalKey.isBlank()
                || externalKey.length() > 256) throw new IllegalArgumentException("知识外部键不合法");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            String value = "knowledge-external-key-v1|" + sourcePublicId + "|" + externalKey;
            return new HashedKey(HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8))), keyVersion);
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("知识外部键 HMAC 失败", exception);
        }
    }
    public record HashedKey(String hmac, int keyVersion) { }
}
