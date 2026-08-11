package com.example.dormitory.dto;

import java.time.LocalDateTime;

public record CheckInRecordResponse(
        Long id,
        Long studentId,
        String studentNo,
        String studentName,
        Long bedId,
        String bedNo,
        Long dormitoryId,
        String dormitoryName,
        String buildingName,
        Long applicationId,
        LocalDateTime checkInDate,
        LocalDateTime checkOutDate,
        String status,
        String remark,
        Long checkInOperatorUserId,
        Long checkOutOperatorUserId) {
}
