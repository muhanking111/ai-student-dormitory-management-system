package com.example.dormitory.ai.port;

import java.util.List;
import java.util.Map;

public interface VectorIndexPort {

    void upsert(VectorDocument document);

    List<VectorSearchHit> search(VectorSearchRequest request);

    void deleteByDocumentVersion(String documentVersionPublicId);

    VectorIndexHealth health();

    void rebuild(String indexVersion);

    record VectorDocument(
            String chunkPublicId,
            String documentVersionPublicId,
            List<Double> values,
            Map<String, String> metadata) {
        public VectorDocument {
            values = List.copyOf(values);
            metadata = Map.copyOf(metadata);
        }
    }

    record VectorSearchRequest(List<Double> values, Map<String, String> requiredMetadata, int topK) {
        public VectorSearchRequest {
            if (values == null || values.isEmpty()
                    || values.stream().anyMatch(value -> value == null || !Double.isFinite(value))) {
                throw new IllegalArgumentException("查询向量不能为空且必须为有限数值");
            }
            if (requiredMetadata == null || requiredMetadata.isEmpty()
                    || requiredMetadata.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                    || entry.getKey().isBlank() || entry.getValue() == null || entry.getValue().isBlank())) {
                throw new IllegalArgumentException("向量搜索必须包含非空 ACL/metadata filter");
            }
            values = List.copyOf(values);
            requiredMetadata = Map.copyOf(requiredMetadata);
            if (topK < 1) throw new IllegalArgumentException("topK 必须大于 0");
        }
    }

    record VectorSearchHit(String chunkPublicId, String documentVersionPublicId, double score) {
    }

    record VectorIndexHealth(boolean available, String indexVersion, String safeReasonCode) {
    }
}
