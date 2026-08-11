package com.example.dormitory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record StudentRequest(
        @NotBlank(message = "学号不能为空")
        @Pattern(regexp = "^[A-Za-z0-9-]{2,32}$", message = "学号需为 2-32 位字母、数字或连字符")
        String studentNo,
        @NotBlank(message = "学生姓名不能为空")
        @Size(max = 32, message = "学生姓名不能超过 32 个字符")
        String name,
        @NotBlank(message = "性别不能为空")
        @Pattern(regexp = "男|女", message = "性别不合法")
        String gender,
        @NotBlank(message = "学院不能为空")
        @Size(max = 64, message = "学院不能超过 64 个字符")
        String college,
        @NotBlank(message = "年级不能为空")
        @Size(max = 16, message = "年级不能超过 16 个字符")
        String grade,
        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "^[0-9+ -]{6,32}$", message = "手机号格式不合法")
        String phone) {
}
