package com.example.dormitory.ai.api;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import com.example.dormitory.ai.application.control.BudgetExceededException;
import com.example.dormitory.ai.application.control.IdempotencyPayloadMismatchException;
import com.example.dormitory.ai.config.RuntimeSwitchConflictException;
import com.example.dormitory.ai.security.SensitiveDataBlockedException;
import com.example.dormitory.ai.approval.IdempotencyConflictException;
import com.example.dormitory.ai.approval.ProposalConflictException;
import com.example.dormitory.ai.dashboard.UnsupportedMetricException;
import com.example.dormitory.ai.risk.RiskCaseConflictException;
import com.example.dormitory.ai.knowledge.KnowledgeQuarantinedException;
import com.example.dormitory.ai.knowledge.KnowledgeSourceConflictException;
import com.example.dormitory.ai.knowledge.UploadPayloadTooLargeException;
import com.example.dormitory.common.ApiResponse;
import com.example.dormitory.common.BusinessException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.example.dormitory.ai")
public class AiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiExceptionHandler.class);

    @ExceptionHandler(AiApiException.class)
    public ResponseEntity<ApiResponse<AiErrorDetail>> handleAi(AiApiException exception) {
        AiErrorDetail detail = new AiErrorDetail(
                exception.errorCode(), exception.retryable(), exception.fieldErrors(),
                exception.runId(), exception.metadata());
        var builder = ResponseEntity.status(exception.status()).headers(AiApiHeaders.privateNoStore());
        if (exception.retryAfterSeconds() != null) {
            builder.header("Retry-After", Integer.toString(exception.retryAfterSeconds()));
        }
        return builder.body(new ApiResponse<>(exception.status().value(), exception.getMessage(), detail));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<AiErrorDetail>> handleValidation(MethodArgumentNotValidException exception) {
        List<AiErrorDetail.FieldError> fields = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new AiErrorDetail.FieldError(
                        error.getField(), "INVALID_VALUE", safeValidationMessage(error.getDefaultMessage())))
                .toList();
        return error(HttpStatus.BAD_REQUEST, "AI_VALIDATION_FAILED", "请求校验失败", false, fields);
    }

    @ExceptionHandler({ConstraintViolationException.class, MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class, ServletRequestBindingException.class,
            IllegalArgumentException.class})
    public ResponseEntity<ApiResponse<AiErrorDetail>> handleMalformed(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "AI_INVALID_REQUEST", "请求参数格式错误", false, List.of());
    }

    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<ApiResponse<AiErrorDetail>> handleNotLogin(NotLoginException exception) {
        return error(HttpStatus.UNAUTHORIZED, "AI_AUTHENTICATION_REQUIRED", "未登录或登录已过期", false, List.of());
    }

    @ExceptionHandler(NotPermissionException.class)
    public ResponseEntity<ApiResponse<AiErrorDetail>> handleNotPermission(NotPermissionException exception) {
        return error(HttpStatus.FORBIDDEN, "AI_PERMISSION_DENIED", "无权执行该操作", false, List.of());
    }

    @ExceptionHandler(IdempotencyPayloadMismatchException.class)
    public ResponseEntity<ApiResponse<AiErrorDetail>> handleIdempotency(IdempotencyPayloadMismatchException exception) {
        return error(HttpStatus.CONFLICT, "AI_IDEMPOTENCY_PAYLOAD_MISMATCH",
                "幂等键已绑定不同请求", false, List.of());
    }

    @ExceptionHandler(BudgetExceededException.class)
    public ResponseEntity<ApiResponse<AiErrorDetail>> handleBudget(BudgetExceededException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .headers(AiApiHeaders.privateNoStore())
                .header("Retry-After", "60")
                .body(new ApiResponse<>(429, "AI 配额不足",
                        new AiErrorDetail("AI_QUOTA_EXCEEDED", true, List.of(), null, java.util.Map.of())));
    }

    @ExceptionHandler(SensitiveDataBlockedException.class)
    public ResponseEntity<ApiResponse<AiErrorDetail>> handleSensitive(SensitiveDataBlockedException exception) {
        return error(HttpStatus.BAD_REQUEST, "AI_SENSITIVE_DATA_BLOCKED",
                "请求包含禁止进入 AI 的敏感数据", false, List.of());
    }

    @ExceptionHandler(UploadPayloadTooLargeException.class)
    public ResponseEntity<ApiResponse<AiErrorDetail>> handleUploadTooLarge(UploadPayloadTooLargeException exception) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "AI_UPLOAD_TOO_LARGE",
                "上传正文超过声明大小或服务器硬上限", false, List.of());
    }

    @ExceptionHandler(KnowledgeQuarantinedException.class)
    public ResponseEntity<ApiResponse<AiErrorDetail>> handleKnowledgeQuarantined(
            KnowledgeQuarantinedException exception) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, exception.errorCode(),
                "知识文件未通过安全检查并已隔离", false, List.of());
    }

    @ExceptionHandler(KnowledgeSourceConflictException.class)
    public ResponseEntity<ApiResponse<AiErrorDetail>> handleKnowledgeSourceConflict(
            KnowledgeSourceConflictException exception) {
        return error(HttpStatus.CONFLICT, "AI_KNOWLEDGE_ACL_VERSION_CONFLICT",
                "知识来源 ACL 版本或状态已变化", false, List.of());
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ApiResponse<AiErrorDetail>> handleSecurity(SecurityException exception) {
        return error(HttpStatus.FORBIDDEN, "AI_PERMISSION_DENIED", "无权执行该操作", false, List.of());
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<AiErrorDetail>> handleBusiness(BusinessException exception) {
        String code = exception.getStatus() == HttpStatus.FORBIDDEN
                ? "AI_CSRF_ORIGIN_REJECTED" : "AI_BUSINESS_REQUEST_REJECTED";
        return error(exception.getStatus(), code, exception.getMessage(), false, List.of());
    }

    @ExceptionHandler({ProposalConflictException.class, IdempotencyConflictException.class})
    public ResponseEntity<ApiResponse<AiErrorDetail>> handleProposalConflict(RuntimeException exception) {
        String code = exception instanceof ProposalConflictException conflict
                ? conflict.errorCode() : "AI_IDEMPOTENCY_PAYLOAD_MISMATCH";
        return error(HttpStatus.CONFLICT, code, "提案状态或幂等请求冲突", false, List.of());
    }

    @ExceptionHandler(RiskCaseConflictException.class)
    public ResponseEntity<ApiResponse<AiErrorDetail>> handleRiskConflict(RiskCaseConflictException exception) {
        return error(HttpStatus.CONFLICT, exception.errorCode(), "风险案例状态或版本已变化", false, List.of());
    }

    @ExceptionHandler(RuntimeSwitchConflictException.class)
    public ResponseEntity<ApiResponse<AiErrorDetail>> handleRuntimeSwitchConflict(
            RuntimeSwitchConflictException exception) {
        return error(HttpStatus.CONFLICT, "AI_KILL_SWITCH_VERSION_CONFLICT",
                "Kill Switch 状态或版本已变化", false, List.of());
    }

    @ExceptionHandler(UnsupportedMetricException.class)
    public ResponseEntity<ApiResponse<AiErrorDetail>> handleUnsupportedMetric(UnsupportedMetricException exception) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "AI_UNSUPPORTED_METRIC",
                "问题无法映射到已批准指标", false, List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<AiErrorDetail>> handleUnexpected(Exception exception) {
        LOGGER.error("AI request failed with an unexpected server error", exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "AI_INTERNAL_ERROR", "AI 服务内部错误", false, List.of());
    }

    private ResponseEntity<ApiResponse<AiErrorDetail>> error(
            HttpStatus status,
            String code,
            String message,
            boolean retryable,
            List<AiErrorDetail.FieldError> fields) {
        return ResponseEntity.status(status)
                .headers(AiApiHeaders.privateNoStore())
                .body(new ApiResponse<>(status.value(), message,
                        new AiErrorDetail(code, retryable, fields, null, java.util.Map.of())));
    }

    private String safeValidationMessage(String message) {
        return message == null || message.isBlank() ? "字段值不合法" : message;
    }
}
