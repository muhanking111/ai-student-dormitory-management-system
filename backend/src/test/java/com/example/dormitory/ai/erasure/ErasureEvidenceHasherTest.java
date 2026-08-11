package com.example.dormitory.ai.erasure;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ErasureEvidenceHasherTest {

    private static final ErasureJobRepository.Job JOB = new ErasureJobRepository.Job(
            1L, "job-id", "conversation-id", 9L, false, "PROCESSING", 2, 1);

    @Test
    void evidenceIsOrderStableAndBoundToKindReferenceStateAndProof() {
        ErasureJobRepository.Target first = target(2L, "VECTOR", 'a', "VERIFIED", 'b');
        ErasureJobRepository.Target second = target(1L, "RAW_OBJECT", 'c', "VERIFIED", 'd');
        String baseline = ErasureEvidenceHasher.hash(
                "ERASURE_TOMBSTONE", JOB, List.of(first, second));

        assertEquals(baseline, ErasureEvidenceHasher.hash(
                "ERASURE_TOMBSTONE", JOB, List.of(second, first)));
        assertNotEquals(baseline, ErasureEvidenceHasher.hash("ERASURE_TOMBSTONE", JOB,
                List.of(target(2L, "CACHE", 'a', "VERIFIED", 'b'), second)));
        assertNotEquals(baseline, ErasureEvidenceHasher.hash("ERASURE_TOMBSTONE", JOB,
                List.of(target(2L, "VECTOR", 'e', "VERIFIED", 'b'), second)));
        assertNotEquals(baseline, ErasureEvidenceHasher.hash("ERASURE_TOMBSTONE", JOB,
                List.of(target(2L, "VECTOR", 'a', "NEEDS_REVIEW", 'b'), second)));
        assertNotEquals(baseline, ErasureEvidenceHasher.hash("ERASURE_TOMBSTONE", JOB,
                List.of(target(2L, "VECTOR", 'a', "VERIFIED", 'f'), second)));
    }

    private ErasureJobRepository.Target target(
            long id, String kind, char reference, String state, char proof) {
        return new ErasureJobRepository.Target(id, kind, String.valueOf(reference).repeat(64),
                "fake", state, 1, null, String.valueOf(proof).repeat(64));
    }
}
