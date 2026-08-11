package com.example.dormitory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record NoticeRequest(
        @NotBlank(message = "公告标题不能为空")
        @Size(max = 128, message = "公告标题不能超过 128 个字符")
        String title,
        @NotBlank(message = "公告类型不能为空")
        @Size(max = 32, message = "公告类型不能超过 32 个字符")
        String type,
        @NotBlank(message = "发布人不能为空")
        @Size(max = 32, message = "发布人不能超过 32 个字符")
        String publisher,
        @NotBlank(message = "公告状态不能为空")
        @Pattern(regexp = "草稿|已发布", message = "公告状态不合法")
        String status,
        @Size(max = 10000, message = "公告正文不能超过 10000 个字符")
        @Pattern(regexp = "(?s)^(?!.*<[^>]+>).*$", message = "公告正文只允许纯文本")
        String content) {
}
