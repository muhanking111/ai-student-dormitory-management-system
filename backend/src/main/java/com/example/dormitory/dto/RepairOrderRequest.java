package com.example.dormitory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record RepairOrderRequest(
        @NotBlank(message = "报修人不能为空")
        @Size(max = 32, message = "报修人不能超过 32 个字符")
        String reporter,
        @NotBlank(message = "报修位置不能为空")
        @Size(max = 64, message = "报修位置不能超过 64 个字符")
        String location,
        @NotBlank(message = "报修类型不能为空")
        @Size(max = 32, message = "报修类型不能超过 32 个字符")
        String type,
        @Size(max = 255, message = "报修描述不能超过 255 个字符")
        String description,
        @Positive(message = "维修人员 ID 不合法")
        Long assigneeUserId) {
}
