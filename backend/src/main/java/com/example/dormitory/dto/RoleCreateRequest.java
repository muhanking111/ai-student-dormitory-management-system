package com.example.dormitory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record RoleCreateRequest(
        @NotBlank(message = "角色编码不能为空")
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{2,31}$", message = "角色编码需为 3-32 位大写字母、数字或下划线")
        String code,
        @NotBlank(message = "角色名称不能为空")
        @Size(max = 64, message = "角色名称不能超过 64 个字符")
        String name,
        @Size(max = 255, message = "角色说明不能超过 255 个字符")
        String description,
        @NotNull(message = "启用状态不能为空")
        Boolean enabled,
        @NotEmpty(message = "至少选择一个权限")
        List<Long> permissionIds) {
}
