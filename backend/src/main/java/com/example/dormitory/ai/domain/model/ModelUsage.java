package com.example.dormitory.ai.domain.model;

public record ModelUsage(long inputTokens, long outputTokens, Source source) {

    public ModelUsage {
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("Token 用量不能为负数");
        }
        if (source == null) {
            throw new IllegalArgumentException("Token 用量来源不能为空");
        }
    }

    public enum Source {
        PROVIDER,
        ESTIMATED
    }
}
