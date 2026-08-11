package com.example.dormitory.ai.api;

import com.example.dormitory.ai.application.run.AiActorContext;
import com.example.dormitory.ai.application.run.AiActorResolver;
import com.example.dormitory.ai.approval.CanonicalJsonHasher;
import com.example.dormitory.ai.approval.ActionProposalService;
import com.example.dormitory.ai.config.AiRuntimeControlService;
import com.example.dormitory.ai.domain.model.AiCapability;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiIdempotencyRepository;
import com.example.dormitory.ai.knowledge.KnowledgeExternalKeyHasher;
import com.example.dormitory.ai.knowledge.KnowledgeIngestionJobRepository;
import com.example.dormitory.ai.knowledge.KnowledgeIngestionService;
import com.example.dormitory.ai.knowledge.KnowledgeIngestionDispatcher;
import com.example.dormitory.ai.knowledge.KnowledgeSourceRepository;
import com.example.dormitory.ai.knowledge.KnowledgeUploadSessionRepository;
import com.example.dormitory.ai.knowledge.KnowledgeVersionRepository;
import com.example.dormitory.ai.knowledge.PermissionMatchMode;
import com.example.dormitory.ai.knowledge.UploadSessionService;
import com.example.dormitory.ai.security.AuthenticatedRunContext;
import com.example.dormitory.ai.security.DataClassification;
import com.example.dormitory.ai.security.RecentAuthenticationPolicy;
import com.example.dormitory.common.ApiResponse;
import com.example.dormitory.common.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/ai/knowledge")
@ConditionalOnProperty(prefix = "dormitory.ai.capabilities", name = "knowledge", havingValue = "true")
public class AiKnowledgeController {

    private final KnowledgeSourceRepository sources;
    private final KnowledgeVersionRepository versions;
    private final KnowledgeIngestionJobRepository jobs;
    private final KnowledgeUploadSessionRepository uploadFacts;
    private final UploadSessionService uploads;
    private final KnowledgeIngestionService ingestion;
    private final KnowledgeExternalKeyHasher externalKeys;
    private final AiActorResolver actors;
    private final AiRuntimeControlService controls;
    private final JdbcAiIdempotencyRepository idempotency;
    private final RecentAuthenticationPolicy recentAuthentication;
    private final KnowledgeIngestionDispatcher dispatcher;
    private final ActionProposalService.TransactionRunner transactions;

    public AiKnowledgeController(
            KnowledgeSourceRepository sources,
            KnowledgeVersionRepository versions,
            KnowledgeIngestionJobRepository jobs,
            KnowledgeUploadSessionRepository uploadFacts,
            UploadSessionService uploads,
            KnowledgeIngestionService ingestion,
            KnowledgeExternalKeyHasher externalKeys,
            AiActorResolver actors,
            AiRuntimeControlService controls,
            JdbcAiIdempotencyRepository idempotency,
            RecentAuthenticationPolicy recentAuthentication,
            KnowledgeIngestionDispatcher dispatcher) {
        this(sources, versions, jobs, uploadFacts, uploads, ingestion, externalKeys, actors, controls,
                idempotency, recentAuthentication, dispatcher, ActionProposalService.TransactionRunner.direct());
    }

    @Autowired
    public AiKnowledgeController(
            KnowledgeSourceRepository sources,
            KnowledgeVersionRepository versions,
            KnowledgeIngestionJobRepository jobs,
            KnowledgeUploadSessionRepository uploadFacts,
            UploadSessionService uploads,
            KnowledgeIngestionService ingestion,
            KnowledgeExternalKeyHasher externalKeys,
            AiActorResolver actors,
            AiRuntimeControlService controls,
            JdbcAiIdempotencyRepository idempotency,
            RecentAuthenticationPolicy recentAuthentication,
            KnowledgeIngestionDispatcher dispatcher,
            ActionProposalService.TransactionRunner transactions) {
        this.sources = sources;
        this.versions = versions;
        this.jobs = jobs;
        this.uploadFacts = uploadFacts;
        this.uploads = uploads;
        this.ingestion = ingestion;
        this.externalKeys = externalKeys;
        this.actors = actors;
        this.controls = controls;
        this.idempotency = idempotency;
        this.recentAuthentication = recentAuthentication;
        this.dispatcher = dispatcher;
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @GetMapping("/sources")
    public ResponseEntity<ApiResponse<PageResponse<SourceResponse>>> listSources(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        requireKnowledgeEnabled();
        AiActorContext actor = actors.current("ai:knowledge:read");
        List<KnowledgeSourceRepository.KnowledgeSource> visible = sources.findAll().stream()
                .filter(source -> canManage(actor, source)
                        || sources.canRead(source.publicId(), Set.copyOf(actor.permissionCodes())))
                .toList();
        int from = Math.min((page - 1) * pageSize, visible.size());
        int to = Math.min(from + pageSize, visible.size());
        List<SourceResponse> records = visible.subList(from, to).stream().map(this::sourceResponse).toList();
        return ok(new PageResponse<>(records, visible.size(), page, pageSize));
    }

    @PostMapping("/sources")
    public ResponseEntity<ApiResponse<SourceResponse>> createSource(@Valid @RequestBody CreateSourceRequest request) {
        requireKnowledgeEnabled();
        AiActorContext actor = actors.current("ai:knowledge:manage");
        if (request.visibility() != null && !request.visibility().isBlank()) {
            throw new IllegalArgumentException("创建知识来源不得自报 PUBLIC_APPROVED");
        }
        long owner = request.ownerUserId() == null ? actor.userId() : request.ownerUserId();
        if (owner != actor.userId() && !isAdmin(actor)) throw new SecurityException("非管理员只能创建自己的知识来源");
        DataClassification classification = classification(request.classification());
        if (classification == DataClassification.L3) throw new IllegalArgumentException("L3 来源禁止进入 AI");
        Set<String> requestedPermissions = Set.copyOf(request.permissions());
        validateAssignablePermissions(actor, requestedPermissions);
        KnowledgeSourceRepository.KnowledgeSource source = sources.create(
                new KnowledgeSourceRepository.CreateSource(UUID.randomUUID().toString(), request.name(), "UPLOAD",
                        owner, classification, matchMode(request.matchMode()), "knowledge-object-v1",
                        requestedPermissions, actor.userId()));
        HttpHeaders headers = AiApiHeaders.privateNoStore();
        headers.setLocation(URI.create("/api/ai/knowledge/sources/" + source.publicId()));
        return new ResponseEntity<>(ApiResponse.ok(sourceResponse(source)), headers, HttpStatus.CREATED);
    }

    @PatchMapping("/sources/{id}")
    @Transactional
    public ResponseEntity<ApiResponse<SourceResponse>> updateSource(
            @PathVariable String id,
            @Valid @RequestBody UpdateSourceRequest request) {
        AiActorContext actor = ownerOrAdmin(id, "ai:knowledge:manage");
        KnowledgeSourceRepository.KnowledgeSource current = requireSource(id);
        String name = request.name() == null ? current.name() : request.name().trim();
        if (name.isBlank()) throw new IllegalArgumentException("知识来源名称不能为空");
        DataClassification classification = request.classification() == null
                ? current.classification() : classification(request.classification());
        if (classification == DataClassification.L3) throw new IllegalArgumentException("L3 来源禁止进入 AI");
        PermissionMatchMode matchMode = request.matchMode() == null
                ? current.matchMode() : matchMode(request.matchMode());
        String status = request.status() == null ? current.status()
                : request.status().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ACTIVE", "PAUSED").contains(status)) {
            throw new IllegalArgumentException("知识来源状态不合法");
        }
        Set<String> permissions = request.permissions() == null
                ? current.permissions() : Set.copyOf(request.permissions());
        validateAssignablePermissions(actor, permissions);
        KnowledgeSourceRepository.KnowledgeSource updated = sources.update(
                new KnowledgeSourceRepository.UpdateSource(id, request.expectedAclVersion(), name,
                        classification, matchMode, status, permissions, actor.userId()));
        return ok(sourceResponse(updated));
    }

    @GetMapping("/sources/{id}")
    public ResponseEntity<ApiResponse<SourceResponse>> source(@PathVariable String id) {
        requireKnowledgeEnabled();
        AiActorContext actor = actors.current("ai:knowledge:read");
        KnowledgeSourceRepository.KnowledgeSource source = requireSource(id);
        if (!canManage(actor, source) && !sources.canRead(id, Set.copyOf(actor.permissionCodes()))) {
            throw AiApiException.notFound();
        }
        return ok(sourceResponse(source));
    }

    @PostMapping("/sources/{id}/uploads")
    @Transactional
    public ResponseEntity<ApiResponse<UploadResponse>> createUpload(
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            @Valid @RequestBody CreateUploadRequest request) {
        AiActorContext actor = ownerOrAdmin(id, "ai:knowledge:manage");
        requireSourceEnabled(id);
        String hash = CanonicalJsonHasher.sha256("knowledge-upload-create.v1|" + id + "|"
                + request.mimeType().toLowerCase(Locale.ROOT) + "|" + request.sizeBytes() + "|"
                + request.sha256().toLowerCase(Locale.ROOT));
        JdbcAiIdempotencyRepository.Reservation reservation = reserve(actor, "AI_KNOWLEDGE_UPLOAD_CREATE", id, key, hash);
        if (reservation.status() == JdbcAiIdempotencyRepository.ReservationStatus.REPLAY) {
            String uploadId = completedResource(reservation);
            return createdUpload(uploads.get(uploadId));
        }
        try {
            UploadSessionService.UploadSession session = uploads.create(requireSource(id).id(), actor.userId(), id,
                    request.sha256(),
                    request.sizeBytes(), request.mimeType(), Instant.now().plus(15, ChronoUnit.MINUTES));
            idempotency.complete(reservation.recordId(), 201, session.publicId());
            return createdUpload(session);
        } catch (RuntimeException failure) {
            idempotency.releasePending(reservation.recordId());
            throw failure;
        }
    }

    @PutMapping(value = "/uploads/{id}/content", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<Void> uploadContent(
            @PathVariable String id,
            HttpServletRequest request) throws IOException {
        KnowledgeUploadSessionRepository.UploadRecord fact = requireUpload(id);
        AiActorContext actor = ownerOrAdmin(fact.sourcePublicId(), "ai:knowledge:manage");
        if (actor.userId() != fact.ownerUserId()) throw new SecurityException("上传 target 只允许会话创建者写入");
        requireSourceEnabled(fact.sourcePublicId());
        uploads.upload(actor.userId(), id, request.getInputStream(), request.getContentLengthLong());
        return ResponseEntity.noContent().headers(AiApiHeaders.privateNoStore()).build();
    }

    @PostMapping("/uploads/{id}/finalize")
    public ResponseEntity<ApiResponse<FinalizeUploadResponse>> finalizeUpload(
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key) {
        KnowledgeUploadSessionRepository.UploadRecord fact = requireUpload(id);
        AiActorContext actor = ownerOrAdmin(fact.sourcePublicId(), "ai:knowledge:manage");
        if (actor.userId() != fact.ownerUserId()) throw new SecurityException("上传会话只允许创建者完成");
        requireSourceEnabled(fact.sourcePublicId());
        String hash = CanonicalJsonHasher.sha256("knowledge-upload-finalize.v1|" + id);
        JdbcAiIdempotencyRepository.Reservation reservation = idempotency.reserve(
                new JdbcAiIdempotencyRepository.Scope(actor.userId(), "AI_KNOWLEDGE_UPLOAD_FINALIZE", id, key),
                hash, Instant.now().plus(24, ChronoUnit.HOURS));
        try {
            if (reservation.status() == JdbcAiIdempotencyRepository.ReservationStatus.REPLAY
                    && !"PENDING".equals(reservation.state()) && !"COMPLETED".equals(reservation.state())) {
                throw new AiApiException(HttpStatus.CONFLICT, "AI_IDEMPOTENCY_IN_PROGRESS",
                        "相同幂等请求状态不可恢复", true);
            }
            UploadSessionService.FinalizedUpload upload = uploads.finalizeUpload(actor.userId(), fact.sourcePublicId(), id);
            completeFinalizeIdempotency(reservation, id);
            HttpHeaders headers = AiApiHeaders.privateNoStore();
            headers.setLocation(URI.create("/api/ai/knowledge/uploads/" + id));
            return new ResponseEntity<>(ApiResponse.ok(new FinalizeUploadResponse(id, "FINALIZED",
                    upload.observedSha256(), upload.observedSizeBytes(), uploads.scannerStatus().code(),
                    uploads.scannerStatus().mode(), uploads.scannerStatus().malwareScannerAvailable())),
                    headers, HttpStatus.ACCEPTED);
        } catch (RuntimeException failure) {
            if (!"COMPLETED".equals(reservation.state())) {
                idempotency.releasePending(reservation.recordId());
            }
            throw failure;
        }
    }

    @PostMapping("/sources/{id}/versions")
    @Transactional
    public ResponseEntity<ApiResponse<ScheduledResponse>> createVersion(
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            @Valid @RequestBody CreateVersionRequest request) {
        AiActorContext actor = ownerOrAdmin(id, "ai:knowledge:manage");
        requireSourceEnabled(id);
        if (nonBlank(request.objectKey()) || nonBlank(request.bucket()) || nonBlank(request.url())
                || nonBlank(request.visibility())) {
            throw new IllegalArgumentException("版本创建不得提交 object key、bucket、URL 或 visibility");
        }
        KnowledgeUploadSessionRepository.UploadRecord fact = requireUpload(request.uploadSessionId());
        if (!id.equals(fact.sourcePublicId()) || actor.userId() != fact.ownerUserId()
                || !"FINALIZED".equals(fact.state()) || !"SCANNED_CLEAN".equals(fact.scanState())) {
            throw new SecurityException("上传会话不可用于该知识来源");
        }
        String hash = CanonicalJsonHasher.sha256("knowledge-version-create.v1|" + id + "|"
                + request.uploadSessionId() + "|" + request.externalKey() + "|" + request.title().trim()
                + "|" + request.version());
        JdbcAiIdempotencyRepository.Reservation reservation = reserve(actor, "AI_KNOWLEDGE_VERSION_CREATE", id, key, hash);
        if (reservation.status() == JdbcAiIdempotencyRepository.ReservationStatus.REPLAY) {
            String versionId = completedResource(reservation);
            KnowledgeVersionRepository.KnowledgeVersion version = requireVersion(versionId);
            KnowledgeIngestionJobRepository.IngestionJob job = jobs.findByDocumentVersionId(version.id()).orElseThrow();
            return accepted(versionId, job.publicId());
        }
        try {
            UploadSessionService.FinalizedUpload finalized = uploads.promoteFinalized(
                    actor.userId(), id, request.uploadSessionId());
            KnowledgeExternalKeyHasher.HashedKey external = externalKeys.hash(id, request.externalKey());
            KnowledgeIngestionService.ScheduledIngestion scheduled = ingestion.enqueue(finalized,
                    new KnowledgeIngestionService.EnqueueCommand(external.hmac(), external.keyVersion(),
                            request.title(), request.version(),
                            com.example.dormitory.ai.knowledge.KnowledgeVisibility.EXPLICIT_ACL,
                            "plain-text-v1", "paragraph-2000-v1", actor.userId()));
            idempotency.complete(reservation.recordId(), 202, scheduled.versionPublicId());
            dispatcher.dispatchAfterCommit();
            return accepted(scheduled.versionPublicId(), scheduled.jobPublicId());
        } catch (RuntimeException failure) {
            idempotency.releasePending(reservation.recordId());
            throw failure;
        }
    }

    @GetMapping("/versions/{id}")
    public ResponseEntity<ApiResponse<VersionResponse>> version(@PathVariable String id) {
        KnowledgeVersionRepository.KnowledgeVersion version = requireVersion(id);
        ownerOrAdmin(version.sourcePublicId(), "ai:knowledge:manage");
        return ok(versionResponse(version));
    }

    @GetMapping("/jobs/{id}")
    public ResponseEntity<ApiResponse<JobResponse>> job(@PathVariable String id) {
        KnowledgeIngestionJobRepository.IngestionJob job = jobs.findByPublicId(id)
                .orElseThrow(AiApiException::notFound);
        KnowledgeVersionRepository.KnowledgeVersion version = versions.findById(job.documentVersionId())
                .orElseThrow(AiApiException::notFound);
        ownerOrAdmin(version.sourcePublicId(), "ai:knowledge:manage");
        return ok(new JobResponse(job.publicId(), version.publicId(), job.state(), job.attempt(), job.errorCode(),
                job.availableAt(), job.startedAt(), job.finishedAt()));
    }

    @PostMapping("/versions/{id}/approve-public")
    public ResponseEntity<Void> approvePublic(
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            @RequestHeader("X-Step-Up-Proof") String proof,
            @Valid @RequestBody PublicApprovalRequest request) {
        requireKnowledgeEnabled();
        AiActorContext actor = actors.current("ai:knowledge:publish-public");
        String requestHash = publicApprovalRequestHash(id, request.contentHash(), request.approvalSnapshotHash());
        JdbcAiIdempotencyRepository.Scope scope = new JdbcAiIdempotencyRepository.Scope(
                actor.userId(), "AI_KNOWLEDGE_PUBLIC_APPROVE", id, key);
        Optional<JdbcAiIdempotencyRepository.Reservation> existing = idempotency.inspect(scope, requestHash);
        if (existing.isPresent()) {
            JdbcAiIdempotencyRepository.Reservation replay = existing.get();
            if (!"COMPLETED".equals(replay.state())) {
                throw new AiApiException(HttpStatus.CONFLICT, "AI_IDEMPOTENCY_IN_PROGRESS",
                        "相同幂等请求仍在处理", true);
            }
            completedResource(replay);
            return ResponseEntity.noContent().headers(AiApiHeaders.privateNoStore()).build();
        }

        KnowledgeVersionRepository.KnowledgeVersion version = requireVersion(id);
        recentAuthentication.consume(proof, AuthenticatedRunContext.from(actor),
                "KNOWLEDGE_PUBLIC_APPROVE", id, requestHash);
        transactions.required(() -> {
            JdbcAiIdempotencyRepository.Reservation reservation = reserve(scope, requestHash);
            if (reservation.status() == JdbcAiIdempotencyRepository.ReservationStatus.REPLAY) {
                completedResource(reservation);
                return null;
            }
            ingestion.approvePublic(id, request.contentHash(), request.approvalSnapshotHash(), actor.userId(),
                    reservation.recordId(), "knowledge-public-governance-v1");
            idempotency.complete(reservation.recordId(), 204, version.publicId());
            return null;
        });
        return ResponseEntity.noContent().headers(AiApiHeaders.privateNoStore()).build();
    }

    @PostMapping("/versions/{id}/activate")
    public ResponseEntity<Void> activate(@PathVariable String id) {
        KnowledgeVersionRepository.KnowledgeVersion version = requireVersion(id);
        AiActorContext actor = ownerOrAdmin(version.sourcePublicId(), "ai:knowledge:manage");
        ingestion.activate(id, actor.userId());
        return noContent();
    }

    @PostMapping("/versions/{id}/retire")
    public ResponseEntity<Void> retire(@PathVariable String id) {
        KnowledgeVersionRepository.KnowledgeVersion version = requireVersion(id);
        AiActorContext actor = ownerOrAdmin(version.sourcePublicId(), "ai:knowledge:manage");
        ingestion.retire(id, actor.userId());
        return noContent();
    }

    @PostMapping("/versions/{id}/rollback")
    public ResponseEntity<Void> rollback(@PathVariable String id) {
        KnowledgeVersionRepository.KnowledgeVersion version = requireVersion(id);
        AiActorContext actor = ownerOrAdmin(version.sourcePublicId(), "ai:knowledge:manage");
        ingestion.rollback(id, actor.userId());
        return noContent();
    }

    public static String publicApprovalRequestHash(String versionId, String contentHash, String snapshotHash) {
        if (versionId == null || contentHash == null || snapshotHash == null) {
            throw new IllegalArgumentException("知识公开批准请求不能为空");
        }
        return CanonicalJsonHasher.sha256("knowledge-public-approve.v1|" + framed(versionId)
                + framed(contentHash.toLowerCase(Locale.ROOT)) + framed(snapshotHash.toLowerCase(Locale.ROOT)));
    }

    private static String framed(String value) { return value.length() + ":" + value + "|"; }

    private JdbcAiIdempotencyRepository.Reservation reserve(
            AiActorContext actor, String route, String aggregate, String key, String hash) {
        return reserve(new JdbcAiIdempotencyRepository.Scope(actor.userId(), route, aggregate, key), hash);
    }

    private JdbcAiIdempotencyRepository.Reservation reserve(
            JdbcAiIdempotencyRepository.Scope scope, String hash) {
        JdbcAiIdempotencyRepository.Reservation reservation = idempotency.reserve(scope, hash,
                Instant.now().plus(24, ChronoUnit.HOURS));
        if (reservation.status() == JdbcAiIdempotencyRepository.ReservationStatus.REPLAY
                && !"COMPLETED".equals(reservation.state())) {
            throw new AiApiException(HttpStatus.CONFLICT, "AI_IDEMPOTENCY_IN_PROGRESS",
                    "相同幂等请求仍在处理", true);
        }
        return reservation;
    }

    private String completedResource(JdbcAiIdempotencyRepository.Reservation reservation) {
        return idempotency.completedResponse(reservation.recordId())
                .map(JdbcAiIdempotencyRepository.CompletedResponse::resourcePublicId)
                .orElseThrow(() -> new IllegalStateException("幂等响应引用不存在"));
    }

    private void completeFinalizeIdempotency(
            JdbcAiIdempotencyRepository.Reservation reservation,
            String uploadId) {
        if ("COMPLETED".equals(reservation.state())) {
            completedResource(reservation);
            return;
        }
        try {
            idempotency.complete(reservation.recordId(), 202, uploadId);
        } catch (IllegalStateException concurrentCompletion) {
            String completed = completedResource(reservation);
            if (!uploadId.equals(completed)) throw concurrentCompletion;
        }
    }

    private AiActorContext ownerOrAdmin(String sourceId, String permission) {
        requireKnowledgeEnabled();
        AiActorContext actor = actors.current(permission);
        KnowledgeSourceRepository.KnowledgeSource source = requireSource(sourceId);
        if (!canManage(actor, source)) throw new SecurityException("知识来源不可见");
        return actor;
    }

    private boolean canManage(AiActorContext actor, KnowledgeSourceRepository.KnowledgeSource source) {
        return actor.userId() == source.ownerUserId() || isAdmin(actor);
    }

    private boolean isAdmin(AiActorContext actor) { return actor.roleCodes().contains("ADMIN"); }

    private void validateAssignablePermissions(AiActorContext actor, Set<String> requested) {
        if (!isAdmin(actor) && !actor.permissionCodes().containsAll(requested)) {
            throw new SecurityException("不得授予操作者自身不具备的知识范围");
        }
    }

    private void requireKnowledgeEnabled() {
        if (!controls.capabilityEnabled(AiCapability.KNOWLEDGE)) {
            throw AiApiException.unavailable("AI_KNOWLEDGE_DISABLED", "知识能力当前未启用");
        }
    }

    private void requireSourceEnabled(String sourceId) {
        requireKnowledgeEnabled();
        if (!controls.sourceEnabled(sourceId)) {
            throw AiApiException.unavailable("AI_KNOWLEDGE_SOURCE_DISABLED", "知识来源当前已由 Kill Switch 关闭");
        }
    }

    private KnowledgeSourceRepository.KnowledgeSource requireSource(String id) {
        return sources.findByPublicId(id).orElseThrow(AiApiException::notFound);
    }

    private KnowledgeVersionRepository.KnowledgeVersion requireVersion(String id) {
        return versions.findByPublicId(id).orElseThrow(AiApiException::notFound);
    }

    private KnowledgeUploadSessionRepository.UploadRecord requireUpload(String id) {
        return uploadFacts.findByPublicId(id).orElseThrow(AiApiException::notFound);
    }

    private SourceResponse sourceResponse(KnowledgeSourceRepository.KnowledgeSource value) {
        return new SourceResponse(value.publicId(), value.name(), value.sourceType(), value.ownerUserId(),
                value.classification().name(), value.matchMode().name(), value.aclVersion(), value.status(),
                value.permissions());
    }

    private VersionResponse versionResponse(KnowledgeVersionRepository.KnowledgeVersion value) {
        KnowledgeVersionRepository.PublicApprovalPreview preview = versions.publicApprovalPreview(value.publicId());
        return new VersionResponse(value.publicId(), value.documentPublicId(), value.sourcePublicId(), value.version(),
                value.contentHash(), value.visibility().name(), value.status(), value.sizeBytes(),
                preview.aclVersion(), preview.approvalSnapshotHash());
    }

    private ResponseEntity<ApiResponse<UploadResponse>> createdUpload(UploadSessionService.UploadSession session) {
        HttpHeaders headers = AiApiHeaders.privateNoStore();
        headers.setLocation(URI.create("/api/ai/knowledge/uploads/" + session.publicId()));
        UploadResponse body = new UploadResponse(session.publicId(), session.state().name(), session.mimeType(),
                session.expectedSizeBytes(), session.expectedSha256(), session.expiresAt(),
                "/api/ai/knowledge/uploads/" + session.publicId() + "/content",
                uploads.storageStatus().code(), uploads.storageStatus().durableAcrossProcessRestart(),
                uploads.scannerStatus().code(), uploads.scannerStatus().mode());
        return new ResponseEntity<>(ApiResponse.ok(body), headers, HttpStatus.CREATED);
    }

    private ResponseEntity<ApiResponse<ScheduledResponse>> accepted(String versionId, String jobId) {
        HttpHeaders headers = AiApiHeaders.privateNoStore();
        headers.setLocation(URI.create("/api/ai/knowledge/jobs/" + jobId));
        return new ResponseEntity<>(ApiResponse.ok(new ScheduledResponse(versionId, jobId, "QUEUED")),
                headers, HttpStatus.ACCEPTED);
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(T value) {
        return ResponseEntity.ok().headers(AiApiHeaders.privateNoStore()).body(ApiResponse.ok(value));
    }

    private ResponseEntity<Void> noContent() {
        return ResponseEntity.noContent().headers(AiApiHeaders.privateNoStore()).build();
    }

    private DataClassification classification(String value) {
        try { return DataClassification.valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("知识分类不合法", invalid); }
    }

    private PermissionMatchMode matchMode(String value) {
        try { return PermissionMatchMode.valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("知识 ACL 匹配模式不合法", invalid); }
    }

    private boolean nonBlank(String value) { return value != null && !value.isBlank(); }

    public record CreateSourceRequest(@NotBlank @Size(max = 200) String name, Long ownerUserId,
                                      @NotBlank String classification, @NotBlank String matchMode,
                                      @Size(max = 50) List<@NotBlank @Size(max = 64) String> permissions,
                                      String visibility) {
        public CreateSourceRequest { permissions = permissions == null ? List.of() : List.copyOf(permissions); }
    }
    public record UpdateSourceRequest(@Min(1) long expectedAclVersion,
                                      @Size(min = 1, max = 200) String name,
                                      String classification,
                                      String matchMode,
                                      String status,
                                      @Size(max = 50) List<@NotBlank @Size(max = 64) String> permissions) {
        public UpdateSourceRequest {
            permissions = permissions == null ? null : List.copyOf(permissions);
        }
    }
    public record CreateUploadRequest(@NotBlank String mimeType, @Min(1) @Max(20 * 1024 * 1024) long sizeBytes,
                                      @NotBlank @Pattern(regexp = "[0-9a-fA-F]{64}") String sha256) { }
    public record CreateVersionRequest(@NotBlank @Pattern(regexp = "[0-9a-fA-F-]{36}") String uploadSessionId,
                                       @NotBlank @Size(max = 256) String externalKey,
                                       @NotBlank @Size(max = 500) String title,
                                       @NotBlank @Size(max = 32) String version,
                                       String objectKey, String bucket, String url, String visibility) { }
    public record PublicApprovalRequest(@NotBlank @Pattern(regexp = "[0-9a-fA-F]{64}") String contentHash,
                                        @NotBlank @Pattern(regexp = "[0-9a-fA-F]{64}") String approvalSnapshotHash) { }
    public record SourceResponse(String id, String name, String sourceType, long ownerUserId,
                                 String classification, String matchMode, long aclVersion, String status,
                                 Set<String> permissions) { }
    public record UploadResponse(String id, String state, String mimeType, long sizeBytes, String sha256,
                                 Instant expiresAt, String uploadTarget, String storageAdapter,
                                 boolean storageDurableAcrossRestart, String scanAdapter, String scanMode) { }
    public record FinalizeUploadResponse(String id, String state, String observedSha256, long observedSizeBytes,
                                         String scanAdapter, String scanMode,
                                         boolean malwareScannerAvailable) { }
    public record ScheduledResponse(String versionId, String jobId, String state) { }
    public record VersionResponse(String id, String documentId, String sourceId, String version, String contentHash,
                                  String visibility, String status, long sizeBytes, long aclVersion,
                                  String approvalSnapshotHash) { }
    public record JobResponse(String id, String versionId, String state, int attempt, String errorCode,
                              Instant availableAt, Instant startedAt, Instant finishedAt) { }
}
