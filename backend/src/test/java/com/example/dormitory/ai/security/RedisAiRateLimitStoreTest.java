package com.example.dormitory.ai.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class RedisAiRateLimitStoreTest {

    @Test
    void consumesAllBucketsAtomicallyAndParsesAllowedAndDeniedDecisions() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisAiRateLimitStore store = new RedisAiRateLimitStore(redis);
        doReturn(List.of(1L, 1_001L, "2"), List.of("0", "2501", 0L))
                .when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
        List<AiRateLimitStore.Bucket> buckets = List.of(
                new AiRateLimitStore.Bucket("actor", 3, Duration.ofMillis(1_500)),
                new AiRateLimitStore.Bucket("ip", 4, Duration.ofMillis(2_001)));

        AiRateLimitStore.Decision allowed = store.consume(buckets);
        AiRateLimitStore.Decision denied = store.consume(buckets);

        assertTrue(allowed.allowed());
        assertEquals(2, allowed.remaining());
        assertEquals(2, allowed.resetAfterSeconds());
        assertFalse(denied.allowed());
        assertEquals(0, denied.remaining());
        assertEquals(3, denied.resetAfterSeconds());
    }

    @Test
    void rejectsMissingNullAndDuplicateBucketsBeforeCallingRedis() {
        RedisAiRateLimitStore store = new RedisAiRateLimitStore(mock(StringRedisTemplate.class));
        AiRateLimitStore.Bucket bucket = new AiRateLimitStore.Bucket("actor", 1, Duration.ofSeconds(1));

        assertThrows(IllegalArgumentException.class, () -> store.consume(null));
        assertThrows(IllegalArgumentException.class, () -> store.consume(List.of()));
        assertThrows(IllegalArgumentException.class, () -> store.consume(Arrays.asList(bucket, null)));
        assertThrows(IllegalArgumentException.class, () -> store.consume(List.of(bucket, bucket)));
    }

    @Test
    void rejectsMalformedScriptResultsAndWrapsParsingFailuresAsStoreUnavailable() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisAiRateLimitStore store = new RedisAiRateLimitStore(redis);
        AiRateLimitStore.Bucket bucket = new AiRateLimitStore.Bucket("actor", 1, Duration.ofSeconds(1));
        doReturn(null,
                List.of(1L, 1L),
                Arrays.asList(null, 1L, 0L),
                Arrays.asList(1L, null, 0L),
                List.of(1L, 1L, -1L),
                List.of(1L, 1L, (long) Integer.MAX_VALUE + 1L),
                List.of("not-a-number", 1L, 0L))
                .when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));

        for (int attempt = 0; attempt < 7; attempt++) {
            AiRateLimitStoreUnavailableException error = assertThrows(
                    AiRateLimitStoreUnavailableException.class, () -> store.consume(List.of(bucket)));
            assertEquals("Redis AI 限流控制面不可用", error.getMessage());
        }
    }

    @Test
    void capsVeryLargeResetAndDoesNotDoubleWrapKnownStoreFailures() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisAiRateLimitStore store = new RedisAiRateLimitStore(redis);
        AiRateLimitStore.Bucket bucket = new AiRateLimitStore.Bucket("actor", 1, Duration.ofSeconds(1));
        doReturn(List.of(0L, ((long) Integer.MAX_VALUE + 1L) * 1_000L, 0L))
                .when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));

        assertEquals(Integer.MAX_VALUE, store.consume(List.of(bucket)).resetAfterSeconds());

        AiRateLimitStoreUnavailableException classified = new AiRateLimitStoreUnavailableException(
                "classified", new IllegalStateException("redis down"));
        doThrow(classified).when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
        assertSame(classified, assertThrows(AiRateLimitStoreUnavailableException.class,
                () -> store.consume(List.of(bucket))));

        doThrow(new IllegalStateException("redis down")).when(redis)
                .execute(any(RedisScript.class), anyList(), any(Object[].class));
        AiRateLimitStoreUnavailableException wrapped = assertThrows(AiRateLimitStoreUnavailableException.class,
                () -> store.consume(List.of(bucket)));
        assertEquals("redis down", wrapped.getCause().getMessage());
    }

    @ParameterizedTest
    @MethodSource("invalidBuckets")
    void bucketContractRejectsInvalidInputs(String key, int limit, Duration window) {
        assertThrows(IllegalArgumentException.class, () -> new AiRateLimitStore.Bucket(key, limit, window));
    }

    static Stream<Object[]> invalidBuckets() {
        return Stream.of(
                new Object[] {null, 1, Duration.ofSeconds(1)},
                new Object[] {" ", 1, Duration.ofSeconds(1)},
                new Object[] {"x".repeat(257), 1, Duration.ofSeconds(1)},
                new Object[] {"actor", 0, Duration.ofSeconds(1)},
                new Object[] {"actor", 1, null},
                new Object[] {"actor", 1, Duration.ZERO},
                new Object[] {"actor", 1, Duration.ofNanos(-1)},
                new Object[] {"actor", 1, Duration.ofDays(1).plusNanos(1)});
    }

    @Test
    void decisionAndIdentityContractsRejectInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> new AiRateLimitStore.Decision(true, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new AiRateLimitStore.Decision(true, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new AiRateLimitIdentityResolver.Identity(null, "ip"));
        assertThrows(IllegalArgumentException.class, () -> new AiRateLimitIdentityResolver.Identity(" ", "ip"));
        assertThrows(IllegalArgumentException.class, () -> new AiRateLimitIdentityResolver.Identity("actor", null));
        assertThrows(IllegalArgumentException.class, () -> new AiRateLimitIdentityResolver.Identity("actor", " "));
    }
}
