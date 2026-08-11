package com.example.dormitory.ai.knowledge;

import com.example.dormitory.ai.infrastructure.fake.InMemoryVersionedObjectStorage;
import com.example.dormitory.ai.port.ObjectStoragePort;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UploadSessionServiceBoundaryBranchTest {

    private static final Instant NOW = Instant.parse("2026-07-13T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String SESSION_ID = "a78235de-545e-48e7-bcd7-afce368520f9";
    private static final String SOURCE_ID = "source-a";
    private static final String QUARANTINE_KEY = "quarantine/" + SESSION_ID;
    private static final byte[] CONTENT = "text".getBytes(StandardCharsets.UTF_8);
    private static final String HASH = InMemoryVersionedObjectStorage.sha256(CONTENT);
    private static final String OTHER_HASH = "b".repeat(64);
    private static final ObjectStoragePort.ObjectReference REFERENCE =
            new ObjectStoragePort.ObjectReference(QUARANTINE_KEY, "version-1", "etag-1");

    @Test
    void uploadTreatsOnlyEquivalentConcurrentCompletionAsSuccess() {
        KnowledgeUploadSessionRepository.UploadRecord equivalent = record(
                "UPLOADED", "PENDING", REFERENCE, HASH, null, 4, null, NOW, 3);
        assertDoesNotThrow(() -> uploadWithLostCompletionCas(equivalent));

        List<KnowledgeUploadSessionRepository.UploadRecord> divergent = List.of(
                record("UPLOADING", "PENDING", null, HASH, null, 4, null, null, 3),
                record("UPLOADED", "PENDING", null, HASH, null, 4, null, NOW, 3),
                record("UPLOADED", "PENDING",
                        new ObjectStoragePort.ObjectReference(QUARANTINE_KEY, "version-2", "etag-2"),
                        HASH, null, 4, null, NOW, 3),
                record("UPLOADED", "PENDING", REFERENCE, HASH, null, 5, null, NOW, 3),
                record("UPLOADED", "PENDING", REFERENCE, OTHER_HASH, null, 4, null, NOW, 3));
        for (KnowledgeUploadSessionRepository.UploadRecord current : divergent) {
            UploadTransition transition = uploadWithLostCompletionCasFixture(current);
            assertThrows(IllegalStateException.class, transition::execute, current.toString());
            verify(transition.repository()).markQuarantined(SESSION_ID, "UPLOAD_STATE_CONFLICT", 7);
        }
    }

    @Test
    void uploadFailsClosedForPreWriteCasAndStorageAdapterFailures() {
        KnowledgeUploadSessionRepository repository = mock(KnowledgeUploadSessionRepository.class);
        ObjectStoragePort storage = storage();
        KnowledgeFileScanner scanner = mock(KnowledgeFileScanner.class);
        KnowledgeUploadSessionRepository.UploadRecord created = createdRecord();
        when(repository.findByPublicId(SESSION_ID)).thenReturn(Optional.of(created));
        UploadSessionService casConflict = new UploadSessionService(storage, repository, scanner, CLOCK);

        assertThrows(IllegalStateException.class,
                () -> casConflict.upload(7, SESSION_ID, new ByteArrayInputStream(CONTENT), CONTENT.length));

        repository = mock(KnowledgeUploadSessionRepository.class);
        storage = storage();
        scanner = mock(KnowledgeFileScanner.class);
        when(repository.findByPublicId(SESSION_ID)).thenReturn(Optional.of(created), Optional.of(created));
        when(repository.markUploading(anyString(), anyLong(), any(), anyLong())).thenReturn(true);
        UploadSessionService stateConflict = new UploadSessionService(storage, repository, scanner, CLOCK);
        assertThrows(IllegalStateException.class,
                () -> stateConflict.upload(7, SESSION_ID, new ByteArrayInputStream(CONTENT), CONTENT.length));

        assertStorageWriteFailure(StorageFailure.TOO_LARGE, UploadPayloadTooLargeException.class,
                "UPLOAD_TOO_LARGE");
        assertStorageWriteFailure(StorageFailure.RUNTIME, KnowledgeQuarantinedException.class,
                "CONDITIONAL_PUT_FAILED");
        assertStorageWriteFailure(StorageFailure.NULL_RESULT, KnowledgeQuarantinedException.class,
                "CONDITIONAL_CREATE_CONFLICT");
    }

    @Test
    void finalizeMapsScannerRejectionsToDurableFailureCodes() {
        Map<String, String> rejectionCodes = Map.of(
                "输入不是合法 UTF-8 文本", "INVALID_UTF8",
                "固定对象 checksum 不匹配", "CHECKSUM_MISMATCH",
                "固定对象 size 不匹配", "SIZE_MISMATCH",
                "固定对象 metadata 不匹配", "METADATA_MISMATCH",
                "固定对象 version/ETag 已变化", "OBJECT_VERSION_CHANGED",
                "检测到二进制签名", "BINARY_SIGNATURE",
                "内容安全检查拒绝", "CONTENT_REJECTED",
                "隔离对象固定版本不存在", "OBJECT_MISSING",
                "其他扫描拒绝", "SCAN_REJECTED");

        rejectionCodes.forEach((message, code) -> {
            FinalizeFixture fixture = finalizeFixture();
            when(fixture.scanner().scan(any(), anyLong()))
                    .thenThrow(new KnowledgeQuarantinedException(message));
            assertThrows(KnowledgeQuarantinedException.class, fixture::execute, message);
            verify(fixture.repository()).markQuarantined(SESSION_ID, code, 7);
        });

        FinalizeFixture runtimeFailure = finalizeFixture();
        when(runtimeFailure.scanner().scan(any(), anyLong()))
                .thenThrow(new IllegalStateException("scanner offline"));
        assertThrows(KnowledgeQuarantinedException.class, runtimeFailure::execute);
        verify(runtimeFailure.repository()).markQuarantined(SESSION_ID, "OBJECT_UNAVAILABLE", 7);
    }

    @Test
    void finalizeRejectsMimeAndSizeIndependently() {
        FinalizeFixture wrongMime = finalizeFixture();
        stubSuccessfulScan(wrongMime.scanner(), "application/pdf", CONTENT.length);
        assertThrows(KnowledgeQuarantinedException.class, wrongMime::execute);
        verify(wrongMime.repository()).markQuarantined(SESSION_ID, "MIME_OR_SIZE_REJECTED", 7);

        FinalizeFixture wrongSize = finalizeFixture();
        stubSuccessfulScan(wrongSize.scanner(), "text/plain", CONTENT.length - 1);
        assertThrows(KnowledgeQuarantinedException.class, wrongSize::execute);
        verify(wrongSize.repository()).markQuarantined(SESSION_ID, "MIME_OR_SIZE_REJECTED", 7);
    }

    @Test
    void finalizeResolvesEachLostCasFromThePersistedTerminalFact() {
        KnowledgeUploadSessionRepository.UploadRecord finalized = record(
                "FINALIZED", "SCANNED_CLEAN", REFERENCE, HASH, HASH, 4, 4L, NOW, 5);
        KnowledgeUploadSessionRepository.UploadRecord quarantined = record(
                "QUARANTINED", "REJECTED", REFERENCE, HASH, HASH, 4, 4L, NOW, 5);
        KnowledgeUploadSessionRepository.UploadRecord invalid = record(
                "CREATED", "PENDING", REFERENCE, HASH, HASH, 4, 4L, NOW, 5);

        assertEquals(HASH, scanningCasLost(finalized).observedSha256());
        assertThrows(KnowledgeQuarantinedException.class, () -> scanningCasLost(quarantined));
        assertThrows(IllegalStateException.class, () -> scanningCasLost(invalid));

        assertEquals(HASH, scannedCleanCasLost(finalized).observedSha256());
        assertThrows(KnowledgeQuarantinedException.class, () -> scannedCleanCasLost(quarantined));
        assertThrows(IllegalStateException.class, () -> scannedCleanCasLost(invalid));

        assertEquals(HASH, finalizedCasLost(finalized).observedSha256());
        assertThrows(KnowledgeQuarantinedException.class, () -> finalizedCasLost(quarantined));
        assertThrows(IllegalStateException.class, () -> finalizedCasLost(invalid));
    }

    @Test
    void promotionRejectsDeduplicatedObjectsWithoutProvenanceAndAdapterFailures() {
        assertPromotionRejected(Map.of("quarantine", "false", "promotedFromUpload", "other"), false,
                "OBJECT_PROMOTION_REJECTED");
        assertPromotionRejected(Map.of("quarantine", "false", "source", "other-source"), false,
                "OBJECT_PROMOTION_REJECTED");
        assertPromotionRejected(Map.of("quarantine", "false", "source", SOURCE_ID,
                "promotedFromUpload", "other"), true, "OBJECT_PROMOTION_REJECTED");
        assertPromotionRejected(null, false, "OBJECT_PROMOTION_REJECTED");
        assertPromotionRejected(Map.of(), false, "OBJECT_PROMOTION_FAILED");
    }

    private void uploadWithLostCompletionCas(KnowledgeUploadSessionRepository.UploadRecord current) {
        uploadWithLostCompletionCasFixture(current).execute();
    }

    private UploadTransition uploadWithLostCompletionCasFixture(
            KnowledgeUploadSessionRepository.UploadRecord current) {
        KnowledgeUploadSessionRepository repository = mock(KnowledgeUploadSessionRepository.class);
        ObjectStoragePort storage = storage();
        KnowledgeFileScanner scanner = mock(KnowledgeFileScanner.class);
        KnowledgeUploadSessionRepository.UploadRecord uploading = record(
                "UPLOADING", "PENDING", null, HASH, null, 4, null, null, 2);
        when(repository.findByPublicId(SESSION_ID)).thenReturn(
                Optional.of(createdRecord()), Optional.of(uploading), Optional.of(current));
        when(repository.markUploading(anyString(), anyLong(), any(), anyLong())).thenReturn(true);
        when(storage.putIfAbsent(any())).thenReturn(new ObjectStoragePort.PutResult(storedObject(), true));
        when(repository.markUploaded(anyString(), anyLong(), any(), any(), anyLong())).thenReturn(false);
        UploadSessionService service = new UploadSessionService(storage, repository, scanner, CLOCK);
        return new UploadTransition(repository, () ->
                service.upload(7, SESSION_ID, new ByteArrayInputStream(CONTENT), CONTENT.length));
    }

    private void assertStorageWriteFailure(
            StorageFailure failure,
            Class<? extends Throwable> expectedType,
            String expectedCode) {
        KnowledgeUploadSessionRepository repository = mock(KnowledgeUploadSessionRepository.class);
        ObjectStoragePort storage = storage();
        KnowledgeFileScanner scanner = mock(KnowledgeFileScanner.class);
        KnowledgeUploadSessionRepository.UploadRecord uploading = record(
                "UPLOADING", "PENDING", null, HASH, null, 4, null, null, 2);
        when(repository.findByPublicId(SESSION_ID)).thenReturn(
                Optional.of(createdRecord()), Optional.of(uploading));
        when(repository.markUploading(anyString(), anyLong(), any(), anyLong())).thenReturn(true);
        switch (failure) {
            case TOO_LARGE -> when(storage.putIfAbsent(any()))
                    .thenThrow(new ObjectStoragePort.ObjectTooLargeException("too large"));
            case RUNTIME -> when(storage.putIfAbsent(any()))
                    .thenThrow(new ObjectStoragePort.ObjectStorageException("offline"));
            case NULL_RESULT -> when(storage.putIfAbsent(any())).thenReturn(null);
        }
        UploadSessionService service = new UploadSessionService(storage, repository, scanner, CLOCK);

        assertThrows(expectedType,
                () -> service.upload(7, SESSION_ID, new ByteArrayInputStream(CONTENT), CONTENT.length));
        verify(repository).markQuarantined(SESSION_ID, expectedCode, 7);
    }

    private FinalizeFixture finalizeFixture() {
        KnowledgeUploadSessionRepository repository = mock(KnowledgeUploadSessionRepository.class);
        ObjectStoragePort storage = storage();
        KnowledgeFileScanner scanner = mock(KnowledgeFileScanner.class);
        KnowledgeUploadSessionRepository.UploadRecord uploaded = record(
                "UPLOADED", "PENDING", REFERENCE, HASH, null, 4, null, NOW, 3);
        KnowledgeUploadSessionRepository.UploadRecord scanning = record(
                "SCANNING", "SCANNING", REFERENCE, HASH, HASH, 4, 4L, NOW, 4);
        when(repository.findByPublicId(SESSION_ID)).thenReturn(Optional.of(uploaded), Optional.of(scanning));
        when(repository.markScanning(anyString(), anyLong(), anyString(), anyLong(), anyLong())).thenReturn(true);
        when(storage.open(REFERENCE)).thenAnswer(ignored -> Optional.of(storedStream()));
        UploadSessionService service = new UploadSessionService(storage, repository, scanner, CLOCK);
        return new FinalizeFixture(repository, scanner,
                () -> service.finalizeUpload(7, SOURCE_ID, SESSION_ID));
    }

    private UploadSessionService.FinalizedUpload scanningCasLost(
            KnowledgeUploadSessionRepository.UploadRecord current) {
        KnowledgeUploadSessionRepository repository = mock(KnowledgeUploadSessionRepository.class);
        ObjectStoragePort storage = storage();
        KnowledgeFileScanner scanner = mock(KnowledgeFileScanner.class);
        KnowledgeUploadSessionRepository.UploadRecord uploaded = record(
                "UPLOADED", "PENDING", REFERENCE, HASH, null, 4, null, NOW, 3);
        when(repository.findByPublicId(SESSION_ID)).thenReturn(Optional.of(uploaded), Optional.of(current));
        when(storage.open(REFERENCE)).thenAnswer(ignored -> Optional.of(storedStream()));
        return new UploadSessionService(storage, repository, scanner, CLOCK)
                .finalizeUpload(7, SOURCE_ID, SESSION_ID);
    }

    private UploadSessionService.FinalizedUpload scannedCleanCasLost(
            KnowledgeUploadSessionRepository.UploadRecord current) {
        KnowledgeUploadSessionRepository repository = mock(KnowledgeUploadSessionRepository.class);
        ObjectStoragePort storage = storage();
        KnowledgeFileScanner scanner = mock(KnowledgeFileScanner.class);
        KnowledgeUploadSessionRepository.UploadRecord scanning = record(
                "SCANNING", "SCANNING", REFERENCE, HASH, HASH, 4, 4L, NOW, 4);
        when(repository.findByPublicId(SESSION_ID)).thenReturn(Optional.of(scanning), Optional.of(current));
        when(storage.open(REFERENCE)).thenAnswer(ignored -> Optional.of(storedStream()));
        stubSuccessfulScan(scanner, "text/plain", 4);
        return new UploadSessionService(storage, repository, scanner, CLOCK)
                .finalizeUpload(7, SOURCE_ID, SESSION_ID);
    }

    private UploadSessionService.FinalizedUpload finalizedCasLost(
            KnowledgeUploadSessionRepository.UploadRecord current) {
        KnowledgeUploadSessionRepository repository = mock(KnowledgeUploadSessionRepository.class);
        ObjectStoragePort storage = storage();
        KnowledgeFileScanner scanner = mock(KnowledgeFileScanner.class);
        KnowledgeUploadSessionRepository.UploadRecord scanning = record(
                "SCANNING", "SCANNING", REFERENCE, HASH, HASH, 4, 4L, NOW, 4);
        KnowledgeUploadSessionRepository.UploadRecord clean = record(
                "SCANNED_CLEAN", "SCANNED_CLEAN", REFERENCE, HASH, HASH, 4, 4L, NOW, 5);
        when(repository.findByPublicId(SESSION_ID)).thenReturn(
                Optional.of(scanning), Optional.of(clean), Optional.of(current));
        when(repository.markScannedClean(anyString(), anyLong(), anyString(), anyLong())).thenReturn(true);
        when(storage.open(REFERENCE)).thenAnswer(ignored -> Optional.of(storedStream()));
        stubSuccessfulScan(scanner, "text/plain", 4);
        return new UploadSessionService(storage, repository, scanner, CLOCK)
                .finalizeUpload(7, SOURCE_ID, SESSION_ID);
    }

    private void assertPromotionRejected(
            Map<String, String> metadata,
            boolean created,
            String expectedCode) {
        KnowledgeUploadSessionRepository repository = mock(KnowledgeUploadSessionRepository.class);
        ObjectStoragePort storage = storage();
        KnowledgeFileScanner scanner = mock(KnowledgeFileScanner.class);
        KnowledgeUploadSessionRepository.UploadRecord finalized = record(
                "FINALIZED", "SCANNED_CLEAN", REFERENCE, HASH, HASH, 4, 4L, NOW, 5);
        when(repository.findByPublicId(SESSION_ID)).thenReturn(Optional.of(finalized));
        when(storage.open(REFERENCE)).thenAnswer(ignored -> Optional.of(storedStream()));
        if (metadata == null) {
            when(storage.putIfAbsent(any())).thenReturn(null);
        } else if (metadata.isEmpty()) {
            when(storage.putIfAbsent(any())).thenThrow(new ObjectStoragePort.ObjectStorageException("offline"));
        } else {
            ObjectStoragePort.StoredObject promoted = new ObjectStoragePort.StoredObject(
                    new ObjectStoragePort.ObjectReference("sha256/" + HASH, "final-version", "final-etag"),
                    HASH, 4, metadata);
            when(storage.putIfAbsent(any())).thenReturn(new ObjectStoragePort.PutResult(promoted, created));
        }
        UploadSessionService service = new UploadSessionService(storage, repository, scanner, CLOCK);

        assertThrows(KnowledgeQuarantinedException.class,
                () -> service.promoteFinalized(7, SOURCE_ID, SESSION_ID));
        verify(repository).markQuarantined(SESSION_ID, expectedCode, 7);
    }

    private ObjectStoragePort storage() {
        ObjectStoragePort storage = mock(ObjectStoragePort.class);
        when(storage.status()).thenReturn(new ObjectStoragePort.AdapterStatus(
                "test-storage", false, true, true, true, false));
        return storage;
    }

    private void stubSuccessfulScan(KnowledgeFileScanner scanner, String mimeType, long observedSize) {
        when(scanner.scan(any(), anyLong())).thenAnswer(invocation -> {
            ((java.io.InputStream) invocation.getArgument(0)).readAllBytes();
            return new KnowledgeFileScanner.ScanResult(mimeType, observedSize);
        });
    }

    private ObjectStoragePort.StoredObject storedObject() {
        return new ObjectStoragePort.StoredObject(REFERENCE, HASH, 4,
                Map.of("source", SOURCE_ID, "quarantine", "true", "uploadSession", SESSION_ID));
    }

    private ObjectStoragePort.StoredObjectStream storedStream() {
        return new ObjectStoragePort.StoredObjectStream(storedObject(), new ByteArrayInputStream(CONTENT));
    }

    private KnowledgeUploadSessionRepository.UploadRecord createdRecord() {
        return record("CREATED", "PENDING", null, HASH, null, 4, null, null, 1);
    }

    private KnowledgeUploadSessionRepository.UploadRecord record(
            String state,
            String scanState,
            ObjectStoragePort.ObjectReference reference,
            String expectedHash,
            String observedHash,
            long expectedSize,
            Long observedSize,
            Instant writeRevokedAt,
            long version) {
        return new KnowledgeUploadSessionRepository.UploadRecord(
                1, SESSION_ID, 1, 7, SOURCE_ID, QUARANTINE_KEY, expectedHash, observedHash,
                expectedSize, observedSize, "text/plain",
                "SCANNED_CLEAN".equals(scanState) ? "text/plain" : null,
                state, scanState, reference == null ? null : reference.versionId(),
                reference == null ? null : reference.etag(), NOW.plusSeconds(60), writeRevokedAt,
                null, version, NOW);
    }

    private enum StorageFailure { TOO_LARGE, RUNTIME, NULL_RESULT }

    private record UploadTransition(KnowledgeUploadSessionRepository repository, Runnable action) {
        void execute() { action.run(); }
    }

    private record FinalizeFixture(
            KnowledgeUploadSessionRepository repository,
            KnowledgeFileScanner scanner,
            FinalizeAction action) {
        UploadSessionService.FinalizedUpload execute() { return action.execute(); }
    }

    @FunctionalInterface
    private interface FinalizeAction {
        UploadSessionService.FinalizedUpload execute();
    }
}
