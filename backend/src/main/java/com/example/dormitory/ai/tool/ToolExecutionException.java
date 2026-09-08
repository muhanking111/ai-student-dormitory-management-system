package com.example.dormitory.ai.tool;

/** 固定工具已经通过授权，但请求、响应或执行资源边界不满足运行合同。 */
public final class ToolExecutionException extends RuntimeException {

    private final String errorCode;

    public ToolExecutionException(String errorCode, String message) {
        super(message);
        if (errorCode == null || !errorCode.matches("AI_TOOL_[A-Z0-9_]{2,48}")) {
            throw new IllegalArgumentException("工具执行错误码不合法");
        }
        this.errorCode = errorCode;
    }

    public ToolExecutionException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        if (errorCode == null || !errorCode.matches("AI_TOOL_[A-Z0-9_]{2,48}")) {
            throw new IllegalArgumentException("工具执行错误码不合法");
        }
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
