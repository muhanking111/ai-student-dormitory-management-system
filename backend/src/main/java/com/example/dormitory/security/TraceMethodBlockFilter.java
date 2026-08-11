package com.example.dormitory.security;

import com.example.dormitory.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 禁止 Servlet 默认 TRACE 回显绕过 MVC interceptor 链。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceMethodBlockFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;

    public TraceMethodBlockFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!HttpMethod.TRACE.matches(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        response.setHeader(HttpHeaders.ALLOW, "GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
                new ApiResponse<Void>(HttpServletResponse.SC_METHOD_NOT_ALLOWED, "请求方法不受支持", null));
    }
}
