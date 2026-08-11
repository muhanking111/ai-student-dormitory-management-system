package com.example.dormitory.ai.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 确定性、进程内、只追加 Fake，仅用于本地合同测试，不能作为生产外部锚。 */
public final class DeterministicFakeAuditAnchorSink implements AuditAnchorSink {

    private static final String CODE = "deterministic-fake-append-only";
    private final Map<String, StoredAnchor> anchors = new ConcurrentHashMap<>();

    @Override
    public Receipt append(AnchorRequest request) {
        validate(request);
        String key = request.anchorDate() + "|" + request.chainScope();
        String receiptHash = sha256(canonical(request));
        StoredAnchor candidate = new StoredAnchor(request.rootHash(), new Receipt(CODE, receiptHash));
        StoredAnchor stored = anchors.compute(key, (ignored, existing) -> {
            if (existing != null && !existing.rootHash().equals(request.rootHash())) {
                throw new AuditAnchorIntegrityException("append-only fake sink 已存在不同 root");
            }
            return existing == null ? candidate : existing;
        });
        return stored.receipt();
    }

    private void validate(AnchorRequest request) {
        if (request == null || request.anchorDate() == null || request.chainScope() == null
                || !request.chainScope().matches("[A-Z0-9_]{1,64}")
                || request.rootHash() == null || !request.rootHash().matches("[0-9a-fA-F]{64}")
                || request.eventCount() < 1 || request.integrityAlgorithm() == null
                || request.integrityKeyVersion() < 1 || request.canonicalizationVersion() == null) {
            throw new IllegalArgumentException("fake audit anchor 请求不合法");
        }
    }

    private String canonical(AnchorRequest request) {
        return request.anchorDate() + "|" + request.chainScope() + "|"
                + request.rootHash().toLowerCase() + "|" + request.eventCount() + "|"
                + request.integrityAlgorithm() + "|" + request.integrityKeyVersion() + "|"
                + request.canonicalizationVersion();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private record StoredAnchor(String rootHash, Receipt receipt) {
    }
}
