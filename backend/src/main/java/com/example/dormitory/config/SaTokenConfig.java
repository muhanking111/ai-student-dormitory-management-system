package com.example.dormitory.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import com.example.dormitory.ai.security.AiRateLimitInterceptor;
import com.example.dormitory.security.CsrfOriginInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;
    private final CsrfOriginInterceptor csrfOriginInterceptor;
    private final AiRateLimitInterceptor aiRateLimitInterceptor;

    public SaTokenConfig(
            @Value("${dormitory.cors.allowed-origins:http://localhost:5173}") String allowedOrigins,
            @Value("${sa-token.token-name:Authorization}") String tokenName,
            @Value("${dormitory.security.require-login-origin:true}") boolean requireLoginOrigin,
            com.example.dormitory.security.CsrfTokenService csrfTokenService,
            AiRateLimitInterceptor aiRateLimitInterceptor) {
        this.allowedOrigins = java.util.Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toArray(String[]::new);
        this.csrfOriginInterceptor = new CsrfOriginInterceptor(
                csrfTokenService, this.allowedOrigins, tokenName, requireLoginOrigin);
        this.aiRateLimitInterceptor = aiRateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin()))
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/login", "/api/health");
        registry.addInterceptor(csrfOriginInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/health");
        registry.addInterceptor(aiRateLimitInterceptor)
                .addPathPatterns("/api/ai/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
