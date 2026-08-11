package com.example.dormitory.service;

import com.example.dormitory.domain.StatisticCard;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Service
public class DashboardCacheService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DashboardCacheService.class);
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String statisticsKey;

    public DashboardCacheService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${dormitory.dashboard.cache-key}") String statisticsKey) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.statisticsKey = statisticsKey;
    }

    public Optional<List<StatisticCard>> getStatistics() {
        try {
            String value = redisTemplate.opsForValue().get(statisticsKey);
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(value, new TypeReference<>() {
            }));
        } catch (Exception exception) {
            LOGGER.debug("Dashboard cache read unavailable", exception);
            return Optional.empty();
        }
    }

    public void putStatistics(List<StatisticCard> statistics) {
        try {
            redisTemplate.opsForValue().set(
                    statisticsKey,
                    objectMapper.writeValueAsString(statistics),
                    CACHE_TTL);
        } catch (Exception exception) {
            LOGGER.debug("Dashboard cache write unavailable", exception);
        }
    }

    public void evictStatistics() {
        try {
            redisTemplate.delete(statisticsKey);
        } catch (Exception exception) {
            LOGGER.debug("Dashboard cache eviction unavailable", exception);
        }
    }
}
