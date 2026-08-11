package com.example.dormitory.ai.knowledge;

public final class KnowledgeSourceConflictException extends RuntimeException {

    public KnowledgeSourceConflictException() {
        super("知识来源 ACL 版本或状态已变化");
    }
}
