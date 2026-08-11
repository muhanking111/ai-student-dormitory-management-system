package com.example.dormitory.ai.erasure;

import com.example.dormitory.ai.approval.CanonicalJsonHasher;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** 为 tombstone/checkpoint 绑定完整目标状态；只输出哈希，不暴露目标引用。 */
final class ErasureEvidenceHasher {

    private ErasureEvidenceHasher() {
    }

    static String hash(
            String evidenceType,
            ErasureJobRepository.Job job,
            List<ErasureJobRepository.Target> targets) {
        Objects.requireNonNull(evidenceType, "evidenceType");
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(targets, "targets");
        StringBuilder canonical = new StringBuilder("erasure-evidence.v1|");
        append(canonical, evidenceType);
        append(canonical, job.publicId());
        append(canonical, job.conversationId());
        canonical.append(targets.size()).append('|');
        targets.stream().sorted(Comparator.comparingLong(ErasureJobRepository.Target::id))
                .forEach(target -> {
                    canonical.append(target.id()).append('|');
                    append(canonical, target.kind());
                    append(canonical, target.targetRefHash());
                    append(canonical, target.providerCode());
                    append(canonical, target.state());
                    append(canonical, target.proofRefHash());
                });
        return CanonicalJsonHasher.sha256(canonical.toString());
    }

    private static void append(StringBuilder target, String value) {
        if (value == null) {
            target.append("-1:|");
            return;
        }
        target.append(value.length()).append(':').append(value).append('|');
    }
}
