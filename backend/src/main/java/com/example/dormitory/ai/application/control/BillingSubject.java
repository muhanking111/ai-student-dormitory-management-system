package com.example.dormitory.ai.application.control;

import java.util.UUID;

public record BillingSubject(Kind kind, String publicId) {

    public BillingSubject {
        if (kind == null || publicId == null) {
            throw new IllegalArgumentException("计费主体不能为空");
        }
        try {
            UUID.fromString(publicId);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("计费主体 public ID 必须是 UUID", exception);
        }
    }

    public enum Kind {
        RUN,
        EVAL,
        INGESTION
    }
}
