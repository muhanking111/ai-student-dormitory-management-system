package com.example.dormitory.ai.contract;

import com.example.dormitory.ai.port.ObjectStoragePort;
import com.example.dormitory.ai.port.VectorIndexPort;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortInputValidationTest {

    @Test
    void vectorSearchRejectsMissingAclOrMetadataFilter() {
        assertThrows(IllegalArgumentException.class,
                () -> new VectorIndexPort.VectorSearchRequest(
                        List.of(0.1d, 0.2d),
                        Map.of(),
                        5));
    }

    @Test
    void vectorSearchRejectsInvalidVectorsFiltersAndTopK() {
        Map<String, String> acl = Map.of("permissionDigest", "repair");
        assertThrows(IllegalArgumentException.class,
                () -> new VectorIndexPort.VectorSearchRequest(null, acl, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new VectorIndexPort.VectorSearchRequest(List.of(), acl, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new VectorIndexPort.VectorSearchRequest(Arrays.asList(0.1, null), acl, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new VectorIndexPort.VectorSearchRequest(List.of(Double.NaN), acl, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new VectorIndexPort.VectorSearchRequest(List.of(Double.POSITIVE_INFINITY), acl, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new VectorIndexPort.VectorSearchRequest(List.of(0.1), null, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new VectorIndexPort.VectorSearchRequest(List.of(0.1), Map.of("", "repair"), 1));

        Map<String, String> nullKey = new HashMap<>();
        nullKey.put(null, "repair");
        assertThrows(IllegalArgumentException.class,
                () -> new VectorIndexPort.VectorSearchRequest(List.of(0.1), nullKey, 1));
        Map<String, String> nullValue = new HashMap<>();
        nullValue.put("permissionDigest", null);
        assertThrows(IllegalArgumentException.class,
                () -> new VectorIndexPort.VectorSearchRequest(List.of(0.1), nullValue, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new VectorIndexPort.VectorSearchRequest(
                        List.of(0.1), Map.of("permissionDigest", " "), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new VectorIndexPort.VectorSearchRequest(List.of(0.1), acl, 0));

        List<Double> values = new ArrayList<>(List.of(0.1, 0.2));
        Map<String, String> metadata = new HashMap<>(acl);
        VectorIndexPort.VectorSearchRequest request =
                new VectorIndexPort.VectorSearchRequest(values, metadata, 2);
        values.clear();
        metadata.clear();
        assertEquals(List.of(0.1, 0.2), request.values());
        assertEquals(acl, request.requiredMetadata());
        assertThrows(UnsupportedOperationException.class, () -> request.values().add(0.3));
    }

    @Test
    void objectStorageRequestsDefensivelyCopyContentAndRejectInvalidMetadata() {
        byte[] content = "safe".getBytes(StandardCharsets.UTF_8);
        String sha = "a".repeat(64);
        Map<String, String> metadata = new HashMap<>(Map.of("purpose", "test"));
        ObjectStoragePort.ObjectWriteRequest request =
                new ObjectStoragePort.ObjectWriteRequest(content, sha, metadata);
        content[0] = 'X';
        metadata.clear();
        assertArrayEquals("safe".getBytes(StandardCharsets.UTF_8), request.content());
        assertEquals(Map.of("purpose", "test"), request.metadata());
        byte[] returned = request.content();
        returned[0] = 'Y';
        assertArrayEquals("safe".getBytes(StandardCharsets.UTF_8), request.content());

        assertThrows(IllegalArgumentException.class,
                () -> new ObjectStoragePort.ObjectWriteRequest(null, sha, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> portReturning(Optional.empty()).put(
                        new ObjectStoragePort.ObjectWriteRequest(new byte[0], "bad", Map.of())));

        Map<String, String> tooMany = new LinkedHashMap<>();
        for (int index = 0; index < 33; index++) tooMany.put("key" + index, "value");
        assertThrows(IllegalArgumentException.class,
                () -> new ObjectStoragePort.ObjectWriteRequest(new byte[0], sha, tooMany));
        for (Map<String, String> invalid : List.of(
                Map.of("bad key", "value"),
                Map.of("k", "x".repeat(513)),
                Map.of("k", "bad\nvalue"))) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ObjectStoragePort.ObjectWriteRequest(new byte[0], sha, invalid));
        }
        Map<String, String> nullMetadataKey = new HashMap<>();
        nullMetadataKey.put(null, "value");
        assertThrows(IllegalArgumentException.class,
                () -> new ObjectStoragePort.ObjectWriteRequest(new byte[0], sha, nullMetadataKey));
        Map<String, String> nullMetadataValue = new HashMap<>();
        nullMetadataValue.put("key", null);
        assertThrows(IllegalArgumentException.class,
                () -> new ObjectStoragePort.ObjectWriteRequest(new byte[0], sha, nullMetadataValue));
    }

    @Test
    void objectStorageIdentifiersRejectTraversalWhitespaceAndControlCharacters() {
        String sha = "b".repeat(64);
        for (String key : Arrays.asList(null, "", " key", "key ", "/key", "key/", "a/../b",
                "a\\b", "a:b", "a?b", "a\nb", "x".repeat(513))) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ObjectStoragePort.StreamingObjectWriteRequest(
                            key, new ByteArrayInputStream(new byte[]{1}), 1, sha, Map.of()));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new ObjectStoragePort.StreamingObjectWriteRequest(
                        "valid/key", null, 1, sha, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ObjectStoragePort.StreamingObjectWriteRequest(
                        "valid/key", new ByteArrayInputStream(new byte[]{1}), 0, sha, Map.of()));

        String validKey = "sha256/" + sha;
        for (String providerRef : Arrays.asList(null, "", " ref", "ref ", "bad\nref", "x".repeat(257))) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ObjectStoragePort.ObjectReference(validKey, providerRef, "etag"));
            assertThrows(IllegalArgumentException.class,
                    () -> new ObjectStoragePort.ObjectReference(validKey, "version", providerRef));
        }
        assertEquals(sha, ObjectStoragePort.requireSha256(sha.toUpperCase()));
    }

    @Test
    void bufferedGetFailsClosedForProviderReferenceSizeAndStreamViolations() {
        String sha = "c".repeat(64);
        ObjectStoragePort.ObjectReference reference =
                new ObjectStoragePort.ObjectReference("sha256/" + sha, "v1", "etag");

        ObjectStoragePort missing = portReturning(Optional.empty());
        assertTrue(missing.get(reference).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> missing.get(reference, 0));
        assertThrows(IllegalArgumentException.class,
                () -> missing.get(reference, ObjectStoragePort.MAXIMUM_BUFFERED_GET_BYTES + 1));
        assertThrows(NullPointerException.class, () -> missing.get(null));

        ObjectStoragePort.ObjectReference otherReference =
                new ObjectStoragePort.ObjectReference("sha256/" + "d".repeat(64), "v2", "etag2");
        ObjectStoragePort.StoredObject other =
                new ObjectStoragePort.StoredObject(otherReference, "d".repeat(64), 1);
        assertThrows(ObjectStoragePort.ObjectStorageException.class,
                () -> portReturning(stream(other, new byte[]{1})).get(reference));

        ObjectStoragePort.StoredObject oversized =
                new ObjectStoragePort.StoredObject(reference, sha, 5);
        assertThrows(ObjectStoragePort.ObjectTooLargeException.class,
                () -> portReturning(stream(oversized, new byte[5])).get(reference, 4));

        ObjectStoragePort.StoredObject inaccurate =
                new ObjectStoragePort.StoredObject(reference, sha, 2);
        assertThrows(ObjectStoragePort.ObjectStorageException.class,
                () -> portReturning(stream(inaccurate, new byte[1])).get(reference, 4));

        InputStream broken = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("broken"); }
            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                throw new IOException("broken");
            }
        };
        assertThrows(ObjectStoragePort.ObjectStorageException.class,
                () -> portReturning(Optional.of(
                        new ObjectStoragePort.StoredObjectStream(inaccurate, broken))).get(reference, 4));
    }

    @Test
    void objectStorageResultRecordsRejectMissingProviderFacts() {
        String sha = "e".repeat(64);
        ObjectStoragePort.ObjectReference reference =
                new ObjectStoragePort.ObjectReference("sha256/" + sha, "v1", "etag");
        ObjectStoragePort.StoredObject object = new ObjectStoragePort.StoredObject(reference, sha, 0);
        assertThrows(NullPointerException.class, () -> new ObjectStoragePort.PutResult(null, true));
        assertThrows(NullPointerException.class,
                () -> new ObjectStoragePort.StoredObjectStream(null, new ByteArrayInputStream(new byte[0])));
        assertThrows(NullPointerException.class,
                () -> new ObjectStoragePort.StoredObjectStream(object, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ObjectStoragePort.StoredObjectContent(object, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ObjectStoragePort.AdapterStatus(null, false, false, false, false, false));
        assertThrows(IllegalArgumentException.class,
                () -> new ObjectStoragePort.AdapterStatus("A", false, false, false, false, false));
    }

    private static Optional<ObjectStoragePort.StoredObjectStream> stream(
            ObjectStoragePort.StoredObject object, byte[] content) {
        return Optional.of(new ObjectStoragePort.StoredObjectStream(
                object, new ByteArrayInputStream(content)));
    }

    private static ObjectStoragePort portReturning(
            Optional<ObjectStoragePort.StoredObjectStream> opened) {
        return new ObjectStoragePort() {
            @Override public PutResult putIfAbsent(StreamingObjectWriteRequest request) {
                throw new UnsupportedOperationException();
            }
            @Override public Optional<StoredObjectStream> open(ObjectReference reference) { return opened; }
            @Override public boolean delete(ObjectReference reference) { return false; }
            @Override public boolean exists(ObjectReference reference) { return opened.isPresent(); }
            @Override public AdapterStatus status() {
                return new AdapterStatus("test-storage", false, true, true, true, false);
            }
        };
    }
}
