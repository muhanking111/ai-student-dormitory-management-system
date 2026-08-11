package com.example.dormitory.ai.infrastructure.persistence.knowledge;

import com.example.dormitory.ai.knowledge.KnowledgeUploadSessionRepository;
import com.example.dormitory.ai.port.ObjectStoragePort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcKnowledgeUploadSessionRepository implements KnowledgeUploadSessionRepository {
    private final JdbcTemplate jdbc;

    public JdbcKnowledgeUploadSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UploadRecord create(CreateUpload command) {
        validate(command);
        jdbc.update("INSERT INTO ai_upload_session (public_id,source_id,owner_user_id,quarantine_object_key,"
                        + "expected_sha256,expected_size_bytes,declared_mime_type,scan_state,state,expires_at,version,"
                        + "created_operator_user_id,updated_operator_user_id,created_at,updated_at) "
                        + "VALUES(?,?,?,?,?,?,?,'PENDING','CREATED',?,0,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                command.publicId(), command.sourceId(), command.ownerUserId(), command.quarantineObjectKey(),
                command.expectedSha256(), command.expectedSizeBytes(), command.declaredMimeType(),
                Timestamp.from(command.expiresAt()), command.actorUserId(), command.actorUserId());
        return findByPublicId(command.publicId()).orElseThrow();
    }

    @Override
    public boolean markUploading(String publicId, long expectedVersion, Instant startedAt, long actorUserId) {
        return jdbc.update("UPDATE ai_upload_session SET state='UPLOADING',scan_state='PENDING',"
                        + "updated_operator_user_id=?,version=version+1,updated_at=CURRENT_TIMESTAMP "
                        + "WHERE public_id=? AND state='CREATED' AND version=? AND expires_at>?",
                actorUserId, publicId, expectedVersion, Timestamp.from(startedAt)) == 1;
    }

    @Override
    public boolean markUploaded(
            String publicId,
            long expectedVersion,
            ObjectStoragePort.StoredObject object,
            Instant writeRevokedAt,
            long actorUserId) {
        return jdbc.update("UPDATE ai_upload_session SET object_version_id=?,object_etag=?,"
                        + "state='UPLOADED',write_revoked_at=?,updated_operator_user_id=?,"
                        + "version=version+1,updated_at=CURRENT_TIMESTAMP WHERE public_id=? AND state='UPLOADING' "
                        + "AND version=? AND expires_at>?",
                object.reference().versionId(), object.reference().etag(), Timestamp.from(writeRevokedAt),
                actorUserId, publicId, expectedVersion,
                Timestamp.from(writeRevokedAt)) == 1;
    }

    @Override
    public boolean markScanning(
            String publicId,
            long expectedVersion,
            String observedSha256,
            long observedSizeBytes,
            long actorUserId) {
        return jdbc.update("UPDATE ai_upload_session SET observed_sha256=?,observed_size_bytes=?,"
                        + "state='SCANNING',scan_state='SCANNING',"
                        + "updated_operator_user_id=?,version=version+1,updated_at=CURRENT_TIMESTAMP "
                        + "WHERE public_id=? AND state='UPLOADED' AND version=?",
                observedSha256, observedSizeBytes, actorUserId, publicId, expectedVersion) == 1;
    }

    @Override
    public boolean markScannedClean(
            String publicId,
            long expectedVersion,
            String detectedMimeType,
            long actorUserId) {
        return jdbc.update("UPDATE ai_upload_session SET detected_mime_type=?,scan_state='SCANNED_CLEAN',"
                        + "state='SCANNED_CLEAN',updated_operator_user_id=?,version=version+1,"
                        + "updated_at=CURRENT_TIMESTAMP WHERE public_id=? AND state='SCANNING' AND version=?",
                detectedMimeType, actorUserId, publicId, expectedVersion) == 1;
    }

    @Override
    public boolean markFinalized(
            String publicId,
            long expectedVersion,
            String detectedMimeType,
            long actorUserId) {
        return jdbc.update("UPDATE ai_upload_session SET state='FINALIZED',updated_operator_user_id=?,"
                        + "version=version+1,updated_at=CURRENT_TIMESTAMP WHERE public_id=? "
                        + "AND state='SCANNED_CLEAN' AND scan_state='SCANNED_CLEAN' "
                        + "AND detected_mime_type=? AND version=?",
                actorUserId, publicId, detectedMimeType, expectedVersion) == 1;
    }

    @Override
    public boolean markQuarantined(String publicId, String scanState, long actorUserId) {
        String safeState = scanState == null ? "REJECTED" : scanState.trim().toUpperCase(java.util.Locale.ROOT);
        if (!safeState.matches("[A-Z0-9_]{2,32}")) safeState = "REJECTED";
        return jdbc.update("UPDATE ai_upload_session SET state='QUARANTINED',scan_state=?,"
                        + "updated_operator_user_id=?,version=version+1,updated_at=CURRENT_TIMESTAMP "
                        + "WHERE public_id=? AND finalized_document_version_id IS NULL "
                        + "AND state IN ('CREATED','UPLOADING','UPLOADED','SCANNING','SCANNED_CLEAN','FINALIZED')",
                safeState, actorUserId, publicId) == 1;
    }

    @Override
    public boolean markExpired(String publicId, long actorUserId) {
        return jdbc.update("UPDATE ai_upload_session SET state='EXPIRED',scan_state='EXPIRED',"
                        + "updated_operator_user_id=?,version=version+1,updated_at=CURRENT_TIMESTAMP "
                        + "WHERE public_id=? AND finalized_document_version_id IS NULL "
                        + "AND state IN ('CREATED','UPLOADING','UPLOADED')",
                actorUserId, publicId) == 1;
    }

    @Override
    public Optional<UploadRecord> findByPublicId(String publicId) {
        if (publicId == null || publicId.isBlank()) return Optional.empty();
        List<UploadRecord> rows = jdbc.query("SELECT u.*,s.public_id source_public_id FROM ai_upload_session u "
                        + "JOIN ai_knowledge_source s ON s.id=u.source_id WHERE u.public_id=?",
                (rs, row) -> new UploadRecord(
                        rs.getLong("id"),
                        rs.getString("public_id"),
                        rs.getLong("source_id"),
                        rs.getLong("owner_user_id"),
                        rs.getString("source_public_id"),
                        rs.getString("quarantine_object_key"),
                        rs.getString("expected_sha256"),
                        rs.getString("observed_sha256"),
                        rs.getLong("expected_size_bytes"),
                        rs.getObject("observed_size_bytes", Long.class),
                        rs.getString("declared_mime_type"),
                        rs.getString("detected_mime_type"),
                        rs.getString("state"),
                        rs.getString("scan_state"),
                        rs.getString("object_version_id"),
                        rs.getString("object_etag"),
                        instant(rs.getTimestamp("expires_at")),
                        instant(rs.getTimestamp("write_revoked_at")),
                        rs.getObject("finalized_document_version_id", Long.class),
                        rs.getLong("version"),
                        instant(rs.getTimestamp("updated_at"))),
                publicId);
        return rows.stream().findFirst();
    }

    private void validate(CreateUpload command) {
        if (command == null || command.publicId() == null || command.publicId().length() != 36
                || command.sourceId() < 1 || command.ownerUserId() < 1
                || command.quarantineObjectKey() == null || command.quarantineObjectKey().length() > 512
                || command.expectedSha256() == null || !command.expectedSha256().matches("[0-9a-f]{64}")
                || command.expectedSizeBytes() < 1 || !"text/plain".equals(command.declaredMimeType())
                || command.expiresAt() == null || command.actorUserId() < 1) {
            throw new IllegalArgumentException("上传会话持久化参数不合法");
        }
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
