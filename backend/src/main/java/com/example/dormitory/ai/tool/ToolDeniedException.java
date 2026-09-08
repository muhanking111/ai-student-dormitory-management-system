package com.example.dormitory.ai.tool;

public class ToolDeniedException extends RuntimeException {

    private final String errorCode;

    public ToolDeniedException(String message) {
        this("AI_TOOL_DENIED", message);
    }

    public ToolDeniedException(String errorCode, String message) {
        super(message);
        if (errorCode == null || !errorCode.matches("AI_TOOL_[A-Z0-9_]{2,48}")) {
            throw new IllegalArgumentException("工具拒绝错误码不合法");
        }
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
