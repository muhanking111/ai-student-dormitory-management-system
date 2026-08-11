package com.example.dormitory.ai.tool;

public class ToolDeniedException extends RuntimeException {

    private final String errorCode;

    public ToolDeniedException(String message) {
        super(message);
        this.errorCode = "AI_TOOL_DENIED";
    }

    public String errorCode() {
        return errorCode;
    }
}
