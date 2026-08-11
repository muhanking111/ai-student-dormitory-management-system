package com.example.dormitory.security;

import com.example.dormitory.ai.security.AiRateLimitStore;
import com.example.dormitory.ai.security.AiRateLimitStoreUnavailableException;
import com.example.dormitory.common.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LoginRateLimitServiceTest {

    @Test
    void disabledPolicyDoesNotTouchSharedRateLimitStore() {
        AiRateLimitStore store = mock(AiRateLimitStore.class);
        LoginRateLimitProperties properties = new LoginRateLimitProperties();
        properties.setEnabled(false);

        new LoginRateLimitService(store, properties).check(request("203.0.113.10"), "Admin");

        verifyNoInteractions(store);
    }

    @Test
    void consumesIndependentUsernameAndIpBucketsWithoutRawIdentifiers() {
        AiRateLimitStore store = mock(AiRateLimitStore.class);
        when(store.consume(anyList())).thenReturn(AiRateLimitStore.Decision.allowed(7, 60));
        LoginRateLimitProperties properties = new LoginRateLimitProperties();
        properties.setKeyPrefix("dormitory:auth:test:v1");

        new LoginRateLimitService(store, properties).check(request("203.0.113.10"), "Admin");

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(store).consume(captor.capture());
        @SuppressWarnings("unchecked")
        List<AiRateLimitStore.Bucket> buckets = (List<AiRateLimitStore.Bucket>) captor.getValue();
        assertEquals(2, buckets.size());
        assertTrue(buckets.stream().allMatch(bucket -> bucket.key().startsWith("dormitory:auth:test:v1:")));
        assertFalse(buckets.stream().anyMatch(bucket -> bucket.key().contains("Admin")
                || bucket.key().contains("203.0.113.10")));
    }

    @Test
    void deniedDecisionReturnsTooManyRequests() {
        AiRateLimitStore store = mock(AiRateLimitStore.class);
        when(store.consume(anyList())).thenReturn(AiRateLimitStore.Decision.denied(17));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> new LoginRateLimitService(store, new LoginRateLimitProperties())
                        .check(request("203.0.113.10"), "admin"));

        assertEquals(429, exception.getStatus().value());
    }

    @Test
    void unavailableControlPlaneFailsClosed() {
        AiRateLimitStore store = mock(AiRateLimitStore.class);
        when(store.consume(anyList())).thenThrow(new AiRateLimitStoreUnavailableException(
                "unavailable", new IllegalStateException("redis")));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> new LoginRateLimitService(store, new LoginRateLimitProperties())
                        .check(request("203.0.113.10"), "admin"));

        assertEquals(503, exception.getStatus().value());
    }

    @Test
    void propertiesRejectUnsafeValues() {
        LoginRateLimitProperties properties = new LoginRateLimitProperties();
        assertThrows(IllegalArgumentException.class, () -> properties.setUsernameAttempts(0));
        assertThrows(IllegalArgumentException.class, () -> properties.setIpAttempts(1_000_001));
        assertThrows(IllegalArgumentException.class, () -> properties.setWindow(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> properties.setKeyPrefix("short"));
    }

    private static HttpServletRequest request(String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr(remoteAddress);
        return request;
    }
}
