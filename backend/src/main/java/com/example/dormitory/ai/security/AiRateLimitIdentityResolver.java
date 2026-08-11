package com.example.dormitory.ai.security;

import jakarta.servlet.http.HttpServletRequest;

@FunctionalInterface
public interface AiRateLimitIdentityResolver {

    Identity resolve(HttpServletRequest request);

    record Identity(String actorKey, String ipKey) {
        public Identity {
            if (actorKey == null || actorKey.isBlank() || ipKey == null || ipKey.isBlank()) {
                throw new IllegalArgumentException("AI 限流身份不能为空");
            }
        }
    }
}
