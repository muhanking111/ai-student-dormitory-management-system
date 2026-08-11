package com.example.dormitory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record BedStatusRequest(
        @NotBlank(message = "床位状态不能为空")
        @Pattern(regexp = "空闲|维修中|停用", message = "床位状态不合法")
        String status) {
}
