package com.example.dormitory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UserCreateRequest(
        @NotBlank(message = "用户名不能为空")
        @Pattern(regexp = "^[A-Za-z0-9._-]{3,32}$", message = "用户名需为 3-32 位字母、数字或 ._- 字符")
        String username,
        @NotBlank(message = "显示名称不能为空")
        @Size(max = 64, message = "显示名称不能超过 64 个字符")
        String displayName,
        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 64, message = "密码长度需为 8-64 位")
        String password,
        @NotNull(message = "启用状态不能为空")
        Boolean enabled,
        @NotEmpty(message = "至少选择一个角色")
        List<Long> roleIds) {
}
