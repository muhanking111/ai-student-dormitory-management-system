package com.example.dormitory.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CheckInRecordRequest(
        @NotNull(message = "学生不能为空")
        @Positive(message = "学生 ID 不合法")
        Long studentId,
        @NotNull(message = "床位不能为空")
        @Positive(message = "床位 ID 不合法")
        Long bedId,
        @Size(max = 255, message = "入住备注不能超过 255 个字符")
        String remark) {
}
