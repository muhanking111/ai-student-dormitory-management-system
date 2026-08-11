package com.example.dormitory.ai.audit;

public final class AuditAnchorUnavailableException extends RuntimeException {
    public AuditAnchorUnavailableException(Throwable cause) {
        super("审计外锚介质不可用", cause);
    }
}
