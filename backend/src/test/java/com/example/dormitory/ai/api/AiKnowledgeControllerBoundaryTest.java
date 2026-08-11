package com.example.dormitory.ai.api;

import com.example.dormitory.ai.application.control.IdempotencyPayloadMismatchException;
import com.example.dormitory.ai.application.run.AiActorContext;
import com.example.dormitory.ai.application.run.AiActorResolver;
import com.example.dormitory.ai.approval.ActionProposalService;
import com.example.dormitory.ai.config.AiRuntimeControlService;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.AiCapability;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiIdempotencyRepository;
import com.example.dormitory.ai.knowledge.KnowledgeExternalKeyHasher;
import com.example.dormitory.ai.knowledge.KnowledgeFileScanner;
import com.example.dormitory.ai.knowledge.KnowledgeIngestionDispatcher;
import com.example.dormitory.ai.knowledge.KnowledgeIngestionJobRepository;
import com.example.dormitory.ai.knowledge.KnowledgeIngestionService;
import com.example.dormitory.ai.knowledge.KnowledgeSourceRepository;
import com.example.dormitory.ai.knowledge.KnowledgeUploadSessionRepository;
import com.example.dormitory.ai.knowledge.KnowledgeVersionRepository;
import com.example.dormitory.ai.knowledge.KnowledgeVisibility;
import com.example.dormitory.ai.knowledge.PermissionMatchMode;
import com.example.dormitory.ai.knowledge.UploadSessionService;
import com.example.dormitory.ai.knowledge.UploadSessionState;
import com.example.dormitory.ai.port.ObjectStoragePort;
import com.example.dormitory.ai.security.DataClassification;
import com.example.dormitory.ai.security.RecentAuthenticationPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiKnowledgeControllerBoundaryTest {

    private static final String SOURCE_ID = "947ea40c-0a37-452e-b5e5-a8bbf525f676";
    private static final String READABLE_SOURCE_ID = "52d58994-2960-4a63-8f84-bb55308218cc";
    private static final String HIDDEN_SOURCE_ID = "ff02da3a-5428-48ce-883d-d73f3903a705";
    private static final String UPLOAD_ID = "2e90407c-f464-4a07-a82a-57c771b77f3c";
    private static final String VERSION_ID = "86a3b98f-72d0-4a87-a8c2-6d515b884f4b";
    private static final String HASH = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-07-13T00:00:00Z");

    private final KnowledgeSourceRepository sources = mock(KnowledgeSourceRepository.class);
    private final KnowledgeVersionRepository versions = mock(KnowledgeVersionRepository.class);
    private final KnowledgeIngestionJobRepository jobs = mock(KnowledgeIngestionJobRepository.class);
    private final KnowledgeUploadSessionRepository uploadFacts = mock(KnowledgeUploadSessionRepository.class);
    private final UploadSessionService uploads = mock(UploadSessionService.class);
    private final KnowledgeIngestionService ingestion = mock(KnowledgeIngestionService.class);
    private final KnowledgeExternalKeyHasher externalKeys = mock(KnowledgeExternalKeyHasher.class);
    private final AiActorResolver actors = mock(AiActorResolver.class);
    private final AiRuntimeControlService controls = mock(AiRuntimeControlService.class);
    private final JdbcAiIdempotencyRepository idempotency = mock(JdbcAiIdempotencyRepository.class);
    private final RecentAuthenticationPolicy recentAuthentication = mock(RecentAuthenticationPolicy.class);
    private final KnowledgeIngestionDispatcher dispatcher = mock(KnowledgeIngestionDispatcher.class);
    private final RecordingTransactionRunner transactions = new RecordingTransactionRunner();
    private final AiKnowledgeController controller = new AiKnowledgeController(
            sources, versions, jobs, uploadFacts, uploads, ingestion, externalKeys, actors, controls,
            idempotency, recentAuthentication, dispatcher, transactions);

    @BeforeEach
    void defaults() {
        when(controls.capabilityEnabled(AiCapability.KNOWLEDGE)).thenReturn(true);
        when(controls.sourceEnabled(anyString())).thenReturn(true);
        when(actors.current(anyString())).thenReturn(actor(7, false,
                "ai:knowledge:read", "ai:knowledge:manage", "repair:read"));
        when(sources.findByPublicId(SOURCE_ID)).thenReturn(Optional.of(source(SOURCE_ID, 7)));
    }

    @Test
    void listAndReadOnlyExposeOwnedOrAclReadableSources() {
        KnowledgeSourceRepository.KnowledgeSource owned = source(SOURCE_ID, 7);
        KnowledgeSourceRepository.KnowledgeSource readable = source(READABLE_SOURCE_ID, 8);
        KnowledgeSourceRepository.KnowledgeSource hidden = source(HIDDEN_SOURCE_ID, 9);
        when(sources.findAll()).thenReturn(List.of(owned, readable, hidden));
        when(sources.canRead(anyString(), any())).thenAnswer(invocation ->
                READABLE_SOURCE_ID.equals(invocation.getArgument(0)));

        var firstPage = controller.listSources(1, 1).getBody();
        var secondPage = controller.listSources(2, 1).getBody();

        assertNotNull(firstPage);
        assertNotNull(secondPage);
        assertEquals(List.of(SOURCE_ID), firstPage.data().records().stream()
                .map(AiKnowledgeController.SourceResponse::id).toList());
        assertEquals(List.of(READABLE_SOURCE_ID), secondPage.data().records().stream()
                .map(AiKnowledgeController.SourceResponse::id).toList());
        assertEquals(2, firstPage.data().total());

        when(actors.current("ai:knowledge:read")).thenReturn(actor(10, false, "ai:knowledge:read"));
        when(sources.findByPublicId(HIDDEN_SOURCE_ID)).thenReturn(Optional.of(hidden));
        when(sources.canRead(HIDDEN_SOURCE_ID, Set.of("ai:knowledge:read"))).thenReturn(false);
        AiApiException hiddenFailure = assertThrows(AiApiException.class,
                () -> controller.source(HIDDEN_SOURCE_ID));
        assertEquals("AI_RESOURCE_NOT_FOUND", hiddenFailure.errorCode());

        when(sources.findByPublicId(READABLE_SOURCE_ID)).thenReturn(Optional.of(readable));
        when(sources.canRead(READABLE_SOURCE_ID, Set.of("ai:knowledge:read"))).thenReturn(true);
        assertEquals(READABLE_SOURCE_ID, controller.source(READABLE_SOURCE_ID).getBody().data().id());
    }

    @Test
    void createSourceEnforcesServerOwnedVisibilityClassificationAndDelegation() {
        when(sources.create(any())).thenAnswer(invocation -> {
            KnowledgeSourceRepository.CreateSource command = invocation.getArgument(0);
            return new KnowledgeSourceRepository.KnowledgeSource(
                    1, command.publicId(), command.name(), command.sourceType(), command.ownerUserId(),
                    command.classification(), command.matchMode(), command.objectStoreCode(), 1, "ACTIVE",
                    command.permissions());
        });

        var created = controller.createSource(new AiKnowledgeController.CreateSourceRequest(
                "制度来源", null, "l1", "any", null, null));
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        assertEquals(7, created.getBody().data().ownerUserId());

        var blankVisibility = controller.createSource(new AiKnowledgeController.CreateSourceRequest(
                "空白可见性", 7L, "L2", "ALL", List.of("repair:read"), " "));
        assertEquals("L2", blankVisibility.getBody().data().classification());

        assertThrows(IllegalArgumentException.class, () -> controller.createSource(
                new AiKnowledgeController.CreateSourceRequest(
                        "来源", null, "L1", "ANY", List.of(), "PUBLIC_APPROVED")));
        assertThrows(SecurityException.class, () -> controller.createSource(
                new AiKnowledgeController.CreateSourceRequest(
                        "来源", 8L, "L1", "ANY", List.of(), null)));
        assertThrows(IllegalArgumentException.class, () -> controller.createSource(
                new AiKnowledgeController.CreateSourceRequest(
                        "来源", null, "L3", "ANY", List.of(), null)));
        assertThrows(IllegalArgumentException.class, () -> controller.createSource(
                new AiKnowledgeController.CreateSourceRequest(
                        "来源", null, "unknown", "ANY", List.of(), null)));
        assertThrows(IllegalArgumentException.class, () -> controller.createSource(
                new AiKnowledgeController.CreateSourceRequest(
                        "来源", null, "L1", null, List.of(), null)));
        assertThrows(SecurityException.class, () -> controller.createSource(
                new AiKnowledgeController.CreateSourceRequest(
                        "来源", null, "L1", "ANY", List.of("notice:publish"), null)));

        when(actors.current("ai:knowledge:manage")).thenReturn(actor(7, true, "ai:knowledge:manage"));
        var delegated = controller.createSource(new AiKnowledgeController.CreateSourceRequest(
                "管理员委派来源", 8L, "L0", "ANY", List.of("notice:publish"), null));
        assertEquals(8, delegated.getBody().data().ownerUserId());
    }

    @Test
    void updateSourceValidatesEveryOptionalFieldAndOwnerBoundary() {
        when(sources.update(any())).thenAnswer(invocation -> {
            KnowledgeSourceRepository.UpdateSource command = invocation.getArgument(0);
            return new KnowledgeSourceRepository.KnowledgeSource(
                    1, command.publicId(), command.name(), "UPLOAD", 7, command.classification(),
                    command.matchMode(), "knowledge-object-v1", command.expectedAclVersion() + 1,
                    command.status(), command.permissions());
        });

        var unchanged = controller.updateSource(SOURCE_ID,
                new AiKnowledgeController.UpdateSourceRequest(1, null, null, null, null, null));
        assertEquals("来源", unchanged.getBody().data().name());
        assertEquals("ACTIVE", unchanged.getBody().data().status());

        var updated = controller.updateSource(SOURCE_ID,
                new AiKnowledgeController.UpdateSourceRequest(2, " 更新后 ", "l2", "all", " paused ",
                        List.of("repair:read")));
        assertEquals("更新后", updated.getBody().data().name());
        assertEquals("PAUSED", updated.getBody().data().status());

        assertThrows(IllegalArgumentException.class, () -> controller.updateSource(SOURCE_ID,
                new AiKnowledgeController.UpdateSourceRequest(1, " ", null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> controller.updateSource(SOURCE_ID,
                new AiKnowledgeController.UpdateSourceRequest(1, null, "L3", null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> controller.updateSource(SOURCE_ID,
                new AiKnowledgeController.UpdateSourceRequest(1, null, null, null, "retired", null)));
        assertThrows(SecurityException.class, () -> controller.updateSource(SOURCE_ID,
                new AiKnowledgeController.UpdateSourceRequest(1, null, null, null, null,
                        List.of("notice:publish"))));

        when(actors.current("ai:knowledge:manage")).thenReturn(actor(8, false, "ai:knowledge:manage"));
        assertThrows(SecurityException.class, () -> controller.updateSource(SOURCE_ID,
                new AiKnowledgeController.UpdateSourceRequest(1, null, null, null, null, null)));
    }

    @Test
    void versionCreationRejectsClientStorageCoordinatesAndForeignUploadFacts() {
        AiKnowledgeController.CreateVersionRequest valid = versionRequest(null, null, null, null);
        for (AiKnowledgeController.CreateVersionRequest request : List.of(
                versionRequest("client-key", null, null, null),
                versionRequest(null, "client-bucket", null, null),
                versionRequest(null, null, "https://example.invalid/object", null),
                versionRequest(null, null, null, "PUBLIC_APPROVED"))) {
            assertThrows(IllegalArgumentException.class,
                    () -> controller.createVersion(SOURCE_ID, "version-key", request));
        }

        List<KnowledgeUploadSessionRepository.UploadRecord> invalidFacts = List.of(
                uploadRecord(UPLOAD_ID, "another-source", 7, "FINALIZED", "SCANNED_CLEAN"),
                uploadRecord(UPLOAD_ID, SOURCE_ID, 8, "FINALIZED", "SCANNED_CLEAN"),
                uploadRecord(UPLOAD_ID, SOURCE_ID, 7, "UPLOADED", "SCANNED_CLEAN"),
                uploadRecord(UPLOAD_ID, SOURCE_ID, 7, "FINALIZED", "PENDING"));
        for (KnowledgeUploadSessionRepository.UploadRecord fact : invalidFacts) {
            when(uploadFacts.findByPublicId(UPLOAD_ID)).thenReturn(Optional.of(fact));
            assertThrows(SecurityException.class,
                    () -> controller.createVersion(SOURCE_ID, "version-key", valid));
        }
    }

    @Test
    void uploadCreationReplaysCompletedResponseAndReleasesFailedNewReservation() {
        JdbcAiIdempotencyRepository.Reservation replay = reservation(
                41, JdbcAiIdempotencyRepository.ReservationStatus.REPLAY, "COMPLETED");
        when(idempotency.reserve(any(), anyString(), any())).thenReturn(replay);
        when(idempotency.completedResponse(41)).thenReturn(Optional.of(
                new JdbcAiIdempotencyRepository.CompletedResponse(201, UPLOAD_ID)));
        when(uploads.get(UPLOAD_ID)).thenReturn(uploadSession());
        when(uploads.storageStatus()).thenReturn(storageStatus());
        when(uploads.scannerStatus()).thenReturn(scannerStatus());

        var response = controller.createUpload(SOURCE_ID, "upload-key",
                new AiKnowledgeController.CreateUploadRequest("TEXT/PLAIN", 4, HASH.toUpperCase()));
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(UPLOAD_ID, response.getBody().data().id());

        JdbcAiIdempotencyRepository.Reservation created = reservation(
                42, JdbcAiIdempotencyRepository.ReservationStatus.CREATED, "PENDING");
        when(idempotency.reserve(any(), anyString(), any())).thenReturn(created);
        when(uploads.create(anyLong(), anyLong(), anyString(), anyString(),
                anyLong(), anyString(), any())).thenThrow(new IllegalStateException("storage unavailable"));
        assertThrows(IllegalStateException.class, () -> controller.createUpload(SOURCE_ID, "failed-key",
                new AiKnowledgeController.CreateUploadRequest("text/plain", 4, HASH)));
        verify(idempotency).releasePending(42);
    }

    @Test
    void finalizeRecoversCompletedAndConcurrentIdempotencyResponsesButRejectsDifferentResource() {
        KnowledgeUploadSessionRepository.UploadRecord fact = uploadRecord(
                UPLOAD_ID, SOURCE_ID, 7, "UPLOADED", "PENDING");
        when(uploadFacts.findByPublicId(UPLOAD_ID)).thenReturn(Optional.of(fact));
        when(uploads.finalizeUpload(7, SOURCE_ID, UPLOAD_ID)).thenReturn(finalizedUpload());
        when(uploads.scannerStatus()).thenReturn(scannerStatus());
        JdbcAiIdempotencyRepository.Reservation completed = reservation(
                51, JdbcAiIdempotencyRepository.ReservationStatus.REPLAY, "COMPLETED");
        JdbcAiIdempotencyRepository.Reservation pending = reservation(
                52, JdbcAiIdempotencyRepository.ReservationStatus.REPLAY, "PENDING");
        JdbcAiIdempotencyRepository.Reservation conflicting = reservation(
                53, JdbcAiIdempotencyRepository.ReservationStatus.REPLAY, "PENDING");
        when(idempotency.reserve(any(), anyString(), any())).thenReturn(completed, pending, conflicting);
        when(idempotency.completedResponse(51)).thenReturn(Optional.of(
                new JdbcAiIdempotencyRepository.CompletedResponse(202, UPLOAD_ID)));
        when(idempotency.completedResponse(52)).thenReturn(Optional.of(
                new JdbcAiIdempotencyRepository.CompletedResponse(202, UPLOAD_ID)));
        when(idempotency.completedResponse(53)).thenReturn(Optional.of(
                new JdbcAiIdempotencyRepository.CompletedResponse(202, UUID.randomUUID().toString())));
        doThrow(new IllegalStateException("already completed")).when(idempotency).complete(52, 202, UPLOAD_ID);
        doThrow(new IllegalStateException("completed by another request")).when(idempotency)
                .complete(53, 202, UPLOAD_ID);

        assertEquals(HttpStatus.ACCEPTED, controller.finalizeUpload(UPLOAD_ID, "completed-key").getStatusCode());
        assertEquals(HttpStatus.ACCEPTED, controller.finalizeUpload(UPLOAD_ID, "concurrent-key").getStatusCode());
        assertThrows(IllegalStateException.class,
                () -> controller.finalizeUpload(UPLOAD_ID, "conflicting-key"));
        verify(idempotency, never()).releasePending(51);
        verify(idempotency, never()).releasePending(52);
        verify(idempotency).releasePending(53);
    }

    @Test
    void killSwitchAndApprovalHashFailClosedAtPublicBoundary() {
        when(controls.capabilityEnabled(AiCapability.KNOWLEDGE)).thenReturn(false);
        AiApiException disabled = assertThrows(AiApiException.class, () -> controller.listSources(1, 20));
        assertEquals("AI_KNOWLEDGE_DISABLED", disabled.errorCode());

        when(controls.capabilityEnabled(AiCapability.KNOWLEDGE)).thenReturn(true);
        when(controls.sourceEnabled(SOURCE_ID)).thenReturn(false);
        AiApiException sourceDisabled = assertThrows(AiApiException.class, () -> controller.createUpload(
                SOURCE_ID, "disabled-key", new AiKnowledgeController.CreateUploadRequest("text/plain", 4, HASH)));
        assertEquals("AI_KNOWLEDGE_SOURCE_DISABLED", sourceDisabled.errorCode());

        for (List<String> values : List.of(
                Arrays.asList(null, HASH, HASH),
                Arrays.asList(VERSION_ID, null, HASH),
                Arrays.asList(VERSION_ID, HASH, null))) {
            assertThrows(IllegalArgumentException.class,
                    () -> AiKnowledgeController.publicApprovalRequestHash(
                            values.get(0), values.get(1), values.get(2)));
        }
        assertEquals(
                AiKnowledgeController.publicApprovalRequestHash(VERSION_ID, HASH, HASH),
                AiKnowledgeController.publicApprovalRequestHash(
                        VERSION_ID, HASH.toUpperCase(), HASH.toUpperCase()));
        assertTrue(AiKnowledgeController.publicApprovalRequestHash(VERSION_ID, HASH, HASH)
                .matches("[0-9a-f]{64}"));
    }

    @Test
    void publicApprovalReplaysCompletedPreflightWithoutConsumingAnotherProof() {
        approvalDefaults();
        String requestHash = AiKnowledgeController.publicApprovalRequestHash(VERSION_ID, HASH, HASH);
        JdbcAiIdempotencyRepository.Reservation completed = new JdbcAiIdempotencyRepository.Reservation(
                61, JdbcAiIdempotencyRepository.ReservationStatus.REPLAY, requestHash, "COMPLETED");
        when(idempotency.inspect(any(), anyString())).thenReturn(Optional.of(completed));
        when(idempotency.completedResponse(61)).thenReturn(Optional.of(
                new JdbcAiIdempotencyRepository.CompletedResponse(204, VERSION_ID)));

        var response = controller.approvePublic(VERSION_ID, "approval-key", "unused-proof",
                new AiKnowledgeController.PublicApprovalRequest(HASH, HASH));

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(recentAuthentication, never()).consume(anyString(), any(), anyString(), anyString(), anyString());
        verify(idempotency, never()).reserve(any(), anyString(), any());
        assertEquals(0, transactions.requiredCalls);
    }

    @Test
    void publicApprovalRejectsPreflightHashMismatchBeforeProofConsumption() {
        approvalDefaults();
        when(idempotency.inspect(any(), anyString())).thenThrow(new IdempotencyPayloadMismatchException());

        assertThrows(IdempotencyPayloadMismatchException.class,
                () -> controller.approvePublic(VERSION_ID, "approval-key", "unused-proof",
                        new AiKnowledgeController.PublicApprovalRequest(HASH, HASH)));

        verify(recentAuthentication, never()).consume(anyString(), any(), anyString(), anyString(), anyString());
        verify(idempotency, never()).reserve(any(), anyString(), any());
        assertEquals(0, transactions.requiredCalls);
    }

    @Test
    void publicApprovalRejectsPendingPreflightBeforeProofConsumption() {
        approvalDefaults();
        String requestHash = AiKnowledgeController.publicApprovalRequestHash(VERSION_ID, HASH, HASH);
        JdbcAiIdempotencyRepository.Reservation pending = new JdbcAiIdempotencyRepository.Reservation(
                62, JdbcAiIdempotencyRepository.ReservationStatus.REPLAY, requestHash, "PENDING");
        when(idempotency.inspect(any(), anyString())).thenReturn(Optional.of(pending));

        AiApiException failure = assertThrows(AiApiException.class,
                () -> controller.approvePublic(VERSION_ID, "approval-key", "unused-proof",
                        new AiKnowledgeController.PublicApprovalRequest(HASH, HASH)));

        assertEquals("AI_IDEMPOTENCY_IN_PROGRESS", failure.errorCode());
        verify(recentAuthentication, never()).consume(anyString(), any(), anyString(), anyString(), anyString());
        verify(idempotency, never()).reserve(any(), anyString(), any());
        assertEquals(0, transactions.requiredCalls);
    }

    @Test
    void publicApprovalConsumesProofBeforeAtomicRequiredApprovalAndCompletion() {
        approvalDefaults();
        String requestHash = AiKnowledgeController.publicApprovalRequestHash(VERSION_ID, HASH, HASH);
        JdbcAiIdempotencyRepository.Reservation created = new JdbcAiIdempotencyRepository.Reservation(
                63, JdbcAiIdempotencyRepository.ReservationStatus.CREATED, requestHash, "PENDING");
        when(idempotency.inspect(any(), anyString())).thenAnswer(invocation -> {
            transactions.events.add("inspect");
            return Optional.empty();
        });
        doAnswer(invocation -> {
            transactions.events.add("consume");
            return null;
        }).when(recentAuthentication).consume(anyString(), any(), anyString(), anyString(), anyString());
        when(idempotency.reserve(any(), anyString(), any())).thenAnswer(invocation -> {
            assertTrue(transactions.active);
            transactions.events.add("reserve");
            return created;
        });
        doAnswer(invocation -> {
            assertTrue(transactions.active);
            transactions.events.add("approve");
            return null;
        }).when(ingestion).approvePublic(anyString(), anyString(), anyString(), anyLong(), anyLong(), anyString());
        doAnswer(invocation -> {
            assertTrue(transactions.active);
            transactions.events.add("complete");
            return null;
        }).when(idempotency).complete(63, 204, VERSION_ID);

        var response = controller.approvePublic(VERSION_ID, "approval-key", "proof",
                new AiKnowledgeController.PublicApprovalRequest(HASH, HASH));

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        assertEquals(List.of("inspect", "consume", "required:start", "reserve", "approve", "complete",
                "required:commit"), transactions.events);
        verify(idempotency, never()).releasePending(anyLong());
        assertEquals(0, transactions.requiresNewCalls);
    }

    @Test
    void publicApprovalApproveFailureRollsBackTheSingleRequiredBoundary() {
        approvalDefaults();
        createdApprovalReservation(64);
        doAnswer(invocation -> {
            assertTrue(transactions.active);
            transactions.events.add("approve");
            throw new IllegalStateException("approval failed");
        }).when(ingestion).approvePublic(anyString(), anyString(), anyString(), anyLong(), anyLong(), anyString());

        assertThrows(IllegalStateException.class,
                () -> controller.approvePublic(VERSION_ID, "approval-key", "proof",
                        new AiKnowledgeController.PublicApprovalRequest(HASH, HASH)));

        assertEquals(List.of("consume", "required:start", "reserve", "approve", "required:rollback"),
                transactions.events);
        verify(idempotency, never()).complete(anyLong(), anyInt(), anyString());
        verify(idempotency, never()).releasePending(anyLong());
        assertEquals(0, transactions.requiresNewCalls);
    }

    @Test
    void publicApprovalCompletionFailureRollsBackApprovalInTheSameRequiredBoundary() {
        approvalDefaults();
        createdApprovalReservation(65);
        doAnswer(invocation -> {
            assertTrue(transactions.active);
            transactions.events.add("approve");
            return null;
        }).when(ingestion).approvePublic(anyString(), anyString(), anyString(), anyLong(), anyLong(), anyString());
        doAnswer(invocation -> {
            assertTrue(transactions.active);
            transactions.events.add("complete");
            throw new IllegalStateException("completion failed");
        }).when(idempotency).complete(65, 204, VERSION_ID);

        assertThrows(IllegalStateException.class,
                () -> controller.approvePublic(VERSION_ID, "approval-key", "proof",
                        new AiKnowledgeController.PublicApprovalRequest(HASH, HASH)));

        assertEquals(List.of("consume", "required:start", "reserve", "approve", "complete",
                "required:rollback"), transactions.events);
        verify(idempotency, never()).releasePending(anyLong());
        assertEquals(0, transactions.requiresNewCalls);
    }

    private void approvalDefaults() {
        when(actors.current("ai:knowledge:publish-public"))
                .thenReturn(actor(7, false, "ai:knowledge:publish-public"));
        when(versions.findByPublicId(VERSION_ID)).thenReturn(Optional.of(version()));
    }

    private void createdApprovalReservation(long recordId) {
        String requestHash = AiKnowledgeController.publicApprovalRequestHash(VERSION_ID, HASH, HASH);
        when(idempotency.inspect(any(), anyString())).thenReturn(Optional.empty());
        doAnswer(invocation -> {
            transactions.events.add("consume");
            return null;
        }).when(recentAuthentication).consume(anyString(), any(), anyString(), anyString(), anyString());
        when(idempotency.reserve(any(), anyString(), any())).thenAnswer(invocation -> {
            assertTrue(transactions.active);
            transactions.events.add("reserve");
            return new JdbcAiIdempotencyRepository.Reservation(
                    recordId, JdbcAiIdempotencyRepository.ReservationStatus.CREATED, requestHash, "PENDING");
        });
    }

    private AiKnowledgeController.CreateVersionRequest versionRequest(
            String objectKey, String bucket, String url, String visibility) {
        return new AiKnowledgeController.CreateVersionRequest(
                UPLOAD_ID, "external-key", "标题", "v1", objectKey, bucket, url, visibility);
    }

    private AiActorContext actor(long userId, boolean admin, String... permissions) {
        return new AiActorContext(userId, "token", HASH, 1, HASH,
                admin ? List.of("ADMIN") : List.of("USER"), List.of(permissions), ActorDescriptor.user(userId));
    }

    private KnowledgeSourceRepository.KnowledgeSource source(String publicId, long owner) {
        return new KnowledgeSourceRepository.KnowledgeSource(
                1, publicId, "来源", "UPLOAD", owner, DataClassification.L1,
                PermissionMatchMode.ANY, "knowledge-object-v1", 1, "ACTIVE", Set.of("repair:read"));
    }

    private KnowledgeUploadSessionRepository.UploadRecord uploadRecord(
            String publicId, String sourceId, long owner, String state, String scanState) {
        return new KnowledgeUploadSessionRepository.UploadRecord(
                1, publicId, 1, owner, sourceId, "quarantine/" + publicId, HASH, HASH,
                4, 4L, "text/plain", "SCANNED_CLEAN".equals(scanState) ? "text/plain" : null,
                state, scanState, "version-1", "etag-1", NOW.plusSeconds(60), NOW, null, 1, NOW);
    }

    private KnowledgeVersionRepository.KnowledgeVersion version() {
        return new KnowledgeVersionRepository.KnowledgeVersion(
                1, VERSION_ID, 1, "document", 1, SOURCE_ID, "v1", HASH,
                KnowledgeVisibility.EXPLICIT_ACL,
                new ObjectStoragePort.ObjectReference("sha256/" + HASH, "version-1", "etag-1"),
                "text/plain", 4, "plain-text-v1", "paragraph-2000-v1", "READY", null);
    }

    private JdbcAiIdempotencyRepository.Reservation reservation(
            long id, JdbcAiIdempotencyRepository.ReservationStatus status, String state) {
        return new JdbcAiIdempotencyRepository.Reservation(id, status, HASH, state);
    }

    private UploadSessionService.UploadSession uploadSession() {
        return new UploadSessionService.UploadSession(
                UPLOAD_ID, 7, SOURCE_ID, "quarantine/" + UPLOAD_ID, HASH, 4, "text/plain",
                NOW.plusSeconds(60), UploadSessionState.CREATED, "PENDING", null, null, null);
    }

    private UploadSessionService.FinalizedUpload finalizedUpload() {
        return new UploadSessionService.FinalizedUpload(
                UPLOAD_ID, SOURCE_ID,
                new ObjectStoragePort.ObjectReference("quarantine/" + UPLOAD_ID, "version-1", "etag-1"),
                HASH, 4, NOW);
    }

    private ObjectStoragePort.AdapterStatus storageStatus() {
        return new ObjectStoragePort.AdapterStatus("test-storage", false, true, true, true, false);
    }

    private KnowledgeFileScanner.ScannerStatus scannerStatus() {
        return new KnowledgeFileScanner.ScannerStatus(
                "test-scanner", "CONTROLLED_PLAIN_TEXT_ONLY", false, false);
    }

    private static final class RecordingTransactionRunner implements ActionProposalService.TransactionRunner {
        private final List<String> events = new ArrayList<>();
        private int requiredCalls;
        private int requiresNewCalls;
        private boolean active;

        @Override
        public <T> T required(Supplier<T> work) {
            requiredCalls++;
            events.add("required:start");
            active = true;
            try {
                T result = work.get();
                events.add("required:commit");
                return result;
            } catch (RuntimeException | Error failure) {
                events.add("required:rollback");
                throw failure;
            } finally {
                active = false;
            }
        }

        @Override
        public <T> T requiresNew(Supplier<T> work) {
            requiresNewCalls++;
            throw new AssertionError("approve-public 不得开启嵌套 REQUIRES_NEW");
        }
    }
}
