package com.example.dormitory.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PaymentRecordResponse(
        Long id,
        Long paymentId,
        String studentNo,
        String name,
        String type,
        BigDecimal amount,
        String method,
        LocalDateTime paidAt,
        Long operatorUserId,
        String operatorName) {
}
