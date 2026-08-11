package com.example.dormitory.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CheckInApplicationRequest(
        @NotNull(message = "学生不能为空")
        @Positive(message = "学生 ID 不合法")
        Long studentId,
        @NotNull(message = "宿舍不能为空")
        @Positive(message = "宿舍 ID 不合法")
        Long dormitoryId,
        @Size(max = 255, message = "申请备注不能超过 255 个字符")
        String remark) {
}
