package com.example.dormitory;

import com.example.dormitory.domain.StatisticCard;
import com.example.dormitory.service.DashboardCacheService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardCacheServiceTest {

    @Test
    void readsCachedStatistics() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        ObjectMapper objectMapper = new ObjectMapper();
        List<StatisticCard> cards = List.of(new StatisticCard("宿舍总数", 5, "间", "实时数据", "blue", "home"));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("dormitory:dashboard:statistics")).thenReturn(objectMapper.writeValueAsString(cards));

        DashboardCacheService cacheService = new DashboardCacheService(
                redisTemplate,
                objectMapper,
                "dormitory:dashboard:statistics");

        assertThat(cacheService.getStatistics()).contains(cards);
    }

    @Test
    void redisUnavailableFallsBackToCacheMiss() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException("offline"));

        DashboardCacheService cacheService = new DashboardCacheService(
                redisTemplate,
                new ObjectMapper(),
                "dormitory:dashboard:statistics");

        assertThat(cacheService.getStatistics()).isEmpty();
    }

    @Test
    void usesTheConfiguredCandidateScopedCacheKey() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        ObjectMapper objectMapper = new ObjectMapper();
        List<StatisticCard> cards = List.of(new StatisticCard("宿舍总数", 5, "间", "实时数据", "blue", "home"));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("stage6:20260802:ao:dashboard:statistics"))
                .thenReturn(objectMapper.writeValueAsString(cards));

        DashboardCacheService cacheService = new DashboardCacheService(
                redisTemplate,
                objectMapper,
                "stage6:20260802:ao:dashboard:statistics");

        assertThat(cacheService.getStatistics()).contains(cards);
    }
}
