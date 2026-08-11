package com.example.dormitory.dto;

import java.util.List;

public record UserResponse(
        Long id,
        String username,
        String displayName,
        Boolean enabled,
        List<RoleOptionResponse> roles) {
}
