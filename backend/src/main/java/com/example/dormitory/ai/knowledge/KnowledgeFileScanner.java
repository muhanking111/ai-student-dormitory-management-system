package com.example.dormitory.ai.knowledge;

import java.io.InputStream;

/** 文件安全扫描端口；状态必须区分真实恶意文件扫描与受控纯文本校验。 */
public interface KnowledgeFileScanner {

    ScanResult scan(InputStream content, long expectedSizeBytes);

    ScannerStatus status();

    record ScanResult(String detectedMimeType, long observedSizeBytes) {
    }

    record ScannerStatus(
            String code,
            String mode,
            boolean malwareScannerAvailable,
            boolean productionApproved) {
        public ScannerStatus {
            if (code == null || !code.matches("[a-z0-9._-]{2,64}") || mode == null || mode.isBlank()) {
                throw new IllegalArgumentException("扫描适配器状态不合法");
            }
        }
    }
}
