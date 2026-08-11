package com.example.dormitory.ai.knowledge;

import com.example.dormitory.ai.infrastructure.persistence.JdbcAiOutboxRepository;
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
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcKnowledgeVersionRepositoryBranchTest {

    private static final Instant NOW = Instant.parse("2026-07-13T00:00:00Z");

    private JdbcTemplate jdbc;
    private JdbcKnowledgeSourceRepository sources;
    private JdbcKnowledgeVersionRepository versions;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:knowledge-version-branches-" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql"),
                new ClassPathResource("ai-schema.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO sys_user (id,username,password_hash,display_name,role_code,enabled,deleted) "
                + "VALUES (10,'owner','fixture-hash','Owner','ADMIN',TRUE,FALSE)");
        jdbc.update("INSERT INTO sys_permission (code,name,module) VALUES ('repair:read','repair:read','AI_TEST')");
        JdbcAiOutboxRepository outbox = new JdbcAiOutboxRepository(jdbc);
        sources = new JdbcKnowledgeSourceRepository(jdbc, new KnowledgeAclPolicy(), outbox);
        versions = new JdbcKnowledgeVersionRepository(jdbc, sources, outbox);
    }

    @Test
    void createPendingRejectsMalformedCommandInactiveSourceAndDuplicateDocumentVersion() {
        assertThrows(IllegalArgumentException.class, () -> versions.createPending(null));
        KnowledgeSourceRepository.KnowledgeSource source = source(DataClassification.L1);
        assertThrows(IllegalArgumentException.class, () -> versions.createPending(new KnowledgeVersionRepository.CreateVersion(
                source.publicId(), UUID.randomUUID().toString(), NOW, sha256("external"), 1,
                "title", "v1", sha256("content"), KnowledgeVisibility.EXPLICIT_ACL,
                new ObjectStoragePort.ObjectReference("sha256/" + sha256("content"), "v1", "etag"),
                "application/pdf", 7, "plain-v1", "chunk-v1", 10)));

        String firstUpload = finalizedUpload(source, "same-content");
        versions.createPending(command(source, firstUpload, "same-document", "v1", "same-content",
                KnowledgeVisibility.EXPLICIT_ACL));
        String duplicateUpload = finalizedUpload(source, "same-content");
        assertThrows(IllegalStateException.class, () -> versions.createPending(command(
                source, duplicateUpload, "same-document", "v1", "same-content",
                KnowledgeVisibility.EXPLICIT_ACL)));

        jdbc.update("UPDATE ai_knowledge_source SET status='PAUSED' WHERE public_id=?", source.publicId());
        assertThrows(IllegalArgumentException.class, () -> versions.createPending(command(
                source, UUID.randomUUID().toString(), "paused", "v1", "paused-content",
                KnowledgeVisibility.EXPLICIT_ACL)));
    }

    @Test
    void createPendingRejectsMismatchedUploadFactsAndAlreadyLinkedSession() {
        KnowledgeSourceRepository.KnowledgeSource source = source(DataClassification.L1);
        String mismatched = finalizedUpload(source, "actual-content");
        assertThrows(IllegalStateException.class, () -> versions.createPending(command(
                source, mismatched, "mismatch", "v1", "different-content",
                KnowledgeVisibility.EXPLICIT_ACL)));

        String firstUpload = finalizedUpload(source, "first-content");
        KnowledgeVersionRepository.KnowledgeVersion first = versions.createPending(command(
                source, firstUpload, "first-document", "v1", "first-content",
                KnowledgeVisibility.EXPLICIT_ACL));
        String linkedUpload = finalizedUpload(source, "second-content");
        jdbc.update("UPDATE ai_upload_session SET finalized_document_version_id=? WHERE public_id=?",
                first.id(), linkedUpload);

        assertThrows(IllegalStateException.class, () -> versions.createPending(command(
                source, linkedUpload, "second-document", "v1", "second-content",
                KnowledgeVisibility.EXPLICIT_ACL)));
    }

    @Test
    void chunkAndQuarantineOperationsRejectMissingMalformedAndActiveVersions() {
        KnowledgeSourceRepository.KnowledgeSource source = source(DataClassification.L1);
        KnowledgeVersionRepository.KnowledgeVersion pending = pending(
                source, "chunk-document", "v1", "chunk-content", KnowledgeVisibility.EXPLICIT_ACL);

        assertThrows(IllegalArgumentException.class, () -> versions.replaceChunksAndMarkReady(
                "missing", List.of(chunk(0, "content")), 10));
        assertThrows(IllegalArgumentException.class, () -> versions.replaceChunksAndMarkReady(
                pending.publicId(), List.of(chunk(0, "content")), 0));
        assertThrows(IllegalArgumentException.class, () -> versions.replaceChunksAndMarkReady(
                pending.publicId(), Arrays.asList((KnowledgeVersionRepository.NewChunk) null), 10));
        assertThrows(IllegalArgumentException.class, () -> versions.replaceChunksAndMarkReady(
                pending.publicId(), List.of(new KnowledgeVersionRepository.NewChunk(
                        0, " ", sha256("blank"), "p:0", "{}")), 10));

        versions.replaceChunksAndMarkReady(pending.publicId(), List.of(chunk(0, "content")), 10);
        versions.activate(pending.publicId(), 10);
        assertThrows(IllegalStateException.class, () -> versions.markQuarantined(pending.publicId(), 10));
        assertThrows(IllegalArgumentException.class, () -> versions.markQuarantined("missing", 10));
    }

    @Test
    void publicApprovalRejectsInvalidArgumentsVersionStateAndCurrentSourcePolicy() {
        KnowledgeSourceRepository.KnowledgeSource l0 = source(DataClassification.L0);
        KnowledgeVersionRepository.KnowledgeVersion pending = pending(
                l0, "public-pending", "v1", "public-content", KnowledgeVisibility.PUBLIC_APPROVED);

        assertThrows(IllegalArgumentException.class,
                () -> versions.approvePublic(pending.publicId(), 0, 1, "public-v1"));
        assertThrows(IllegalArgumentException.class,
                () -> versions.approvePublic(pending.publicId(), 20, 0, "public-v1"));
        assertThrows(IllegalArgumentException.class,
                () -> versions.approvePublic(pending.publicId(), 20, 1, " "));
        assertThrows(IllegalArgumentException.class,
                () -> versions.approvePublic("missing", 20, 1, "public-v1"));
        assertThrows(IllegalStateException.class,
                () -> versions.approvePublic(pending.publicId(), 20, 2, "public-v1"));
        assertThrows(IllegalArgumentException.class,
                () -> versions.publicApprovalPreview("missing"));

        versions.replaceChunksAndMarkReady(pending.publicId(), List.of(chunk(0, "public-content")), 10);
        assertThrows(IllegalStateException.class,
                () -> versions.approvePublic(pending.publicId(), 10, 3, "public-v1"));
        jdbc.update("UPDATE ai_knowledge_source SET status='PAUSED' WHERE public_id=?", l0.publicId());
        assertThrows(IllegalStateException.class,
                () -> versions.approvePublic(pending.publicId(), 20, 4, "public-v1"));

        KnowledgeSourceRepository.KnowledgeSource l1 = source(DataClassification.L1);
        KnowledgeVersionRepository.KnowledgeVersion explicit = pending(
                l1, "explicit-ready", "v1", "explicit-content", KnowledgeVisibility.EXPLICIT_ACL);
        versions.replaceChunksAndMarkReady(explicit.publicId(), List.of(chunk(0, "explicit-content")), 10);
        assertThrows(IllegalStateException.class,
                () -> versions.approvePublic(explicit.publicId(), 20, 5, "public-v1"));
    }

    @Test
    void activationRetirementAndReadChecksFailClosedAcrossMissingAndUnapprovedStates() {
        KnowledgeSourceRepository.KnowledgeSource source = source(DataClassification.L1);
        KnowledgeVersionRepository.KnowledgeVersion pending = pending(
                source, "lifecycle", "v1", "lifecycle-content", KnowledgeVisibility.EXPLICIT_ACL);

        assertThrows(IllegalArgumentException.class, () -> versions.activate(pending.publicId(), 0));
        assertThrows(IllegalArgumentException.class, () -> versions.activate("missing", 10));
        assertThrows(IllegalStateException.class, () -> versions.activate(pending.publicId(), 10));
        assertThrows(IllegalArgumentException.class, () -> versions.retire(pending.publicId(), 0));
        assertThrows(IllegalArgumentException.class, () -> versions.retire("missing", 10));
        assertThrows(IllegalStateException.class, () -> versions.retire(pending.publicId(), 10));
        assertFalse(versions.canRead("missing", Set.of("repair:read")));
        assertFalse(versions.canRead(pending.publicId(), Set.of("repair:read")));

        versions.replaceChunksAndMarkReady(pending.publicId(), List.of(chunk(0, "lifecycle-content")), 10);
        versions.activate(pending.publicId(), 10);
        assertTrue(versions.canRead(pending.publicId(), Set.of("repair:read")));
        assertFalse(versions.canRead(pending.publicId(), Set.of()));

        KnowledgeSourceRepository.KnowledgeSource publicSource = source(DataClassification.L0);
        KnowledgeVersionRepository.KnowledgeVersion publicReady = pending(
                publicSource, "unapproved-public", "v1", "unapproved-content",
                KnowledgeVisibility.PUBLIC_APPROVED);
        versions.replaceChunksAndMarkReady(publicReady.publicId(), List.of(chunk(0, "unapproved-content")), 10);
        assertThrows(IllegalStateException.class, () -> versions.activate(publicReady.publicId(), 20));
    }

    private KnowledgeSourceRepository.KnowledgeSource source(DataClassification classification) {
        String publicId = UUID.randomUUID().toString();
        Set<String> permissions = classification == DataClassification.L0 ? Set.of() : Set.of("repair:read");
        return sources.create(new KnowledgeSourceRepository.CreateSource(
                publicId, "source", "UPLOAD", 10, classification,
                PermissionMatchMode.ALL, "object-v1", permissions, 10));
    }

    private KnowledgeVersionRepository.KnowledgeVersion pending(
            KnowledgeSourceRepository.KnowledgeSource source,
            String externalKey,
            String version,
            String content,
            KnowledgeVisibility visibility) {
        String uploadId = finalizedUpload(source, content);
        return versions.createPending(command(source, uploadId, externalKey, version, content, visibility));
    }

    private KnowledgeVersionRepository.CreateVersion command(
            KnowledgeSourceRepository.KnowledgeSource source,
            String uploadId,
            String externalKey,
            String version,
            String content,
            KnowledgeVisibility visibility) {
        String hash = sha256(content);
        return new KnowledgeVersionRepository.CreateVersion(
                source.publicId(), uploadId, NOW, sha256(externalKey), 1,
                "title", version, hash, visibility,
                new ObjectStoragePort.ObjectReference("sha256/" + hash, "object-v1", "etag-v1"),
                "text/plain", content.getBytes(StandardCharsets.UTF_8).length,
                "plain-v1", "paragraph-v1", 10);
    }

    private String finalizedUpload(KnowledgeSourceRepository.KnowledgeSource source, String content) {
        String uploadId = UUID.randomUUID().toString();
        String hash = sha256(content);
        long size = content.getBytes(StandardCharsets.UTF_8).length;
        jdbc.update("INSERT INTO ai_upload_session(public_id,source_id,owner_user_id,quarantine_object_key,"
                        + "object_version_id,object_etag,expected_sha256,observed_sha256,expected_size_bytes,"
                        + "observed_size_bytes,declared_mime_type,detected_mime_type,scan_state,state,expires_at,"
                        + "write_revoked_at,version,created_operator_user_id,updated_operator_user_id,created_at,updated_at) "
                        + "VALUES(?,?,?,?,?,?,?, ?,?,?,'text/plain','text/plain','SCANNED_CLEAN','FINALIZED',"
                        + "DATEADD('MINUTE',5,CURRENT_TIMESTAMP),CURRENT_TIMESTAMP,0,10,10,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                uploadId, source.id(), 10, "quarantine/" + uploadId, "object-v1", "etag-v1",
                hash, hash, size, size);
        return uploadId;
    }

    private KnowledgeVersionRepository.NewChunk chunk(int number, String content) {
        return new KnowledgeVersionRepository.NewChunk(number, content, sha256(content), "p:" + number, "{}");
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
