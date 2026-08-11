package com.example.dormitory.dto;

import java.util.List;

public record RoleResponse(
        Long id,
        String code,
        String name,
        String description,
        Boolean enabled,
        Boolean builtIn,
        List<Long> permissionIds,
        List<String> permissionCodes) {
}
