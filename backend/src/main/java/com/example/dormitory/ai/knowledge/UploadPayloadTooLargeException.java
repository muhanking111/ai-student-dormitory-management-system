package com.example.dormitory.ai.knowledge;

/** 上传正文超过声明大小或服务器硬上限。 */
public final class UploadPayloadTooLargeException extends RuntimeException {
    public UploadPayloadTooLargeException(String message) {
        super(message);
    }
}
