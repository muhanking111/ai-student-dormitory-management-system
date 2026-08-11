package com.example.dormitory.dto;

public record BedResponse(
        Long id,
        String bedNo,
        String status,
        Long studentId,
        String studentName,
        Long dormitoryId,
        String dormitoryName,
        Long buildingId,
        String buildingName) {
}
