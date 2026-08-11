package com.example.dormitory.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.example.dormitory.common.ApiResponse;
import com.example.dormitory.common.PageResponse;
import com.example.dormitory.dto.UserCreateRequest;
import com.example.dormitory.dto.UserResponse;
import com.example.dormitory.dto.UserUpdateRequest;
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

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final RbacService rbacService;

    public UserController(RbacService rbacService) {
        this.rbacService = rbacService;
    }

    @GetMapping
    public ApiResponse<PageResponse<UserResponse>> users(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean enabled) {
        StpUtil.checkPermission("system:user:read");
        return ApiResponse.ok(rbacService.users(page, pageSize, keyword, enabled));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<UserResponse>> create(@Valid @RequestBody UserCreateRequest request) {
        StpUtil.checkPermission("system:user:write");
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(rbacService.createUser(request)));
    }

    @PatchMapping("/{id}")
    public ApiResponse<UserResponse> update(@PathVariable Long id, @Valid @RequestBody UserUpdateRequest request) {
        StpUtil.checkPermission("system:user:write");
        return ApiResponse.ok(rbacService.updateUser(id, request, StpUtil.getLoginIdAsLong()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        StpUtil.checkPermission("system:user:write");
        rbacService.deleteUser(id, StpUtil.getLoginIdAsLong());
        return ResponseEntity.noContent().build();
    }
}
