package com.example.dormitory.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.dormitory.domain.UserAccount;
import com.example.dormitory.mapper.UserAccountMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import org.springframework.util.StringUtils;

@Component
@Order(10)
public class BootstrapAdminInitializer implements ApplicationRunner {

    private static final String USERNAME_PATTERN = "[A-Za-z0-9._-]{3,32}";
    private static final int MINIMUM_PASSWORD_LENGTH = 8;
    private static final int MAXIMUM_PASSWORD_LENGTH = 64;

    private final UserAccountMapper userAccountMapper;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;
    private final String displayName;

    public BootstrapAdminInitializer(
            UserAccountMapper userAccountMapper,
            PasswordEncoder passwordEncoder,
            @Value("${dormitory.bootstrap-admin.username:}") String username,
            @Value("${dormitory.bootstrap-admin.password:}") String password,
            @Value("${dormitory.bootstrap-admin.display-name:系统管理员}") String displayName) {
        this.userAccountMapper = userAccountMapper;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.password = password;
        this.displayName = displayName;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            return;
        }
        validateCredentials();
        Long count = userAccountMapper.selectCount(
                Wrappers.<UserAccount>lambdaQuery().eq(UserAccount::getUsername, username));
        if (count > 0) {
            return;
        }
        userAccountMapper.insert(new UserAccount(
                null,
                username,
                passwordEncoder.encode(password),
                displayName,
                "ADMIN",
                true));
    }

    private void validateCredentials() {
        if (!username.matches(USERNAME_PATTERN)) {
            throw new IllegalStateException("Bootstrap 管理员用户名必须符合 3-32 位字母、数字或 ._- 合同");
        }
        if (password.length() < MINIMUM_PASSWORD_LENGTH || password.length() > MAXIMUM_PASSWORD_LENGTH) {
            throw new IllegalStateException("Bootstrap 管理员密码长度必须为 8-64 位");
        }
    }
}
