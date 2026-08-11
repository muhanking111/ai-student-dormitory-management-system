package com.example.dormitory.ai.domain.model;

public record ModelResult(String content, ModelUsage usage, String finishReason) {

    public ModelResult {
        if (content == null) {
            throw new IllegalArgumentException("模型结果不能为空");
        }
        if (usage == null) {
            throw new IllegalArgumentException("模型用量不能为空");
        }
        if (finishReason == null || finishReason.isBlank()) {
            throw new IllegalArgumentException("模型结束原因不能为空");
        }
    }
}
