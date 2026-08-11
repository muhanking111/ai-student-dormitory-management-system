package com.example.dormitory.ai.knowledge;

import com.example.dormitory.ai.infrastructure.persistence.JdbcAiOutboxRepository;
import com.example.dormitory.ai.infrastructure.persistence.knowledge.JdbcKnowledgeIngestionJobRepository;
import com.example.dormitory.ai.infrastructure.persistence.knowledge.JdbcKnowledgeSourceRepository;
import com.example.dormitory.ai.infrastructure.persistence.knowledge.JdbcKnowledgeVersionRepository;
import com.example.dormitory.ai.port.ObjectStoragePort;
import com.example.dormitory.ai.security.DataClassification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcKnowledgeRepositoryBoundaryTest {

    private static final Instant NOW = Instant.parse("2026-07-13T00:00:00Z");

    private JdbcTemplate jdbc;
    private JdbcKnowledgeSourceRepository sources;
    private JdbcKnowledgeVersionRepository versions;
    private JdbcKnowledgeIngestionJobRepository jobs;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:knowledge-repository-" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql"),
                new ClassPathResource("ai-schema.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO sys_user (id,username,password_hash,display_name,role_code,enabled,deleted) "
                + "VALUES (10,'owner','fixture-hash','Owner','ADMIN',TRUE,FALSE)");
        jdbc.update("INSERT INTO sys_user (id,username,password_hash,display_name,role_code,enabled,deleted) "
                + "VALUES (11,'disabled','fixture-hash','Disabled','ADMIN',FALSE,FALSE)");
        for (String permission : Set.of("repair:read", "notice:read")) {
            jdbc.update("INSERT INTO sys_permission (code,name,module) VALUES (?,?,?)",
                    permission, permission, "AI_TEST");
        }
        JdbcAiOutboxRepository outbox = new JdbcAiOutboxRepository(jdbc);
        sources = new JdbcKnowledgeSourceRepository(jdbc, new KnowledgeAclPolicy(), outbox);
        versions = new JdbcKnowledgeVersionRepository(jdbc, sources, outbox);
        jobs = new JdbcKnowledgeIngestionJobRepository(jdbc, outbox);
    }

    @Test
    void sourceCreationEnforcesOwnerPermissionClassificationAndAclCasBoundaries() {
        assertTrue(sources.findByPublicId(null).isEmpty());
        assertTrue(sources.findAll().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> createSource(
                UUID.randomUUID().toString(), 11, DataClassification.L1, Set.of("repair:read")));
        assertThrows(IllegalArgumentException.class, () -> createSource(
                UUID.randomUUID().toString(), 10, DataClassification.L1, Set.of("unknown:read")));
        assertThrows(IllegalArgumentException.class, () -> createSource(
                UUID.randomUUID().toString(), 10, DataClassification.L3, Set.of("repair:read")));
        assertThrows(IllegalArgumentException.class, () -> createSource(
                "not-a-uuid", 10, DataClassification.L1, Set.of("repair:read")));

        String publicId = UUID.randomUUID().toString();
        KnowledgeSourceRepository.KnowledgeSource created = createSource(
                publicId, 10, DataClassification.L1, Set.of("repair:read", "notice:read"));
        assertEquals(1, created.aclVersion());
        assertEquals(Set.of("repair:read", "notice:read"), created.permissions());
        assertEquals(List.of(publicId), sources.findAll().stream()
                .map(KnowledgeSourceRepository.KnowledgeSource::publicId).toList());
        assertFalse(sources.canRead(publicId, Set.of("repair:read")));
        assertTrue(sources.canRead(publicId, Set.of("repair:read", "notice:read")));

        assertThrows(KnowledgeSourceConflictException.class, () -> sources.replaceAcl(
                publicId, 2, Set.of("repair:read"), 10));
        KnowledgeSourceRepository.KnowledgeSource paused = sources.update(
                new KnowledgeSourceRepository.UpdateSource(publicId, created.aclVersion(), "暂停来源",
                        DataClassification.L2, PermissionMatchMode.ANY, "PAUSED",
                        Set.of("notice:read"), 10));
        assertEquals(2, paused.aclVersion());
        assertEquals(DataClassification.L2, paused.classification());
        assertFalse(sources.canRead(publicId, Set.of("notice:read")));
        assertThrows(KnowledgeSourceConflictException.class, () -> sources.update(
                new KnowledgeSourceRepository.UpdateSource(publicId, 1, "过期写入",
                        DataClassification.L1, PermissionMatchMode.ANY, "ACTIVE",
                        Set.of("repair:read"), 10)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_outbox_event "
                + "WHERE aggregate_public_id=? AND event_type='KnowledgeSourceChanged.v1'",
                Integer.class, publicId));
        assertThrows(IllegalStateException.class, () -> createSource(
                publicId, 10, DataClassification.L1, Set.of("repair:read")));
    }

    @Test
    void versionRepositoryRejectsUnsafeVisibilityUploadFactsChunksAndInvalidStates() {
        KnowledgeSourceRepository.KnowledgeSource source = createSource(
                UUID.randomUUID().toString(), 10, DataClassification.L1, Set.of("repair:read"));
        assertThrows(IllegalArgumentException.class, () -> createVersion(
                source, "public-not-l0", "v1", KnowledgeVisibility.PUBLIC_APPROVED, "公开正文"));

        KnowledgeVersionRepository.KnowledgeVersion pending = createVersion(
                source, "repair-policy", "v1", KnowledgeVisibility.EXPLICIT_ACL, "维修正文");
        assertTrue(versions.findByPublicId(" ").isEmpty());
        assertTrue(versions.findById(0).isEmpty());
        assertTrue(versions.findCurrentForDocument(pending.publicId()).isEmpty());
        assertTrue(versions.findActiveVersions().isEmpty());

        assertThrows(IllegalArgumentException.class,
                () -> versions.replaceChunksAndMarkReady(pending.publicId(), List.of(), 10));
        assertThrows(IllegalArgumentException.class, () -> versions.replaceChunksAndMarkReady(
                pending.publicId(), List.of(new KnowledgeVersionRepository.NewChunk(
                        1, "跳号", sha256("跳号"), "paragraph:1", "{}")), 10));
        assertThrows(IllegalArgumentException.class, () -> versions.replaceChunksAndMarkReady(
                pending.publicId(), List.of(new KnowledgeVersionRepository.NewChunk(
                        0, "正文", "bad-hash", "paragraph:0", "{}")), 10));

        KnowledgeVersionRepository.NewChunk chunk = new KnowledgeVersionRepository.NewChunk(
                0, "脱敏后的维修正文", sha256("脱敏后的维修正文"), "paragraph:0", "{}");
        versions.replaceChunksAndMarkReady(pending.publicId(), List.of(chunk), 10);
        assertEquals("READY", versions.findByPublicId(pending.publicId()).orElseThrow().status());
        assertEquals(1, versions.findChunks(pending.publicId()).size());
        assertThrows(IllegalStateException.class,
                () -> versions.replaceChunksAndMarkReady(pending.publicId(), List.of(chunk), 10));

        versions.markQuarantined(pending.publicId(), 10);
        versions.markQuarantined(pending.publicId(), 10);
        assertEquals("QUARANTINED", versions.findByPublicId(pending.publicId()).orElseThrow().status());
        assertTrue(versions.findChunks(pending.publicId()).isEmpty());
        assertThrows(IllegalStateException.class, () -> versions.activate(pending.publicId(), 10));
        assertThrows(IllegalStateException.class, () -> versions.retire(pending.publicId(), 10));
        assertThrows(IllegalArgumentException.class, () -> versions.markQuarantined(pending.publicId(), 0));

        persistFinalizedUpload(UUID.randomUUID().toString(), source, sha256("错误上传"), 12);
        String missingUpload = UUID.randomUUID().toString();
        assertThrows(IllegalStateException.class, () -> versions.createPending(versionCommand(
                source, missingUpload, "missing", "v1", KnowledgeVisibility.EXPLICIT_ACL, "缺失上传")));
    }

    @Test
    void publicApprovalBindsIdempotencyContentAndCurrentAclSnapshot() {
        KnowledgeSourceRepository.KnowledgeSource source = createSource(
                UUID.randomUUID().toString(), 10, DataClassification.L0, Set.of());
        KnowledgeVersionRepository.KnowledgeVersion first = readyVersion(
                source, "public-policy", "v1", KnowledgeVisibility.PUBLIC_APPROVED, "第一版公共制度");
        KnowledgeVersionRepository.PublicApprovalPreview preview = versions.publicApprovalPreview(first.publicId());

        assertThrows(IllegalStateException.class, () -> versions.approvePublic(first.publicId(),
                "0".repeat(64), preview.approvalSnapshotHash(), 20, 1001, "public-v1"));
        assertThrows(IllegalStateException.class, () -> versions.approvePublic(first.publicId(),
                preview.contentHash(), "f".repeat(64), 20, 1002, "public-v1"));
        assertThrows(IllegalStateException.class,
                () -> versions.approvePublic(first.publicId(), 10, 1003, "public-v1"));

        KnowledgeVersionRepository.PublicApproval approved = versions.approvePublic(
                first.publicId(), preview.contentHash(), preview.approvalSnapshotHash(),
                20, 1004, "public-v1");
        assertEquals(approved, versions.approvePublic(first.publicId(), 99, 1004, "ignored-on-replay"));

        KnowledgeVersionRepository.KnowledgeVersion second = readyVersion(
                source, "public-policy", "v2", KnowledgeVisibility.PUBLIC_APPROVED, "第二版公共制度");
        assertThrows(IllegalStateException.class,
                () -> versions.approvePublic(second.publicId(), 20, 1004, "public-v1"));

        versions.activate(first.publicId(), 20);
        assertTrue(versions.canRead(first.publicId(), Set.of()));
        assertEquals(first.publicId(), versions.findCurrentForDocument(first.publicId()).orElseThrow().publicId());
        jdbc.update("UPDATE ai_knowledge_source SET acl_version=acl_version+1 WHERE public_id=?", source.publicId());
        assertFalse(versions.canRead(first.publicId(), Set.of()));
        versions.retire(first.publicId(), 20);
        assertThrows(IllegalStateException.class, () -> versions.activate(first.publicId(), 20));
    }

    @Test
    void ingestionJobsEnforceEligibilityWorkerOwnershipTerminalStatesAndIdempotency() {
        KnowledgeSourceRepository.KnowledgeSource source = createSource(
                UUID.randomUUID().toString(), 10, DataClassification.L1, Set.of("repair:read"));
        KnowledgeVersionRepository.KnowledgeVersion version = createVersion(
                source, "job-policy", "v1", KnowledgeVisibility.EXPLICIT_ACL, "待摄取正文");
        Instant availableAt = NOW.plusSeconds(30);
        KnowledgeIngestionJobRepository.IngestionJob queued = jobs.enqueue(version.id(), 10, availableAt);
        KnowledgeIngestionJobRepository.IngestionJob replay = jobs.enqueue(version.id(), 10, availableAt);
        assertEquals(queued.publicId(), replay.publicId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_outbox_event "
                + "WHERE aggregate_public_id=? AND event_type='KnowledgeVersionRegistered.v1'",
                Integer.class, version.publicId()));
        assertTrue(jobs.claimNext("worker-a", NOW).isEmpty());

        KnowledgeIngestionJobRepository.IngestionJob running = jobs.claimNext(
                "worker-a", availableAt).orElseThrow();
        assertEquals("RUNNING", running.state());
        assertEquals(queued.publicId(), jobs.findByDocumentVersionId(version.id()).orElseThrow().publicId());
        assertThrows(IllegalStateException.class,
                () -> jobs.succeed(queued.publicId(), "worker-b", NOW.plusSeconds(31)));
        assertThrows(IllegalStateException.class,
                () -> jobs.requeue(queued.publicId(), "worker-b", NOW.plusSeconds(60)));
        jobs.requeue(queued.publicId(), "worker-a", NOW.plusSeconds(60));
        assertEquals("QUEUED", jobs.findByPublicId(queued.publicId()).orElseThrow().state());

        assertEquals("RUNNING", jobs.claim(queued.publicId(), "worker-c", NOW.plusSeconds(60))
                .orElseThrow().state());
        assertThrows(IllegalArgumentException.class, () -> jobs.fail(
                queued.publicId(), "worker-c", "x", "失败", NOW.plusSeconds(61)));
        jobs.fail(queued.publicId(), "worker-c", "SCAN_FAILED", "扫描失败", NOW.plusSeconds(61));
        assertEquals("FAILED", jobs.claim(queued.publicId(), "another-worker", NOW.plusSeconds(62))
                .orElseThrow().state());
        jobs.dead(queued.publicId(), "RETRY_EXHAUSTED", NOW.plusSeconds(63));
        assertEquals("FAILED", jobs.findByPublicId(queued.publicId()).orElseThrow().state());

        assertTrue(jobs.findByPublicId(null).isEmpty());
        assertTrue(jobs.findByDocumentVersionId(0).isEmpty());
        assertTrue(jobs.claim("missing", "worker", NOW).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> jobs.claimNext(" ", NOW));
        assertThrows(IllegalArgumentException.class, () -> jobs.enqueue(0, 10, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> jobs.dead(" ", "BAD", NOW));
    }

    private KnowledgeSourceRepository.KnowledgeSource createSource(
            String publicId, long ownerUserId, DataClassification classification, Set<String> permissions) {
        return sources.create(new KnowledgeSourceRepository.CreateSource(
                publicId, "知识来源", "UPLOAD", ownerUserId, classification,
                PermissionMatchMode.ALL, "object-v1", permissions, 10));
    }

    private KnowledgeVersionRepository.KnowledgeVersion readyVersion(
            KnowledgeSourceRepository.KnowledgeSource source,
            String externalKey,
            String version,
            KnowledgeVisibility visibility,
            String content) {
        KnowledgeVersionRepository.KnowledgeVersion created = createVersion(
                source, externalKey, version, visibility, content);
        versions.replaceChunksAndMarkReady(created.publicId(), List.of(
                new KnowledgeVersionRepository.NewChunk(0, content, sha256(content), "paragraph:0", "{}")), 10);
        return versions.findByPublicId(created.publicId()).orElseThrow();
    }

    private KnowledgeVersionRepository.KnowledgeVersion createVersion(
            KnowledgeSourceRepository.KnowledgeSource source,
            String externalKey,
            String version,
            KnowledgeVisibility visibility,
            String content) {
        String uploadId = UUID.randomUUID().toString();
        String hash = sha256(content);
        persistFinalizedUpload(uploadId, source, hash, content.getBytes(StandardCharsets.UTF_8).length);
        return versions.createPending(versionCommand(source, uploadId, externalKey, version, visibility, content));
    }

    private KnowledgeVersionRepository.CreateVersion versionCommand(
            KnowledgeSourceRepository.KnowledgeSource source,
            String uploadId,
            String externalKey,
            String version,
            KnowledgeVisibility visibility,
            String content) {
        String hash = sha256(content);
        return new KnowledgeVersionRepository.CreateVersion(
                source.publicId(), uploadId, NOW, sha256(externalKey), 1,
                "知识标题", version, hash, visibility,
                new ObjectStoragePort.ObjectReference("sha256/" + hash, "object-version-1", "etag-1"),
                "text/plain", content.getBytes(StandardCharsets.UTF_8).length,
                "plain-text-v1", "paragraph-2000-v1", 10);
    }

    private void persistFinalizedUpload(
            String uploadId,
            KnowledgeSourceRepository.KnowledgeSource source,
            String hash,
            long sizeBytes) {
        jdbc.update("INSERT INTO ai_upload_session(public_id,source_id,owner_user_id,quarantine_object_key,"
                        + "object_version_id,object_etag,expected_sha256,observed_sha256,expected_size_bytes,"
                        + "observed_size_bytes,declared_mime_type,detected_mime_type,scan_state,state,expires_at,"
                        + "write_revoked_at,version,created_operator_user_id,updated_operator_user_id,created_at,updated_at) "
                        + "VALUES(?,?,?,?,?,?,?, ?,?,?,'text/plain','text/plain','SCANNED_CLEAN','FINALIZED',"
                        + "DATEADD('MINUTE',5,CURRENT_TIMESTAMP),CURRENT_TIMESTAMP,0,10,10,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                uploadId, source.id(), 10, "quarantine/" + uploadId, "quarantine-version", "quarantine-etag",
                hash, hash, sizeBytes, sizeBytes);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
