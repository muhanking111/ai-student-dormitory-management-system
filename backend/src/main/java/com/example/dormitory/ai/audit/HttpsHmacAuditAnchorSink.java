package com.example.dormitory.ai.audit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/** 将最小 Merkle root 写入固定 HTTPS 只追加服务，并验证共享密钥签名的 receipt。 */
public final class HttpsHmacAuditAnchorSink implements AuditAnchorSink {

    private static final int MAXIMUM_RESPONSE_BYTES = 4_096;
    private static final String SINK_CODE = "https-hmac-append-only";

    private final URI endpoint;
    private final byte[] hmacKey;
    private final Duration timeout;
    private final AuditAnchorHttpTransport transport;

    public HttpsHmacAuditAnchorSink(
            URI endpoint,
            byte[] hmacKey,
            Duration timeout,
            AuditAnchorHttpTransport transport) {
        validateEndpoint(endpoint);
        if (hmacKey == null || hmacKey.length < 32) throw new IllegalArgumentException("外锚 HMAC key 长度不足");
        if (timeout == null || timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("外锚超时必须在 0 到 30 秒内");
        }
        this.endpoint = endpoint.normalize();
        this.hmacKey = hmacKey.clone();
        this.timeout = timeout;
        this.transport = java.util.Objects.requireNonNull(transport);
    }

    @Override
    public Receipt append(AnchorRequest request) {
        String canonical = canonical(request);
        String body = json(request);
        String requestId = sha256(canonical);
        AuditAnchorHttpTransport.Response response = transport.send(new AuditAnchorHttpTransport.Request(
                endpoint, body, Map.of(
                        "Content-Type", "application/json",
                        "Accept", "text/plain",
                        "Idempotency-Key", requestId,
                        "X-Audit-Anchor-Signature", hmacHex(hmacKey, "request.v1|" + canonical)),
                timeout, MAXIMUM_RESPONSE_BYTES));
        String receiptHash = response.body() == null ? "" : response.body().trim().toLowerCase(Locale.ROOT);
        String signature = response.headers().getOrDefault("x-audit-receipt-signature", "");
        if (!SetOfStatus.success(response.statusCode()) || !receiptHash.matches("[0-9a-f]{64}")
                || !signature.matches("[0-9a-fA-F]{64}")) {
            throw new AuditAnchorIntegrityException("外锚 receipt 合同不合法");
        }
        String expected = hmacHex(hmacKey, "receipt.v1|" + requestId + "|" + receiptHash);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                signature.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII))) {
            throw new AuditAnchorIntegrityException("外锚 receipt 签名不匹配");
        }
        return new Receipt(SINK_CODE, receiptHash);
    }

    static String hmacHex(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("HMAC-SHA256 不可用", failure);
        }
    }

    private String canonical(AnchorRequest request) {
        validateRequest(request);
        return request.anchorDate() + "|" + request.chainScope() + "|"
                + request.rootHash().toLowerCase(Locale.ROOT) + "|" + request.eventCount() + "|"
                + request.integrityAlgorithm() + "|" + request.integrityKeyVersion() + "|"
                + request.canonicalizationVersion();
    }

    private String json(AnchorRequest request) {
        return "{\"schemaVersion\":\"audit-anchor.v1\",\"anchorDate\":\"" + request.anchorDate()
                + "\",\"chainScope\":\"" + request.chainScope() + "\",\"rootHash\":\""
                + request.rootHash().toLowerCase(Locale.ROOT) + "\",\"eventCount\":" + request.eventCount()
                + ",\"integrityAlgorithm\":\"" + request.integrityAlgorithm()
                + "\",\"integrityKeyVersion\":" + request.integrityKeyVersion()
                + ",\"canonicalizationVersion\":\"" + request.canonicalizationVersion() + "\"}";
    }

    private void validateRequest(AnchorRequest request) {
        if (request == null || request.anchorDate() == null || request.chainScope() == null
                || !request.chainScope().matches("[A-Z0-9_]{1,64}")
                || request.rootHash() == null || !request.rootHash().matches("[0-9a-fA-F]{64}")
                || request.eventCount() < 1 || request.integrityAlgorithm() == null
                || !request.integrityAlgorithm().matches("[A-Z0-9-]{1,32}")
                || request.integrityKeyVersion() < 1 || request.canonicalizationVersion() == null
                || !request.canonicalizationVersion().matches("[a-zA-Z0-9._-]{1,32}")) {
            throw new IllegalArgumentException("审计外锚请求不合法");
        }
    }

    private void validateEndpoint(URI value) {
        if (value == null || !"https".equalsIgnoreCase(value.getScheme()) || value.getHost() == null
                || value.getUserInfo() != null || value.getQuery() != null || value.getFragment() != null) {
            throw new IllegalArgumentException("审计外锚只能使用固定 HTTPS endpoint");
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 不可用", failure);
        }
    }

    private static final class SetOfStatus {
        private SetOfStatus() { }
        private static boolean success(int status) { return status == 200 || status == 201; }
    }
}
