package com.example.dormitory.security;

import com.example.dormitory.common.BusinessException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

import java.net.URI;
import java.util.Arrays;
import java.util.Set;

public class CsrfOriginInterceptor implements HandlerInterceptor {

    public static final String CSRF_HEADER = "X-CSRF-Token";
    private static final String LOGIN_PATH = "/api/auth/login";

    private final CsrfTokenService csrfTokenService;
    private final Set<String> allowedOrigins;
    private final String authenticationCookieName;
    private final boolean requireLoginOrigin;

    public CsrfOriginInterceptor(CsrfTokenService csrfTokenService, String[] allowedOrigins) {
        this(csrfTokenService, allowedOrigins, "Authorization", false);
    }

    public CsrfOriginInterceptor(
            CsrfTokenService csrfTokenService,
            String[] allowedOrigins,
            String authenticationCookieName) {
        this(csrfTokenService, allowedOrigins, authenticationCookieName, false);
    }

    public CsrfOriginInterceptor(
            CsrfTokenService csrfTokenService,
            String[] allowedOrigins,
            String authenticationCookieName,
            boolean requireLoginOrigin) {
        this.csrfTokenService = csrfTokenService;
        this.allowedOrigins = Set.copyOf(Arrays.asList(allowedOrigins));
        if (authenticationCookieName == null || authenticationCookieName.isBlank()) {
            throw new IllegalArgumentException("Sa-Token Cookie 名不能为空");
        }
        this.authenticationCookieName = authenticationCookieName;
        this.requireLoginOrigin = requireLoginOrigin;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (isSafe(request.getMethod())) return true;

        if (isLoginRequest(request)) {
            verifyLoginRequest(request);
            return true;
        }

        boolean cookieAuthenticated = hasCookie(request, authenticationCookieName);
        if (!cookieAuthenticated) {
            verifyLoginOriginWhenPresent(request);
            return true;
        }

        if (!originAllowed(request) || !csrfTokenService.verifyForCurrentSession(request.getHeader(CSRF_HEADER))) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "CSRF 或请求来源校验失败");
        }
        return true;
    }

    private void verifyLoginRequest(HttpServletRequest request) {
        if (hasCookie(request, authenticationCookieName)) {
            if (!originAllowed(request)) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "请求来源不受信任");
            }
            return;
        }
        if (requireLoginOrigin && !originAllowed(request)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "请求来源不受信任");
        }
        if (!requireLoginOrigin) verifyLoginOriginWhenPresent(request);
    }

    private boolean isLoginRequest(HttpServletRequest request) {
        return (request.getContextPath() + LOGIN_PATH).equals(request.getRequestURI());
    }

    private void verifyLoginOriginWhenPresent(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && !origin.isBlank() && !allowedOrigins.contains(origin)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "请求来源不受信任");
        }
    }

    private boolean originAllowed(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && !origin.isBlank()) return allowedOrigins.contains(origin);

        String referer = request.getHeader(HttpHeaders.REFERER);
        if (referer == null || referer.isBlank()) return false;
        try {
            URI uri = URI.create(referer);
            String normalized = uri.getScheme() + "://" + uri.getAuthority();
            return allowedOrigins.contains(normalized);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private boolean hasCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return false;
        return Arrays.stream(cookies).anyMatch(cookie -> name.equals(cookie.getName())
                && cookie.getValue() != null && !cookie.getValue().isBlank());
    }

    private boolean isSafe(String method) {
        return HttpMethod.GET.matches(method)
                || HttpMethod.HEAD.matches(method)
                || HttpMethod.OPTIONS.matches(method);
    }
}
