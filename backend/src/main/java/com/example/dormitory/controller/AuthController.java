package com.example.dormitory.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.dormitory.common.ApiResponse;
import com.example.dormitory.common.BusinessException;
import com.example.dormitory.domain.UserAccount;
import com.example.dormitory.mapper.UserAccountMapper;
import com.example.dormitory.service.RbacService;
import com.example.dormitory.ai.security.SessionFingerprintService;
import com.example.dormitory.ai.security.StepUpGrantRevocationPort;
import com.example.dormitory.security.LoginRateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserAccountMapper userAccountMapper;
    private final PasswordEncoder passwordEncoder;
    private final RbacService rbacService;
    private final StepUpGrantRevocationPort stepUpGrantRevocation;
    private final SessionFingerprintService sessionFingerprintService;
    private final LoginRateLimitService loginRateLimitService;

    public AuthController(
            UserAccountMapper userAccountMapper,
            PasswordEncoder passwordEncoder,
            RbacService rbacService,
            StepUpGrantRevocationPort stepUpGrantRevocation,
            SessionFingerprintService sessionFingerprintService,
            LoginRateLimitService loginRateLimitService) {
        this.userAccountMapper = userAccountMapper;
        this.passwordEncoder = passwordEncoder;
        this.rbacService = rbacService;
        this.stepUpGrantRevocation = stepUpGrantRevocation;
        this.sessionFingerprintService = sessionFingerprintService;
        this.loginRateLimitService = loginRateLimitService;
    }

    @PostMapping("/login")
    public ApiResponse<CurrentUserResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest) {
        loginRateLimitService.check(servletRequest, request.username());
        UserAccount user = userAccountMapper.selectOne(
                Wrappers.<UserAccount>lambdaQuery().eq(UserAccount::getUsername, request.username()));
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())
                || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "账号或密码错误");
        }
        StpUtil.login(user.getId());
        return ApiResponse.ok(toCurrentUser(user));
    }

    @GetMapping("/me")
    public ApiResponse<CurrentUserResponse> currentUser() {
        UserAccount user = userAccountMapper.selectById(StpUtil.getLoginIdAsLong());
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())) {
            StpUtil.logout();
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "用户不存在或已停用");
        }
        return ApiResponse.ok(toCurrentUser(user));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        try {
            if (StpUtil.isLogin()) {
                long userId = StpUtil.getLoginIdAsLong();
                String token = StpUtil.getTokenValue();
                sessionFingerprintService.fingerprintIfConfigured(token)
                        .ifPresentOrElse(
                                fingerprint -> stepUpGrantRevocation.revokeSession(
                                        userId, fingerprint.hash(), java.time.Instant.now()),
                                () -> stepUpGrantRevocation.revokeActor(userId, java.time.Instant.now()));
            }
        } finally {
            // 即便 AI grant 存储暂时不可用，也必须先使当前 Sa-Token 会话失效。
            StpUtil.logout();
        }
        return ApiResponse.ok(null);
    }

    public record LoginRequest(@NotBlank(message = "用户名不能为空") @Size(max = 64, message = "用户名不能超过 64 个字符") String username,
                               @NotBlank(message = "密码不能为空") @Size(max = 200, message = "密码不能超过 200 个字符") String password) {
    }

    private CurrentUserResponse toCurrentUser(UserAccount user) {
        List<String> roleCodes = rbacService.roleCodesForUser(user.getId());
        String roleCode = roleCodes.isEmpty() ? user.getRoleCode() : roleCodes.getFirst();
        return new CurrentUserResponse(user.getId(), user.getUsername(), user.getDisplayName(), roleCode,
                roleCodes, rbacService.permissionCodesForUser(user.getId()));
    }

    public record CurrentUserResponse(
            Long id,
            String username,
            String userName,
            String roleCode,
            List<String> roleCodes,
            List<String> permissions) {
    }
}
