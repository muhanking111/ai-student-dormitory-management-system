package com.example.dormitory.dto;

import java.time.LocalDateTime;

public record CheckInApplicationResponse(
        Long id,
        Long studentId,
        String studentNo,
        String studentName,
        Long dormitoryId,
        String dormitoryName,
        String buildingName,
        String date,
        String status,
        Long bedId,
        String bedNo,
        String applyRemark,
        String reviewRemark,
        Long createdByUserId,
        LocalDateTime appliedAt,
        Long reviewerUserId,
        LocalDateTime reviewedAt) {
}
