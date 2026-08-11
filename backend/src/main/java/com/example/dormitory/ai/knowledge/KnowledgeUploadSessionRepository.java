package com.example.dormitory.ai.knowledge;

import com.example.dormitory.ai.port.ObjectStoragePort;

import java.time.Instant;
import java.util.Optional;

/** MySQL 是上传会话状态、固定隔离对象版本与恢复事实的唯一权威。 */
public interface KnowledgeUploadSessionRepository {

    UploadRecord create(CreateUpload command);

    boolean markUploading(String publicId, long expectedVersion, Instant startedAt, long actorUserId);

    boolean markUploaded(
            String publicId,
            long expectedVersion,
            ObjectStoragePort.StoredObject object,
            Instant writeRevokedAt,
            long actorUserId);

    boolean markScanning(
            String publicId,
            long expectedVersion,
            String observedSha256,
            long observedSizeBytes,
            long actorUserId);

    boolean markScannedClean(
            String publicId,
            long expectedVersion,
            String detectedMimeType,
            long actorUserId);

    boolean markFinalized(
            String publicId,
            long expectedVersion,
            String detectedMimeType,
            long actorUserId);

    boolean markQuarantined(String publicId, String scanState, long actorUserId);

    boolean markExpired(String publicId, long actorUserId);

    Optional<UploadRecord> findByPublicId(String publicId);

    record CreateUpload(
            String publicId,
            long sourceId,
            long ownerUserId,
            String quarantineObjectKey,
            String expectedSha256,
            long expectedSizeBytes,
            String declaredMimeType,
            Instant expiresAt,
            long actorUserId) {
    }

    record UploadRecord(
            long id,
            String publicId,
            long sourceId,
            long ownerUserId,
            String sourcePublicId,
            String quarantineObjectKey,
            String expectedSha256,
            String observedSha256,
            long expectedSizeBytes,
            Long observedSizeBytes,
            String declaredMimeType,
            String detectedMimeType,
            String state,
            String scanState,
            String objectVersionId,
            String objectEtag,
            Instant expiresAt,
            Instant writeRevokedAt,
            Long finalizedDocumentVersionId,
            long version,
            Instant updatedAt) {

        public UploadSessionState stateEnum() {
            return UploadSessionState.valueOf(state);
        }

        public ObjectStoragePort.ObjectReference quarantineReference() {
            if (objectVersionId == null || objectEtag == null) return null;
            return new ObjectStoragePort.ObjectReference(quarantineObjectKey, objectVersionId, objectEtag);
        }
    }
}
