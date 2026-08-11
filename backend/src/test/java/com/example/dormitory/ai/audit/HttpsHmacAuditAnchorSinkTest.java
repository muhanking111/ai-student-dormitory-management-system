package com.example.dormitory.ai.audit;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpsHmacAuditAnchorSinkTest {

    private static final byte[] KEY = "audit-anchor-contract-hmac-key-32-bytes".getBytes(StandardCharsets.UTF_8);

    @Test
    void fixedHttpsEndpointUsesSignedIdempotentRequestAndVerifiesSignedReceipt() {
        AtomicReference<AuditAnchorHttpTransport.Request> observed = new AtomicReference<>();
        AuditAnchorHttpTransport transport = request -> {
            observed.set(request);
            String receiptHash = "c".repeat(64);
            String requestId = request.headers().get("Idempotency-Key");
            String signature = HttpsHmacAuditAnchorSink.hmacHex(
                    KEY, "receipt.v1|" + requestId + "|" + receiptHash);
            return new AuditAnchorHttpTransport.Response(201, receiptHash,
                    Map.of("x-audit-receipt-signature", signature));
        };
        HttpsHmacAuditAnchorSink sink = new HttpsHmacAuditAnchorSink(
                URI.create("https://anchor.example.edu/v1/roots"), KEY, Duration.ofSeconds(3), transport);

        AuditAnchorSink.Receipt receipt = sink.append(request());

        assertEquals("https-hmac-append-only", receipt.sinkCode());
        assertEquals("c".repeat(64), receipt.receiptHash());
        assertEquals("https://anchor.example.edu/v1/roots", observed.get().endpoint().toString());
        assertTrue(observed.get().headers().get("X-Audit-Anchor-Signature").matches("[0-9a-f]{64}"));
        assertTrue(observed.get().headers().get("Idempotency-Key").matches("[0-9a-f]{64}"));
        assertTrue(observed.get().body().contains("\"rootHash\":\"" + "a".repeat(64) + "\""));
    }

    @Test
    void rejectsDynamicOrUnsignedDestinationsAndTamperedReceipts() {
        assertThrows(IllegalArgumentException.class, () -> new HttpsHmacAuditAnchorSink(
                URI.create("http://127.0.0.1/anchor"), KEY, Duration.ofSeconds(3),
                request -> new AuditAnchorHttpTransport.Response(201, "c".repeat(64), Map.of())));

        HttpsHmacAuditAnchorSink sink = new HttpsHmacAuditAnchorSink(
                URI.create("https://anchor.example.edu/v1/roots"), KEY, Duration.ofSeconds(3),
                request -> new AuditAnchorHttpTransport.Response(201, "c".repeat(64),
                        Map.of("x-audit-receipt-signature", "0".repeat(64))));
        assertThrows(AuditAnchorIntegrityException.class, () -> sink.append(request()));
    }

    private AuditAnchorSink.AnchorRequest request() {
        return new AuditAnchorSink.AnchorRequest(LocalDate.of(2026, 7, 11), "RUN", "a".repeat(64),
                7, "SHA-256-MERKLE", 2, "v1");
    }
}
