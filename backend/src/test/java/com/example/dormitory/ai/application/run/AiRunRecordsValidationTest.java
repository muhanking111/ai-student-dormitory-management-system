package com.example.dormitory.ai.application.run;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiRunRecordsValidationTest {

    @Test
    void messageKeepsLegacyConstructorAndCopiesPersistedEvidenceIds() {
        Instant createdAt = Instant.parse("2026-07-18T00:00:00Z");
        AiRunRecords.Message legacy = new AiRunRecords.Message("message", "USER", "text", "L1", createdAt);
        assertNull(legacy.runId());
        assertNull(legacy.runState());
        assertNull(legacy.asOf());
        assertTrue(legacy.citationIds().isEmpty());

        java.util.ArrayList<String> mutableIds = new java.util.ArrayList<>(List.of("citation-1"));
        AiRunRecords.Message assistant = new AiRunRecords.Message(
                "assistant", "ASSISTANT", "answer", "L2", createdAt,
                "run-1", "SUCCEEDED", null, mutableIds);
        mutableIds.add("citation-2");

        assertEquals(List.of("citation-1"), assistant.citationIds());
        assertThrows(UnsupportedOperationException.class, () -> assistant.citationIds().add("citation-3"));
    }

    @Test
    void retrySourceRequiresCompletePersistedParentConversationAndHashFacts() {
        AiRunRecords.Run run = run();
        AiRunRecords.Conversation conversation = conversation();
        new AiRunRecords.RetrySource(run, conversation, "safe input", "L1", "a".repeat(64));

        assertThrows(IllegalArgumentException.class,
                () -> new AiRunRecords.RetrySource(null, conversation, "input", "L1", "a".repeat(64)));
        assertThrows(IllegalArgumentException.class,
                () -> new AiRunRecords.RetrySource(run, null, "input", "L1", "a".repeat(64)));
        for (String input : Arrays.asList(null, " ")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new AiRunRecords.RetrySource(run, conversation, input, "L1", "a".repeat(64)));
        }
        for (String classification : Arrays.asList(null, " ")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new AiRunRecords.RetrySource(
                            run, conversation, "input", classification, "a".repeat(64)));
        }
        for (String hash : Arrays.asList(null, "bad")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new AiRunRecords.RetrySource(run, conversation, "input", "L1", hash));
        }
    }

    @Test
    void citationCandidateRejectsEveryMissingIdentityContentHashAndRankBound() {
        citation("source", "version", "label", "locator", "quote", "a".repeat(64), 1);
        for (String source : Arrays.asList(null, " ")) {
            assertThrows(IllegalArgumentException.class,
                    () -> citation(source, "version", "label", "locator", "quote", "a".repeat(64), 1));
        }
        for (String version : Arrays.asList(null, " ")) {
            assertThrows(IllegalArgumentException.class,
                    () -> citation("source", version, "label", "locator", "quote", "a".repeat(64), 1));
        }
        for (String label : Arrays.asList(null, " ")) {
            assertThrows(IllegalArgumentException.class,
                    () -> citation("source", "version", label, "locator", "quote", "a".repeat(64), 1));
        }
        for (String locator : Arrays.asList(null, " ")) {
            assertThrows(IllegalArgumentException.class,
                    () -> citation("source", "version", "label", locator, "quote", "a".repeat(64), 1));
        }
        for (String quote : Arrays.asList(null, " ")) {
            assertThrows(IllegalArgumentException.class,
                    () -> citation("source", "version", "label", "locator", quote, "a".repeat(64), 1));
        }
        for (String hash : Arrays.asList(null, "bad")) {
            assertThrows(IllegalArgumentException.class,
                    () -> citation("source", "version", "label", "locator", "quote", hash, 1));
        }
        assertThrows(IllegalArgumentException.class,
                () -> citation("source", "version", "label", "locator", "quote", "a".repeat(64), 0));
        assertThrows(IllegalArgumentException.class,
                () -> citation("source", "version", "label", "locator", "quote", "a".repeat(64), 21));
    }

    @Test
    void retrievalTraceRejectsEveryMissingVersionCountOrderingAndLatencyBound() {
        AiRunRecords.RetrievalTrace valid = trace("a".repeat(64), "policy", "mode", "index",
                "index-v1", "embed-v1", "filter", 5, 3, 2, 1, 0, "SUCCEEDED");
        assertEquals("a".repeat(64), valid.queryHash());
        assertEquals("a".repeat(64), trace("A".repeat(64), "policy", "mode", "index",
                "index-v1", "embed-v1", "filter", 5, 3, 2, 1, 0, "SUCCEEDED").queryHash());

        for (String hash : Arrays.asList(null, "bad")) {
            assertThrows(IllegalArgumentException.class, () -> trace(hash, "policy", "mode", "index",
                    "index-v1", "embed-v1", "filter", 5, 3, 2, 1, 0, "SUCCEEDED"));
        }
        for (int field = 0; field < 6; field++) {
            String[] values = {"policy", "mode", "index", "index-v1", "embed-v1", "filter"};
            values[field] = field % 2 == 0 ? null : " ";
            assertThrows(IllegalArgumentException.class, () -> trace("a".repeat(64),
                    values[0], values[1], values[2], values[3], values[4], values[5],
                    5, 3, 2, 1, 0, "SUCCEEDED"));
        }
        assertThrows(IllegalArgumentException.class, () -> trace("a".repeat(64), "p", "m", "i", "iv", "ev",
                "f", 0, 0, 0, 0, 0, "OK"));
        assertThrows(IllegalArgumentException.class, () -> trace("a".repeat(64), "p", "m", "i", "iv", "ev",
                "f", 21, 0, 0, 0, 0, "OK"));
        assertThrows(IllegalArgumentException.class, () -> trace("a".repeat(64), "p", "m", "i", "iv", "ev",
                "f", 1, -1, 0, 0, 0, "OK"));
        assertThrows(IllegalArgumentException.class, () -> trace("a".repeat(64), "p", "m", "i", "iv", "ev",
                "f", 1, 0, -1, 0, 0, "OK"));
        assertThrows(IllegalArgumentException.class, () -> trace("a".repeat(64), "p", "m", "i", "iv", "ev",
                "f", 1, 0, 0, -1, 0, "OK"));
        assertThrows(IllegalArgumentException.class, () -> trace("a".repeat(64), "p", "m", "i", "iv", "ev",
                "f", 1, 1, 2, 0, 0, "OK"));
        assertThrows(IllegalArgumentException.class, () -> trace("a".repeat(64), "p", "m", "i", "iv", "ev",
                "f", 1, 2, 1, 2, 0, "OK"));
        assertThrows(IllegalArgumentException.class, () -> trace("a".repeat(64), "p", "m", "i", "iv", "ev",
                "f", 1, 0, 0, 0, -1, "OK"));
        assertThrows(IllegalArgumentException.class, () -> trace("a".repeat(64), "p", "m", "i", "iv", "ev",
                "f", 1, 0, 0, 0, 0, null));
        assertThrows(IllegalArgumentException.class, () -> trace("a".repeat(64), "p", "m", "i", "iv", "ev",
                "f", 1, 0, 0, 0, 0, " "));
    }

    @Test
    void tracedResultAuditFiltersAndAuthorizationScopeFailClosedAndNormalize() {
        AiRunRecords.RetrievalTrace trace = trace("a".repeat(64), "p", "m", "i", "iv", "ev",
                "f", 1, 0, 0, 0, 0, "OK");
        assertThrows(IllegalArgumentException.class, () -> new AiRunRecords.TracedCommandResult(null, trace));
        assertThrows(IllegalArgumentException.class, () -> new AiRunRecords.TracedCommandResult("body", null));

        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        assertThrows(IllegalArgumentException.class,
                () -> new AiRunRecords.AuditRunFilter(from.plusSeconds(1), from, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new AiRunRecords.AuditRunFilter(from, from.plusSeconds(367L * 86_400), null, null, null));
        AiRunRecords.AuditRunFilter normalized = new AiRunRecords.AuditRunFilter(
                from, from.plusSeconds(60), " repair ", " succeeded ", " FAKE ");
        assertEquals("REPAIR", normalized.capability());
        assertEquals("SUCCEEDED", normalized.state());
        assertEquals("fake", normalized.providerCode());
        assertThrows(IllegalArgumentException.class,
                () -> new AiRunRecords.AuditRunFilter(null, null, "unknown", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new AiRunRecords.AuditRunFilter(null, null, null, "unknown", null));
        for (String provider : List.of("x", "bad_provider", "x".repeat(33))) {
            assertThrows(IllegalArgumentException.class,
                    () -> new AiRunRecords.AuditRunFilter(null, null, null, null, provider));
        }
        assertEquals(AiRunRecords.AuditRunFilter.none(),
                new AiRunRecords.AuditRunFilter(null, null, null, null, null));

        AiRunRecords.AuditAuthorizationScope scope =
                new AiRunRecords.AuditAuthorizationScope(7L, "surface", "type", 1L, null);
        assertTrue(scope.citationDocumentVersionIds().isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> new AiRunRecords.AuditAuthorizationScope(0L, "surface", "type", 1L, null));
    }

    private AiRunRecords.CitationCandidate citation(
            String source,
            String version,
            String label,
            String locator,
            String quote,
            String hash,
            int rank) {
        return new AiRunRecords.CitationCandidate(
                source, version, null, label, locator, quote, hash, rank, BigDecimal.ONE);
    }

    private AiRunRecords.RetrievalTrace trace(
            String hash,
            String policy,
            String mode,
            String index,
            String indexVersion,
            String embedding,
            String filter,
            int topK,
            int pre,
            int post,
            int returned,
            long latency,
            String state) {
        return new AiRunRecords.RetrievalTrace(hash, policy, mode, index, indexVersion, embedding,
                filter, topK, pre, post, returned, latency, state);
    }

    private AiRunRecords.Run run() {
        return new AiRunRecords.Run("run", "conversation", 1, "REPAIR", "FAILED",
                "permission", "session", "prompt", Instant.now(), Instant.now(), "failure");
    }

    private AiRunRecords.Conversation conversation() {
        return new AiRunRecords.Conversation(
                "conversation", "GLOBAL", "NONE", null, "ACTIVE", "title", Instant.now(), Instant.now());
    }
}
