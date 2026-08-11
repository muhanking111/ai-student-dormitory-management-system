package com.example.dormitory.ai.knowledge;

import com.example.dormitory.ai.infrastructure.persistence.JdbcAiOutboxRepository;
import com.example.dormitory.ai.infrastructure.persistence.knowledge.JdbcKnowledgeIngestionJobRepository;
import com.example.dormitory.ai.infrastructure.persistence.knowledge.JdbcKnowledgeSourceRepository;
import com.example.dormitory.ai.infrastructure.persistence.knowledge.JdbcKnowledgeUploadSessionRepository;
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
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcKnowledgePersistenceEdgeCaseTest {

    private static final Instant NOW = Instant.parse("2026-07-14T00:00:00Z");
    private static final String HASH = "a".repeat(64);

    private JdbcTemplate jdbc;
    private JdbcKnowledgeSourceRepository sources;
    private JdbcKnowledgeUploadSessionRepository uploads;
    private JdbcKnowledgeVersionRepository versions;
    private JdbcKnowledgeIngestionJobRepository jobs;
    private KnowledgeSourceRepository.KnowledgeSource source;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:knowledge-persistence-edge-" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql"),
                new ClassPathResource("ai-schema.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO sys_user (id,username,password_hash,display_name,role_code,enabled,deleted) "
                + "VALUES (10,'edge-owner','fixture-hash','Owner','ADMIN',TRUE,FALSE)");
        for (String permission : Set.of("repair:read", "notice:read")) {
            jdbc.update("INSERT INTO sys_permission (code,name,module) VALUES (?,?,?)",
                    permission, permission, "AI_TEST");
        }
        JdbcAiOutboxRepository outbox = new JdbcAiOutboxRepository(jdbc);
        sources = new JdbcKnowledgeSourceRepository(jdbc, new KnowledgeAclPolicy(), outbox);
        uploads = new JdbcKnowledgeUploadSessionRepository(jdbc);
        versions = new JdbcKnowledgeVersionRepository(jdbc, sources, outbox);
        jobs = new JdbcKnowledgeIngestionJobRepository(jdbc, outbox);
        source = sources.create(createSourceCommand(UUID.randomUUID().toString(), "source", "UPLOAD",
                10, DataClassification.L1, PermissionMatchMode.ANY, "object-v1", 10));
    }

    @Test
    void sourceRepositoryRejectsEveryIndependentCreateAndUpdateViolation() {
        List<KnowledgeSourceRepository.CreateSource> invalidCreates = Arrays.asList(
                null,
                createSourceCommand(null, "name", "UPLOAD", 10, DataClassification.L1,
                        PermissionMatchMode.ANY, "object-v1", 10),
                createSourceCommand(" ", "name", "UPLOAD", 10, DataClassification.L1,
                        PermissionMatchMode.ANY, "object-v1", 10),
                createSourceCommand("x".repeat(37), "name", "UPLOAD", 10, DataClassification.L1,
                        PermissionMatchMode.ANY, "object-v1", 10),
                createSourceCommand(UUID.randomUUID().toString(), null, "UPLOAD", 10, DataClassification.L1,
                        PermissionMatchMode.ANY, "object-v1", 10),
                createSourceCommand(UUID.randomUUID().toString(), " ", "UPLOAD", 10, DataClassification.L1,
                        PermissionMatchMode.ANY, "object-v1", 10),
                createSourceCommand(UUID.randomUUID().toString(), "x".repeat(201), "UPLOAD", 10,
                        DataClassification.L1, PermissionMatchMode.ANY, "object-v1", 10),
                createSourceCommand(UUID.randomUUID().toString(), "name", null, 10, DataClassification.L1,
                        PermissionMatchMode.ANY, "object-v1", 10),
                createSourceCommand(UUID.randomUUID().toString(), "name", "x".repeat(33), 10,
                        DataClassification.L1, PermissionMatchMode.ANY, "object-v1", 10),
                createSourceCommand(UUID.randomUUID().toString(), "name", "UPLOAD", 0,
                        DataClassification.L1, PermissionMatchMode.ANY, "object-v1", 10),
                createSourceCommand(UUID.randomUUID().toString(), "name", "UPLOAD", 10, null,
                        PermissionMatchMode.ANY, "object-v1", 10),
                createSourceCommand(UUID.randomUUID().toString(), "name", "UPLOAD", 10,
                        DataClassification.L1, null, "object-v1", 10),
                createSourceCommand(UUID.randomUUID().toString(), "name", "UPLOAD", 10,
                        DataClassification.L1, PermissionMatchMode.ANY, null, 10),
                createSourceCommand(UUID.randomUUID().toString(), "name", "UPLOAD", 10,
                        DataClassification.L1, PermissionMatchMode.ANY, "x".repeat(65), 10),
                createSourceCommand(UUID.randomUUID().toString(), "name", "UPLOAD", 10,
                        DataClassification.L1, PermissionMatchMode.ANY, "object-v1", 0));
        for (KnowledgeSourceRepository.CreateSource command : invalidCreates) {
            assertThrows(IllegalArgumentException.class, () -> sources.create(command), String.valueOf(command));
        }

        List<KnowledgeSourceRepository.UpdateSource> invalidUpdates = Arrays.asList(
                null,
                updateSourceCommand(null, 1, "name", DataClassification.L1,
                        PermissionMatchMode.ANY, "ACTIVE", 10),
                updateSourceCommand(" ", 1, "name", DataClassification.L1,
                        PermissionMatchMode.ANY, "ACTIVE", 10),
                updateSourceCommand(source.publicId(), 0, "name", DataClassification.L1,
                        PermissionMatchMode.ANY, "ACTIVE", 10),
                updateSourceCommand(source.publicId(), 1, null, DataClassification.L1,
                        PermissionMatchMode.ANY, "ACTIVE", 10),
                updateSourceCommand(source.publicId(), 1, " ", DataClassification.L1,
                        PermissionMatchMode.ANY, "ACTIVE", 10),
                updateSourceCommand(source.publicId(), 1, " " + "x".repeat(201) + " ",
                        DataClassification.L1, PermissionMatchMode.ANY, "ACTIVE", 10),
                updateSourceCommand(source.publicId(), 1, "name", null,
                        PermissionMatchMode.ANY, "ACTIVE", 10),
                updateSourceCommand(source.publicId(), 1, "name", DataClassification.L3,
                        PermissionMatchMode.ANY, "ACTIVE", 10),
                updateSourceCommand(source.publicId(), 1, "name", DataClassification.L1,
                        null, "ACTIVE", 10),
                updateSourceCommand(source.publicId(), 1, "name", DataClassification.L1,
                        PermissionMatchMode.ANY, null, 10),
                updateSourceCommand(source.publicId(), 1, "name", DataClassification.L1,
                        PermissionMatchMode.ANY, "RETIRED", 10),
                updateSourceCommand(source.publicId(), 1, "name", DataClassification.L1,
                        PermissionMatchMode.ANY, "ACTIVE", 0));
        for (KnowledgeSourceRepository.UpdateSource command : invalidUpdates) {
            assertThrows(IllegalArgumentException.class, () -> sources.update(command), String.valueOf(command));
        }

        assertTrue(sources.findByPublicId(null).isEmpty());
        assertTrue(sources.findByPublicId(" ").isEmpty());
        assertFalse(sources.canRead(UUID.randomUUID().toString(), Set.of("repair:read")));
        assertThrows(IllegalArgumentException.class, () -> sources.replaceAcl(
                UUID.randomUUID().toString(), 1, Set.of("repair:read"), 10));
    }

    @Test
    void uploadRepositoryValidatesCommandsAndExposesEveryCasOutcome() {
        List<KnowledgeUploadSessionRepository.CreateUpload> invalid = Arrays.asList(
                null,
                uploadCommand(null, source.id(), 10, "quarantine/x", HASH, 4, "text/plain", NOW, 10),
                uploadCommand("short", source.id(), 10, "quarantine/x", HASH, 4, "text/plain", NOW, 10),
                uploadCommand(UUID.randomUUID().toString(), 0, 10, "quarantine/x", HASH, 4,
                        "text/plain", NOW, 10),
                uploadCommand(UUID.randomUUID().toString(), source.id(), 0, "quarantine/x", HASH, 4,
                        "text/plain", NOW, 10),
                uploadCommand(UUID.randomUUID().toString(), source.id(), 10, null, HASH, 4,
                        "text/plain", NOW, 10),
                uploadCommand(UUID.randomUUID().toString(), source.id(), 10, "x".repeat(513), HASH, 4,
                        "text/plain", NOW, 10),
                uploadCommand(UUID.randomUUID().toString(), source.id(), 10, "quarantine/x", null, 4,
                        "text/plain", NOW, 10),
                uploadCommand(UUID.randomUUID().toString(), source.id(), 10, "quarantine/x",
                        HASH.toUpperCase(), 4, "text/plain", NOW, 10),
                uploadCommand(UUID.randomUUID().toString(), source.id(), 10, "quarantine/x", HASH, 0,
                        "text/plain", NOW, 10),
                uploadCommand(UUID.randomUUID().toString(), source.id(), 10, "quarantine/x", HASH, 4,
                        null, NOW, 10),
                uploadCommand(UUID.randomUUID().toString(), source.id(), 10, "quarantine/x", HASH, 4,
                        "application/pdf", NOW, 10),
                uploadCommand(UUID.randomUUID().toString(), source.id(), 10, "quarantine/x", HASH, 4,
                        "text/plain", null, 10),
                uploadCommand(UUID.randomUUID().toString(), source.id(), 10, "quarantine/x", HASH, 4,
                        "text/plain", NOW, 0));
        for (KnowledgeUploadSessionRepository.CreateUpload command : invalid) {
            assertThrows(IllegalArgumentException.class, () -> uploads.create(command), String.valueOf(command));
        }

        assertTrue(uploads.findByPublicId(null).isEmpty());
        assertTrue(uploads.findByPublicId(" ").isEmpty());
        assertFalse(uploads.markUploading("missing", 0, NOW, 10));

        KnowledgeUploadSessionRepository.UploadRecord created = createUpload();
        assertFalse(uploads.markUploading(created.publicId(), created.version() + 1, NOW, 10));
        assertTrue(uploads.markUploading(created.publicId(), created.version(), NOW, 10));
        KnowledgeUploadSessionRepository.UploadRecord uploading = uploads.findByPublicId(created.publicId())
                .orElseThrow();
        ObjectStoragePort.StoredObject object = storedObject(created);
        assertFalse(uploads.markUploaded(created.publicId(), uploading.version() + 1, object, NOW, 10));
        assertTrue(uploads.markUploaded(created.publicId(), uploading.version(), object, NOW, 10));
        KnowledgeUploadSessionRepository.UploadRecord uploaded = uploads.findByPublicId(created.publicId())
                .orElseThrow();
        assertFalse(uploads.markScanning(created.publicId(), uploaded.version() + 1, HASH, 4, 10));
        assertTrue(uploads.markScanning(created.publicId(), uploaded.version(), HASH, 4, 10));
        KnowledgeUploadSessionRepository.UploadRecord scanning = uploads.findByPublicId(created.publicId())
                .orElseThrow();
        assertFalse(uploads.markScannedClean(created.publicId(), scanning.version() + 1, "text/plain", 10));
        assertTrue(uploads.markScannedClean(created.publicId(), scanning.version(), "text/plain", 10));
        KnowledgeUploadSessionRepository.UploadRecord clean = uploads.findByPublicId(created.publicId())
                .orElseThrow();
        assertFalse(uploads.markFinalized(created.publicId(), clean.version(), "application/pdf", 10));
        assertTrue(uploads.markFinalized(created.publicId(), clean.version(), "text/plain", 10));

        KnowledgeUploadSessionRepository.UploadRecord nullReason = createUpload();
        assertTrue(uploads.markQuarantined(nullReason.publicId(), null, 10));
        assertEquals("REJECTED", uploads.findByPublicId(nullReason.publicId()).orElseThrow().scanState());
        KnowledgeUploadSessionRepository.UploadRecord invalidReason = createUpload();
        assertTrue(uploads.markQuarantined(invalidReason.publicId(), "not safe!", 10));
        assertEquals("REJECTED", uploads.findByPublicId(invalidReason.publicId()).orElseThrow().scanState());
        assertFalse(uploads.markQuarantined("missing", "REJECTED", 10));

        KnowledgeUploadSessionRepository.UploadRecord expiring = createUpload();
        assertFalse(uploads.markExpired("missing", 10));
        assertTrue(uploads.markExpired(expiring.publicId(), 10));
        assertFalse(uploads.markExpired(expiring.publicId(), 10));
    }

    @Test
    void ingestionJobRepositoryValidatesWorkerInputsAndFailsClosedOnStaleOwnership() {
        KnowledgeVersionRepository.KnowledgeVersion version = createPendingVersion("job-edge-one");
        List<InvalidCall> invalidCalls = List.of(
                () -> jobs.enqueue(0, 10, NOW),
                () -> jobs.enqueue(version.id(), 0, NOW),
                () -> jobs.enqueue(version.id(), 10, null),
                () -> jobs.claimNext(null, NOW),
                () -> jobs.claimNext(" ", NOW),
                () -> jobs.claimNext("x".repeat(129), NOW),
                () -> jobs.claimNext("worker", null),
                () -> jobs.claim(null, "worker", NOW),
                () -> jobs.claim(" ", "worker", NOW),
                () -> jobs.claim("missing", null, NOW),
                () -> jobs.claim("missing", " ", NOW),
                () -> jobs.claim("missing", "x".repeat(129), NOW),
                () -> jobs.claim("missing", "worker", null),
                () -> jobs.fail("missing", "worker", null, "summary", NOW),
                () -> jobs.fail("missing", "worker", "x", "summary", NOW),
                () -> jobs.fail("missing", "worker", "bad-code", "summary", NOW),
                () -> jobs.fail("missing", "worker", "SCAN_FAILED", null, NOW),
                () -> jobs.fail("missing", "worker", "SCAN_FAILED", " ", NOW),
                () -> jobs.fail("missing", "worker", "SCAN_FAILED", "x".repeat(501), NOW),
                () -> jobs.requeue(null, "worker", NOW),
                () -> jobs.requeue(" ", "worker", NOW),
                () -> jobs.requeue("missing", null, NOW),
                () -> jobs.requeue("missing", " ", NOW),
                () -> jobs.requeue("missing", "worker", null),
                () -> jobs.dead(null, "RETRY", NOW),
                () -> jobs.dead(" ", "RETRY", NOW),
                () -> jobs.dead("missing", null, NOW),
                () -> jobs.dead("missing", "bad-code", NOW),
                () -> jobs.dead("missing", "RETRY", null),
                () -> jobs.succeed(null, "worker", NOW),
                () -> jobs.succeed(" ", "worker", NOW),
                () -> jobs.succeed("missing", null, NOW),
                () -> jobs.succeed("missing", " ", NOW),
                () -> jobs.succeed("missing", "worker", null));
        for (InvalidCall invalid : invalidCalls) {
            assertThrows(IllegalArgumentException.class, invalid::run);
        }

        assertTrue(jobs.findByPublicId(" ").isEmpty());
        assertThrows(IllegalStateException.class, () -> jobs.dead("missing", "RETRY", NOW));

        KnowledgeIngestionJobRepository.IngestionJob queued = jobs.enqueue(version.id(), 10, NOW);
        KnowledgeIngestionJobRepository.IngestionJob running = jobs.claim(
                queued.publicId(), "worker-a", NOW).orElseThrow();
        jobs.succeed(running.publicId(), "worker-a", NOW.plusSeconds(1));
        assertEquals("SUCCEEDED", jobs.claim(running.publicId(), "worker-b", NOW.plusSeconds(2))
                .orElseThrow().state());
        jobs.dead(running.publicId(), "RETRY", NOW.plusSeconds(3));
        assertEquals("SUCCEEDED", jobs.findByPublicId(running.publicId()).orElseThrow().state());

        KnowledgeVersionRepository.KnowledgeVersion secondVersion = createPendingVersion("job-edge-two");
        KnowledgeIngestionJobRepository.IngestionJob second = jobs.enqueue(secondVersion.id(), 10, NOW);
        jobs.claim(second.publicId(), "worker-dead", NOW).orElseThrow();
        jobs.dead(second.publicId(), "RETRY", NOW.plusSeconds(1));
        jobs.dead(second.publicId(), "RETRY", NOW.plusSeconds(2));
        assertEquals("DEAD", jobs.findByPublicId(second.publicId()).orElseThrow().state());

        KnowledgeVersionRepository.KnowledgeVersion staleVersion = createPendingVersion("job-edge-stale");
        KnowledgeIngestionJobRepository.IngestionJob stale = jobs.enqueue(staleVersion.id(), 10, NOW);
        jdbc.update("UPDATE ai_ingestion_job SET version=version+1 WHERE public_id=?", stale.publicId());
        JdbcKnowledgeIngestionJobRepository staleView = new JdbcKnowledgeIngestionJobRepository(jdbc) {
            @Override
            public java.util.Optional<IngestionJob> findByPublicId(String publicId) {
                if (stale.publicId().equals(publicId)) return java.util.Optional.of(stale);
                return super.findByPublicId(publicId);
            }
        };
        assertThrows(IllegalStateException.class,
                () -> staleView.claim(stale.publicId(), "worker-stale", NOW));
    }

    private KnowledgeSourceRepository.CreateSource createSourceCommand(
            String publicId,
            String name,
            String sourceType,
            long owner,
            DataClassification classification,
            PermissionMatchMode matchMode,
            String objectStore,
            long actor) {
        return new KnowledgeSourceRepository.CreateSource(publicId, name, sourceType, owner, classification,
                matchMode, objectStore, Set.of("repair:read"), actor);
    }

    private KnowledgeSourceRepository.UpdateSource updateSourceCommand(
            String publicId,
            long expectedVersion,
            String name,
            DataClassification classification,
            PermissionMatchMode matchMode,
            String status,
            long actor) {
        return new KnowledgeSourceRepository.UpdateSource(publicId, expectedVersion, name, classification,
                matchMode, status, Set.of("repair:read"), actor);
    }

    private KnowledgeUploadSessionRepository.CreateUpload uploadCommand(
            String publicId,
            long sourceId,
            long owner,
            String objectKey,
            String hash,
            long size,
            String mimeType,
            Instant expiresAt,
            long actor) {
        return new KnowledgeUploadSessionRepository.CreateUpload(
                publicId, sourceId, owner, objectKey, hash, size, mimeType, expiresAt, actor);
    }

    private KnowledgeUploadSessionRepository.UploadRecord createUpload() {
        String publicId = UUID.randomUUID().toString();
        return uploads.create(uploadCommand(publicId, source.id(), 10, "quarantine/" + publicId,
                HASH, 4, "text/plain", NOW.plusSeconds(60), 10));
    }

    private ObjectStoragePort.StoredObject storedObject(KnowledgeUploadSessionRepository.UploadRecord upload) {
        return new ObjectStoragePort.StoredObject(
                new ObjectStoragePort.ObjectReference(upload.quarantineObjectKey(), "version-1", "etag-1"),
                HASH, 4, Map.of());
    }

    private KnowledgeVersionRepository.KnowledgeVersion createPendingVersion(String text) {
        String uploadId = UUID.randomUUID().toString();
        String hash = sha256(text);
        persistFinalizedUpload(uploadId, hash, text.getBytes(StandardCharsets.UTF_8).length);
        return versions.createPending(new KnowledgeVersionRepository.CreateVersion(
                source.publicId(), uploadId, NOW, sha256(UUID.randomUUID().toString()), 1,
                "title", "v1", hash, KnowledgeVisibility.EXPLICIT_ACL,
                new ObjectStoragePort.ObjectReference("sha256/" + hash, "object-version-1", "etag-1"),
                "text/plain", text.getBytes(StandardCharsets.UTF_8).length,
                "plain-text-v1", "paragraph-2000-v1", 10));
    }

    private void persistFinalizedUpload(String uploadId, String hash, long sizeBytes) {
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

    @FunctionalInterface
    private interface InvalidCall {
        void run();
    }
}
