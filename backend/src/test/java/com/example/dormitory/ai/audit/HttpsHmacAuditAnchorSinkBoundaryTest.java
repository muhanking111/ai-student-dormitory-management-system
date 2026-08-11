package com.example.dormitory.ai.audit;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpsHmacAuditAnchorSinkBoundaryTest {

    private static final byte[] KEY = "audit-anchor-boundary-hmac-key-32-bytes".getBytes(StandardCharsets.UTF_8);
    private static final URI ENDPOINT = URI.create("https://anchor.example.edu/v1/roots");
    private static final AuditAnchorHttpTransport UNUSED_TRANSPORT = request -> {
        throw new AssertionError("invalid input must not reach the transport");
    };

    @Test
    void constructorRejectsEveryUnsafeEndpointAndTransportConfiguration() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new HttpsHmacAuditAnchorSink(null, KEY, Duration.ofSeconds(1), UNUSED_TRANSPORT)),
                () -> assertThrows(IllegalArgumentException.class, () -> new HttpsHmacAuditAnchorSink(
                        URI.create("http://anchor.example.edu/v1/roots"), KEY,
                        Duration.ofSeconds(1), UNUSED_TRANSPORT)),
                () -> assertThrows(IllegalArgumentException.class, () -> new HttpsHmacAuditAnchorSink(
                        URI.create("https:///v1/roots"), KEY, Duration.ofSeconds(1), UNUSED_TRANSPORT)),
                () -> assertThrows(IllegalArgumentException.class, () -> new HttpsHmacAuditAnchorSink(
                        URI.create("https://user@anchor.example.edu/v1/roots"), KEY,
                        Duration.ofSeconds(1), UNUSED_TRANSPORT)),
                () -> assertThrows(IllegalArgumentException.class, () -> new HttpsHmacAuditAnchorSink(
                        URI.create("https://anchor.example.edu/v1/roots?target=other"), KEY,
                        Duration.ofSeconds(1), UNUSED_TRANSPORT)),
                () -> assertThrows(IllegalArgumentException.class, () -> new HttpsHmacAuditAnchorSink(
                        URI.create("https://anchor.example.edu/v1/roots#fragment"), KEY,
                        Duration.ofSeconds(1), UNUSED_TRANSPORT)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new HttpsHmacAuditAnchorSink(ENDPOINT, null,
                                Duration.ofSeconds(1), UNUSED_TRANSPORT)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new HttpsHmacAuditAnchorSink(ENDPOINT, "short".getBytes(StandardCharsets.UTF_8),
                                Duration.ofSeconds(1), UNUSED_TRANSPORT)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new HttpsHmacAuditAnchorSink(ENDPOINT, KEY, null, UNUSED_TRANSPORT)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new HttpsHmacAuditAnchorSink(ENDPOINT, KEY, Duration.ZERO, UNUSED_TRANSPORT)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new HttpsHmacAuditAnchorSink(ENDPOINT, KEY,
                                Duration.ofSeconds(-1), UNUSED_TRANSPORT)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new HttpsHmacAuditAnchorSink(ENDPOINT, KEY,
                                Duration.ofSeconds(31), UNUSED_TRANSPORT)),
                () -> assertThrows(NullPointerException.class,
                        () -> new HttpsHmacAuditAnchorSink(ENDPOINT, KEY, Duration.ofSeconds(1), null)));
    }

    @Test
    void appendRejectsEachMalformedReceiptContract() {
        assertAll(
                () -> assertReceiptRejected(new AuditAnchorHttpTransport.Response(
                        202, "c".repeat(64), Map.of("x-audit-receipt-signature", "0".repeat(64)))),
                () -> assertReceiptRejected(new AuditAnchorHttpTransport.Response(
                        200, null, Map.of("x-audit-receipt-signature", "0".repeat(64)))),
                () -> assertReceiptRejected(new AuditAnchorHttpTransport.Response(
                        200, "not-a-hash", Map.of("x-audit-receipt-signature", "0".repeat(64)))),
                () -> assertReceiptRejected(new AuditAnchorHttpTransport.Response(
                        200, "c".repeat(64), Map.of())),
                () -> assertReceiptRejected(new AuditAnchorHttpTransport.Response(
                        200, "c".repeat(64), Map.of("x-audit-receipt-signature", "xyz"))));
    }

    @Test
    void appendAcceptsStatus200AndNormalizesUppercaseReceiptMaterial() {
        HttpsHmacAuditAnchorSink sink = new HttpsHmacAuditAnchorSink(
                ENDPOINT, KEY, Duration.ofSeconds(1), request -> {
                    String receiptHash = "C".repeat(64);
                    String requestId = request.headers().get("Idempotency-Key");
                    String signature = HttpsHmacAuditAnchorSink.hmacHex(
                            KEY, "receipt.v1|" + requestId + "|" + receiptHash.toLowerCase());
                    return new AuditAnchorHttpTransport.Response(200, receiptHash,
                            Map.of("x-audit-receipt-signature", signature.toUpperCase()));
                });

        AuditAnchorSink.Receipt receipt = sink.append(validRequest());

        assertEquals("c".repeat(64), receipt.receiptHash());
    }

    @Test
    void appendRejectsEveryInvalidAnchorRequestFieldBeforeNetworkEgress() {
        HttpsHmacAuditAnchorSink sink = new HttpsHmacAuditAnchorSink(
                ENDPOINT, KEY, Duration.ofSeconds(1), UNUSED_TRANSPORT);
        String root = "a".repeat(64);

        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> sink.append(null)),
                () -> assertThrows(IllegalArgumentException.class, () -> sink.append(new AuditAnchorSink.AnchorRequest(
                        null, "RUN", root, 1, "SHA-256", 1, "v1"))),
                () -> assertThrows(IllegalArgumentException.class, () -> sink.append(new AuditAnchorSink.AnchorRequest(
                        LocalDate.now(), null, root, 1, "SHA-256", 1, "v1"))),
                () -> assertThrows(IllegalArgumentException.class, () -> sink.append(new AuditAnchorSink.AnchorRequest(
                        LocalDate.now(), "run", root, 1, "SHA-256", 1, "v1"))),
                () -> assertThrows(IllegalArgumentException.class, () -> sink.append(new AuditAnchorSink.AnchorRequest(
                        LocalDate.now(), "RUN", null, 1, "SHA-256", 1, "v1"))),
                () -> assertThrows(IllegalArgumentException.class, () -> sink.append(new AuditAnchorSink.AnchorRequest(
                        LocalDate.now(), "RUN", "bad", 1, "SHA-256", 1, "v1"))),
                () -> assertThrows(IllegalArgumentException.class, () -> sink.append(new AuditAnchorSink.AnchorRequest(
                        LocalDate.now(), "RUN", root, 0, "SHA-256", 1, "v1"))),
                () -> assertThrows(IllegalArgumentException.class, () -> sink.append(new AuditAnchorSink.AnchorRequest(
                        LocalDate.now(), "RUN", root, 1, null, 1, "v1"))),
                () -> assertThrows(IllegalArgumentException.class, () -> sink.append(new AuditAnchorSink.AnchorRequest(
                        LocalDate.now(), "RUN", root, 1, "sha-256", 1, "v1"))),
                () -> assertThrows(IllegalArgumentException.class, () -> sink.append(new AuditAnchorSink.AnchorRequest(
                        LocalDate.now(), "RUN", root, 1, "SHA-256", 0, "v1"))),
                () -> assertThrows(IllegalArgumentException.class, () -> sink.append(new AuditAnchorSink.AnchorRequest(
                        LocalDate.now(), "RUN", root, 1, "SHA-256", 1, null))),
                () -> assertThrows(IllegalArgumentException.class, () -> sink.append(new AuditAnchorSink.AnchorRequest(
                        LocalDate.now(), "RUN", root, 1, "SHA-256", 1, "bad value"))));
    }

    private void assertReceiptRejected(AuditAnchorHttpTransport.Response response) {
        HttpsHmacAuditAnchorSink sink = new HttpsHmacAuditAnchorSink(
                ENDPOINT, KEY, Duration.ofSeconds(1), request -> response);
        assertThrows(AuditAnchorIntegrityException.class, () -> sink.append(validRequest()));
    }

    private AuditAnchorSink.AnchorRequest validRequest() {
        return new AuditAnchorSink.AnchorRequest(LocalDate.of(2026, 7, 13), "RUN", "a".repeat(64),
                1, "SHA-256-MERKLE", 1, "v1");
    }
}
