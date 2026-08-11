package com.example.dormitory.ai.risk;

/** 数据库活动 dedup key 唯一约束竞态；调用方应回读已存在的活动案例。 */
public class ActiveRiskCaseConflictException extends IllegalStateException {
    public ActiveRiskCaseConflictException(String message) {
        super(message);
    }

    public ActiveRiskCaseConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
