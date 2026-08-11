package com.example.dormitory.ai.infrastructure.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public final class RedisAiSseConnectionLeaseStore implements AiSseConnectionLeaseStore {

    private static final String ACQUIRE_LUA = """
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
            if tonumber(redis.call('ZCARD', KEYS[1])) >= tonumber(ARGV[3]) then return 0 end
            redis.call('ZADD', KEYS[1], 'NX', ARGV[2], ARGV[4])
            redis.call('PEXPIRE', KEYS[1], ARGV[5])
            return 1
            """;
    private static final String RENEW_LUA = """
            if not redis.call('ZSCORE', KEYS[1], ARGV[1]) then return 0 end
            redis.call('ZADD', KEYS[1], 'XX', ARGV[2], ARGV[1])
            redis.call('PEXPIRE', KEYS[1], ARGV[3])
            return 1
            """;
    private static final RedisScript<Long> ACQUIRE = new DefaultRedisScript<>(ACQUIRE_LUA, Long.class);
    private static final RedisScript<Long> RENEW = new DefaultRedisScript<>(RENEW_LUA, Long.class);

    private final StringRedisTemplate redis;
    private final String keyPrefix;

    public RedisAiSseConnectionLeaseStore(
            StringRedisTemplate redis,
            @Value("${dormitory.ai.runtime.sse-key-prefix:dormitory:ai:sse:v1}") String keyPrefix) {
        this.redis = java.util.Objects.requireNonNull(redis);
        if (keyPrefix == null || !keyPrefix.matches("[a-zA-Z0-9:_-]{8,160}")) {
            throw new IllegalArgumentException("SSE Redis key prefix 不合法");
        }
        this.keyPrefix = keyPrefix;
    }

    @Override
    public Optional<Lease> acquire(String actorOpaqueKey, int limit, Duration ttl) {
        validate(actorOpaqueKey, limit, ttl);
        Lease lease = new Lease(UUID.randomUUID().toString(), actorOpaqueKey, ttl);
        long now = System.currentTimeMillis();
        try {
            Long acquired = redis.execute(ACQUIRE, List.of(key(actorOpaqueKey)),
                    Long.toString(now), Long.toString(now + ttl.toMillis()), Integer.toString(limit),
                    lease.leaseId(), Long.toString(ttl.toMillis() * 2));
            return acquired != null && acquired == 1L ? Optional.of(lease) : Optional.empty();
        } catch (RuntimeException failure) {
            throw new AiSseLeaseStoreUnavailableException("Redis SSE 租约控制面不可用", failure);
        }
    }

    @Override
    public boolean renew(Lease lease) {
        if (lease == null) throw new IllegalArgumentException("SSE lease 不能为空");
        long now = System.currentTimeMillis();
        try {
            Long renewed = redis.execute(RENEW, List.of(key(lease.actorOpaqueKey())), lease.leaseId(),
                    Long.toString(now + lease.ttl().toMillis()), Long.toString(lease.ttl().toMillis() * 2));
            return renewed != null && renewed == 1L;
        } catch (RuntimeException failure) {
            throw new AiSseLeaseStoreUnavailableException("Redis SSE 租约续期失败", failure);
        }
    }

    @Override
    public void release(Lease lease) {
        if (lease == null) return;
        try {
            redis.opsForZSet().remove(key(lease.actorOpaqueKey()), lease.leaseId());
        } catch (RuntimeException failure) {
            throw new AiSseLeaseStoreUnavailableException("Redis SSE 租约释放失败", failure);
        }
    }

    private void validate(String actorKey, int limit, Duration ttl) {
        if (actorKey == null || !actorKey.matches("[0-9a-f]{64}") || limit < 1 || limit > 100
                || ttl == null || ttl.isZero() || ttl.isNegative()
                || ttl.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("SSE lease 参数不合法");
        }
    }

    private String key(String actorOpaqueKey) {
        return keyPrefix + ":actor:" + actorOpaqueKey;
    }
}
