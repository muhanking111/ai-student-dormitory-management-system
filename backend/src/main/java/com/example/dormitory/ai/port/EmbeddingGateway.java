package com.example.dormitory.ai.port;

import java.util.List;

public interface EmbeddingGateway {

    EmbeddingResult embed(EmbeddingRequest request);

    record EmbeddingRequest(List<String> texts, String modelAlias) {
        public EmbeddingRequest {
            texts = texts == null ? List.of() : List.copyOf(texts);
            if (texts.isEmpty() || texts.stream().anyMatch(text -> text == null || text.isBlank())) {
                throw new IllegalArgumentException("Embedding 文本不能为空");
            }
            if (modelAlias == null || modelAlias.isBlank()) {
                throw new IllegalArgumentException("Embedding 模型别名不能为空");
            }
        }
    }

    record EmbeddingResult(List<EmbeddingVector> vectors, String modelAlias) {
        public EmbeddingResult {
            vectors = List.copyOf(vectors);
        }
    }

    record EmbeddingVector(String contentHash, List<Double> values) {
        public EmbeddingVector {
            if (contentHash == null || contentHash.isBlank()) {
                throw new IllegalArgumentException("内容哈希不能为空");
            }
            values = List.copyOf(values);
            if (values.isEmpty()) {
                throw new IllegalArgumentException("Embedding 向量不能为空");
            }
        }
    }
}
