package com.example.dormitory.ai.port;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 对象存储边界。生产调用必须使用服务端生成的 key、条件创建和固定 version/ETag 读取；
 * byte[] 方法只为现有小型 contract/fixture 保留，上传 HTTP 链使用流式方法。
 */
public interface ObjectStoragePort {

    long MAXIMUM_BUFFERED_GET_BYTES = 20L * 1024 * 1024;
    int MAXIMUM_OBJECT_KEY_LENGTH = 512;
    int MAXIMUM_PROVIDER_REFERENCE_LENGTH = 256;

    default StoredObject put(ObjectWriteRequest request) {
        Objects.requireNonNull(request, "对象写请求不能为空");
        String normalized = requireSha256(request.expectedSha256());
        return putIfAbsent(new StreamingObjectWriteRequest(
                "sha256/" + normalized,
                new ByteArrayInputStream(request.content()),
                request.content().length,
                normalized,
                request.metadata())).object();
    }

    PutResult putIfAbsent(StreamingObjectWriteRequest request);

    Optional<StoredObjectStream> open(ObjectReference reference);

    default Optional<StoredObjectContent> get(ObjectReference reference) {
        return get(reference, MAXIMUM_BUFFERED_GET_BYTES);
    }

    default Optional<StoredObjectContent> get(ObjectReference reference, long maximumBytes) {
        Objects.requireNonNull(reference, "对象引用不能为空");
        if (maximumBytes < 1 || maximumBytes > MAXIMUM_BUFFERED_GET_BYTES) {
            throw new IllegalArgumentException("对象缓冲读取上限不合法");
        }
        Optional<StoredObjectStream> opened = open(reference);
        if (opened.isEmpty()) return Optional.empty();
        try (StoredObjectStream stream = opened.orElseThrow()) {
            if (!reference.equals(stream.object().reference())) {
                throw new ObjectStorageException("固定对象引用与读取结果不一致");
            }
            if (stream.object().sizeBytes() > maximumBytes) {
                throw new ObjectTooLargeException("对象超过缓冲读取硬上限");
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream(
                    (int) Math.min(stream.object().sizeBytes(), maximumBytes));
            byte[] buffer = new byte[8 * 1024];
            long total = 0;
            int read;
            while ((read = stream.content().read(buffer)) != -1) {
                total += read;
                if (total > maximumBytes) {
                    throw new ObjectTooLargeException("对象读取流超过缓冲读取硬上限");
                }
                output.write(buffer, 0, read);
            }
            if (total != stream.object().sizeBytes()) {
                throw new ObjectStorageException("对象读取流大小与固定版本元数据不一致");
            }
            return Optional.of(new StoredObjectContent(stream.object(), output.toByteArray()));
        } catch (IOException failure) {
            throw new ObjectStorageException("固定对象读取失败", failure);
        }
    }

    boolean delete(ObjectReference reference);

    boolean exists(ObjectReference reference);

    AdapterStatus status();

    record ObjectWriteRequest(byte[] content, String expectedSha256, Map<String, String> metadata) {
        public ObjectWriteRequest {
            if (content == null) throw new IllegalArgumentException("对象正文不能为空");
            content = Arrays.copyOf(content, content.length);
            metadata = requireMetadata(metadata);
        }

        @Override
        public byte[] content() {
            return Arrays.copyOf(content, content.length);
        }
    }

    record StreamingObjectWriteRequest(
            String objectKey,
            InputStream content,
            long expectedSizeBytes,
            String expectedSha256,
            Map<String, String> metadata) {
        public StreamingObjectWriteRequest {
            if (objectKey == null || objectKey.isBlank() || content == null || expectedSizeBytes < 1) {
                throw new IllegalArgumentException("流式对象写请求不合法");
            }
            objectKey = requireObjectKey(objectKey);
            expectedSha256 = requireSha256(expectedSha256);
            metadata = requireMetadata(metadata);
        }
    }

    record ObjectReference(String objectKey, String versionId, String etag) {
        public ObjectReference {
            objectKey = requireObjectKey(objectKey);
            versionId = requireProviderReference("versionId", versionId);
            etag = requireProviderReference("ETag", etag);
        }
    }

    /**
     * 固定版本对象的可验证读取元数据。metadata 必须来自该固定版本，而不是调用方缓存。
     */
    record StoredObject(
            ObjectReference reference,
            String sha256,
            long sizeBytes,
            Map<String, String> metadata) {
        public StoredObject {
            Objects.requireNonNull(reference, "对象引用不能为空");
            sha256 = requireSha256(sha256);
            if (sizeBytes < 0) throw new IllegalArgumentException("对象大小不合法");
            metadata = requireMetadata(metadata);
        }

        public StoredObject(ObjectReference reference, String sha256, long sizeBytes) {
            this(reference, sha256, sizeBytes, Map.of());
        }
    }

    record PutResult(StoredObject object, boolean created) {
        public PutResult {
            Objects.requireNonNull(object, "存储结果不能为空");
        }
    }

    record StoredObjectStream(StoredObject object, InputStream content) implements AutoCloseable {
        public StoredObjectStream {
            Objects.requireNonNull(object, "对象元数据不能为空");
            Objects.requireNonNull(content, "对象流不能为空");
        }

        @Override
        public void close() throws IOException {
            content.close();
        }
    }

    record StoredObjectContent(StoredObject object, byte[] content) {
        public StoredObjectContent {
            if (content == null) throw new IllegalArgumentException("对象正文不能为空");
            content = Arrays.copyOf(content, content.length);
        }

        @Override
        public byte[] content() {
            return Arrays.copyOf(content, content.length);
        }
    }

    record AdapterStatus(
            String code,
            boolean durableAcrossProcessRestart,
            boolean conditionalCreate,
            boolean immutableVersionReads,
            boolean streaming,
            boolean productionApproved) {
        public AdapterStatus {
            if (code == null || !code.matches("[a-z0-9._-]{2,64}")) {
                throw new IllegalArgumentException("对象适配器 code 不合法");
            }
        }
    }

    class ObjectStorageException extends RuntimeException {
        public ObjectStorageException(String message) { super(message); }
        public ObjectStorageException(String message, Throwable cause) { super(message, cause); }
    }

    class ObjectTooLargeException extends IllegalArgumentException {
        public ObjectTooLargeException(String message) { super(message); }
    }

    static String requireSha256(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("对象 checksum 不合法");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static String requireObjectKey(String value) {
        if (value == null || value.isBlank() || value.length() > MAXIMUM_OBJECT_KEY_LENGTH
                || !value.equals(value.trim()) || value.startsWith("/") || value.endsWith("/")
                || value.contains("..") || value.indexOf('\\') >= 0 || value.indexOf(':') >= 0
                || !value.matches("[A-Za-z0-9][A-Za-z0-9/_.-]*")
                || value.chars().anyMatch(character -> character < 0x20 || character == 0x7f)) {
            throw new IllegalArgumentException("对象 key 不合法");
        }
        return value;
    }

    private static String requireProviderReference(String name, String value) {
        if (value == null || value.isBlank() || value.length() > MAXIMUM_PROVIDER_REFERENCE_LENGTH
                || !value.equals(value.trim())
                || value.chars().anyMatch(character -> character < 0x21 || character == 0x7f)) {
            throw new IllegalArgumentException("对象 " + name + " 不合法");
        }
        return value;
    }

    private static Map<String, String> requireMetadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) return Map.of();
        if (metadata.size() > 32) throw new IllegalArgumentException("对象 metadata 条目过多");
        metadata.forEach((key, value) -> {
            if (key == null || !key.matches("[A-Za-z0-9._-]{1,64}")
                    || value == null || value.length() > 512
                    || value.chars().anyMatch(character -> character < 0x20 || character == 0x7f)) {
                throw new IllegalArgumentException("对象 metadata 不合法");
            }
        });
        return Map.copyOf(metadata);
    }
}
