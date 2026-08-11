package com.example.dormitory.ai.infrastructure.risk;

import com.example.dormitory.ai.config.AiProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/** 对风险主体进行用途域隔离 HMAC；原始位置、学号或姓名绝不进入 RiskSignal。 */
@Component
@ConditionalOnProperty(prefix = "dormitory.ai.capabilities", name = "risk", havingValue = "true")
public class RiskSubjectTokenizer {

    private final byte[] key;
    private final int keyVersion;

    @Autowired
    public RiskSubjectTokenizer(AiProperties properties) {
        this(properties.getTokenization().getHmacKey().getBytes(StandardCharsets.UTF_8),
                properties.getTokenization().getActiveKeyVersion());
    }

    public RiskSubjectTokenizer(byte[] key, int keyVersion) {
        if (key == null || key.length < 32 || keyVersion < 1) {
            throw new IllegalArgumentException("风险主体 tokenization key 未就绪");
        }
        this.key = key.clone();
        this.keyVersion = keyVersion;
    }

    public String tokenize(String purpose, String stableValue) {
        if (purpose == null || !purpose.matches("[a-z0-9-]{2,64}")
                || stableValue == null || stableValue.isBlank()) {
            throw new IllegalArgumentException("风险主体 token 参数不合法");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            String digest = HexFormat.of().formatHex(mac.doFinal(
                    ("risk-subject|" + purpose + "|" + stableValue).getBytes(StandardCharsets.UTF_8)));
            return "risk_k" + keyVersion + "_" + digest.substring(0, 40);
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("风险主体 HMAC 不可用", exception);
        }
    }
}
