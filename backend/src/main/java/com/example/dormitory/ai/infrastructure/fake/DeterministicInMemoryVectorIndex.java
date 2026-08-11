package com.example.dormitory.ai.infrastructure.fake;

import com.example.dormitory.ai.port.VectorIndexPort;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DeterministicInMemoryVectorIndex implements VectorIndexPort {

    private final Map<String, VectorDocument> documents = new ConcurrentHashMap<>();
    private volatile String indexVersion;

    public DeterministicInMemoryVectorIndex(String indexVersion) {
        if (indexVersion == null || indexVersion.isBlank()) throw new IllegalArgumentException("index version 不能为空");
        this.indexVersion = indexVersion;
    }

    @Override
    public void upsert(VectorDocument document) {
        if (document == null || document.chunkPublicId() == null || document.chunkPublicId().isBlank()
                || document.documentVersionPublicId() == null || document.documentVersionPublicId().isBlank()
                || document.values().isEmpty() || document.metadata().isEmpty()) {
            throw new IllegalArgumentException("向量文档合同不完整");
        }
        documents.put(document.chunkPublicId(), document);
    }

    @Override
    public List<VectorSearchHit> search(VectorSearchRequest request) {
        return documents.values().stream()
                .filter(document -> request.requiredMetadata().entrySet().stream()
                        .allMatch(entry -> entry.getValue().equals(document.metadata().get(entry.getKey()))))
                .filter(document -> document.values().size() == request.values().size())
                .map(document -> new VectorSearchHit(document.chunkPublicId(),
                        document.documentVersionPublicId(), cosine(request.values(), document.values())))
                .sorted(Comparator.comparingDouble(VectorSearchHit::score).reversed()
                        .thenComparing(VectorSearchHit::chunkPublicId))
                .limit(request.topK())
                .toList();
    }

    @Override
    public void deleteByDocumentVersion(String documentVersionPublicId) {
        documents.entrySet().removeIf(entry -> entry.getValue().documentVersionPublicId()
                .equals(documentVersionPublicId));
    }

    @Override
    public VectorIndexHealth health() {
        return new VectorIndexHealth(true, indexVersion, null);
    }

    @Override
    public void rebuild(String newIndexVersion) {
        if (newIndexVersion == null || newIndexVersion.isBlank()) throw new IllegalArgumentException("index version 不能为空");
        indexVersion = newIndexVersion;
    }

    private double cosine(List<Double> left, List<Double> right) {
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int index = 0; index < left.size(); index++) {
            dot += left.get(index) * right.get(index);
            leftNorm += left.get(index) * left.get(index);
            rightNorm += right.get(index) * right.get(index);
        }
        if (leftNorm == 0 || rightNorm == 0) return 0;
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}
