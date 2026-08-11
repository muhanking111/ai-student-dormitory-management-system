package com.example.dormitory.ai.api;

import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

public class AiApiException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final boolean retryable;
    private final Integer retryAfterSeconds;
    private final String runId;
    private final List<AiErrorDetail.FieldError> fieldErrors;
    private final Map<String, String> metadata;

    public AiApiException(HttpStatus status, String errorCode, String safeMessage, boolean retryable) {
        this(status, errorCode, safeMessage, retryable, null, null, List.of(), Map.of());
    }

    public AiApiException(
            HttpStatus status,
            String errorCode,
            String safeMessage,
            boolean retryable,
            Integer retryAfterSeconds,
            String runId,
            List<AiErrorDetail.FieldError> fieldErrors,
            Map<String, String> metadata) {
        super(safeMessage);
        this.status = status;
        this.errorCode = errorCode;
        this.retryable = retryable;
        this.retryAfterSeconds = retryAfterSeconds;
        this.runId = runId;
        this.fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
        this.metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static AiApiException unavailable(String code, String message) {
        return new AiApiException(HttpStatus.SERVICE_UNAVAILABLE, code, message, true,
                30, null, List.of(), Map.of());
    }

    public static AiApiException notFound() {
        return new AiApiException(HttpStatus.NOT_FOUND, "AI_RESOURCE_NOT_FOUND", "资源不存在或不可见", false);
    }

    public HttpStatus status() { return status; }
    public String errorCode() { return errorCode; }
    public boolean retryable() { return retryable; }
    public Integer retryAfterSeconds() { return retryAfterSeconds; }
    public String runId() { return runId; }
    public List<AiErrorDetail.FieldError> fieldErrors() { return fieldErrors; }
    public Map<String, String> metadata() { return metadata; }
}
