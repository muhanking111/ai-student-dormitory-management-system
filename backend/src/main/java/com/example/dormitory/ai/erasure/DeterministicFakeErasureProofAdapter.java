package com.example.dormitory.ai.erasure;

import org.springframework.stereotype.Component;

/** 仅证明项目自有 Fake adapter；绝不伪装成外部供应商删除证明。 */
@Component
public final class DeterministicFakeErasureProofAdapter implements ErasureProofPort {
    @Override
    public ProofResult eraseAndVerify(String targetKind, String targetRefHash, String providerCode) {
        if (!"fake".equals(providerCode)) {
            return new ProofResult(ProofResult.Status.NEEDS_REVIEW, null,
                    "EXTERNAL_PROOF_ADAPTER_UNAVAILABLE");
        }
        // Fake 只能用于合同/状态机测试。没有真实删除动作及 exists 复验，就不能声称目标已清除。
        return new ProofResult(ProofResult.Status.NEEDS_REVIEW, null,
                "ERASURE_TARGET_VERIFICATION_UNAVAILABLE");
    }
}
