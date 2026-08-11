package com.example.dormitory.ai.security;

import java.time.Duration;
import java.util.List;

/**
 * AI burst 限流的共享事实端口。生产 adapter 必须支持跨实例原子消费；硬成本预算仍由 MySQL 控制。
 */
public interface AiRateLimitStore {

    Decision consume(List<Bucket> buckets);

    record Bucket(String key, int limit, Duration window) {
        public Bucket {
            if (key == null || key.isBlank() || key.length() > 256
                    || limit < 1 || window == null || window.isZero() || window.isNegative()
                    || window.compareTo(Duration.ofDays(1)) > 0) {
                throw new IllegalArgumentException("AI 限流 bucket 参数不合法");
            }
        }
    }

    record Decision(boolean allowed, int remaining, int resetAfterSeconds) {
        public Decision {
            if (remaining < 0 || resetAfterSeconds < 1) {
                throw new IllegalArgumentException("AI 限流决策参数不合法");
            }
        }

        public static Decision allowed(int remaining, int resetAfterSeconds) {
            return new Decision(true, remaining, resetAfterSeconds);
        }

        public static Decision denied(int retryAfterSeconds) {
            return new Decision(false, 0, retryAfterSeconds);
        }
    }
}
