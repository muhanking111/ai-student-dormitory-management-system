package com.example.dormitory.ai.knowledge;

public class KnowledgeQuarantinedException extends RuntimeException {

    private final String errorCode;

    public KnowledgeQuarantinedException(String message) {
        super(message);
        this.errorCode = "AI_KNOWLEDGE_QUARANTINED";
    }

    public String errorCode() {
        return errorCode;
    }
}
