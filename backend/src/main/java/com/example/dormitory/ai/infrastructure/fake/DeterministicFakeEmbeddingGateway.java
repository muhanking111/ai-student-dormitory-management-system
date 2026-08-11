package com.example.dormitory.ai.infrastructure.fake;

import com.example.dormitory.ai.port.EmbeddingGateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.List;

public final class DeterministicFakeEmbeddingGateway implements EmbeddingGateway {

    private static final int DIMENSION = 8;

    @Override
    public EmbeddingResult embed(EmbeddingRequest request) {
        List<EmbeddingVector> vectors = request.texts().stream()
                .map(this::vector)
                .toList();
        return new EmbeddingResult(vectors, request.modelAlias());
    }

    private EmbeddingVector vector(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFC);
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
        List<Double> values = java.util.stream.IntStream.range(0, DIMENSION)
                .mapToObj(index -> (digest[index] & 0xff) / 127.5d - 1.0d)
                .toList();
        return new EmbeddingVector(HexFormat.of().formatHex(digest), values);
    }
}
