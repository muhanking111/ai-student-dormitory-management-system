package com.example.dormitory.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record RepairRecordRequest(
        @Size(max = 32, message = "处理人不能超过 32 个字符")
        String handler,
        @NotBlank(message = "处理内容不能为空")
        @Size(max = 255, message = "处理内容不能超过 255 个字符")
        String content,
        @NotNull(message = "维修费用不能为空")
        @DecimalMin(value = "0.00", message = "维修费用不能小于 0")
        BigDecimal cost,
        @NotBlank(message = "维修状态不能为空")
        @Pattern(regexp = "处理中|已完成", message = "维修状态不合法")
        String status) {
}
