package com.example.dormitory.ai.risk;

public class RiskCaseConflictException extends IllegalStateException {
    private final String errorCode;

    public RiskCaseConflictException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
