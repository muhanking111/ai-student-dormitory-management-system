package com.example.dormitory.ai.domain.model;

public record AiStreamEvent(Type type, String textDelta, ModelUsage usage) {

    public AiStreamEvent {
        if (type == null) {
            throw new IllegalArgumentException("流事件类型不能为空");
        }
        if (type == Type.TEXT_DELTA && textDelta == null) {
            throw new IllegalArgumentException("文本增量不能为空");
        }
        if (type == Type.USAGE && usage == null) {
            throw new IllegalArgumentException("用量事件必须包含用量");
        }
    }

    public static AiStreamEvent started() {
        return new AiStreamEvent(Type.STARTED, null, null);
    }

    public static AiStreamEvent textDelta(String delta) {
        return new AiStreamEvent(Type.TEXT_DELTA, delta, null);
    }

    public static AiStreamEvent usage(ModelUsage usage) {
        return new AiStreamEvent(Type.USAGE, null, usage);
    }

    public static AiStreamEvent completed() {
        return new AiStreamEvent(Type.COMPLETED, null, null);
    }

    public static AiStreamEvent cancelled() {
        return new AiStreamEvent(Type.CANCELLED, null, null);
    }

    public enum Type {
        STARTED,
        TEXT_DELTA,
        USAGE,
        COMPLETED,
        CANCELLED
    }
}
