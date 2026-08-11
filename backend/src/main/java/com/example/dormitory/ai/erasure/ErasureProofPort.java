package com.example.dormitory.ai.erasure;

public interface ErasureProofPort {
    ProofResult eraseAndVerify(String targetKind, String targetRefHash, String providerCode);

    record ProofResult(Status status, String proofHash, String errorCode) {
        public enum Status { VERIFIED, RETRYABLE_FAILURE, NEEDS_REVIEW }
    }
}
