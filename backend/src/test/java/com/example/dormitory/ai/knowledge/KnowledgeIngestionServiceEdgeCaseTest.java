package com.example.dormitory.ai.knowledge;

import com.example.dormitory.ai.port.ObjectStoragePort;
import com.example.dormitory.ai.security.DataClassification;
import com.example.dormitory.ai.security.PiiClassificationService;
import com.example.dormitory.ai.security.PiiRedactionService;
import com.example.dormitory.ai.security.PromptInjectionGuard;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeIngestionServiceEdgeCaseTest {

    private static final Instant NOW = Instant.parse("2026-07-14T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String HASH = "a".repeat(64);

    @Test
    void processingSurfacesShortCircuitMissingAndTerminalJobs() {
        KnowledgeSourceRepository sources = mock(KnowledgeSourceRepository.class);
        KnowledgeVersionRepository versions = mock(KnowledgeVersionRepository.class);
        KnowledgeIngestionJobRepository jobs = mock(KnowledgeIngestionJobRepository.class);
        KnowledgeIngestionService service = service(sources, versions, jobs, ignored -> true, null);

        when(jobs.claimNext("worker", NOW)).thenReturn(Optional.empty());
        assertTrue(service.processNext("worker").isEmpty());

        when(jobs.claim("missing", "worker", NOW)).thenReturn(Optional.empty());
        when(jobs.claim("succeeded", "worker", NOW)).thenReturn(Optional.of(job("succeeded", "SUCCEEDED")));
        when(jobs.claim("failed", "worker", NOW)).thenReturn(Optional.of(job("failed", "FAILED")));
        assertTrue(service.processJob("missing", "worker").isEmpty());
        assertTrue(service.processJob("succeeded", "worker").isEmpty());
        assertTrue(service.processJob("failed", "worker").isEmpty());

        when(jobs.findByPublicId("unknown")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> service.processJob("unknown", "version", "worker"));

        KnowledgeIngestionJobRepository.IngestionJob linked = job("linked", "QUEUED");
        when(jobs.findByPublicId("linked")).thenReturn(Optional.of(linked));
        when(versions.findById(linked.documentVersionId())).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class,
                () -> service.processJob("linked", "version", "worker"));

        KnowledgeVersionRepository.KnowledgeVersion version = version("version", "source", "safe");
        when(versions.findById(linked.documentVersionId())).thenReturn(Optional.of(version));
        assertThrows(SecurityException.class,
                () -> service.processJob("linked", "another-version", "worker"));
        when(jobs.claim("linked", "worker", NOW)).thenReturn(Optional.of(job("linked", "SUCCEEDED")));
        assertTrue(service.processJob("linked", "version", "worker").isEmpty());
    }

    @Test
    void deadLetterAndReadChecksFailClosedWithoutMutatingTerminalJobs() {
        KnowledgeSourceRepository sources = mock(KnowledgeSourceRepository.class);
        KnowledgeVersionRepository versions = mock(KnowledgeVersionRepository.class);
        KnowledgeIngestionJobRepository jobs = mock(KnowledgeIngestionJobRepository.class);
        KnowledgeIngestionService service = service(sources, versions, jobs, ignored -> true, null);

        when(jobs.findByPublicId("succeeded")).thenReturn(Optional.of(job("succeeded", "SUCCEEDED")));
        when(jobs.findByPublicId("failed")).thenReturn(Optional.of(job("failed", "FAILED")));
        service.markDead("succeeded", "RETRY");
        service.markDead("failed", "RETRY");
        verify(jobs, never()).dead(anyString(), anyString(), any());
        verify(versions, never()).markQuarantined(anyString(), anyLong());

        KnowledgeVersionRepository.KnowledgeVersion readable = version("readable", "source", "safe");
        when(versions.findByPublicId("readable")).thenReturn(Optional.of(readable));
        when(versions.canRead("readable", Set.of())).thenReturn(true);
        assertTrue(service.canRead("readable", null));
        verify(versions).canRead("readable", Set.of());

        KnowledgeIngestionService disabled = service(sources, versions, jobs, ignored -> false, null);
        assertFalse(disabled.canRead("readable", Set.of("repair:read")));
        verify(versions, never()).canRead("readable", Set.of("repair:read"));
    }

    @Test
    void chunkingPreservesSurrogatePairsAndParagraphBoundaries() {
        String surrogateBoundary = "x".repeat(1_999) + "\uD83D\uDE00" + "z";
        ProcessingFixture surrogate = processingFixture("safe original", surrogateBoundary, null);
        KnowledgeIngestionService.ProcessedIngestion surrogateResult = surrogate.execute();
        assertEquals(2, surrogateResult.chunkCount());
        assertEquals(1_999, surrogate.chunks().get(0).contentRedacted().length());
        assertEquals("\uD83D\uDE00z", surrogate.chunks().get(1).contentRedacted());

        String joinedParagraphs = "a".repeat(1_000) + "\n\n" + "b".repeat(500);
        ProcessingFixture joined = processingFixture("safe original", joinedParagraphs, null);
        assertEquals(1, joined.execute().chunkCount());
        assertTrue(joined.chunks().getFirst().contentRedacted().contains("\n\n"));

        String splitParagraphs = "a".repeat(1_000) + "\n\n" + "b".repeat(1_500);
        ProcessingFixture split = processingFixture("safe original", splitParagraphs, null);
        assertEquals(2, split.execute().chunkCount());
        assertEquals(1_000, split.chunks().getFirst().contentRedacted().length());
        assertEquals(1_500, split.chunks().getLast().contentRedacted().length());
    }

    @Test
    void blankRedactionAndUnsafePlainTextAreQuarantinedAsIntegrityFailures() {
        ProcessingFixture emptyRedaction = processingFixture("safe original", " \n\n ", null);
        assertThrows(KnowledgeQuarantinedException.class, emptyRedaction::execute);
        verify(emptyRedaction.versions()).markQuarantined("version", 7);
        verify(emptyRedaction.jobs()).fail(
                "job", "worker", "OBJECT_INTEGRITY", "知识对象完整性校验失败", NOW);

        ProcessingFixture blankObject = processingFixture("   ", "ignored", null);
        assertThrows(KnowledgeQuarantinedException.class, blankObject::execute);
        verify(blankObject.jobs()).fail(
                "job", "worker", "OBJECT_INTEGRITY", "知识对象完整性校验失败", NOW);

        ProcessingFixture nullCharacter = processingFixture("safe\u0000text", "ignored", null);
        assertThrows(KnowledgeQuarantinedException.class, nullCharacter::execute);
        verify(nullCharacter.jobs()).fail(
                "job", "worker", "OBJECT_INTEGRITY", "知识对象完整性校验失败", NOW);
    }

    @Test
    void optionalVectorIndexFailureDoesNotRollBackDurableChunks() {
        SafeKnowledgeService retrieval = mock(SafeKnowledgeService.class);
        doThrow(new IllegalStateException("index unavailable")).when(retrieval).indexVersion("version");
        ProcessingFixture fixture = processingFixture("safe original", "redacted safe content", retrieval);

        assertEquals(1, fixture.execute().chunkCount());
        verify(retrieval).indexVersion("version");
        verify(fixture.jobs()).succeed("job", "worker", NOW);
        verify(fixture.versions(), never()).markQuarantined(anyString(), anyLong());
    }

    private KnowledgeIngestionService service(
            KnowledgeSourceRepository sources,
            KnowledgeVersionRepository versions,
            KnowledgeIngestionJobRepository jobs,
            java.util.function.Predicate<String> sourceEnabled,
            SafeKnowledgeService retrieval) {
        return new KnowledgeIngestionService(
                sources, versions, jobs, mock(ObjectStoragePort.class), new PromptInjectionGuard(),
                mock(PiiClassificationService.class), CLOCK, sourceEnabled, retrieval);
    }

    private ProcessingFixture processingFixture(
            String originalText,
            String redactedText,
            SafeKnowledgeService retrieval) {
        KnowledgeSourceRepository sources = mock(KnowledgeSourceRepository.class);
        KnowledgeVersionRepository versions = mock(KnowledgeVersionRepository.class);
        KnowledgeIngestionJobRepository jobs = mock(KnowledgeIngestionJobRepository.class);
        ObjectStoragePort storage = mock(ObjectStoragePort.class);
        PiiClassificationService classification = mock(PiiClassificationService.class);
        byte[] bytes = originalText.getBytes(StandardCharsets.UTF_8);
        String hash = sha256(bytes);
        ObjectStoragePort.ObjectReference reference = new ObjectStoragePort.ObjectReference(
                "sha256/" + hash, "version-1", "etag-1");
        KnowledgeVersionRepository.KnowledgeVersion version = version(
                "version", "source", hash, bytes.length, reference);
        KnowledgeIngestionJobRepository.IngestionJob job = job("job", "RUNNING");
        ObjectStoragePort.StoredObject object = new ObjectStoragePort.StoredObject(
                reference, hash, bytes.length, Map.of("quarantine", "false"));

        when(jobs.claim("job", "worker", NOW)).thenReturn(Optional.of(job));
        when(versions.findById(job.documentVersionId())).thenReturn(Optional.of(version));
        when(sources.findByPublicId("source")).thenReturn(Optional.of(source()));
        when(storage.get(reference, bytes.length)).thenReturn(Optional.of(
                new ObjectStoragePort.StoredObjectContent(object, bytes)));
        when(classification.redact(originalText, "knowledge-ingestion:source", DataClassification.L1))
                .thenReturn(redaction(redactedText));
        AtomicReference<List<KnowledgeVersionRepository.NewChunk>> captured = new AtomicReference<>(List.of());
        doAnswer(invocation -> {
            captured.set(List.copyOf(invocation.getArgument(1)));
            return null;
        }).when(versions).replaceChunksAndMarkReady(anyString(), any(), anyLong());
        KnowledgeIngestionService service = new KnowledgeIngestionService(
                sources, versions, jobs, storage, new PromptInjectionGuard(), classification,
                CLOCK, ignored -> true, retrieval);
        return new ProcessingFixture(service, versions, jobs, captured);
    }

    private KnowledgeSourceRepository.KnowledgeSource source() {
        return new KnowledgeSourceRepository.KnowledgeSource(
                1, "source", "source", "UPLOAD", 7, DataClassification.L1,
                PermissionMatchMode.ANY, "object-v1", 1, "ACTIVE", Set.of("repair:read"));
    }

    private KnowledgeVersionRepository.KnowledgeVersion version(
            String publicId, String sourceId, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        String hash = sha256(bytes);
        return version(publicId, sourceId, hash, bytes.length,
                new ObjectStoragePort.ObjectReference("sha256/" + hash, "version-1", "etag-1"));
    }

    private KnowledgeVersionRepository.KnowledgeVersion version(
            String publicId,
            String sourceId,
            String hash,
            long size,
            ObjectStoragePort.ObjectReference reference) {
        return new KnowledgeVersionRepository.KnowledgeVersion(
                1, publicId, 1, "document", 1, sourceId, "v1", hash,
                KnowledgeVisibility.EXPLICIT_ACL, reference, "text/plain", size,
                "plain-text-v1", "paragraph-2000-v1", "PENDING", null);
    }

    private KnowledgeIngestionJobRepository.IngestionJob job(String publicId, String state) {
        return new KnowledgeIngestionJobRepository.IngestionJob(
                1, publicId, 1, state, 1, "worker", 7, 0,
                NOW, NOW, null, null);
    }

    private PiiRedactionService.RedactionResult redaction(String text) {
        return new PiiRedactionService.RedactionResult(
                text, DataClassification.L1, List.of(), "pii-v1", "key-v1", HASH);
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.GeneralSecurityException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private record ProcessingFixture(
            KnowledgeIngestionService service,
            KnowledgeVersionRepository versions,
            KnowledgeIngestionJobRepository jobs,
            AtomicReference<List<KnowledgeVersionRepository.NewChunk>> captured) {
        KnowledgeIngestionService.ProcessedIngestion execute() {
            return service.processJob("job", "worker").orElseThrow();
        }

        List<KnowledgeVersionRepository.NewChunk> chunks() {
            return captured.get();
        }
    }
}
