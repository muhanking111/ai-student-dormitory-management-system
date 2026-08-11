package com.example.dormitory.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record BuildingRequest(
        @NotBlank(message = "楼栋编码不能为空")
        @Pattern(regexp = "^[A-Za-z0-9-]{2,32}$", message = "楼栋编码需为 2-32 位字母、数字或连字符")
        String code,
        @NotBlank(message = "楼栋名称不能为空")
        @Size(max = 64, message = "楼栋名称不能超过 64 个字符")
        String name,
        @NotBlank(message = "住宿类型不能为空")
        @Pattern(regexp = "男生宿舍|女生宿舍|混合宿舍", message = "住宿类型不合法")
        String genderType,
        @NotNull(message = "楼层数不能为空")
        @Min(value = 1, message = "楼层数不能小于 1")
        @Max(value = 50, message = "楼层数不能超过 50")
        Integer floors,
        @Size(max = 64, message = "管理员名称不能超过 64 个字符")
        String manager,
        @NotBlank(message = "楼栋状态不能为空")
        @Pattern(regexp = "启用|停用", message = "楼栋状态不合法")
        String status) {
}
