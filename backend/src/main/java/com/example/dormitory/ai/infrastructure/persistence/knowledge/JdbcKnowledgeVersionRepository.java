package com.example.dormitory.ai.infrastructure.persistence.knowledge;

import com.example.dormitory.ai.knowledge.KnowledgeSourceRepository;
import com.example.dormitory.ai.knowledge.KnowledgeVersionRepository;
import com.example.dormitory.ai.knowledge.KnowledgeVisibility;
import com.example.dormitory.ai.port.ObjectStoragePort;
import com.example.dormitory.ai.security.DataClassification;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiOutboxRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@Repository
public class JdbcKnowledgeVersionRepository implements KnowledgeVersionRepository {

    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeSourceRepository sourceRepository;
    private final JdbcAiOutboxRepository outbox;

    @Autowired
    public JdbcKnowledgeVersionRepository(
            JdbcTemplate jdbcTemplate,
            KnowledgeSourceRepository sourceRepository,
            JdbcAiOutboxRepository outbox) {
        this.jdbcTemplate = jdbcTemplate;
        this.sourceRepository = sourceRepository;
        this.outbox = outbox;
    }

    public JdbcKnowledgeVersionRepository(
            JdbcTemplate jdbcTemplate,
            KnowledgeSourceRepository sourceRepository) {
        this(jdbcTemplate, sourceRepository, new JdbcAiOutboxRepository(jdbcTemplate));
    }

    @Override
    @Transactional
    public KnowledgeVersion createPending(CreateVersion command) {
        validate(command);
        KnowledgeSourceRepository.KnowledgeSource source = sourceRepository
                .findByPublicId(command.sourcePublicId())
                .filter(item -> "ACTIVE".equals(item.status()))
                .orElseThrow(() -> new IllegalArgumentException("知识来源不存在或不可用"));
        if (command.visibility() == KnowledgeVisibility.PUBLIC_APPROVED
                && source.classification() != DataClassification.L0) {
            throw new IllegalArgumentException("只有 L0 来源可创建公开知识版本");
        }
        persistFinalizedUpload(source, command);
        long documentId = findDocumentId(source.id(), command.externalKeyHmac()).orElseGet(() -> {
            String documentPublicId = UUID.randomUUID().toString();
            jdbcTemplate.update("INSERT INTO ai_document "
                            + "(public_id, source_id, external_key_hmac, external_key_key_version, title, status, "
                            + "created_operator_user_id, updated_operator_user_id, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    documentPublicId, source.id(), command.externalKeyHmac().toLowerCase(java.util.Locale.ROOT),
                    command.externalKeyKeyVersion(), command.title().trim(), command.actorUserId(),
                    command.actorUserId());
            Long value = jdbcTemplate.queryForObject("SELECT id FROM ai_document WHERE public_id = ?",
                    Long.class, documentPublicId);
            if (value == null) throw new IllegalStateException("知识文档创建失败");
            return value;
        });
        String publicId = UUID.randomUUID().toString();
        try {
            jdbcTemplate.update("INSERT INTO ai_document_version "
                            + "(public_id, document_id, version, content_hash, visibility, object_key, "
                            + "object_version_id, object_etag, mime_type, size_bytes, parser_version, "
                            + "chunk_policy_version, status, created_operator_user_id, updated_operator_user_id, "
                            + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', "
                            + "?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    publicId, documentId, command.version(), command.contentHash().toLowerCase(java.util.Locale.ROOT),
                    command.visibility().name(), command.objectReference().objectKey(),
                    command.objectReference().versionId(), command.objectReference().etag(), command.mimeType(),
                    command.sizeBytes(), command.parserVersion(), command.chunkPolicyVersion(),
                    command.actorUserId(), command.actorUserId());
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException("相同文档版本或内容已存在", duplicate);
        }
        KnowledgeVersion created = findByPublicId(publicId).orElseThrow();
        int linked = jdbcTemplate.update("UPDATE ai_upload_session SET finalized_document_version_id = ?, "
                        + "updated_operator_user_id = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE public_id = ? AND state = 'FINALIZED' AND finalized_document_version_id IS NULL",
                created.id(), command.actorUserId(), command.uploadSessionPublicId());
        if (linked != 1) throw new IllegalStateException("知识版本无法绑定上传会话事实");
        return created;
    }

    @Override
    public Optional<KnowledgeVersion> findByPublicId(String publicId) {
        if (publicId == null || publicId.isBlank()) return Optional.empty();
        return jdbcTemplate.query(VERSION_SELECT + " WHERE v.public_id = ?", this::mapVersion, publicId)
                .stream().findFirst();
    }

    @Override
    public Optional<KnowledgeVersion> findById(long id) {
        if (id < 1) return Optional.empty();
        return jdbcTemplate.query(VERSION_SELECT + " WHERE v.id = ?", this::mapVersion, id)
                .stream().findFirst();
    }

    @Override
    public Optional<KnowledgeVersion> findCurrentForDocument(String versionPublicId) {
        Optional<KnowledgeVersion> target = findByPublicId(versionPublicId);
        if (target.isEmpty()) return Optional.empty();
        return jdbcTemplate.query(VERSION_SELECT + " WHERE v.id = (SELECT current_version_id FROM ai_document "
                        + "WHERE id = ?)", this::mapVersion, target.get().documentId()).stream().findFirst();
    }

    @Override
    public List<KnowledgeChunk> findChunks(String versionPublicId) {
        return jdbcTemplate.query("SELECT c.public_id, c.chunk_no, c.content_redacted, c.content_hash, "
                        + "c.locator_text, c.metadata_text, c.status FROM ai_document_chunk c "
                        + "JOIN ai_document_version v ON v.id = c.document_version_id "
                        + "WHERE v.public_id = ? ORDER BY c.chunk_no",
                (resultSet, rowNum) -> new KnowledgeChunk(resultSet.getString("public_id"),
                        resultSet.getInt("chunk_no"), resultSet.getString("content_redacted"),
                        resultSet.getString("content_hash"), resultSet.getString("locator_text"),
                        resultSet.getString("metadata_text"), resultSet.getString("status")), versionPublicId);
    }

    @Override
    public List<KnowledgeVersion> findActiveVersions() {
        return jdbcTemplate.query(VERSION_SELECT + " WHERE v.status = 'ACTIVE' ORDER BY v.id",
                this::mapVersion);
    }

    @Override
    @Transactional
    public void replaceChunksAndMarkReady(String versionPublicId, List<NewChunk> chunks, long actorUserId) {
        if (chunks == null || chunks.isEmpty() || actorUserId < 1) {
            throw new IllegalArgumentException("知识 chunk 不能为空");
        }
        KnowledgeVersion version = findByPublicId(versionPublicId)
                .orElseThrow(() -> new IllegalArgumentException("知识版本不存在"));
        if (!"PENDING".equals(version.status())) throw new IllegalStateException("知识版本不处于待摄取状态");
        jdbcTemplate.update("DELETE FROM ai_document_chunk WHERE document_version_id = ?", version.id());
        int expected = 0;
        for (NewChunk chunk : chunks) {
            if (chunk == null || chunk.chunkNo() != expected++ || chunk.contentRedacted() == null
                    || chunk.contentRedacted().isBlank() || !isSha256(chunk.contentHash())) {
                throw new IllegalArgumentException("知识 chunk 合同不合法");
            }
            jdbcTemplate.update("INSERT INTO ai_document_chunk "
                            + "(public_id, document_version_id, chunk_no, content_redacted, content_hash, "
                            + "locator_text, metadata_text, status, created_operator_user_id, "
                            + "updated_operator_user_id, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, 'READY', ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    UUID.randomUUID().toString(), version.id(), chunk.chunkNo(), chunk.contentRedacted(),
                    chunk.contentHash().toLowerCase(java.util.Locale.ROOT), chunk.locator(), chunk.metadata(),
                    actorUserId, actorUserId);
        }
        int updated = jdbcTemplate.update("UPDATE ai_document_version SET status = 'READY', "
                        + "updated_operator_user_id = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND status = 'PENDING'",
                actorUserId, version.id());
        if (updated != 1) throw new IllegalStateException("知识版本状态冲突");
    }

    @Override
    @Transactional
    public void markQuarantined(String versionPublicId, long actorUserId) {
        if (actorUserId < 1) throw new IllegalArgumentException("操作人不合法");
        KnowledgeVersion version = findByPublicId(versionPublicId)
                .orElseThrow(() -> new IllegalArgumentException("知识版本不存在"));
        jdbcTemplate.update("DELETE FROM ai_document_chunk WHERE document_version_id = ?", version.id());
        int updated = jdbcTemplate.update("UPDATE ai_document_version SET status = 'QUARANTINED', "
                        + "updated_operator_user_id = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND status IN ('PENDING', 'READY')",
                actorUserId, version.id());
        if (updated != 1 && !"QUARANTINED".equals(version.status())) {
            throw new IllegalStateException("知识版本无法隔离");
        }
    }

    @Override
    @Transactional
    public PublicApproval approvePublic(
            String versionPublicId,
            long reviewerUserId,
            long idempotencyRecordId,
            String approvalPolicyVersion) {
        PublicApprovalPreview preview = publicApprovalPreview(versionPublicId);
        return approvePublic(versionPublicId, preview.contentHash(), preview.approvalSnapshotHash(),
                reviewerUserId, idempotencyRecordId, approvalPolicyVersion);
    }

    @Override
    @Transactional
    public PublicApproval approvePublic(
            String versionPublicId, String expectedContentHash, String expectedSnapshotHash,
            long reviewerUserId, long idempotencyRecordId, String approvalPolicyVersion) {
        if (reviewerUserId < 1 || idempotencyRecordId < 1 || blank(approvalPolicyVersion)
                || approvalPolicyVersion.length() > 64) {
            throw new IllegalArgumentException("公开批准参数不合法");
        }
        List<PublicApproval> replay = findApprovalByIdempotency(idempotencyRecordId);
        if (!replay.isEmpty()) {
            PublicApproval existing = replay.getFirst();
            if (!existing.versionPublicId().equals(versionPublicId)) {
                throw new IllegalStateException("公开批准幂等键已绑定其他版本");
            }
            return existing;
        }
        KnowledgeVersion version = findByPublicId(versionPublicId)
                .orElseThrow(() -> new IllegalArgumentException("知识版本不存在"));
        if (!"READY".equals(version.status())) {
            throw new IllegalStateException("只有 READY 版本可批准公开");
        }
        KnowledgeSourceRepository.KnowledgeSource source = sourceRepository.findByPublicId(version.sourcePublicId())
                .orElseThrow(() -> new IllegalStateException("知识来源不存在"));
        if (source.classification() != DataClassification.L0 || !"ACTIVE".equals(source.status())) {
            throw new IllegalStateException("公开批准要求当前来源为 ACTIVE L0");
        }
        if (source.ownerUserId() == reviewerUserId) {
            throw new IllegalStateException("知识来源 Owner 不得批准自身公开版本");
        }
        String snapshotHash = approvalSnapshot(version, source);
        if (!version.contentHash().equalsIgnoreCase(expectedContentHash)
                || !snapshotHash.equalsIgnoreCase(expectedSnapshotHash)) {
            throw new IllegalStateException("公开批准内容或 ACL 快照已变化");
        }
        jdbcTemplate.update("INSERT INTO ai_knowledge_public_approval "
                        + "(source_id, document_version_id, decision, approval_policy_version, "
                        + "classification_snapshot, acl_version, content_hash, approval_snapshot_hash, "
                        + "reviewer_user_id, idempotency_record_id, created_at) "
                        + "VALUES (?, ?, 'APPROVED', ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                source.id(), version.id(), approvalPolicyVersion, source.classification().name(),
                source.aclVersion(), version.contentHash(), snapshotHash, reviewerUserId, idempotencyRecordId);
        Long approvalId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_knowledge_public_approval WHERE idempotency_record_id = ?",
                Long.class, idempotencyRecordId);
        if (approvalId == null) throw new IllegalStateException("公开批准写入失败");
        int updated = jdbcTemplate.update("UPDATE ai_document_version SET active_public_approval_id = ?, "
                        + "visibility = 'PUBLIC_APPROVED', "
                        + "updated_operator_user_id = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? "
                        + "AND content_hash = ? AND status = 'READY'",
                approvalId, reviewerUserId, version.id(), version.contentHash());
        if (updated != 1) throw new IllegalStateException("公开批准绑定版本失败");
        return new PublicApproval(approvalId, versionPublicId, version.contentHash(), source.aclVersion(),
                snapshotHash, reviewerUserId, approvalPolicyVersion);
    }

    @Override
    public PublicApprovalPreview publicApprovalPreview(String versionPublicId) {
        KnowledgeVersion version = findByPublicId(versionPublicId)
                .orElseThrow(() -> new IllegalArgumentException("知识版本不存在"));
        KnowledgeSourceRepository.KnowledgeSource source = sourceRepository.findByPublicId(version.sourcePublicId())
                .orElseThrow(() -> new IllegalStateException("知识来源不存在"));
        return new PublicApprovalPreview(version.publicId(), version.contentHash(), source.aclVersion(),
                approvalSnapshot(version, source));
    }

    @Override
    @Transactional
    public KnowledgeVersion activate(String versionPublicId, long actorUserId) {
        if (actorUserId < 1) throw new IllegalArgumentException("操作人不合法");
        KnowledgeVersion target = findByPublicId(versionPublicId)
                .orElseThrow(() -> new IllegalArgumentException("知识版本不存在"));
        if (!Set.of("READY", "RETIRED").contains(target.status())) {
            throw new IllegalStateException("只有 READY/RETIRED 版本可激活");
        }
        if (target.visibility() == KnowledgeVisibility.PUBLIC_APPROVED) requireCurrentApproval(target);
        jdbcTemplate.update("UPDATE ai_document_version SET status = 'RETIRED', retired_at = CURRENT_TIMESTAMP, "
                        + "updated_operator_user_id = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE document_id = ? AND status = 'ACTIVE' AND id <> ?",
                actorUserId, target.documentId(), target.id());
        int activated = jdbcTemplate.update("UPDATE ai_document_version SET status = 'ACTIVE', "
                        + "activated_at = CURRENT_TIMESTAMP, retired_at = NULL, updated_operator_user_id = ?, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status IN ('READY', 'RETIRED')",
                actorUserId, target.id());
        if (activated != 1) throw new IllegalStateException("知识版本激活冲突");
        int document = jdbcTemplate.update("UPDATE ai_document SET current_version_id = ?, status = 'ACTIVE', "
                        + "updated_operator_user_id = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                target.id(), actorUserId, target.documentId());
        if (document != 1) throw new IllegalStateException("知识文档当前版本更新失败");
        Integer priorActivations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_outbox_event WHERE aggregate_type='KNOWLEDGE_VERSION' "
                        + "AND aggregate_public_id=? AND event_type='KnowledgeVersionActivated.v1'",
                Integer.class, target.publicId());
        int activationSequence = (priorActivations == null ? 0 : priorActivations) + 1;
        outbox.enqueueOnce("knowledge-version-activated|" + target.publicId() + "|" + activationSequence,
                new JdbcAiOutboxRepository.OutboxDraft(
                        "KNOWLEDGE_VERSION", target.publicId(), "KnowledgeVersionActivated.v1",
                        "{\"schemaVersion\":\"knowledge-version-activated.v1\",\"sourceId\":\""
                                + target.sourcePublicId() + "\",\"contentHash\":\""
                                + target.contentHash() + "\",\"activationSequence\":"
                                + activationSequence + "}",
                        ActorDescriptor.service("knowledge-governance", actorUserId, actorUserId),
                        java.time.Instant.now()));
        return findByPublicId(versionPublicId).orElseThrow();
    }

    @Override
    @Transactional
    public KnowledgeVersion retire(String versionPublicId, long actorUserId) {
        if (actorUserId < 1) throw new IllegalArgumentException("操作人不合法");
        KnowledgeVersion version = findByPublicId(versionPublicId)
                .orElseThrow(() -> new IllegalArgumentException("知识版本不存在"));
        if (!Set.of("ACTIVE", "READY").contains(version.status())) {
            throw new IllegalStateException("知识版本不可退役");
        }
        int updated = jdbcTemplate.update("UPDATE ai_document_version SET status = 'RETIRED', "
                        + "retired_at = CURRENT_TIMESTAMP, updated_operator_user_id = ?, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status IN ('ACTIVE', 'READY')",
                actorUserId, version.id());
        if (updated != 1) throw new IllegalStateException("知识版本退役冲突");
        jdbcTemplate.update("UPDATE ai_document SET current_version_id = NULL, "
                        + "updated_operator_user_id = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND current_version_id = ?",
                actorUserId, version.documentId(), version.id());
        return findByPublicId(versionPublicId).orElseThrow();
    }

    @Override
    public boolean canRead(String versionPublicId, Set<String> actorPermissions) {
        Optional<KnowledgeVersion> optional = findByPublicId(versionPublicId);
        if (optional.isEmpty() || !"ACTIVE".equals(optional.get().status())) return false;
        KnowledgeVersion version = optional.get();
        if (version.visibility() == KnowledgeVisibility.PUBLIC_APPROVED) {
            try {
                requireCurrentApproval(version);
                return true;
            } catch (IllegalStateException invalid) {
                return false;
            }
        }
        return sourceRepository.canRead(version.sourcePublicId(), actorPermissions);
    }

    private void requireCurrentApproval(KnowledgeVersion version) {
        KnowledgeSourceRepository.KnowledgeSource source = sourceRepository.findByPublicId(version.sourcePublicId())
                .orElseThrow(() -> new IllegalStateException("知识来源不存在"));
        if (source.classification() != DataClassification.L0 || !"ACTIVE".equals(source.status())
                || version.activePublicApprovalId() == null) {
            throw new IllegalStateException("公开版本缺少有效批准");
        }
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_knowledge_public_approval "
                        + "WHERE id = ? AND source_id = ? AND document_version_id = ? AND decision = 'APPROVED' "
                        + "AND classification_snapshot = ? AND acl_version = ? AND content_hash = ? "
                        + "AND approval_snapshot_hash = ?",
                Integer.class, version.activePublicApprovalId(), source.id(), version.id(),
                source.classification().name(), source.aclVersion(), version.contentHash(),
                approvalSnapshot(version, source));
        if (count == null || count != 1) throw new IllegalStateException("公开批准快照已失效");
    }

    private List<PublicApproval> findApprovalByIdempotency(long idempotencyRecordId) {
        return jdbcTemplate.query("SELECT a.id, v.public_id, a.content_hash, a.acl_version, "
                        + "a.approval_snapshot_hash, a.reviewer_user_id, a.approval_policy_version "
                        + "FROM ai_knowledge_public_approval a JOIN ai_document_version v "
                        + "ON v.id = a.document_version_id WHERE a.idempotency_record_id = ?",
                (resultSet, rowNum) -> new PublicApproval(resultSet.getLong(1), resultSet.getString(2),
                        resultSet.getString(3), resultSet.getLong(4), resultSet.getString(5),
                        resultSet.getLong(6), resultSet.getString(7)), idempotencyRecordId);
    }

    private String approvalSnapshot(
            KnowledgeVersion version,
            KnowledgeSourceRepository.KnowledgeSource source) {
        return sha256(String.join("|", version.publicId(), version.contentHash(),
                source.classification().name(), Long.toString(source.aclVersion()),
                source.matchMode().name(), String.join(",", new TreeSet<>(source.permissions()))));
    }

    private Optional<Long> findDocumentId(long sourceId, String externalKeyHmac) {
        return jdbcTemplate.query("SELECT id FROM ai_document WHERE source_id = ? AND external_key_hmac = ?",
                (resultSet, rowNum) -> resultSet.getLong(1), sourceId,
                externalKeyHmac.toLowerCase(java.util.Locale.ROOT)).stream().findFirst();
    }

    private KnowledgeVersion mapVersion(ResultSet resultSet, int rowNumber) throws SQLException {
        long approvalId = resultSet.getLong("active_public_approval_id");
        boolean approvalIdWasNull = resultSet.wasNull();
        return new KnowledgeVersion(resultSet.getLong("id"), resultSet.getString("public_id"),
                resultSet.getLong("document_id"), resultSet.getString("document_public_id"),
                resultSet.getLong("source_id"), resultSet.getString("source_public_id"),
                resultSet.getString("version"), resultSet.getString("content_hash"),
                KnowledgeVisibility.valueOf(resultSet.getString("visibility")),
                new ObjectStoragePort.ObjectReference(resultSet.getString("object_key"),
                        resultSet.getString("object_version_id"), resultSet.getString("object_etag")),
                resultSet.getString("mime_type"), resultSet.getLong("size_bytes"),
                resultSet.getString("parser_version"), resultSet.getString("chunk_policy_version"),
                resultSet.getString("status"), approvalIdWasNull ? null : approvalId);
    }

    private void validate(CreateVersion command) {
        if (command == null || blank(command.sourcePublicId()) || blank(command.uploadSessionPublicId())
                || command.uploadSessionPublicId().length() > 36 || command.uploadFinalizedAt() == null
                || !isSha256(command.externalKeyHmac())
                || command.externalKeyKeyVersion() < 1 || blank(command.title()) || command.title().length() > 500
                || blank(command.version()) || command.version().length() > 32 || !isSha256(command.contentHash())
                || command.visibility() == null || command.objectReference() == null
                || blank(command.objectReference().objectKey()) || blank(command.objectReference().versionId())
                || blank(command.objectReference().etag()) || !"text/plain".equals(command.mimeType())
                || command.sizeBytes() < 1 || blank(command.parserVersion()) || blank(command.chunkPolicyVersion())
                || command.actorUserId() < 1) {
            throw new IllegalArgumentException("知识版本参数不合法");
        }
    }

    private void persistFinalizedUpload(
            KnowledgeSourceRepository.KnowledgeSource source,
            CreateVersion command) {
        List<UploadRow> rows = jdbcTemplate.query("SELECT source_id, owner_user_id, object_version_id, "
                        + "object_etag, observed_sha256, observed_size_bytes, state "
                        + "FROM ai_upload_session WHERE public_id = ?",
                (resultSet, rowNum) -> new UploadRow(resultSet.getLong(1), resultSet.getLong(2),
                        resultSet.getString(3), resultSet.getString(4), resultSet.getString(5),
                        resultSet.getLong(6), resultSet.getString(7)), command.uploadSessionPublicId());
        if (!rows.isEmpty()) {
            UploadRow existing = rows.getFirst();
            if (existing.sourceId() != source.id() || existing.ownerUserId() != command.actorUserId()
                    || !"FINALIZED".equals(existing.state())
                    || !existing.observedSha256().equalsIgnoreCase(command.contentHash())
                    || existing.observedSizeBytes() != command.sizeBytes()
                    || !command.objectReference().objectKey().equals(
                            "sha256/" + command.contentHash().toLowerCase(java.util.Locale.ROOT))) {
                throw new IllegalStateException("上传会话事实与摄取请求不匹配");
            }
            return;
        }
        throw new IllegalStateException("上传会话事实不存在；禁止根据调用参数补造扫描结果");
    }

    private boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-fA-F]{64}");
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("知识快照哈希不可用", exception);
        }
    }

    private static final String VERSION_SELECT = "SELECT v.*, d.public_id AS document_public_id, "
            + "d.source_id, s.public_id AS source_public_id FROM ai_document_version v "
            + "JOIN ai_document d ON d.id = v.document_id JOIN ai_knowledge_source s ON s.id = d.source_id";

    private record UploadRow(
            long sourceId,
            long ownerUserId,
            String objectVersionId,
            String objectEtag,
            String observedSha256,
            long observedSizeBytes,
            String state) {
    }
}
