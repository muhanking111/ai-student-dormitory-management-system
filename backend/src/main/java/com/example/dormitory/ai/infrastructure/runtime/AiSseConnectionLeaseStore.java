package com.example.dormitory.ai.infrastructure.runtime;

import java.time.Duration;
import java.util.Optional;

/** SSE 并发连接的跨实例短租约；生产实现必须原子 acquire。 */
public interface AiSseConnectionLeaseStore {

    Optional<Lease> acquire(String actorOpaqueKey, int limit, Duration ttl);

    boolean renew(Lease lease);

    void release(Lease lease);

    record Lease(String leaseId, String actorOpaqueKey, Duration ttl) {
        public Lease {
            if (leaseId == null || !leaseId.matches("[a-zA-Z0-9._-]{1,128}")
                    || actorOpaqueKey == null || !actorOpaqueKey.matches("[0-9a-f]{64}")
                    || ttl == null || ttl.isNegative() || ttl.isZero()
                    || ttl.compareTo(Duration.ofMinutes(10)) > 0) {
                throw new IllegalArgumentException("SSE connection lease 不合法");
            }
        }
    }
}
