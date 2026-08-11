package com.example.dormitory.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record PaymentBillRequest(
        @NotBlank(message = "学号不能为空")
        @Size(max = 32, message = "学号不能超过 32 个字符")
        String studentNo,
        @NotBlank(message = "姓名不能为空")
        @Size(max = 32, message = "姓名不能超过 32 个字符")
        String name,
        @NotBlank(message = "费用类型不能为空")
        @Size(max = 32, message = "费用类型不能超过 32 个字符")
        String type,
        @NotNull(message = "应缴金额不能为空")
        @DecimalMin(value = "0.01", message = "应缴金额必须大于 0")
        BigDecimal amountDue,
        @NotBlank(message = "截止日期不能为空")
        @Size(max = 20, message = "截止日期不能超过 20 个字符")
        String deadline) {
}
