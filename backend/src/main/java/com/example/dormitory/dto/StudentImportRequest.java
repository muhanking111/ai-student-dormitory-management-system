package com.example.dormitory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record StudentImportRequest(
        @NotNull(message = "学生列表不能为空")
        @Size(min = 1, max = 100, message = "单次导入学生数量须为 1-100")
        @Valid
        List<@NotNull(message = "学生信息不能为空") StudentRequest> students) {
}
