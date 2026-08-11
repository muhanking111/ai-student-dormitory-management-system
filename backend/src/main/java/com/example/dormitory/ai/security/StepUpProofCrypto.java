package com.example.dormitory.ai.security;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.config.AiProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

@Component
public class StepUpProofCrypto {

    private static final String PREFIX = "sup1";
    private final int activeKeyVersion;
    private final Map<Integer, byte[]> keys;
    private final SecureRandom secureRandom;

    @Autowired
    public StepUpProofCrypto(AiProperties properties) {
        this(properties.getStepUp().getActiveKeyVersion(), keyMap(properties), new SecureRandom());
    }

    StepUpProofCrypto(int activeKeyVersion, Map<Integer, byte[]> keys, SecureRandom secureRandom) {
        this.activeKeyVersion = activeKeyVersion;
        this.keys = keys.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> entry.getValue().clone()));
        this.secureRandom = secureRandom;
    }

    public static StepUpProofCrypto forTesting(int version, String key) {
        return new StepUpProofCrypto(version,
                Map.of(version, key.getBytes(StandardCharsets.UTF_8)), new SecureRandom());
    }

    public GeneratedProof generate() {
        requireKey(activeKeyVersion);
        byte[] random = new byte[32];
        secureRandom.nextBytes(random);
        String opaque = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String proof = PREFIX + "." + activeKeyVersion + "." + opaque;
        return new GeneratedProof(proof, activeKeyVersion, hmac(activeKeyVersion, proof));
    }

    public ParsedProof parseAndHash(String proof) {
        if (proof == null || proof.length() > 160) throw invalidProof();
        String[] parts = proof.split("\\.", -1);
        if (parts.length != 3 || !PREFIX.equals(parts[0]) || !parts[1].matches("[1-9][0-9]{0,8}")
                || !parts[2].matches("[A-Za-z0-9_-]{43}")) {
            throw invalidProof();
        }
        int version;
        try { version = Integer.parseInt(parts[1]); }
        catch (NumberFormatException exception) { throw invalidProof(); }
        byte[] key = keys.get(version);
        if (key == null || key.length < 32) throw invalidProof();
        return new ParsedProof(version, hmac(version, proof));
    }

    private String hmac(int version, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(requireKey(version), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (AiApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("step-up proof HMAC 不可用", exception);
        }
    }

    private byte[] requireKey(int version) {
        byte[] key = keys.get(version);
        if (version < 1 || key == null || key.length < 32) {
            throw AiApiException.unavailable("AI_STEP_UP_KEY_UNAVAILABLE", "step-up 密钥控制面不可用");
        }
        return key.clone();
    }

    private static Map<Integer, byte[]> keyMap(AiProperties properties) {
        java.util.HashMap<Integer, byte[]> values = new java.util.HashMap<>();
        AiProperties.StepUp config = properties.getStepUp();
        if (config.getPreviousKeyVersion() > 0
                && config.getPreviousKeyVersion() == config.getActiveKeyVersion()
                && !config.getPreviousHmacKey().isBlank()) {
            throw new IllegalArgumentException("step-up active/previous key version 不能相同");
        }
        if (config.getActiveKeyVersion() > 0 && !config.getHmacKey().isBlank()) {
            values.put(config.getActiveKeyVersion(), config.getHmacKey().getBytes(StandardCharsets.UTF_8));
        }
        if (config.getPreviousKeyVersion() > 0 && !config.getPreviousHmacKey().isBlank()) {
            values.put(config.getPreviousKeyVersion(), config.getPreviousHmacKey().getBytes(StandardCharsets.UTF_8));
        }
        return values;
    }

    private static AiApiException invalidProof() {
        return new AiApiException(HttpStatus.FORBIDDEN, "AI_STEP_UP_PROOF_INVALID",
                "step-up 证明无效、过期或已使用", false);
    }

    public record GeneratedProof(String proof, int keyVersion, String hmac) { }
    public record ParsedProof(int keyVersion, String hmac) { }
}
