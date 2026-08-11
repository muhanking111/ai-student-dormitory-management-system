package com.example.dormitory.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record RepairRecordResponse(
        Long id,
        Long repairOrderId,
        String location,
        String handler,
        String content,
        BigDecimal cost,
        String status,
        LocalDateTime handledAt,
        Long operatorUserId) {
}
