package com.example.dormitory.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record HygieneCheckRequest(
        @NotBlank(message = "宿舍名称不能为空")
        @Size(max = 64, message = "宿舍名称不能超过 64 个字符")
        String dormitory,
        @NotBlank(message = "楼栋不能为空")
        @Size(max = 32, message = "楼栋不能超过 32 个字符")
        String building,
        @NotBlank(message = "检查人不能为空")
        @Size(max = 32, message = "检查人不能超过 32 个字符")
        String inspector,
        @NotNull(message = "卫生评分不能为空")
        @Min(value = 0, message = "卫生评分不能小于 0")
        @Max(value = 100, message = "卫生评分不能超过 100")
        Integer score,
        @Size(max = 255, message = "检查备注不能超过 255 个字符")
        String remark) {
}
