package com.example.dormitory.ai.rollout;

import com.example.dormitory.ai.domain.model.AiCapability;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 只负责稳定选取灰度 cohort，不授予权限、不开启能力，也不替代运行时 Kill Switch。
 * 默认配置为 0 basis points / disabled，因而未显式装配时任何用户都不会进入灰度。
 */
public final class DeterministicCohortPolicy {

    private static final int BUCKET_COUNT = 10_000;
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final Configuration configuration;

    public DeterministicCohortPolicy() {
        this(Configuration.disabled());
    }

    public DeterministicCohortPolicy(Configuration configuration) {
        this.configuration = java.util.Objects.requireNonNull(configuration, "灰度配置不能为空");
    }

    public Decision decide(long actorUserId, AiCapability capability) {
        if (actorUserId < 1 || capability == null) {
            throw new IllegalArgumentException("灰度主体与能力必须来自服务端可信上下文");
        }
        if (!configuration.enabled() || configuration.basisPoints() == 0) {
            return new Decision(false, -1, configuration.basisPoints(),
                    configuration.policyVersion(), "ROLLOUT_DISABLED");
        }
        int bucket = bucket(actorUserId, capability);
        boolean included = bucket < configuration.basisPoints();
        return new Decision(included, bucket, configuration.basisPoints(), configuration.policyVersion(),
                included ? "IN_COHORT" : "OUTSIDE_COHORT");
    }

    private int bucket(long actorUserId, AiCapability capability) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(configuration.hmacKey(), HMAC_ALGORITHM));
            String framed = "dormitory-ai-rollout-v1\u0000" + configuration.policyVersion()
                    + "\u0000" + capability.name() + "\u0000" + actorUserId;
            byte[] digest = mac.doFinal(framed.getBytes(StandardCharsets.UTF_8));
            long unsigned = Integer.toUnsignedLong(ByteBuffer.wrap(digest, 0, Integer.BYTES).getInt());
            return (int) (unsigned % BUCKET_COUNT);
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算灰度 cohort", exception);
        }
    }

    public record Configuration(
            boolean enabled,
            int basisPoints,
            String policyVersion,
            byte[] hmacKey) {

        public Configuration {
            if (basisPoints < 0 || basisPoints > BUCKET_COUNT
                    || policyVersion == null || !policyVersion.matches("[A-Za-z0-9][A-Za-z0-9._-]{2,63}")) {
                throw new IllegalArgumentException("灰度配置不合法");
            }
            byte[] safeKey = hmacKey == null ? new byte[0] : Arrays.copyOf(hmacKey, hmacKey.length);
            if (enabled && basisPoints > 0 && safeKey.length < 32) {
                throw new IllegalArgumentException("启用灰度时必须提供至少 32 字节的服务端 HMAC key");
            }
            if (!enabled && basisPoints != 0) {
                throw new IllegalArgumentException("关闭灰度时比例必须为 0");
            }
            hmacKey = safeKey;
        }

        public static Configuration disabled() {
            return new Configuration(false, 0, "rollout-disabled-v1", new byte[0]);
        }

        public static Configuration enabled(int basisPoints, String policyVersion, byte[] hmacKey) {
            return new Configuration(true, basisPoints, policyVersion, hmacKey);
        }

        @Override
        public byte[] hmacKey() {
            return Arrays.copyOf(hmacKey, hmacKey.length);
        }
    }

    public record Decision(
            boolean included,
            int bucket,
            int basisPoints,
            String policyVersion,
            String reasonCode) {
    }
}
