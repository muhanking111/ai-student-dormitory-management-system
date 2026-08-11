package com.example.dormitory.ai.knowledge;

import com.example.dormitory.ai.port.ObjectStoragePort;

import java.util.List;
import java.util.Optional;

public interface KnowledgeVersionRepository {

    KnowledgeVersion createPending(CreateVersion command);

    Optional<KnowledgeVersion> findByPublicId(String publicId);

    Optional<KnowledgeVersion> findById(long id);

    Optional<KnowledgeVersion> findCurrentForDocument(String versionPublicId);

    List<KnowledgeChunk> findChunks(String versionPublicId);

    /**
     * 返回当前可参与检索的版本。调用方仍必须在检索前、命中后分别执行 ACL 校验。
     */
    List<KnowledgeVersion> findActiveVersions();

    void replaceChunksAndMarkReady(String versionPublicId, List<NewChunk> chunks, long actorUserId);

    void markQuarantined(String versionPublicId, long actorUserId);

    PublicApproval approvePublic(
            String versionPublicId,
            long reviewerUserId,
            long idempotencyRecordId,
            String approvalPolicyVersion);

    PublicApprovalPreview publicApprovalPreview(String versionPublicId);

    PublicApproval approvePublic(
            String versionPublicId, String expectedContentHash, String expectedSnapshotHash,
            long reviewerUserId, long idempotencyRecordId, String approvalPolicyVersion);

    KnowledgeVersion activate(String versionPublicId, long actorUserId);

    KnowledgeVersion retire(String versionPublicId, long actorUserId);

    boolean canRead(String versionPublicId, java.util.Set<String> actorPermissions);

    record CreateVersion(
            String sourcePublicId,
            String uploadSessionPublicId,
            java.time.Instant uploadFinalizedAt,
            String externalKeyHmac,
            int externalKeyKeyVersion,
            String title,
            String version,
            String contentHash,
            KnowledgeVisibility visibility,
            ObjectStoragePort.ObjectReference objectReference,
            String mimeType,
            long sizeBytes,
            String parserVersion,
            String chunkPolicyVersion,
            long actorUserId) {
    }

    record KnowledgeVersion(
            long id,
            String publicId,
            long documentId,
            String documentPublicId,
            long sourceId,
            String sourcePublicId,
            String version,
            String contentHash,
            KnowledgeVisibility visibility,
            ObjectStoragePort.ObjectReference objectReference,
            String mimeType,
            long sizeBytes,
            String parserVersion,
            String chunkPolicyVersion,
            String status,
            Long activePublicApprovalId) {
    }

    record NewChunk(int chunkNo, String contentRedacted, String contentHash, String locator, String metadata) {
    }

    record KnowledgeChunk(
            String publicId,
            int chunkNo,
            String contentRedacted,
            String contentHash,
            String locator,
            String metadata,
            String status) {
    }

    record PublicApproval(
            long id,
            String versionPublicId,
            String contentHash,
            long aclVersion,
            String approvalSnapshotHash,
            long reviewerUserId,
            String approvalPolicyVersion) {
    }

    record PublicApprovalPreview(String versionPublicId, String contentHash, long aclVersion,
                                 String approvalSnapshotHash) { }
}
