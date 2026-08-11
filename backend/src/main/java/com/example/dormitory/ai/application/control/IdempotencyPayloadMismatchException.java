package com.example.dormitory.ai.application.control;

public class IdempotencyPayloadMismatchException extends RuntimeException {

    private final String errorCode = "AI_IDEMPOTENCY_PAYLOAD_MISMATCH";

    public IdempotencyPayloadMismatchException() {
        super("同一幂等范围和 key 对应了不同请求载荷");
    }

    public String errorCode() {
        return errorCode;
    }
}
