package com.example.dormitory.ai.audit;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeterministicFakeAuditAnchorSinkTest {

    @Test
    void isAppendOnlyAndReturnsTheSameReceiptForAnIdempotentRetry() {
        var sink = new DeterministicFakeAuditAnchorSink();
        var request = request("a".repeat(64));

        var first = sink.append(request);
        var retried = sink.append(request);

        assertEquals("deterministic-fake-append-only", first.sinkCode());
        assertEquals(first, retried);
    }

    @Test
    void rejectsASecondRootForTheSameDateAndScope() {
        var sink = new DeterministicFakeAuditAnchorSink();
        sink.append(request("a".repeat(64)));

        assertThrows(AuditAnchorIntegrityException.class,
                () -> sink.append(request("b".repeat(64))));
    }

    private AuditAnchorSink.AnchorRequest request(String root) {
        return new AuditAnchorSink.AnchorRequest(
                LocalDate.of(2026, 7, 10), "RUN", root, 3,
                "SHA-256-MERKLE", 1, "v1");
    }
}
