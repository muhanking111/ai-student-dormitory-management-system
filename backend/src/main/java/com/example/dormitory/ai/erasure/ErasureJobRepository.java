package com.example.dormitory.ai.erasure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ErasureJobRepository {
    Optional<Job> findActive(long ownerUserId, String conversationId);
    Job create(long ownerUserId, String conversationId, boolean retentionHold, Instant now);
    Optional<Job> findOwned(long ownerUserId, String jobId);
    Optional<Job> findByPublicId(String jobId);
    Optional<Job> claimNext(Instant now);
    List<Target> targets(long databaseJobId);
    void markTargetVerified(long targetId, String proofHash, Instant now);
    void markTargetRetained(long targetId, Instant now);
    void markTargetFailure(long targetId, String state, String errorCode, Instant now);
    void finish(long jobId, int expectedVersion, String state, String errorCode, Instant nextAt, Instant now);
    String redactConversationContent(long ownerUserId, String conversationId);

    record Job(long databaseId, String publicId, String conversationId, long ownerUserId,
               boolean retentionHold, String state, int version, int attempts) { }
    record Target(long id, String kind, String targetRefHash, String providerCode, String state,
                  int attempts, String lastErrorCode, String proofRefHash) { }
}
