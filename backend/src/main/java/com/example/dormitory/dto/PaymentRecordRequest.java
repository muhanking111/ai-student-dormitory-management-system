package com.example.dormitory.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record PaymentRecordRequest(
        @NotNull(message = "缴费金额不能为空")
        @DecimalMin(value = "0.01", message = "缴费金额必须大于 0")
        BigDecimal amount,
        @NotBlank(message = "缴费方式不能为空")
        @Size(max = 32, message = "缴费方式不能超过 32 个字符")
        String method) {
}
