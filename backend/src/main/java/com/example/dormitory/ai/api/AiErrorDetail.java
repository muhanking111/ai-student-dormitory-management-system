package com.example.dormitory.ai.api;

import java.util.List;
import java.util.Map;

public record AiErrorDetail(
        String errorCode,
        boolean retryable,
        List<FieldError> fieldErrors,
        String runId,
        Map<String, String> metadata) {

    public AiErrorDetail {
        fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public record FieldError(String field, String code, String message) {
    }
}
