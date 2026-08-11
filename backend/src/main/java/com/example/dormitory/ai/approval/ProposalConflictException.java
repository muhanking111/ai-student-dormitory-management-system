package com.example.dormitory.ai.approval;

public class ProposalConflictException extends RuntimeException {

    private final String errorCode;

    public ProposalConflictException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
