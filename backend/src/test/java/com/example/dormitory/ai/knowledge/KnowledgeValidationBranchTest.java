package com.example.dormitory.ai.knowledge;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.port.EmbeddingGateway;
import com.example.dormitory.ai.port.ObjectStoragePort;
import com.example.dormitory.ai.port.VectorIndexPort;
import com.example.dormitory.ai.security.DataClassification;
import com.example.dormitory.ai.security.PiiClassificationService;
import com.example.dormitory.ai.security.PiiRedactionService;
import com.example.dormitory.ai.security.PromptInjectionGuard;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeValidationBranchTest {

    private static final Instant NOW = Instant.parse("2026-07-13T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String HASH = "a".repeat(64);
    private static final String OTHER_HASH = "b".repeat(64);

    @Test
    void uploadServiceRejectsEachUnsupportedAdapterCapability() {
        KnowledgeUploadSessionRepository repository = mock(KnowledgeUploadSessionRepository.class);
        KnowledgeFileScanner scanner = mock(KnowledgeFileScanner.class);

        assertAll(
                () -> assertThrows(IllegalStateException.class,
                        () -> new UploadSessionService(storageStatus(false, true, true), repository, scanner, CLOCK)),
                () -> assertThrows(IllegalStateException.class,
                        () -> new UploadSessionService(storageStatus(true, false, true), repository, scanner, CLOCK)),
                () -> assertThrows(IllegalStateException.class,
                        () -> new UploadSessionService(storageStatus(true, true, false), repository, scanner, CLOCK)));
    }

    @Test
    void uploadCreationRejectsEveryIndependentContractViolationAndNormalizesHash() {
        KnowledgeUploadSessionRepository repository = mock(KnowledgeUploadSessionRepository.class);
        UploadSessionService service = uploadService(repository);
        when(repository.create(any())).thenAnswer(invocation -> {
            KnowledgeUploadSessionRepository.CreateUpload command = invocation.getArgument(0);
            return new KnowledgeUploadSessionRepository.UploadRecord(
                    1, command.publicId(), command.sourceId(), command.ownerUserId(), "source-a",
                    command.quarantineObjectKey(), command.expectedSha256(), null, command.expectedSizeBytes(),
                    null, command.declaredMimeType(), null, "CREATED", "PENDING", null, null,
                    command.expiresAt(), null, null, 0, NOW);
        });

        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> service.create(1, 7, "source-a", null, 4, "text/plain", NOW.plusSeconds(60))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> service.create(1, 7, "source-a", "bad", 4, "text/plain", NOW.plusSeconds(60))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> service.create(0, 7, "source-a", HASH, 4, "text/plain", NOW.plusSeconds(60))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> service.create(1, 0, "source-a", HASH, 4, "text/plain", NOW.plusSeconds(60))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> service.create(1, 7, null, HASH, 4, "text/plain", NOW.plusSeconds(60))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> service.create(1, 7, " ", HASH, 4, "text/plain", NOW.plusSeconds(60))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> service.create(1, 7, "source-a", HASH, 0, "text/plain", NOW.plusSeconds(60))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> service.create(1, 7, "source-a", HASH,
                                UploadSessionService.MAXIMUM_TEXT_BYTES + 1, "text/plain", NOW.plusSeconds(60))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> service.create(1, 7, "source-a", HASH, 4, null, NOW.plusSeconds(60))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> service.create(1, 7, "source-a", HASH, 4, "application/pdf", NOW.plusSeconds(60))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> service.create(1, 7, "source-a", HASH, 4, "text/plain", null)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> service.create(1, 7, "source-a", HASH, 4, "text/plain", NOW)),
                () -> assertEquals(HASH, service.create(
                        1, 7, "source-a", HASH.toUpperCase(), 4, "TEXT/PLAIN", NOW.plusSeconds(60)).expectedSha256()));

        verify(repository).create(any());
    }

    @Test
    void uploadAndFinalizeRejectOwnershipLengthStateAndIncompleteTerminalFacts() {
        KnowledgeUploadSessionRepository repository = mock(KnowledgeUploadSessionRepository.class);
        UploadSessionService service = uploadService(repository);

        when(repository.findByPublicId("missing")).thenReturn(Optional.empty());
        when(repository.findByPublicId("owned")).thenReturn(Optional.of(uploadRecord(
                "owned", "CREATED", "PENDING", true, null, null, null, NOW.plusSeconds(60), NOW)));
        when(repository.findByPublicId("expired")).thenReturn(Optional.of(uploadRecord(
                "expired", "CREATED", "PENDING", true, null, null, null, NOW.minusSeconds(1), NOW)));

        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> service.get("missing")),
                () -> assertThrows(SecurityException.class,
                        () -> service.upload(8, "owned", new ByteArrayInputStream(new byte[4]), 4)),
                () -> assertThrows(IllegalArgumentException.class, () -> service.upload(7, "missing", null, 4)),
                () -> assertThrows(IllegalArgumentException.class, () -> service.upload(7, "owned", null, 4)),
                () -> assertThrows(UploadPayloadTooLargeException.class,
                        () -> service.upload(7, "owned", new ByteArrayInputStream(new byte[5]), 5)),
                () -> assertThrows(UploadPayloadTooLargeException.class,
                        () -> service.upload(7, "owned", new ByteArrayInputStream(new byte[1]),
                                UploadSessionService.MAXIMUM_TEXT_BYTES + 1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> service.upload(7, "owned", new ByteArrayInputStream(new byte[3]), 3)),
                () -> assertThrows(IllegalStateException.class,
                        () -> service.upload(7, "expired", new ByteArrayInputStream(new byte[4]), 4)));

        assertFinalizeRejected(service, repository, uploadRecord(
                "wrong-source", "UPLOADED", "PENDING", true, HASH, 4L, NOW, NOW.plusSeconds(60), NOW),
                "another-source", SecurityException.class);
        assertFinalizeRejected(service, repository, uploadRecord(
                "quarantined", "QUARANTINED", "REJECTED", true, HASH, 4L, NOW, NOW.plusSeconds(60), NOW),
                "source-a", KnowledgeQuarantinedException.class);
        assertFinalizeRejected(service, repository, uploadRecord(
                "not-uploaded", "CREATED", "PENDING", true, null, null, null, NOW.plusSeconds(60), NOW),
                "source-a", IllegalStateException.class);
        assertFinalizeRejected(service, repository, uploadRecord(
                "no-reference", "UPLOADED", "PENDING", false, null, null, NOW, NOW.plusSeconds(60), NOW),
                "source-a", IllegalStateException.class);
        assertFinalizeRejected(service, repository, uploadRecord(
                "write-open", "UPLOADED", "PENDING", true, null, null, null, NOW.plusSeconds(60), NOW),
                "source-a", IllegalStateException.class);

        assertFinalizeRejected(service, repository, uploadRecord(
                "final-no-reference", "FINALIZED", "SCANNED_CLEAN", false, HASH, 4L, NOW,
                NOW.plusSeconds(60), NOW), "source-a", IllegalStateException.class);
        assertFinalizeRejected(service, repository, uploadRecord(
                "final-no-hash", "FINALIZED", "SCANNED_CLEAN", true, null, 4L, NOW,
                NOW.plusSeconds(60), NOW), "source-a", IllegalStateException.class);
        assertFinalizeRejected(service, repository, uploadRecord(
                "final-no-size", "FINALIZED", "SCANNED_CLEAN", true, HASH, null, NOW,
                NOW.plusSeconds(60), NOW), "source-a", IllegalStateException.class);
        assertFinalizeRejected(service, repository, uploadRecord(
                "final-no-time", "FINALIZED", "SCANNED_CLEAN", true, HASH, 4L, NOW,
                NOW.plusSeconds(60), null), "source-a", IllegalStateException.class);
    }

    @Test
    void promotionRequiresEveryPersistedSafetyFact() {
        KnowledgeUploadSessionRepository repository = mock(KnowledgeUploadSessionRepository.class);
        UploadSessionService service = uploadService(repository);

        List<KnowledgeUploadSessionRepository.UploadRecord> invalid = List.of(
                uploadRecord("not-final", "SCANNED_CLEAN", "SCANNED_CLEAN", true, HASH, 4L, NOW,
                        NOW.plusSeconds(60), NOW),
                uploadRecord("not-clean", "FINALIZED", "PENDING", true, HASH, 4L, NOW,
                        NOW.plusSeconds(60), NOW),
                uploadRecord("missing-reference", "FINALIZED", "SCANNED_CLEAN", false, HASH, 4L, NOW,
                        NOW.plusSeconds(60), NOW),
                uploadRecord("missing-hash", "FINALIZED", "SCANNED_CLEAN", true, null, 4L, NOW,
                        NOW.plusSeconds(60), NOW),
                uploadRecord("missing-size", "FINALIZED", "SCANNED_CLEAN", true, HASH, null, NOW,
                        NOW.plusSeconds(60), NOW));
        for (KnowledgeUploadSessionRepository.UploadRecord record : invalid) {
            when(repository.findByPublicId(record.publicId())).thenReturn(Optional.of(record));
            assertThrows(IllegalStateException.class,
                    () -> service.promoteFinalized(7, "source-a", record.publicId()), record.publicId());
        }
    }

    @Test
    void ingestionEnqueueRejectsEveryIndependentUploadAndCommandViolation() {
        KnowledgeSourceRepository sources = mock(KnowledgeSourceRepository.class);
        KnowledgeVersionRepository versions = mock(KnowledgeVersionRepository.class);
        KnowledgeIngestionJobRepository jobs = mock(KnowledgeIngestionJobRepository.class);
        KnowledgeIngestionService service = ingestionService(sources, versions, jobs, ignored -> true);
        UploadSessionService.FinalizedUpload validUpload = finalizedUpload(
                "upload", "source", reference(), HASH, 4, NOW);
        KnowledgeIngestionService.EnqueueCommand validCommand = command(HASH, 1, "标题", "v1",
                KnowledgeVisibility.EXPLICIT_ACL, "plain-text-v1", "paragraph-2000-v1", 7);

        List<UploadSessionService.FinalizedUpload> invalidUploads = java.util.Arrays.asList(
                null,
                finalizedUpload(null, "source", reference(), HASH, 4, NOW),
                finalizedUpload(" ", "source", reference(), HASH, 4, NOW),
                finalizedUpload("upload", null, reference(), HASH, 4, NOW),
                finalizedUpload("upload", " ", reference(), HASH, 4, NOW),
                finalizedUpload("upload", "source", null, HASH, 4, NOW),
                finalizedUpload("upload", "source", reference(), null, 4, NOW),
                finalizedUpload("upload", "source", reference(), "bad", 4, NOW),
                finalizedUpload("upload", "source", reference(), HASH, 0, NOW),
                finalizedUpload("upload", "source", reference(), HASH, 4, null));
        for (UploadSessionService.FinalizedUpload upload : invalidUploads) {
            assertThrows(IllegalArgumentException.class, () -> service.enqueue(upload, validCommand));
        }

        List<KnowledgeIngestionService.EnqueueCommand> invalidCommands = java.util.Arrays.asList(
                null,
                command(null, 1, "标题", "v1", KnowledgeVisibility.EXPLICIT_ACL,
                        "plain-text-v1", "paragraph-2000-v1", 7),
                command("bad", 1, "标题", "v1", KnowledgeVisibility.EXPLICIT_ACL,
                        "plain-text-v1", "paragraph-2000-v1", 7),
                command(HASH, 0, "标题", "v1", KnowledgeVisibility.EXPLICIT_ACL,
                        "plain-text-v1", "paragraph-2000-v1", 7),
                command(HASH, 1, null, "v1", KnowledgeVisibility.EXPLICIT_ACL,
                        "plain-text-v1", "paragraph-2000-v1", 7),
                command(HASH, 1, " ", "v1", KnowledgeVisibility.EXPLICIT_ACL,
                        "plain-text-v1", "paragraph-2000-v1", 7),
                command(HASH, 1, "标题", null, KnowledgeVisibility.EXPLICIT_ACL,
                        "plain-text-v1", "paragraph-2000-v1", 7),
                command(HASH, 1, "标题", " ", KnowledgeVisibility.EXPLICIT_ACL,
                        "plain-text-v1", "paragraph-2000-v1", 7),
                command(HASH, 1, "标题", "v1", null,
                        "plain-text-v1", "paragraph-2000-v1", 7),
                command(HASH, 1, "标题", "v1", KnowledgeVisibility.EXPLICIT_ACL,
                        null, "paragraph-2000-v1", 7),
                command(HASH, 1, "标题", "v1", KnowledgeVisibility.EXPLICIT_ACL,
                        "wrong-parser", "paragraph-2000-v1", 7),
                command(HASH, 1, "标题", "v1", KnowledgeVisibility.EXPLICIT_ACL,
                        "plain-text-v1", null, 7),
                command(HASH, 1, "标题", "v1", KnowledgeVisibility.EXPLICIT_ACL,
                        "plain-text-v1", "wrong-policy", 7),
                command(HASH, 1, "标题", "v1", KnowledgeVisibility.EXPLICIT_ACL,
                        "plain-text-v1", "paragraph-2000-v1", 0));
        for (KnowledgeIngestionService.EnqueueCommand invalid : invalidCommands) {
            assertThrows(IllegalArgumentException.class, () -> service.enqueue(validUpload, invalid));
        }
    }

    @Test
    void ingestionEnqueueAndReadFailClosedForMissingPausedL3AndDisabledSources() {
        KnowledgeSourceRepository sources = mock(KnowledgeSourceRepository.class);
        KnowledgeVersionRepository versions = mock(KnowledgeVersionRepository.class);
        KnowledgeIngestionJobRepository jobs = mock(KnowledgeIngestionJobRepository.class);
        UploadSessionService.FinalizedUpload upload = finalizedUpload(
                "upload", "source", reference(), HASH, 4, NOW);
        KnowledgeIngestionService.EnqueueCommand command = command(HASH, 1, "标题", "v1",
                KnowledgeVisibility.EXPLICIT_ACL, "plain-text-v1", "paragraph-2000-v1", 7);

        when(sources.findByPublicId("source")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> ingestionService(sources, versions, jobs, ignored -> true).enqueue(upload, command));

        when(sources.findByPublicId("source")).thenReturn(Optional.of(source("PAUSED", DataClassification.L1)));
        assertThrows(IllegalArgumentException.class,
                () -> ingestionService(sources, versions, jobs, ignored -> true).enqueue(upload, command));

        when(sources.findByPublicId("source")).thenReturn(Optional.of(source("ACTIVE", DataClassification.L3)));
        assertThrows(IllegalArgumentException.class,
                () -> ingestionService(sources, versions, jobs, ignored -> true).enqueue(upload, command));

        when(sources.findByPublicId("source")).thenReturn(Optional.of(source("ACTIVE", DataClassification.L1)));
        assertThrows(AiApiException.class,
                () -> ingestionService(sources, versions, jobs, ignored -> false).enqueue(upload, command));

        KnowledgeIngestionService readable = ingestionService(sources, versions, jobs, ignored -> true);
        when(versions.findByPublicId("missing")).thenReturn(Optional.empty());
        assertFalse(readable.canRead("missing", null));
    }

    @Test
    void safeKnowledgeValidatesEveryDocumentFieldAndPersistentPortCombination() {
        SafeKnowledgeService service = safeKnowledgeService();
        List<SafeKnowledgeService.KnowledgeDocument> invalid = java.util.Arrays.asList(
                document(null, "doc", "v1", HASH, "标题", "正文", KnowledgeVisibility.EXPLICIT_ACL,
                        PermissionMatchMode.ANY),
                document("source", " ", "v1", HASH, "标题", "正文", KnowledgeVisibility.EXPLICIT_ACL,
                        PermissionMatchMode.ANY),
                document("source", "doc", " ", HASH, "标题", "正文", KnowledgeVisibility.EXPLICIT_ACL,
                        PermissionMatchMode.ANY),
                document("source", "doc", "v1", " ", "标题", "正文", KnowledgeVisibility.EXPLICIT_ACL,
                        PermissionMatchMode.ANY),
                document("source", "doc", "v1", HASH, " ", "正文", KnowledgeVisibility.EXPLICIT_ACL,
                        PermissionMatchMode.ANY),
                document("source", "doc", "v1", HASH, "标题", " ", KnowledgeVisibility.EXPLICIT_ACL,
                        PermissionMatchMode.ANY),
                document("source", "doc", "v1", HASH, "标题", "正文", null, PermissionMatchMode.ANY),
                document("source", "doc", "v1", HASH, "标题", "正文", KnowledgeVisibility.EXPLICIT_ACL, null));
        for (SafeKnowledgeService.KnowledgeDocument document : invalid) {
            assertThrows(IllegalArgumentException.class, () -> service.registerApprovedText(document));
        }

        KnowledgeSourceRepository sources = mock(KnowledgeSourceRepository.class);
        KnowledgeVersionRepository versions = mock(KnowledgeVersionRepository.class);
        EmbeddingGateway embeddings = mock(EmbeddingGateway.class);
        VectorIndexPort index = mock(VectorIndexPort.class);
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> persistentSafeKnowledge(null, versions, embeddings, index)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> persistentSafeKnowledge(sources, null, embeddings, index)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> persistentSafeKnowledge(sources, versions, null, index)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> persistentSafeKnowledge(sources, versions, embeddings, null)));
    }

    private UploadSessionService uploadService(KnowledgeUploadSessionRepository repository) {
        return new UploadSessionService(storageStatus(true, true, true), repository,
                mock(KnowledgeFileScanner.class), CLOCK);
    }

    private ObjectStoragePort storageStatus(boolean conditional, boolean immutable, boolean streaming) {
        ObjectStoragePort storage = mock(ObjectStoragePort.class);
        when(storage.status()).thenReturn(new ObjectStoragePort.AdapterStatus(
                "test-storage", false, conditional, immutable, streaming, false));
        return storage;
    }

    private KnowledgeUploadSessionRepository.UploadRecord uploadRecord(
            String publicId,
            String state,
            String scanState,
            boolean hasReference,
            String observedHash,
            Long observedSize,
            Instant writeRevokedAt,
            Instant expiresAt,
            Instant updatedAt) {
        return new KnowledgeUploadSessionRepository.UploadRecord(
                1, publicId, 2, 7, "source-a", "quarantine/" + publicId, HASH, observedHash,
                4, observedSize, "text/plain", "SCANNED_CLEAN".equals(scanState) ? "text/plain" : null,
                state, scanState, hasReference ? "version-1" : null, hasReference ? "etag-1" : null,
                expiresAt, writeRevokedAt, null, 1, updatedAt);
    }

    private void assertFinalizeRejected(
            UploadSessionService service,
            KnowledgeUploadSessionRepository repository,
            KnowledgeUploadSessionRepository.UploadRecord record,
            String sourceId,
            Class<? extends Throwable> type) {
        when(repository.findByPublicId(record.publicId())).thenReturn(Optional.of(record));
        assertThrows(type, () -> service.finalizeUpload(7, sourceId, record.publicId()), record.publicId());
    }

    private KnowledgeIngestionService ingestionService(
            KnowledgeSourceRepository sources,
            KnowledgeVersionRepository versions,
            KnowledgeIngestionJobRepository jobs,
            Predicate<String> sourceEnabled) {
        return new KnowledgeIngestionService(sources, versions, jobs, mock(ObjectStoragePort.class),
                new PromptInjectionGuard(), mock(PiiClassificationService.class), CLOCK, sourceEnabled);
    }

    private UploadSessionService.FinalizedUpload finalizedUpload(
            String uploadId,
            String sourceId,
            ObjectStoragePort.ObjectReference reference,
            String hash,
            long size,
            Instant finalizedAt) {
        return new UploadSessionService.FinalizedUpload(uploadId, sourceId, reference, hash, size, finalizedAt);
    }

    private ObjectStoragePort.ObjectReference reference() {
        return new ObjectStoragePort.ObjectReference("sha256/" + HASH, "version-1", "etag-1");
    }

    private KnowledgeIngestionService.EnqueueCommand command(
            String externalKeyHmac,
            int keyVersion,
            String title,
            String version,
            KnowledgeVisibility visibility,
            String parser,
            String chunkPolicy,
            long actor) {
        return new KnowledgeIngestionService.EnqueueCommand(
                externalKeyHmac, keyVersion, title, version, visibility, parser, chunkPolicy, actor);
    }

    private KnowledgeSourceRepository.KnowledgeSource source(String status, DataClassification classification) {
        return new KnowledgeSourceRepository.KnowledgeSource(
                1, "source", "来源", "UPLOAD", 7, classification,
                PermissionMatchMode.ANY, "object-v1", 1, status, Set.of("repair:read"));
    }

    private SafeKnowledgeService safeKnowledgeService() {
        return new SafeKnowledgeService(new KnowledgeAclPolicy(), new PromptInjectionGuard(),
                new PiiRedactionService("fixture-key".getBytes(StandardCharsets.UTF_8), "v1"));
    }

    private SafeKnowledgeService.KnowledgeDocument document(
            String sourceId,
            String documentId,
            String versionId,
            String contentHash,
            String title,
            String content,
            KnowledgeVisibility visibility,
            PermissionMatchMode matchMode) {
        return new SafeKnowledgeService.KnowledgeDocument(
                sourceId, documentId, versionId, contentHash, title, content,
                visibility, matchMode, Set.of("repair:read"), true);
    }

    private SafeKnowledgeService persistentSafeKnowledge(
            KnowledgeSourceRepository sources,
            KnowledgeVersionRepository versions,
            EmbeddingGateway embeddings,
            VectorIndexPort index) {
        return new SafeKnowledgeService(new KnowledgeAclPolicy(), new PromptInjectionGuard(),
                new PiiRedactionService("fixture-key".getBytes(StandardCharsets.UTF_8), "v1"),
                ignored -> true, sources, versions, embeddings, index);
    }
}
