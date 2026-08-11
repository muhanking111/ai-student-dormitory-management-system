package com.example.dormitory.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record RepairAssignmentRequest(
        @NotNull(message = "维修人员不能为空")
        @Positive(message = "维修人员 ID 不合法")
        Long assigneeUserId) {
}
