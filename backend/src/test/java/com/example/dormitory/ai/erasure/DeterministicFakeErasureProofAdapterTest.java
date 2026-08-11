package com.example.dormitory.ai.erasure;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DeterministicFakeErasureProofAdapterTest {

    private final DeterministicFakeErasureProofAdapter adapter =
            new DeterministicFakeErasureProofAdapter();

    @ParameterizedTest
    @ValueSource(strings = {"RAW_OBJECT", "VECTOR", "CACHE", "PROVIDER"})
    void fakeAdapterNeverClaimsExternalTargetWasVerified(String targetKind) {
        ErasureProofPort.ProofResult result = adapter.eraseAndVerify(
                targetKind, "a".repeat(64), "fake");

        assertEquals(ErasureProofPort.ProofResult.Status.NEEDS_REVIEW, result.status());
        assertNull(result.proofHash());
        assertEquals("ERASURE_TARGET_VERIFICATION_UNAVAILABLE", result.errorCode());
    }
}
