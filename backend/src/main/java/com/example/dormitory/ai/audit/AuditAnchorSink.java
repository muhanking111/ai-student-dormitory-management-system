package com.example.dormitory.ai.audit;

import java.time.LocalDate;

/**
 * 只追加审计锚介质端口。实现只能接收不可逆 root 和最小元数据，禁止接收审计正文或动态地址。
 */
@FunctionalInterface
public interface AuditAnchorSink {

    Receipt append(AnchorRequest request);

    record AnchorRequest(
            LocalDate anchorDate,
            String chainScope,
            String rootHash,
            long eventCount,
            String integrityAlgorithm,
            int integrityKeyVersion,
            String canonicalizationVersion) {
    }

    record Receipt(String sinkCode, String receiptHash) {
    }
}
