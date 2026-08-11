package com.example.dormitory.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "dormitory.auth.rate-limit")
public class LoginRateLimitProperties {

    private boolean enabled = true;
    private String keyPrefix = "dormitory:auth:login:v1";
    private int usernameAttempts = 8;
    private int ipAttempts = 40;
    private Duration window = Duration.ofMinutes(1);

    public boolean isEnabled() { return enabled; }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getKeyPrefix() { return keyPrefix; }

    public void setKeyPrefix(String keyPrefix) {
        if (keyPrefix == null || !keyPrefix.matches("[a-zA-Z0-9:_-]{8,160}")) {
            throw new IllegalArgumentException("登录限流 Redis key prefix 不合法");
        }
        this.keyPrefix = keyPrefix;
    }

    public int getUsernameAttempts() { return usernameAttempts; }

    public void setUsernameAttempts(int usernameAttempts) {
        this.usernameAttempts = positive(usernameAttempts, "用户名登录限流阈值");
    }

    public int getIpAttempts() { return ipAttempts; }

    public void setIpAttempts(int ipAttempts) {
        this.ipAttempts = positive(ipAttempts, "IP 登录限流阈值");
    }

    public Duration getWindow() { return window; }

    public void setWindow(Duration window) {
        if (window == null || window.isZero() || window.isNegative()
                || window.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("登录限流窗口必须在 1 秒到 1 小时之间");
        }
        this.window = window;
    }

    private int positive(int value, String label) {
        if (value < 1 || value > 1_000_000) throw new IllegalArgumentException(label + "不合法");
        return value;
    }
}
