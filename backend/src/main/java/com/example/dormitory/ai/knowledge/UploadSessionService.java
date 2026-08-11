package com.example.dormitory.ai.knowledge;

import com.example.dormitory.ai.port.ObjectStoragePort;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 上传状态编排。所有可恢复事实来自 MySQL repository；服务实例不保存会话 Map。
 * 隔离对象使用 session-random key，扫描通过后仅在创建 document version 前提升为内容寻址对象。
 */
public class UploadSessionService {

    public static final long MAXIMUM_TEXT_BYTES = 20L * 1024 * 1024;

    private final ObjectStoragePort storage;
    private final KnowledgeUploadSessionRepository repository;
    private final KnowledgeFileScanner scanner;
    private final Clock clock;

    public UploadSessionService(
            ObjectStoragePort storage,
            KnowledgeUploadSessionRepository repository,
            KnowledgeFileScanner scanner,
            Clock clock) {
        this.storage = java.util.Objects.requireNonNull(storage);
        this.repository = java.util.Objects.requireNonNull(repository);
        this.scanner = java.util.Objects.requireNonNull(scanner);
        this.clock = java.util.Objects.requireNonNull(clock);
        ObjectStoragePort.AdapterStatus status = storage.status();
        if (!status.conditionalCreate() || !status.immutableVersionReads() || !status.streaming()) {
            throw new IllegalStateException("对象存储不满足条件写、固定版本和流式读取合同");
        }
    }

    public UploadSession create(
            long sourceId,
            long ownerUserId,
            String sourcePublicId,
            String expectedSha256,
            long expectedSizeBytes,
            String mimeType,
            Instant expiresAt) {
        String normalizedHash = normalizeSha256(expectedSha256);
        if (sourceId < 1 || ownerUserId < 1 || sourcePublicId == null || sourcePublicId.isBlank()
                || expectedSizeBytes < 1 || expectedSizeBytes > MAXIMUM_TEXT_BYTES
                || !"text/plain".equalsIgnoreCase(mimeType)
                || expiresAt == null || !expiresAt.isAfter(clock.instant())) {
            throw new IllegalArgumentException("上传会话参数不合法；当前仅允许受控纯文本");
        }
        String publicId = UUID.randomUUID().toString();
        String quarantineKey = "quarantine/" + publicId + "/"
                + UUID.randomUUID().toString().replace("-", "");
        KnowledgeUploadSessionRepository.UploadRecord record = repository.create(
                new KnowledgeUploadSessionRepository.CreateUpload(
                        publicId, sourceId, ownerUserId, quarantineKey, normalizedHash,
                        expectedSizeBytes, "text/plain", expiresAt, ownerUserId));
        return view(record);
    }

    public void upload(
            long actorUserId,
            String sessionPublicId,
            InputStream content,
            long declaredContentLength) {
        KnowledgeUploadSessionRepository.UploadRecord record = requireOwned(actorUserId, sessionPublicId);
        requireNotExpired(record, actorUserId);
        if (!"CREATED".equals(record.state())) throw new IllegalStateException("上传 target 已撤销或已使用");
        if (content == null) throw new IllegalArgumentException("上传正文不能为空");
        if (declaredContentLength > record.expectedSizeBytes()
                || declaredContentLength > MAXIMUM_TEXT_BYTES) {
            throw new UploadPayloadTooLargeException("上传正文超过声明大小或服务器硬上限");
        }
        if (declaredContentLength >= 0 && declaredContentLength != record.expectedSizeBytes()) {
            throw new IllegalArgumentException("Content-Length 与上传会话声明不一致");
        }

        Instant uploadStartedAt = clock.instant();
        if (!repository.markUploading(record.publicId(), record.version(), uploadStartedAt, actorUserId)) {
            KnowledgeUploadSessionRepository.UploadRecord current = requireOwned(actorUserId, sessionPublicId);
            requireNotExpired(current, actorUserId);
            throw new IllegalStateException("上传会话状态冲突");
        }
        record = requireOwned(actorUserId, sessionPublicId);
        if (!"UPLOADING".equals(record.state())) {
            throw new IllegalStateException("上传会话未进入写入状态");
        }

        ObjectStoragePort.PutResult put;
        try {
            put = storage.putIfAbsent(new ObjectStoragePort.StreamingObjectWriteRequest(
                    record.quarantineObjectKey(), content, record.expectedSizeBytes(), record.expectedSha256(),
                    Map.of("source", record.sourcePublicId(), "quarantine", "true",
                            "uploadSession", record.publicId())));
        } catch (ObjectStoragePort.ObjectTooLargeException tooLarge) {
            repository.markQuarantined(record.publicId(), "UPLOAD_TOO_LARGE", actorUserId);
            throw new UploadPayloadTooLargeException("上传正文超过声明大小或服务器硬上限");
        } catch (RuntimeException failure) {
            repository.markQuarantined(record.publicId(), "CONDITIONAL_PUT_FAILED", actorUserId);
            throw new KnowledgeQuarantinedException("隔离对象条件写失败");
        }
        if (put == null || !put.created()) {
            repository.markQuarantined(record.publicId(), "CONDITIONAL_CREATE_CONFLICT", actorUserId);
            throw new KnowledgeQuarantinedException("隔离对象已存在，单次条件写被拒绝");
        }
        try {
            validateStoredObject(record, put.object());
        } catch (RuntimeException invalidObject) {
            repository.markQuarantined(record.publicId(), "OBJECT_METADATA_REJECTED", actorUserId);
            throw new KnowledgeQuarantinedException("对象存储返回的固定版本元数据不可信");
        }
        ObjectStoragePort.StoredObject object = put.object();
        Instant revokedAt = clock.instant();
        if (repository.markUploaded(record.publicId(), record.version(), object, revokedAt, actorUserId)) return;

        KnowledgeUploadSessionRepository.UploadRecord current = requireOwned(actorUserId, sessionPublicId);
        if (!"UPLOADED".equals(current.state()) || !sameUploadedObject(current, object)) {
            repository.markQuarantined(record.publicId(), "UPLOAD_STATE_CONFLICT", actorUserId);
            throw new IllegalStateException("上传会话状态冲突");
        }
    }

    public FinalizedUpload finalizeUpload(long actorUserId, String sourcePublicId, String sessionPublicId) {
        KnowledgeUploadSessionRepository.UploadRecord record = requireOwned(actorUserId, sessionPublicId);
        requireSource(record, sourcePublicId);
        if ("FINALIZED".equals(record.state())) return finalized(record);
        if ("QUARANTINED".equals(record.state())) throw quarantined();
        requireNotExpired(record, actorUserId);
        if (!("UPLOADED".equals(record.state()) || "SCANNING".equals(record.state())
                || "SCANNED_CLEAN".equals(record.state()))
                || record.quarantineReference() == null || record.writeRevokedAt() == null) {
            throw new IllegalStateException("上传尚未完成或写能力未撤销");
        }

        ObjectObservation observation;
        try {
            observation = observeFixedObject(record);
        } catch (KnowledgeQuarantinedException rejected) {
            repository.markQuarantined(record.publicId(), scanFailureCode(rejected), actorUserId);
            throw rejected;
        } catch (RuntimeException failure) {
            repository.markQuarantined(record.publicId(), "OBJECT_UNAVAILABLE", actorUserId);
            throw new KnowledgeQuarantinedException("隔离对象无法按固定版本安全读取");
        }
        if ("UPLOADED".equals(record.state())) {
            if (repository.markScanning(record.publicId(), record.version(), observation.sha256(),
                    observation.sizeBytes(), actorUserId)) {
                record = requireOwned(actorUserId, sessionPublicId);
            } else {
                KnowledgeUploadSessionRepository.UploadRecord current = requireOwned(actorUserId, sessionPublicId);
                if ("FINALIZED".equals(current.state())) return finalized(current);
                if ("QUARANTINED".equals(current.state())) throw quarantined();
                if (!"SCANNING".equals(current.state())
                        && !"SCANNED_CLEAN".equals(current.state())) {
                    throw new IllegalStateException("上传扫描状态冲突");
                }
                record = current;
            }
        }
        try {
            validateRecordedObservation(record, observation);
        } catch (KnowledgeQuarantinedException conflictingFact) {
            repository.markQuarantined(record.publicId(), "OBSERVATION_FACT_CONFLICT", actorUserId);
            throw conflictingFact;
        }

        KnowledgeFileScanner.ScanResult scan;
        try (ObjectStoragePort.StoredObjectStream object = storage.open(record.quarantineReference())
                .orElseThrow(() -> new KnowledgeQuarantinedException("隔离对象固定版本不存在"))) {
            validateStoredObject(record, object.object());
            MessageDigest digest = sha256Digest();
            scan = scanner.scan(new DigestInputStream(object.content(), digest), record.expectedSizeBytes());
            String scanSha256 = HexFormat.of().formatHex(digest.digest());
            if (!sameHash(record.expectedSha256(), scanSha256)
                    || !sameHash(record.observedSha256(), scanSha256)) {
                throw new KnowledgeQuarantinedException("固定对象读取流 checksum 不匹配");
            }
        } catch (KnowledgeQuarantinedException rejected) {
            repository.markQuarantined(record.publicId(), scanFailureCode(rejected), actorUserId);
            throw rejected;
        } catch (java.io.IOException | RuntimeException failure) {
            repository.markQuarantined(record.publicId(), "OBJECT_UNAVAILABLE", actorUserId);
            throw new KnowledgeQuarantinedException("隔离对象无法按固定版本安全读取");
        }

        if (!"text/plain".equals(scan.detectedMimeType())
                || scan.observedSizeBytes() != record.expectedSizeBytes()) {
            repository.markQuarantined(record.publicId(), "MIME_OR_SIZE_REJECTED", actorUserId);
            throw new KnowledgeQuarantinedException("隔离对象 MIME 或大小不一致");
        }
        if (repository.markScannedClean(
                record.publicId(), record.version(), scan.detectedMimeType(), actorUserId)) {
            record = requireOwned(actorUserId, sessionPublicId);
        } else {
            KnowledgeUploadSessionRepository.UploadRecord current = requireOwned(actorUserId, sessionPublicId);
            if ("FINALIZED".equals(current.state())) return finalized(current);
            if ("QUARANTINED".equals(current.state())) throw quarantined();
            if (!"SCANNED_CLEAN".equals(current.state())) {
                throw new IllegalStateException("上传扫描完成状态冲突");
            }
            record = current;
        }
        if (!repository.markFinalized(record.publicId(), record.version(), scan.detectedMimeType(), actorUserId)) {
            KnowledgeUploadSessionRepository.UploadRecord current = requireOwned(actorUserId, sessionPublicId);
            if ("FINALIZED".equals(current.state())) return finalized(current);
            if ("QUARANTINED".equals(current.state())) throw quarantined();
            throw new IllegalStateException("上传扫描状态冲突");
        }
        return finalized(requireOwned(actorUserId, sessionPublicId));
    }

    /**
     * 创建 document version 前，将已扫描的随机隔离对象条件复制为内容寻址对象。
     * 复制是幂等的，MySQL 中的 quarantine key 永不被最终 key 覆盖。
     */
    public FinalizedUpload promoteFinalized(long actorUserId, String sourcePublicId, String sessionPublicId) {
        KnowledgeUploadSessionRepository.UploadRecord record = requireOwned(actorUserId, sessionPublicId);
        requireSource(record, sourcePublicId);
        if (!"FINALIZED".equals(record.state()) || !"SCANNED_CLEAN".equals(record.scanState())
                || record.quarantineReference() == null || record.observedSha256() == null
                || record.observedSizeBytes() == null) {
            throw new IllegalStateException("上传会话尚未达到可创建版本的安全终态");
        }
        try (ObjectStoragePort.StoredObjectStream object = storage.open(record.quarantineReference())
                .orElseThrow(() -> new KnowledgeQuarantinedException("已扫描隔离对象固定版本不存在"))) {
            validateStoredObject(record, object.object());
            String finalKey = "sha256/" + record.observedSha256();
            Map<String, String> finalMetadata = Map.of(
                    "source", record.sourcePublicId(),
                    "quarantine", "false",
                    "promotedFromUpload", record.publicId());
            ObjectStoragePort.PutResult promoted = storage.putIfAbsent(
                    new ObjectStoragePort.StreamingObjectWriteRequest(
                            finalKey, object.content(), record.observedSizeBytes(),
                            record.observedSha256(), finalMetadata));
            validatePromotedObject(record, promoted, finalKey);
            verifyPromotedObjectStream(record, promoted);
            return new FinalizedUpload(record.publicId(), record.sourcePublicId(), promoted.object().reference(),
                    record.observedSha256(), record.observedSizeBytes(), record.updatedAt());
        } catch (KnowledgeQuarantinedException rejected) {
            repository.markQuarantined(record.publicId(), "OBJECT_PROMOTION_REJECTED", actorUserId);
            throw rejected;
        } catch (java.io.IOException | RuntimeException failure) {
            repository.markQuarantined(record.publicId(), "OBJECT_PROMOTION_FAILED", actorUserId);
            throw new KnowledgeQuarantinedException("已扫描对象无法安全提升为最终对象");
        }
    }

    public UploadSession get(String publicId) {
        return view(repository.findByPublicId(publicId)
                .orElseThrow(() -> new IllegalArgumentException("上传会话不存在")));
    }

    public ObjectStoragePort.AdapterStatus storageStatus() {
        return storage.status();
    }

    public KnowledgeFileScanner.ScannerStatus scannerStatus() {
        return scanner.status();
    }

    private KnowledgeUploadSessionRepository.UploadRecord requireOwned(long actorUserId, String publicId) {
        KnowledgeUploadSessionRepository.UploadRecord record = repository.findByPublicId(publicId)
                .orElseThrow(() -> new IllegalArgumentException("上传会话不存在"));
        if (record.ownerUserId() != actorUserId) throw new SecurityException("上传会话不可见");
        return record;
    }

    private void requireSource(KnowledgeUploadSessionRepository.UploadRecord record, String sourcePublicId) {
        if (sourcePublicId == null || !record.sourcePublicId().equals(sourcePublicId)) {
            throw new SecurityException("上传来源不匹配");
        }
    }

    private void requireNotExpired(KnowledgeUploadSessionRepository.UploadRecord record, long actorUserId) {
        if (!record.expiresAt().isAfter(clock.instant())) {
            repository.markExpired(record.publicId(), actorUserId);
            throw new IllegalStateException("上传会话已过期");
        }
    }

    private void validateStoredObject(
            KnowledgeUploadSessionRepository.UploadRecord record,
            ObjectStoragePort.StoredObject object) {
        if (object == null || !record.quarantineObjectKey().equals(object.reference().objectKey())
                || object.sizeBytes() != record.expectedSizeBytes()
                || !sameHash(record.expectedSha256(), object.sha256())) {
            throw new IllegalArgumentException("服务端观测到的对象 key/size/checksum 不匹配");
        }
        if (record.observedSha256() != null && !sameHash(record.observedSha256(), object.sha256())) {
            throw new KnowledgeQuarantinedException("固定对象 checksum 已变化");
        }
        if (record.quarantineReference() != null
                && !record.quarantineReference().equals(object.reference())) {
            throw new KnowledgeQuarantinedException("固定对象 version/ETag 已变化");
        }
        if (!record.sourcePublicId().equals(object.metadata().get("source"))
                || !record.publicId().equals(object.metadata().get("uploadSession"))
                || !"true".equals(object.metadata().get("quarantine"))) {
            throw new KnowledgeQuarantinedException("固定对象 metadata 与上传会话绑定不一致");
        }
    }

    private boolean sameUploadedObject(
            KnowledgeUploadSessionRepository.UploadRecord record,
            ObjectStoragePort.StoredObject object) {
        return record.quarantineReference() != null
                && record.quarantineReference().equals(object.reference())
                && record.expectedSizeBytes() == object.sizeBytes()
                && sameHash(record.expectedSha256(), object.sha256());
    }

    private void validatePromotedObject(
            KnowledgeUploadSessionRepository.UploadRecord record,
            ObjectStoragePort.PutResult promoted,
            String expectedKey) {
        if (promoted == null || promoted.object() == null
                || !expectedKey.equals(promoted.object().reference().objectKey())
                || promoted.object().sizeBytes() != record.observedSizeBytes()
                || !sameHash(record.observedSha256(), promoted.object().sha256())) {
            throw new KnowledgeQuarantinedException("最终对象 key/version/ETag/checksum/size 不可信");
        }
        Map<String, String> metadata = promoted.object().metadata();
        if (!"false".equals(metadata.get("quarantine"))) {
            throw new KnowledgeQuarantinedException("最终对象 metadata 未标记为非隔离对象");
        }
        if (promoted.created()) {
            if (!record.sourcePublicId().equals(metadata.get("source"))
                    || !record.publicId().equals(metadata.get("promotedFromUpload"))) {
                throw new KnowledgeQuarantinedException("新建最终对象 metadata 与上传会话不一致");
            }
        } else if (blankMetadata(metadata.get("source"))
                || blankMetadata(metadata.get("promotedFromUpload"))) {
            throw new KnowledgeQuarantinedException("去重最终对象缺少既有写入来源 metadata");
        }
    }

    private void verifyPromotedObjectStream(
            KnowledgeUploadSessionRepository.UploadRecord record,
            ObjectStoragePort.PutResult promoted) {
        try (ObjectStoragePort.StoredObjectStream stream = storage.open(promoted.object().reference())
                .orElseThrow(() -> new KnowledgeQuarantinedException("最终对象固定版本不存在"))) {
            if (!promoted.object().reference().equals(stream.object().reference())
                    || stream.object().sizeBytes() != record.observedSizeBytes()
                    || !sameHash(stream.object().sha256(), record.observedSha256())
                    || !stream.object().metadata().equals(promoted.object().metadata())) {
                throw new KnowledgeQuarantinedException("最终对象固定版本 metadata 与条件写结果不一致");
            }
            MessageDigest digest = sha256Digest();
            byte[] buffer = new byte[8 * 1024];
            long size = 0;
            int read;
            while ((read = stream.content().read(buffer)) != -1) {
                size += read;
                if (size > record.observedSizeBytes() || size > MAXIMUM_TEXT_BYTES) {
                    throw new KnowledgeQuarantinedException("最终对象读取流超过大小硬上限");
                }
                digest.update(buffer, 0, read);
            }
            String sha256 = HexFormat.of().formatHex(digest.digest());
            if (size != record.observedSizeBytes() || !sameHash(sha256, record.observedSha256())) {
                throw new KnowledgeQuarantinedException("最终对象读取流 checksum/size 不一致");
            }
        } catch (IOException failure) {
            throw new ObjectStoragePort.ObjectStorageException("最终对象固定版本读取失败", failure);
        }
    }

    private boolean blankMetadata(String value) {
        return value == null || value.isBlank();
    }

    private ObjectObservation observeFixedObject(KnowledgeUploadSessionRepository.UploadRecord record) {
        try (ObjectStoragePort.StoredObjectStream stream = storage.open(record.quarantineReference())
                .orElseThrow(() -> new KnowledgeQuarantinedException("隔离对象固定版本不存在"))) {
            validateStoredObject(record, stream.object());
            MessageDigest digest = sha256Digest();
            byte[] buffer = new byte[8 * 1024];
            long observedSize = 0;
            int read;
            while ((read = stream.content().read(buffer)) != -1) {
                observedSize += read;
                if (observedSize > record.expectedSizeBytes() || observedSize > MAXIMUM_TEXT_BYTES) {
                    throw new KnowledgeQuarantinedException("固定对象读取流 size 超过声明或硬上限");
                }
                digest.update(buffer, 0, read);
            }
            String observedSha256 = HexFormat.of().formatHex(digest.digest());
            if (observedSize != record.expectedSizeBytes()
                    || stream.object().sizeBytes() != observedSize) {
                throw new KnowledgeQuarantinedException("固定对象读取流 size 不匹配");
            }
            if (!sameHash(record.expectedSha256(), observedSha256)
                    || !sameHash(stream.object().sha256(), observedSha256)) {
                throw new KnowledgeQuarantinedException("固定对象读取流 checksum 不匹配");
            }
            return new ObjectObservation(observedSha256, observedSize);
        } catch (IOException failure) {
            throw new ObjectStoragePort.ObjectStorageException("固定对象读取失败", failure);
        }
    }

    private void validateRecordedObservation(
            KnowledgeUploadSessionRepository.UploadRecord record,
            ObjectObservation observation) {
        if (record.observedSizeBytes() == null || record.observedSizeBytes() != observation.sizeBytes()
                || !sameHash(record.observedSha256(), observation.sha256())) {
            throw new KnowledgeQuarantinedException("MySQL 固定对象观测事实不一致");
        }
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 不可用", unavailable);
        }
    }

    private boolean sameHash(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(
                left.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII),
                right.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    private String normalizeSha256(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("上传 checksum 不合法");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private String scanFailureCode(KnowledgeQuarantinedException rejected) {
        String message = rejected.getMessage() == null ? "" : rejected.getMessage();
        if (message.contains("UTF-8")) return "INVALID_UTF8";
        if (message.contains("checksum")) return "CHECKSUM_MISMATCH";
        if (message.contains("size")) return "SIZE_MISMATCH";
        if (message.contains("metadata")) return "METADATA_MISMATCH";
        if (message.contains("version/ETag")) return "OBJECT_VERSION_CHANGED";
        if (message.contains("签名")) return "BINARY_SIGNATURE";
        if (message.contains("内容安全")) return "CONTENT_REJECTED";
        if (message.contains("不存在")) return "OBJECT_MISSING";
        return "SCAN_REJECTED";
    }

    private KnowledgeQuarantinedException quarantined() {
        return new KnowledgeQuarantinedException("上传会话已被隔离");
    }

    private record ObjectObservation(String sha256, long sizeBytes) {
    }

    private UploadSession view(KnowledgeUploadSessionRepository.UploadRecord record) {
        return new UploadSession(record.publicId(), record.ownerUserId(), record.sourcePublicId(),
                record.quarantineObjectKey(), record.expectedSha256(), record.expectedSizeBytes(),
                record.declaredMimeType(), record.expiresAt(), record.stateEnum(), record.scanState(),
                record.writeRevokedAt(), record.quarantineReference(),
                "FINALIZED".equals(record.state()) ? finalized(record) : null);
    }

    private FinalizedUpload finalized(KnowledgeUploadSessionRepository.UploadRecord record) {
        if (record.quarantineReference() == null || record.observedSha256() == null
                || record.observedSizeBytes() == null || record.updatedAt() == null) {
            throw new IllegalStateException("上传终态事实不完整");
        }
        return new FinalizedUpload(record.publicId(), record.sourcePublicId(), record.quarantineReference(),
                record.observedSha256(), record.observedSizeBytes(), record.updatedAt());
    }

    public record UploadSession(
            String publicId,
            long ownerUserId,
            String sourcePublicId,
            String quarantineObjectKey,
            String expectedSha256,
            long expectedSizeBytes,
            String mimeType,
            Instant expiresAt,
            UploadSessionState state,
            String scanState,
            Instant writeRevokedAt,
            ObjectStoragePort.ObjectReference objectReference,
            FinalizedUpload finalized) {
    }

    public record FinalizedUpload(
            String uploadSessionPublicId,
            String sourcePublicId,
            ObjectStoragePort.ObjectReference objectReference,
            String observedSha256,
            long observedSizeBytes,
            Instant finalizedAt) {
    }
}
