package com.example.dormitory.ai.knowledge;

import java.time.Instant;
import java.util.Optional;

public interface KnowledgeIngestionJobRepository {

    IngestionJob enqueue(long documentVersionId, long initiatedByUserId, Instant availableAt);

    Optional<IngestionJob> claimNext(String workerId, Instant now);

    Optional<IngestionJob> claim(String publicId, String workerId, Instant now);

    void succeed(String publicId, String workerId, Instant finishedAt);

    void fail(String publicId, String workerId, String errorCode, String safeSummary, Instant finishedAt);
    void requeue(String publicId, String workerId, Instant availableAt);
    void dead(String publicId, String errorCode, Instant finishedAt);

    Optional<IngestionJob> findByPublicId(String publicId);
    Optional<IngestionJob> findByDocumentVersionId(long documentVersionId);

    record IngestionJob(
            long id,
            String publicId,
            long documentVersionId,
            String state,
            int attempt,
            String workerId,
            long initiatedByUserId,
            long version,
            Instant availableAt,
            Instant startedAt,
            Instant finishedAt,
            String errorCode) {
    }
}
