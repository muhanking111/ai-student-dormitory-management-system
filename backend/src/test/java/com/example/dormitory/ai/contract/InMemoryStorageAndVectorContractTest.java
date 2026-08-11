package com.example.dormitory.ai.contract;

import com.example.dormitory.ai.infrastructure.fake.DeterministicInMemoryVectorIndex;
import com.example.dormitory.ai.infrastructure.fake.InMemoryVersionedObjectStorage;
import com.example.dormitory.ai.port.ObjectStoragePort;
import com.example.dormitory.ai.port.VectorIndexPort;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryStorageAndVectorContractTest {

    @Test
    void objectStorageConstructorAndAdapterKeyPolicyFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryVersionedObjectStorage(0));
        InMemoryVersionedObjectStorage storage = new InMemoryVersionedObjectStorage(16);
        byte[] content = new byte[]{1};
        String sha = InMemoryVersionedObjectStorage.sha256(content);

        assertThrows(IllegalArgumentException.class, () -> storage.putIfAbsent(
                new ObjectStoragePort.StreamingObjectWriteRequest(
                        "valid/but-not-approved-key-shape", new ByteArrayInputStream(content),
                        1, sha, Map.of())));
        assertThrows(IllegalArgumentException.class, () -> storage.putIfAbsent(
                new ObjectStoragePort.StreamingObjectWriteRequest(
                        "sha256/" + "0".repeat(64), new ByteArrayInputStream(content),
                        1, sha, Map.of())));
    }

    @Test
    void objectStorageIsContentAddressedVersionBoundAndRejectsChecksumMismatch() {
        InMemoryVersionedObjectStorage storage = new InMemoryVersionedObjectStorage(1024);
        byte[] content = "批准的纯文本知识".getBytes(StandardCharsets.UTF_8);
        String sha = InMemoryVersionedObjectStorage.sha256(content);
        ObjectStoragePort.StoredObject object = storage.put(
                new ObjectStoragePort.ObjectWriteRequest(content, sha, Map.of("classification", "L0")));

        assertFalse(object.reference().objectKey().contains("批准"));
        assertArrayEquals(content, storage.get(object.reference()).orElseThrow().content());
        assertEquals(object.reference(), storage.put(
                new ObjectStoragePort.ObjectWriteRequest(content, sha, Map.of())).reference());
        assertThrows(IllegalArgumentException.class, () -> storage.put(
                new ObjectStoragePort.ObjectWriteRequest(content, "0".repeat(64), Map.of())));
        assertThrows(IllegalArgumentException.class, () -> storage.get(
                new ObjectStoragePort.ObjectReference("../secret", "v1", "etag")));
    }

    @Test
    void objectStorageSupportsConditionalStreamingRandomQuarantineKeysAndFixedVersionNotFound() {
        InMemoryVersionedObjectStorage storage = new InMemoryVersionedObjectStorage(32);
        byte[] content = "streamed text".getBytes(StandardCharsets.UTF_8);
        String sha = InMemoryVersionedObjectStorage.sha256(content);
        ObjectStoragePort.StreamingObjectWriteRequest request = new ObjectStoragePort.StreamingObjectWriteRequest(
                "quarantine/11111111-1111-1111-1111-111111111111/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                new ByteArrayInputStream(content), content.length, sha, Map.of("quarantine", "true"));

        ObjectStoragePort.PutResult first = storage.putIfAbsent(request);
        ObjectStoragePort.PutResult replay = storage.putIfAbsent(new ObjectStoragePort.StreamingObjectWriteRequest(
                first.object().reference().objectKey(), new ByteArrayInputStream(content), content.length, sha,
                Map.of("quarantine", "true")));

        assertTrue(first.created());
        assertFalse(replay.created());
        assertEquals(first.object(), replay.object());
        assertEquals(Map.of("quarantine", "true"), first.object().metadata());
        try (ObjectStoragePort.StoredObjectStream stream = storage.open(first.object().reference()).orElseThrow()) {
            assertEquals(Map.of("quarantine", "true"), stream.object().metadata());
            assertArrayEquals(content, stream.content().readAllBytes());
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
        assertTrue(storage.open(new ObjectStoragePort.ObjectReference(
                first.object().reference().objectKey(), "missing-version", first.object().reference().etag())).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> storage.putIfAbsent(
                new ObjectStoragePort.StreamingObjectWriteRequest("quarantine/../../secret",
                        new ByteArrayInputStream(content), content.length, sha, Map.of())));
        assertThrows(IllegalArgumentException.class, () -> storage.putIfAbsent(
                new ObjectStoragePort.StreamingObjectWriteRequest(
                        "quarantine/11111111-1111-1111-1111-111111111111/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                        new ByteArrayInputStream(new byte[33]), 33, InMemoryVersionedObjectStorage.sha256(new byte[33]),
                        Map.of())));
        assertTrue(storage.delete(first.object().reference()));
        assertFalse(storage.exists(first.object().reference()));
    }

    @Test
    void conditionalCreateRejectsDifferentContentAndReferenceOperationsRequireExactVersionFacts() {
        InMemoryVersionedObjectStorage storage = new InMemoryVersionedObjectStorage(32);
        String key = "quarantine/11111111-1111-1111-1111-111111111111/cccccccccccccccccccccccccccccccc";
        byte[] firstContent = "first".getBytes(StandardCharsets.UTF_8);
        byte[] secondContent = "second".getBytes(StandardCharsets.UTF_8);
        ObjectStoragePort.StoredObject first = storage.putIfAbsent(
                new ObjectStoragePort.StreamingObjectWriteRequest(key,
                        new ByteArrayInputStream(firstContent), firstContent.length,
                        InMemoryVersionedObjectStorage.sha256(firstContent), Map.of())).object();

        assertThrows(ObjectStoragePort.ObjectStorageException.class, () -> storage.putIfAbsent(
                new ObjectStoragePort.StreamingObjectWriteRequest(key,
                        new ByteArrayInputStream(secondContent), secondContent.length,
                        InMemoryVersionedObjectStorage.sha256(secondContent), Map.of())));
        ObjectStoragePort.ObjectReference wrongVersion = new ObjectStoragePort.ObjectReference(
                key, "different-version", first.reference().etag());
        assertTrue(storage.open(wrongVersion).isEmpty());
        assertFalse(storage.exists(wrongVersion));
        assertFalse(storage.delete(wrongVersion));
        assertFalse(storage.exists(new ObjectStoragePort.ObjectReference(
                "quarantine/22222222-2222-2222-2222-222222222222/dddddddddddddddddddddddddddddddd",
                "missing", "missing")));
        assertFalse(storage.delete(new ObjectStoragePort.ObjectReference(
                "quarantine/22222222-2222-2222-2222-222222222222/dddddddddddddddddddddddddddddddd",
                "missing", "missing")));
        assertThrows(IllegalArgumentException.class, () -> storage.open(null));
    }

    @Test
    void streamingWriteRejectsShortOversizedAndUnreadableProviderStreams() {
        InMemoryVersionedObjectStorage storage = new InMemoryVersionedObjectStorage(4);
        String key = "quarantine/33333333-3333-3333-3333-333333333333/eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
        assertThrows(ObjectStoragePort.ObjectTooLargeException.class, () -> storage.putIfAbsent(
                new ObjectStoragePort.StreamingObjectWriteRequest(key,
                        new ByteArrayInputStream(new byte[5]), 5,
                        InMemoryVersionedObjectStorage.sha256(new byte[5]), Map.of())));
        assertThrows(ObjectStoragePort.ObjectTooLargeException.class, () -> storage.putIfAbsent(
                new ObjectStoragePort.StreamingObjectWriteRequest(key,
                        new ByteArrayInputStream(new byte[2]), 1,
                        InMemoryVersionedObjectStorage.sha256(new byte[]{0}), Map.of())));
        assertThrows(IllegalArgumentException.class, () -> storage.putIfAbsent(
                new ObjectStoragePort.StreamingObjectWriteRequest(key,
                        new ByteArrayInputStream(new byte[0]), 1,
                        InMemoryVersionedObjectStorage.sha256(new byte[]{0}), Map.of())));

        InputStream broken = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("broken"); }
            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                throw new IOException("broken");
            }
        };
        assertThrows(ObjectStoragePort.ObjectStorageException.class, () -> storage.putIfAbsent(
                new ObjectStoragePort.StreamingObjectWriteRequest(key, broken, 1,
                        InMemoryVersionedObjectStorage.sha256(new byte[]{0}), Map.of())));
    }

    @Test
    void objectReferenceAndStoredObjectRejectMalformedProviderFacts() {
        String validKey = "sha256/" + "a".repeat(64);
        assertThrows(IllegalArgumentException.class,
                () -> new ObjectStoragePort.ObjectReference(null, "v1", "etag"));
        assertThrows(IllegalArgumentException.class,
                () -> new ObjectStoragePort.ObjectReference("../secret", "v1", "etag"));
        assertThrows(IllegalArgumentException.class,
                () -> new ObjectStoragePort.ObjectReference("a".repeat(513), "v1", "etag"));
        assertThrows(IllegalArgumentException.class,
                () -> new ObjectStoragePort.ObjectReference(validKey, " ", "etag"));
        assertThrows(IllegalArgumentException.class,
                () -> new ObjectStoragePort.ObjectReference(validKey, "v".repeat(257), "etag"));
        assertThrows(IllegalArgumentException.class,
                () -> new ObjectStoragePort.ObjectReference(validKey, "v1", " "));
        assertThrows(IllegalArgumentException.class,
                () -> new ObjectStoragePort.ObjectReference(validKey, "v1", "e".repeat(257)));
        assertThrows(IllegalArgumentException.class,
                () -> new ObjectStoragePort.ObjectReference(validKey, "v1", "bad\netag"));
        assertThrows(NullPointerException.class,
                () -> new ObjectStoragePort.StoredObject(null, "a".repeat(64), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ObjectStoragePort.StoredObject(
                        new ObjectStoragePort.ObjectReference(validKey, "v1", "etag"), "bad", 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ObjectStoragePort.StoredObject(
                        new ObjectStoragePort.ObjectReference(validKey, "v1", "etag"),
                        "a".repeat(64), -1));
    }

    @Test
    void defaultGetStopsAProviderStreamThatLiesAboutItsSize() {
        ObjectStoragePort.ObjectReference reference = new ObjectStoragePort.ObjectReference(
                "sha256/" + "b".repeat(64), "version-1", "etag-1");
        ObjectStoragePort.StoredObject claimed = new ObjectStoragePort.StoredObject(
                reference, "b".repeat(64), 1, Map.of("purpose", "test"));
        ObjectStoragePort malicious = new ObjectStoragePort() {
            @Override
            public PutResult putIfAbsent(StreamingObjectWriteRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<StoredObjectStream> open(ObjectReference ignored) {
                return Optional.of(new StoredObjectStream(claimed, new ByteArrayInputStream(new byte[64])));
            }

            @Override public boolean delete(ObjectReference ignored) { return false; }
            @Override public boolean exists(ObjectReference ignored) { return true; }
            @Override public AdapterStatus status() {
                return new AdapterStatus("malicious-size-test", false, true, true, true, false);
            }
        };

        assertThrows(ObjectStoragePort.ObjectTooLargeException.class,
                () -> malicious.get(reference, 32));
    }

    @Test
    void vectorSearchRequiresAndAppliesAclMetadataBeforeReturningHits() {
        DeterministicInMemoryVectorIndex index = new DeterministicInMemoryVectorIndex("idx-v1");
        index.upsert(new VectorIndexPort.VectorDocument(
                "chunk-a", "version-a", List.of(1.0, 0.0),
                Map.of("permissionDigest", "repair", "visibility", "EXPLICIT_ACL")));
        index.upsert(new VectorIndexPort.VectorDocument(
                "chunk-b", "version-b", List.of(1.0, 0.0),
                Map.of("permissionDigest", "notice", "visibility", "EXPLICIT_ACL")));

        List<VectorIndexPort.VectorSearchHit> hits = index.search(new VectorIndexPort.VectorSearchRequest(
                List.of(1.0, 0.0), Map.of("permissionDigest", "repair"), 5));
        assertEquals(List.of("chunk-a"), hits.stream().map(VectorIndexPort.VectorSearchHit::chunkPublicId).toList());
        assertThrows(IllegalArgumentException.class, () -> index.search(
                new VectorIndexPort.VectorSearchRequest(List.of(1.0), Map.of(), 5)));
        index.deleteByDocumentVersion("version-a");
        assertEquals(0, index.search(new VectorIndexPort.VectorSearchRequest(
                List.of(1.0, 0.0), Map.of("permissionDigest", "repair"), 5)).size());
    }
}
