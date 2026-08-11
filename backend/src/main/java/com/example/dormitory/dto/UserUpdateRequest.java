package com.example.dormitory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UserUpdateRequest(
        @NotBlank(message = "显示名称不能为空")
        @Size(max = 64, message = "显示名称不能超过 64 个字符")
        String displayName,
        @Size(min = 8, max = 64, message = "密码长度需为 8-64 位")
        String password,
        @NotNull(message = "启用状态不能为空")
        Boolean enabled,
        @NotEmpty(message = "至少选择一个角色")
        List<Long> roleIds) {
}
