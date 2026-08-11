package com.example.dormitory.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CheckInApprovalRequest(
        @NotNull(message = "床位不能为空")
        @Positive(message = "床位 ID 不合法")
        Long bedId,
        @Size(max = 255, message = "审核备注不能超过 255 个字符")
        String remark) {
}
