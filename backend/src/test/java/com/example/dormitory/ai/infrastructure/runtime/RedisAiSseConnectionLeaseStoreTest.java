package com.example.dormitory.ai.infrastructure.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisAiSseConnectionLeaseStoreTest {

    private static final String ACTOR_KEY = "a".repeat(64);

    @Test
    void acquireDistinguishesGrantedDeniedAndUnexpectedRedisResult() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisAiSseConnectionLeaseStore store = store(redis);
        doReturn(1L, 0L, null).when(redis)
                .execute(any(RedisScript.class), anyList(), any(Object[].class));

        Optional<AiSseConnectionLeaseStore.Lease> granted =
                store.acquire(ACTOR_KEY, 2, Duration.ofSeconds(30));
        assertTrue(granted.isPresent());
        assertEquals(ACTOR_KEY, granted.orElseThrow().actorOpaqueKey());
        assertEquals(Duration.ofSeconds(30), granted.orElseThrow().ttl());
        assertFalse(store.acquire(ACTOR_KEY, 2, Duration.ofSeconds(30)).isPresent());
        assertFalse(store.acquire(ACTOR_KEY, 2, Duration.ofSeconds(30)).isPresent());
    }

    @Test
    void acquireAndRenewWrapRedisFailuresAndRenewHandlesEveryResult() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisAiSseConnectionLeaseStore store = store(redis);
        AiSseConnectionLeaseStore.Lease lease =
                new AiSseConnectionLeaseStore.Lease("lease-1", ACTOR_KEY, Duration.ofSeconds(30));
        doReturn(1L, 0L, null).when(redis)
                .execute(any(RedisScript.class), anyList(), any(Object[].class));

        assertTrue(store.renew(lease));
        assertFalse(store.renew(lease));
        assertFalse(store.renew(lease));

        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new IllegalStateException("redis unavailable"));
        assertThrows(AiSseLeaseStoreUnavailableException.class,
                () -> store.acquire(ACTOR_KEY, 2, Duration.ofSeconds(30)));
        assertThrows(AiSseLeaseStoreUnavailableException.class, () -> store.renew(lease));
    }

    @Test
    void releaseIsNullSafeRemovesOwnedLeaseAndWrapsRedisFailures() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        RedisAiSseConnectionLeaseStore store = store(redis);
        AiSseConnectionLeaseStore.Lease lease =
                new AiSseConnectionLeaseStore.Lease("lease-1", ACTOR_KEY, Duration.ofSeconds(30));

        assertDoesNotThrow(() -> store.release(null));
        store.release(lease);
        verify(zset).remove("dormitory:ai:sse:test:actor:" + ACTOR_KEY, "lease-1");

        when(redis.opsForZSet()).thenThrow(new IllegalStateException("redis unavailable"));
        assertThrows(AiSseLeaseStoreUnavailableException.class, () -> store.release(lease));
    }

    @Test
    void rejectsNullRedisInvalidPrefixesAndNullRenewal() {
        assertThrows(NullPointerException.class,
                () -> new RedisAiSseConnectionLeaseStore(null, "dormitory:ai:sse:test"));
        assertThrows(IllegalArgumentException.class,
                () -> new RedisAiSseConnectionLeaseStore(mock(StringRedisTemplate.class), null));
        assertThrows(IllegalArgumentException.class,
                () -> new RedisAiSseConnectionLeaseStore(mock(StringRedisTemplate.class), "short"));
        assertThrows(IllegalArgumentException.class,
                () -> new RedisAiSseConnectionLeaseStore(mock(StringRedisTemplate.class), "bad prefix!"));
        assertThrows(IllegalArgumentException.class, () -> store(mock(StringRedisTemplate.class)).renew(null));
    }

    @ParameterizedTest
    @MethodSource("invalidAcquisitions")
    void rejectsInvalidAcquisitionInputs(String actorKey, int limit, Duration ttl) {
        RedisAiSseConnectionLeaseStore store = store(mock(StringRedisTemplate.class));

        assertThrows(IllegalArgumentException.class, () -> store.acquire(actorKey, limit, ttl));
    }

    private static Stream<Object[]> invalidAcquisitions() {
        return Stream.of(
                new Object[] {null, 1, Duration.ofSeconds(1)},
                new Object[] {"g".repeat(64), 1, Duration.ofSeconds(1)},
                new Object[] {ACTOR_KEY, 0, Duration.ofSeconds(1)},
                new Object[] {ACTOR_KEY, 101, Duration.ofSeconds(1)},
                new Object[] {ACTOR_KEY, 1, null},
                new Object[] {ACTOR_KEY, 1, Duration.ZERO},
                new Object[] {ACTOR_KEY, 1, Duration.ofSeconds(-1)},
                new Object[] {ACTOR_KEY, 1, Duration.ofMinutes(11)});
    }

    private RedisAiSseConnectionLeaseStore store(StringRedisTemplate redis) {
        return new RedisAiSseConnectionLeaseStore(redis, "dormitory:ai:sse:test");
    }
}
