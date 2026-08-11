package com.example.dormitory.ai.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Redis Lua 固定窗口：一次脚本原子检查并消费 actor、IP 与资源三类 bucket。 */
@Component
public class RedisAiRateLimitStore implements AiRateLimitStore {

    private static final String CONSUME_SCRIPT = """
            local retry_ms = 0
            for i = 1, #KEYS do
              local arg = (i - 1) * 2
              local limit = tonumber(ARGV[arg + 1])
              local window_ms = tonumber(ARGV[arg + 2])
              local current = tonumber(redis.call('GET', KEYS[i]) or '0')
              if current >= limit then
                local ttl = redis.call('PTTL', KEYS[i])
                if ttl < 1 then ttl = window_ms end
                if ttl > retry_ms then retry_ms = ttl end
              end
            end
            if retry_ms > 0 then
              return {0, retry_ms, 0}
            end
            local min_remaining = 2147483647
            local reset_ms = 1
            for i = 1, #KEYS do
              local arg = (i - 1) * 2
              local limit = tonumber(ARGV[arg + 1])
              local window_ms = tonumber(ARGV[arg + 2])
              local current = redis.call('INCR', KEYS[i])
              if current == 1 then redis.call('PEXPIRE', KEYS[i], window_ms) end
              local remaining = limit - current
              if remaining < min_remaining then min_remaining = remaining end
              local ttl = redis.call('PTTL', KEYS[i])
              if ttl > reset_ms then reset_ms = ttl end
            end
            return {1, reset_ms, min_remaining}
            """;

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SCRIPT = new DefaultRedisScript<>(CONSUME_SCRIPT, List.class);

    private final StringRedisTemplate redis;

    public RedisAiRateLimitStore(StringRedisTemplate redis) {
        this.redis = Objects.requireNonNull(redis);
    }

    @Override
    public Decision consume(List<Bucket> buckets) {
        if (buckets == null || buckets.isEmpty()) {
            throw new IllegalArgumentException("AI 限流 bucket 不能为空");
        }
        Set<String> unique = new HashSet<>();
        List<String> keys = new ArrayList<>(buckets.size());
        List<String> arguments = new ArrayList<>(buckets.size() * 2);
        for (Bucket bucket : buckets) {
            if (bucket == null || !unique.add(bucket.key())) {
                throw new IllegalArgumentException("AI 限流 bucket 不能重复");
            }
            keys.add(bucket.key());
            arguments.add(Integer.toString(bucket.limit()));
            arguments.add(Long.toString(bucket.window().toMillis()));
        }
        try {
            @SuppressWarnings("unchecked")
            List<Object> raw = (List<Object>) redis.execute(SCRIPT, keys, arguments.toArray());
            if (raw == null || raw.size() != 3) {
                throw new IllegalStateException("Redis 限流脚本返回无效结果");
            }
            boolean allowed = number(raw.get(0)) == 1;
            int resetSeconds = seconds(number(raw.get(1)));
            if (!allowed) return Decision.denied(resetSeconds);
            long remaining = number(raw.get(2));
            if (remaining < 0 || remaining > Integer.MAX_VALUE) {
                throw new IllegalStateException("Redis 限流 remaining 无效");
            }
            return Decision.allowed((int) remaining, resetSeconds);
        } catch (AiRateLimitStoreUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AiRateLimitStoreUnavailableException("Redis AI 限流控制面不可用", exception);
        }
    }

    private long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value != null) return Long.parseLong(value.toString());
        throw new IllegalStateException("Redis 限流脚本返回空值");
    }

    private int seconds(long milliseconds) {
        long seconds = Math.max(1, Math.floorDiv(milliseconds + 999, 1_000));
        return seconds > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) seconds;
    }
}
