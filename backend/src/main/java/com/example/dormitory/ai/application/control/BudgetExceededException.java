package com.example.dormitory.ai.application.control;

public class BudgetExceededException extends RuntimeException {

    private final String errorCode = "AI_BUDGET_EXCEEDED";

    public BudgetExceededException() {
        super("AI 预算不足，请求未预留且不会调用供应商");
    }

    public String errorCode() {
        return errorCode;
    }
}
