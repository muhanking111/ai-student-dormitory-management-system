package com.example.dormitory.security;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class CsrfTokenService {

    private static final String SESSION_KEY = "security.csrf-token";
    private final SecureRandom secureRandom = new SecureRandom();

    public String issueForCurrentSession() {
        SaSession session = StpUtil.getSession();
        String existing = session.getString(SESSION_KEY);
        if (existing != null && !existing.isBlank()) return existing;
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        session.set(SESSION_KEY, token);
        return token;
    }

    public boolean verifyForCurrentSession(String candidate) {
        if (candidate == null || candidate.isBlank() || !StpUtil.isLogin()) return false;
        String expected = StpUtil.getSession().getString(SESSION_KEY);
        if (expected == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }
}
