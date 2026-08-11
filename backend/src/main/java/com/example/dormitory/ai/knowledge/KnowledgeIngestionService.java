package com.example.dormitory.ai.knowledge;

import com.example.dormitory.ai.port.ObjectStoragePort;
import com.example.dormitory.ai.security.PiiClassificationService;
import com.example.dormitory.ai.security.PromptInjectionGuard;
import com.example.dormitory.ai.security.SensitiveDataBlockedException;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 受控纯文本知识摄取编排。上传结果始终被当作不可信输入，并在 worker 阶段重新固定对象版本、
 * ETag、checksum、UTF-8、Prompt Injection 和 PII 边界。
 */
public class KnowledgeIngestionService {

    private static final int MAX_CHUNK_CHARACTERS = 2_000;

    private final KnowledgeSourceRepository sources;
    private final KnowledgeVersionRepository versions;
    private final KnowledgeIngestionJobRepository jobs;
    private final ObjectStoragePort storage;
    private final PromptInjectionGuard injectionGuard;
    private final PiiClassificationService classificationService;
    private final Clock clock;
    private final java.util.function.Predicate<String> sourceEnabled;
    private final SafeKnowledgeService retrieval;

    public KnowledgeIngestionService(
            KnowledgeSourceRepository sources,
            KnowledgeVersionRepository versions,
            KnowledgeIngestionJobRepository jobs,
            ObjectStoragePort storage,
            PromptInjectionGuard injectionGuard,
            PiiClassificationService classificationService) {
        this(sources, versions, jobs, storage, injectionGuard, classificationService, Clock.systemUTC(), ignored -> true,
                null);
    }

    KnowledgeIngestionService(
            KnowledgeSourceRepository sources,
            KnowledgeVersionRepository versions,
            KnowledgeIngestionJobRepository jobs,
            ObjectStoragePort storage,
            PromptInjectionGuard injectionGuard,
            PiiClassificationService classificationService,
            Clock clock) {
        this(sources, versions, jobs, storage, injectionGuard, classificationService, clock, ignored -> true, null);
    }

    public KnowledgeIngestionService(
            KnowledgeSourceRepository sources,
            KnowledgeVersionRepository versions,
            KnowledgeIngestionJobRepository jobs,
            ObjectStoragePort storage,
            PromptInjectionGuard injectionGuard,
            PiiClassificationService classificationService,
            Clock clock,
            java.util.function.Predicate<String> sourceEnabled) {
        this(sources, versions, jobs, storage, injectionGuard, classificationService, clock, sourceEnabled, null);
    }

    public KnowledgeIngestionService(
            KnowledgeSourceRepository sources,
            KnowledgeVersionRepository versions,
            KnowledgeIngestionJobRepository jobs,
            ObjectStoragePort storage,
            PromptInjectionGuard injectionGuard,
            PiiClassificationService classificationService,
            Clock clock,
            java.util.function.Predicate<String> sourceEnabled,
            SafeKnowledgeService retrieval) {
        this.sources = java.util.Objects.requireNonNull(sources);
        this.versions = java.util.Objects.requireNonNull(versions);
        this.jobs = java.util.Objects.requireNonNull(jobs);
        this.storage = java.util.Objects.requireNonNull(storage);
        this.injectionGuard = java.util.Objects.requireNonNull(injectionGuard);
        this.classificationService = java.util.Objects.requireNonNull(classificationService);
        this.clock = java.util.Objects.requireNonNull(clock);
        this.sourceEnabled = java.util.Objects.requireNonNull(sourceEnabled);
        this.retrieval = retrieval;
    }

    @Transactional
    public ScheduledIngestion enqueue(
            UploadSessionService.FinalizedUpload upload,
            EnqueueCommand command) {
        validate(upload, command);
        KnowledgeSourceRepository.KnowledgeSource source = sources.findByPublicId(upload.sourcePublicId())
                .filter(item -> "ACTIVE".equals(item.status()))
                .orElseThrow(() -> new IllegalArgumentException("知识来源不存在或不可用"));
        requireSourceEnabled(source.publicId());
        if (source.classification() == com.example.dormitory.ai.security.DataClassification.L3) {
            throw new IllegalArgumentException("L3 来源不得进入知识摄取");
        }
        KnowledgeVersionRepository.KnowledgeVersion version = versions.createPending(
                new KnowledgeVersionRepository.CreateVersion(source.publicId(), upload.uploadSessionPublicId(),
                        upload.finalizedAt(), command.externalKeyHmac(),
                        command.externalKeyKeyVersion(), command.title(), command.version(), upload.observedSha256(),
                        command.visibility(), upload.objectReference(), "text/plain", upload.observedSizeBytes(),
                        command.parserVersion(), command.chunkPolicyVersion(), command.actorUserId()));
        KnowledgeIngestionJobRepository.IngestionJob job = jobs.enqueue(
                // MySQL TIMESTAMP(6) may round a just-created timestamp slightly ahead of the next JVM Instant.
                // A one-second eligibility margin keeps an immediate local queue deterministic without delaying work.
                version.id(), command.actorUserId(), clock.instant().minusSeconds(1));
        return new ScheduledIngestion(version.publicId(), job.publicId());
    }

    public Optional<ProcessedIngestion> processNext(String workerId) {
        KnowledgeIngestionJobRepository.IngestionJob job = jobs.claimNext(workerId, clock.instant()).orElse(null);
        if (job == null) return Optional.empty();
        return processClaimed(job, workerId);
    }

    /** 按 outbox 事件绑定的 job 定向处理，避免消费者领取到另一个聚合的任务。 */
    public Optional<ProcessedIngestion> processJob(String jobPublicId, String workerId) {
        KnowledgeIngestionJobRepository.IngestionJob job = jobs.claim(jobPublicId, workerId, clock.instant())
                .orElse(null);
        if (job == null || "SUCCEEDED".equals(job.state()) || "FAILED".equals(job.state())) {
            return Optional.empty();
        }
        return processClaimed(job, workerId);
    }

    public Optional<ProcessedIngestion> processJob(
            String jobPublicId, String expectedVersionPublicId, String workerId) {
        KnowledgeIngestionJobRepository.IngestionJob job = jobs.findByPublicId(jobPublicId)
                .orElseThrow(() -> new IllegalArgumentException("知识摄取任务不存在"));
        String actualVersion = versions.findById(job.documentVersionId())
                .orElseThrow(() -> new IllegalStateException("摄取任务关联版本不存在"))
                .publicId();
        if (!java.util.Objects.equals(expectedVersionPublicId, actualVersion)) {
            throw new SecurityException("知识摄取 outbox 聚合绑定不一致");
        }
        return processJob(jobPublicId, workerId);
    }

    private Optional<ProcessedIngestion> processClaimed(
            KnowledgeIngestionJobRepository.IngestionJob job, String workerId) {
        KnowledgeVersionRepository.KnowledgeVersion version = versions.findById(job.documentVersionId())
                .orElseThrow(() -> new IllegalStateException("摄取任务关联版本不存在"));
        try {
            requireSourceEnabled(version.sourcePublicId());
        } catch (RuntimeException disabled) {
            jobs.requeue(job.publicId(), workerId, clock.instant().plusSeconds(30));
            throw disabled;
        }
        try {
            List<KnowledgeVersionRepository.NewChunk> chunks = inspectAndChunk(version);
            versions.replaceChunksAndMarkReady(version.publicId(), chunks, job.initiatedByUserId());
            if (retrieval != null) {
                try {
                    retrieval.indexVersion(version.publicId());
                } catch (RuntimeException ignored) {
                    // 向量索引是可重建投影；MySQL chunk 已成功落盘时保留关键词降级能力。
                }
            }
            jobs.succeed(job.publicId(), workerId, clock.instant());
            return Optional.of(new ProcessedIngestion(version.publicId(), chunks.size()));
        } catch (RuntimeException exception) {
            Failure failure = failure(exception);
            versions.markQuarantined(version.publicId(), job.initiatedByUserId());
            jobs.fail(job.publicId(), workerId, failure.code(), failure.safeMessage(), clock.instant());
            throw new KnowledgeQuarantinedException(failure.safeMessage());
        }
    }

    public void markDead(String jobPublicId, String errorCode) {
        KnowledgeIngestionJobRepository.IngestionJob job = jobs.findByPublicId(jobPublicId).orElse(null);
        if (job == null || Set.of("SUCCEEDED", "FAILED", "DEAD").contains(job.state())) return;
        versions.findById(job.documentVersionId())
                .ifPresent(version -> versions.markQuarantined(version.publicId(), job.initiatedByUserId()));
        jobs.dead(jobPublicId, errorCode, clock.instant());
    }

    public void markDead(String jobPublicId, String expectedVersionPublicId, String errorCode) {
        KnowledgeIngestionJobRepository.IngestionJob job = jobs.findByPublicId(jobPublicId).orElse(null);
        if (job == null) return;
        String actualVersion = versions.findById(job.documentVersionId())
                .map(KnowledgeVersionRepository.KnowledgeVersion::publicId).orElse(null);
        if (!java.util.Objects.equals(expectedVersionPublicId, actualVersion)) return;
        markDead(jobPublicId, errorCode);
    }

    public KnowledgeVersionRepository.PublicApproval approvePublic(
            String versionPublicId,
            long reviewerUserId,
            long idempotencyRecordId,
            String approvalPolicyVersion) {
        return versions.approvePublic(versionPublicId, reviewerUserId, idempotencyRecordId,
                approvalPolicyVersion);
    }

    public KnowledgeVersionRepository.PublicApproval approvePublic(
            String versionPublicId, String expectedContentHash, String expectedSnapshotHash,
            long reviewerUserId, long idempotencyRecordId, String approvalPolicyVersion) {
        return versions.approvePublic(versionPublicId, expectedContentHash, expectedSnapshotHash,
                reviewerUserId, idempotencyRecordId, approvalPolicyVersion);
    }

    public KnowledgeVersionRepository.PublicApprovalPreview publicApprovalPreview(String versionPublicId) {
        return versions.publicApprovalPreview(versionPublicId);
    }

    public KnowledgeVersionRepository.KnowledgeVersion activate(String versionPublicId, long actorUserId) {
        return versions.activate(versionPublicId, actorUserId);
    }

    public KnowledgeVersionRepository.KnowledgeVersion retire(String versionPublicId, long actorUserId) {
        return versions.retire(versionPublicId, actorUserId);
    }

    public KnowledgeVersionRepository.KnowledgeVersion rollback(String versionPublicId, long actorUserId) {
        return versions.activate(versionPublicId, actorUserId);
    }

    public boolean canRead(String versionPublicId, Set<String> actorPermissions) {
        KnowledgeVersionRepository.KnowledgeVersion version = versions.findByPublicId(versionPublicId)
                .orElse(null);
        return version != null && sourceEnabled.test(version.sourcePublicId())
                && versions.canRead(versionPublicId,
                actorPermissions == null ? Set.of() : Set.copyOf(actorPermissions));
    }

    private List<KnowledgeVersionRepository.NewChunk> inspectAndChunk(
            KnowledgeVersionRepository.KnowledgeVersion version) {
        com.example.dormitory.ai.security.DataClassification sourceClassification = sources
                .findByPublicId(version.sourcePublicId())
                .orElseThrow(() -> new IntegrityFailure("知识来源不存在"))
                .classification();
        if (version.sizeBytes() < 1 || version.sizeBytes() > UploadSessionService.MAXIMUM_TEXT_BYTES) {
            throw new IntegrityFailure("固定对象大小超过摄取硬上限");
        }
        ObjectStoragePort.StoredObjectContent object = storage.get(
                        version.objectReference(), version.sizeBytes())
                .orElseThrow(() -> new IntegrityFailure("固定隔离对象不存在"));
        byte[] bytes = object.content();
        String observedHash = sha256(bytes);
        if (!same(observedHash, version.contentHash()) || bytes.length != version.sizeBytes()
                || !object.object().reference().equals(version.objectReference())
                || !same(object.object().sha256(), version.contentHash())) {
            throw new IntegrityFailure("固定对象版本或 checksum 不一致");
        }
        String text = strictUtf8(bytes);
        if (text.isBlank() || text.indexOf('\0') >= 0) throw new IntegrityFailure("纯文本内容不合法");
        if (injectionGuard.inspect(text).blocked()) throw new InjectionFailure();
        com.example.dormitory.ai.security.PiiRedactionService.RedactionResult redacted;
        try {
            redacted = classificationService.redact(text, "knowledge-ingestion:" + version.sourcePublicId(),
                    sourceClassification);
        } catch (SensitiveDataBlockedException blocked) {
            throw new SensitiveFailure();
        }
        List<String> contents = split(redacted.redactedText());
        List<KnowledgeVersionRepository.NewChunk> chunks = new ArrayList<>();
        for (int index = 0; index < contents.size(); index++) {
            String content = contents.get(index);
            chunks.add(new KnowledgeVersionRepository.NewChunk(index, content, sha256(content),
                    "段落 " + (index + 1), "{\"classification\":\""
                    + redacted.classification().name() + "\",\"redactionPolicy\":\""
                    + redacted.policyVersion() + "\"}"));
        }
        if (chunks.isEmpty()) throw new IntegrityFailure("纯文本没有可摄取内容");
        return List.copyOf(chunks);
    }

    private List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : text.split("(?:\\r?\\n){2,}")) {
            String normalized = paragraph.trim();
            if (normalized.isEmpty()) continue;
            int cursor = 0;
            while (cursor < normalized.length()) {
                int end = Math.min(cursor + MAX_CHUNK_CHARACTERS, normalized.length());
                if (end < normalized.length() && Character.isHighSurrogate(normalized.charAt(end - 1))) end--;
                String part = normalized.substring(cursor, end);
                if (!current.isEmpty() && current.length() + 2 + part.length() > MAX_CHUNK_CHARACTERS) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }
                if (!current.isEmpty()) current.append("\n\n");
                current.append(part);
                if (current.length() >= MAX_CHUNK_CHARACTERS) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }
                cursor = end;
            }
        }
        if (!current.isEmpty()) chunks.add(current.toString());
        return chunks;
    }

    private void validate(UploadSessionService.FinalizedUpload upload, EnqueueCommand command) {
        if (upload == null || command == null || blank(upload.uploadSessionPublicId())
                || blank(upload.sourcePublicId()) || upload.objectReference() == null
                || !isSha256(upload.observedSha256()) || upload.observedSizeBytes() < 1
                || upload.finalizedAt() == null || !isSha256(command.externalKeyHmac())
                || command.externalKeyKeyVersion() < 1 || blank(command.title()) || blank(command.version())
                || command.visibility() == null || !"plain-text-v1".equals(command.parserVersion())
                || !"paragraph-2000-v1".equals(command.chunkPolicyVersion()) || command.actorUserId() < 1) {
            throw new IllegalArgumentException("知识摄取参数不合法");
        }
    }

    private String strictUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            throw new IntegrityFailure("对象不是有效 UTF-8 纯文本");
        }
    }

    private Failure failure(RuntimeException exception) {
        if (exception instanceof InjectionFailure) {
            return new Failure("PROMPT_INJECTION", "知识文本未通过 Prompt Injection 安全扫描");
        }
        if (exception instanceof SensitiveFailure) {
            return new Failure("SENSITIVE_DATA", "知识文本包含禁止进入 AI 的敏感数据");
        }
        return new Failure("OBJECT_INTEGRITY", "知识对象完整性校验失败");
    }

    private boolean same(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(
                left.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII),
                right.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    private String sha256(String content) {
        return sha256(content.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("知识内容哈希不可用", exception);
        }
    }

    private boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-fA-F]{64}");
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private void requireSourceEnabled(String sourcePublicId) {
        if (!sourceEnabled.test(sourcePublicId)) {
            throw new com.example.dormitory.ai.api.AiApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "AI_KNOWLEDGE_SOURCE_DISABLED", "知识来源当前已由 Kill Switch 关闭", true);
        }
    }

    public record EnqueueCommand(
            String externalKeyHmac,
            int externalKeyKeyVersion,
            String title,
            String version,
            KnowledgeVisibility visibility,
            String parserVersion,
            String chunkPolicyVersion,
            long actorUserId) {
    }

    public record ScheduledIngestion(String versionPublicId, String jobPublicId) {
    }

    public record ProcessedIngestion(String versionPublicId, int chunkCount) {
    }

    private record Failure(String code, String safeMessage) {
    }

    private static final class IntegrityFailure extends RuntimeException {
        private IntegrityFailure(String message) { super(message); }
    }

    private static final class InjectionFailure extends RuntimeException {
    }

    private static final class SensitiveFailure extends RuntimeException {
    }
}
