package com.example.dormitory.dto;

import jakarta.validation.constraints.Size;

public record RemarkRequest(
        @Size(max = 255, message = "备注不能超过 255 个字符")
        String remark) {
}
