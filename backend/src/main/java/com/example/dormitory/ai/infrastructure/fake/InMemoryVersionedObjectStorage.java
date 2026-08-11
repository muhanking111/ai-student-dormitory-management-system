package com.example.dormitory.ai.infrastructure.fake;

import com.example.dormitory.ai.port.ObjectStoragePort;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** 仅供 dev/test 的不可变版本 Fake；进程重启会丢对象，状态会明确报告为非持久。 */
public class InMemoryVersionedObjectStorage implements ObjectStoragePort {

    private static final Pattern CONTENT_KEY = Pattern.compile("sha256/[0-9a-f]{64}");
    private static final Pattern QUARANTINE_KEY = Pattern.compile(
            "quarantine/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/[0-9a-f]{32}");
    private final long maximumBytes;
    private final Map<String, Entry> objects = new ConcurrentHashMap<>();

    public InMemoryVersionedObjectStorage(long maximumBytes) {
        if (maximumBytes < 1) throw new IllegalArgumentException("对象大小上限不合法");
        this.maximumBytes = maximumBytes;
    }

    @Override
    public PutResult putIfAbsent(StreamingObjectWriteRequest request) {
        validateKey(request.objectKey());
        if (request.expectedSizeBytes() > maximumBytes) throw new ObjectTooLargeException("对象超过适配器大小上限");
        byte[] content = readBounded(request);
        String observed = sha256(content);
        if (!constantTimeEquals(observed, request.expectedSha256())) {
            throw new IllegalArgumentException("对象 checksum 不匹配");
        }
        if (CONTENT_KEY.matcher(request.objectKey()).matches()
                && !request.objectKey().equals("sha256/" + observed)) {
            throw new IllegalArgumentException("内容寻址 key 与 checksum 不匹配");
        }
        ObjectReference reference = new ObjectReference(
                request.objectKey(), "mem-" + UUID.randomUUID(), observed);
        Entry candidate = new Entry(new StoredObject(reference, observed, content.length, request.metadata()), content);
        Entry existing = objects.putIfAbsent(request.objectKey(), candidate);
        if (existing == null) return new PutResult(candidate.object(), true);
        if (!constantTimeEquals(existing.object().sha256(), observed)
                || existing.object().sizeBytes() != content.length) {
            throw new ObjectStorageException("条件创建失败：对象 key 已被不同正文占用");
        }
        return new PutResult(existing.object(), false);
    }

    @Override
    public Optional<StoredObjectStream> open(ObjectReference reference) {
        validateReferenceShape(reference);
        Entry entry = objects.get(reference.objectKey());
        if (entry == null || !entry.object().reference().equals(reference)) return Optional.empty();
        return Optional.of(new StoredObjectStream(entry.object(),
                new ByteArrayInputStream(Arrays.copyOf(entry.content(), entry.content().length))));
    }

    @Override
    public boolean delete(ObjectReference reference) {
        validateReferenceShape(reference);
        Entry current = objects.get(reference.objectKey());
        return current != null && current.object().reference().equals(reference)
                && objects.remove(reference.objectKey(), current);
    }

    @Override
    public boolean exists(ObjectReference reference) {
        validateReferenceShape(reference);
        Entry current = objects.get(reference.objectKey());
        return current != null && current.object().reference().equals(reference);
    }

    @Override
    public AdapterStatus status() {
        return new AdapterStatus("fake-in-memory-versioned-v1", false, true, true, true, false);
    }

    private byte[] readBounded(StreamingObjectWriteRequest request) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(
                    (int) Math.min(request.expectedSizeBytes(), 64L * 1024));
            byte[] buffer = new byte[8 * 1024];
            long total = 0;
            int read;
            while ((read = request.content().read(buffer)) != -1) {
                total += read;
                if (total > request.expectedSizeBytes() || total > maximumBytes) {
                    throw new ObjectTooLargeException("对象实际大小超过声明或硬上限");
                }
                output.write(buffer, 0, read);
            }
            if (total != request.expectedSizeBytes()) throw new IllegalArgumentException("对象实际大小与声明不一致");
            return output.toByteArray();
        } catch (IOException failure) {
            throw new ObjectStorageException("流式对象写入失败", failure);
        }
    }

    private void validateReferenceShape(ObjectReference reference) {
        if (reference == null || reference.objectKey() == null) throw new IllegalArgumentException("对象引用不合法");
        validateKey(reference.objectKey());
        if (reference.versionId() == null || reference.versionId().isBlank()
                || reference.etag() == null || reference.etag().isBlank()) {
            throw new IllegalArgumentException("对象引用不合法");
        }
    }

    private void validateKey(String objectKey) {
        if (objectKey == null || objectKey.length() > 512
                || !(CONTENT_KEY.matcher(objectKey).matches() || QUARANTINE_KEY.matcher(objectKey).matches())
                || objectKey.contains("..") || objectKey.indexOf(':') >= 0
                || objectKey.chars().anyMatch(value -> value < 0x20 || value == 0x7f)) {
            throw new IllegalArgumentException("对象 key 不合法");
        }
    }

    private boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII),
                right.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private record Entry(StoredObject object, byte[] content) {
        private Entry {
            content = Arrays.copyOf(content, content.length);
        }
    }
}
