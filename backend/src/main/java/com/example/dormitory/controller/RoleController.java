package com.example.dormitory.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.example.dormitory.common.ApiResponse;
import com.example.dormitory.common.PageResponse;
import com.example.dormitory.dto.PermissionResponse;
import com.example.dormitory.dto.RoleCreateRequest;
import com.example.dormitory.dto.RoleOptionResponse;
import com.example.dormitory.dto.RoleResponse;
import com.example.dormitory.dto.RoleUpdateRequest;
import com.example.dormitory.service.RbacService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class RoleController {

    private final RbacService rbacService;

    public RoleController(RbacService rbacService) {
        this.rbacService = rbacService;
    }

    @GetMapping("/roles")
    public ApiResponse<PageResponse<RoleResponse>> roles(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean enabled) {
        StpUtil.checkPermission("system:role:read");
        return ApiResponse.ok(rbacService.roles(page, pageSize, keyword, enabled));
    }

    @GetMapping("/roles/options")
    public ApiResponse<List<RoleOptionResponse>> roleOptions() {
        StpUtil.checkPermissionOr("system:user:read", "system:role:read");
        return ApiResponse.ok(rbacService.roleOptions());
    }

    @GetMapping("/permissions")
    public ApiResponse<List<PermissionResponse>> permissions() {
        StpUtil.checkPermission("system:role:read");
        return ApiResponse.ok(rbacService.permissions());
    }

    @PostMapping("/roles")
    public ResponseEntity<ApiResponse<RoleResponse>> create(@Valid @RequestBody RoleCreateRequest request) {
        StpUtil.checkPermission("system:role:write");
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(rbacService.createRole(request)));
    }

    @PatchMapping("/roles/{id}")
    public ApiResponse<RoleResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody RoleUpdateRequest request) {
        StpUtil.checkPermission("system:role:write");
        return ApiResponse.ok(rbacService.updateRole(id, request));
    }

    @DeleteMapping("/roles/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        StpUtil.checkPermission("system:role:write");
        rbacService.deleteRole(id);
        return ResponseEntity.noContent().build();
    }
}
