package com.example.dormitory.ai.security;

public class SensitiveDataBlockedException extends RuntimeException {

    private final String errorCode;

    public SensitiveDataBlockedException(String message) {
        super(message);
        this.errorCode = "AI_L3_DATA_BLOCKED";
    }

    public String errorCode() {
        return errorCode;
    }
}
