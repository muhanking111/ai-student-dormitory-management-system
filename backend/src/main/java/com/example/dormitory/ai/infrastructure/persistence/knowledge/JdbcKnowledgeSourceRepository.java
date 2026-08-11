package com.example.dormitory.ai.infrastructure.persistence.knowledge;

import com.example.dormitory.ai.knowledge.KnowledgeAclPolicy;
import com.example.dormitory.ai.knowledge.KnowledgeSourceRepository;
import com.example.dormitory.ai.knowledge.KnowledgeSourceConflictException;
import com.example.dormitory.ai.knowledge.KnowledgeVisibility;
import com.example.dormitory.ai.knowledge.PermissionMatchMode;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiOutboxRepository;
import com.example.dormitory.ai.security.DataClassification;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public class JdbcKnowledgeSourceRepository implements KnowledgeSourceRepository {

    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeAclPolicy aclPolicy;
    private final JdbcAiOutboxRepository outbox;

    @Autowired
    public JdbcKnowledgeSourceRepository(
            JdbcTemplate jdbcTemplate,
            KnowledgeAclPolicy aclPolicy,
            JdbcAiOutboxRepository outbox) {
        this.jdbcTemplate = jdbcTemplate;
        this.aclPolicy = aclPolicy;
        this.outbox = outbox;
    }

    public JdbcKnowledgeSourceRepository(JdbcTemplate jdbcTemplate, KnowledgeAclPolicy aclPolicy) {
        this(jdbcTemplate, aclPolicy, new JdbcAiOutboxRepository(jdbcTemplate));
    }

    @Override
    @Transactional
    public KnowledgeSource create(CreateSource command) {
        validate(command);
        aclPolicy.validatePermissions(command.permissions());
        requireAssignableOwner(command.ownerUserId());
        requireRegisteredPermissions(command.permissions());
        try {
            jdbcTemplate.update("INSERT INTO ai_knowledge_source "
                            + "(public_id, name, source_type, owner_user_id, classification, permission_match_mode, "
                            + "object_store_code, acl_version, status, created_operator_user_id, "
                            + "updated_operator_user_id, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, 1, 'ACTIVE', ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    command.publicId(), command.name().trim(), command.sourceType().trim(), command.ownerUserId(),
                    command.classification().name(), command.matchMode().name(), command.objectStoreCode().trim(),
                    command.actorUserId(), command.actorUserId());
            long sourceId = requireId(command.publicId());
            insertPermissions(sourceId, command.permissions(), command.actorUserId());
            return findByPublicId(command.publicId()).orElseThrow();
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException("知识来源 publicId 已存在", duplicate);
        }
    }

    @Override
    @Transactional
    public KnowledgeSource update(UpdateSource command) {
        validate(command);
        aclPolicy.validatePermissions(command.permissions());
        requireRegisteredPermissions(command.permissions());
        int updated = jdbcTemplate.update("UPDATE ai_knowledge_source SET name=?,classification=?,"
                        + "permission_match_mode=?,status=?,acl_version=acl_version+1,"
                        + "updated_operator_user_id=?,updated_at=CURRENT_TIMESTAMP "
                        + "WHERE public_id=? AND acl_version=? AND status IN ('ACTIVE','PAUSED')",
                command.name().trim(), command.classification().name(), command.matchMode().name(),
                command.status(), command.actorUserId(), command.publicId(), command.expectedAclVersion());
        if (updated != 1) throw new KnowledgeSourceConflictException();
        long sourceId = requireId(command.publicId());
        jdbcTemplate.update("DELETE FROM ai_knowledge_source_permission WHERE source_id=?", sourceId);
        insertPermissions(sourceId, command.permissions(), command.actorUserId());
        jdbcTemplate.update("UPDATE ai_document_version SET visibility='EXPLICIT_ACL',"
                        + "active_public_approval_id=NULL,updated_operator_user_id=?,updated_at=CURRENT_TIMESTAMP "
                        + "WHERE document_id IN (SELECT id FROM ai_document WHERE source_id=?)",
                command.actorUserId(), sourceId);
        KnowledgeSource result = findByPublicId(command.publicId()).orElseThrow();
        outbox.enqueueOnce("knowledge-source-changed|" + result.publicId() + "|" + result.aclVersion(),
                new JdbcAiOutboxRepository.OutboxDraft(
                        "KNOWLEDGE_SOURCE", result.publicId(), "KnowledgeSourceChanged.v1",
                        "{\"schemaVersion\":\"knowledge-source-changed.v1\",\"aclVersion\":"
                                + result.aclVersion() + ",\"status\":\"" + result.status()
                                + "\",\"classification\":\"" + result.classification().name() + "\"}",
                        ActorDescriptor.service("knowledge-governance", command.actorUserId(),
                                command.actorUserId()), java.time.Instant.now()));
        return result;
    }

    @Override
    public Optional<KnowledgeSource> findByPublicId(String publicId) {
        if (blank(publicId)) return Optional.empty();
        List<SourceRow> rows = jdbcTemplate.query("SELECT * FROM ai_knowledge_source WHERE public_id = ?",
                this::mapSource, publicId);
        return rows.stream().findFirst().map(row -> new KnowledgeSource(
                row.id(), row.publicId(), row.name(), row.sourceType(), row.ownerUserId(),
                DataClassification.valueOf(row.classification()), PermissionMatchMode.valueOf(row.matchMode()),
                row.objectStoreCode(), row.aclVersion(), row.status(), permissions(row.id())));
    }

    @Override
    public List<KnowledgeSource> findAll() {
        return jdbcTemplate.query("SELECT * FROM ai_knowledge_source ORDER BY created_at DESC, id DESC",
                        this::mapSource).stream()
                .map(row -> new KnowledgeSource(row.id(), row.publicId(), row.name(), row.sourceType(),
                        row.ownerUserId(), DataClassification.valueOf(row.classification()),
                        PermissionMatchMode.valueOf(row.matchMode()), row.objectStoreCode(), row.aclVersion(),
                        row.status(), permissions(row.id())))
                .toList();
    }

    @Override
    @Transactional
    public KnowledgeSource replaceAcl(
            String publicId, long expectedAclVersion, Set<String> permissions, long actorUserId) {
        KnowledgeSource current = findByPublicId(publicId)
                .orElseThrow(() -> new IllegalArgumentException("知识来源不存在"));
        return update(new UpdateSource(publicId, expectedAclVersion, current.name(), current.classification(),
                current.matchMode(), current.status(), permissions, actorUserId));
    }

    @Override
    public boolean canRead(String publicId, Set<String> actorPermissions) {
        Optional<KnowledgeSource> source = findByPublicId(publicId);
        if (source.isEmpty() || !"ACTIVE".equals(source.get().status())) return false;
        return aclPolicy.canRead(KnowledgeVisibility.EXPLICIT_ACL, source.get().matchMode(),
                source.get().permissions(), actorPermissions, false);
    }

    private void validate(CreateSource command) {
        if (command == null || blank(command.publicId()) || command.publicId().length() > 36
                || blank(command.name()) || command.name().length() > 200
                || blank(command.sourceType()) || command.sourceType().length() > 32
                || command.ownerUserId() < 1 || command.actorUserId() < 1
                || command.classification() == null || command.matchMode() == null
                || blank(command.objectStoreCode()) || command.objectStoreCode().length() > 64) {
            throw new IllegalArgumentException("知识来源参数不合法");
        }
        requireUuid(command.publicId());
        if (command.classification() == DataClassification.L3) {
            throw new IllegalArgumentException("L3 数据不得创建 AI 知识来源");
        }
    }

    private void validate(UpdateSource command) {
        if (command == null || blank(command.publicId()) || command.expectedAclVersion() < 1
                || blank(command.name()) || command.name().trim().length() > 200
                || command.classification() == null || command.classification() == DataClassification.L3
                || command.matchMode() == null || command.status() == null
                || !Set.of("ACTIVE", "PAUSED").contains(command.status())
                || command.actorUserId() < 1) {
            throw new IllegalArgumentException("知识来源更新参数不合法");
        }
        requireUuid(command.publicId());
    }

    private void requireAssignableOwner(long ownerUserId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE id=? AND enabled=TRUE AND deleted=FALSE",
                Integer.class, ownerUserId);
        if (count == null || count != 1) throw new IllegalArgumentException("知识来源 Owner 不存在或已停用");
    }

    private void requireRegisteredPermissions(Set<String> permissions) {
        for (String permission : permissions) {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_permission WHERE code=?",
                    Integer.class, permission);
            if (count == null || count != 1) throw new IllegalArgumentException("知识 ACL 包含未登记权限码");
        }
    }

    private void insertPermissions(long sourceId, Set<String> permissions, long actorUserId) {
        for (String permission : new java.util.TreeSet<>(permissions)) {
            jdbcTemplate.update("INSERT INTO ai_knowledge_source_permission "
                            + "(source_id, permission_code, created_operator_user_id, updated_operator_user_id, "
                            + "created_at, updated_at) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    sourceId, permission, actorUserId, actorUserId);
        }
    }

    private Set<String> permissions(long sourceId) {
        return Set.copyOf(new LinkedHashSet<>(jdbcTemplate.query(
                "SELECT permission_code FROM ai_knowledge_source_permission WHERE source_id = ? "
                        + "ORDER BY permission_code",
                (resultSet, rowNum) -> resultSet.getString(1), sourceId)));
    }

    private long requireId(String publicId) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_knowledge_source WHERE public_id = ?", Long.class, publicId);
        if (value == null) throw new IllegalStateException("知识来源不存在");
        return value;
    }

    private SourceRow mapSource(ResultSet resultSet, int rowNumber) throws SQLException {
        return new SourceRow(resultSet.getLong("id"), resultSet.getString("public_id"),
                resultSet.getString("name"), resultSet.getString("source_type"),
                resultSet.getLong("owner_user_id"), resultSet.getString("classification"),
                resultSet.getString("permission_match_mode"), resultSet.getString("object_store_code"),
                resultSet.getLong("acl_version"), resultSet.getString("status"));
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private void requireUuid(String value) {
        try {
            java.util.UUID.fromString(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("知识来源 publicId 必须为 UUID", invalid);
        }
    }

    private record SourceRow(
            long id, String publicId, String name, String sourceType, long ownerUserId, String classification,
            String matchMode, String objectStoreCode, long aclVersion, String status) {
    }
}
