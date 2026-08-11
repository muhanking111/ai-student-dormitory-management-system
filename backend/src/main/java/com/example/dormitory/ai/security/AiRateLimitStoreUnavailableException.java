package com.example.dormitory.ai.security;

public class AiRateLimitStoreUnavailableException extends RuntimeException {
    public AiRateLimitStoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
