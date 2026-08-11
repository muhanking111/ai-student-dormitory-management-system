package com.example.dormitory.ai.knowledge;

import com.example.dormitory.ai.application.run.AiRunRecords;
import com.example.dormitory.ai.approval.CanonicalJsonHasher;
import com.example.dormitory.ai.security.PiiRedactionService;
import com.example.dormitory.ai.security.PromptInjectionGuard;
import com.example.dormitory.ai.security.SensitiveDataBlockedException;
import com.example.dormitory.ai.port.EmbeddingGateway;
import com.example.dormitory.ai.port.VectorIndexPort;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class SafeKnowledgeService {

    private final KnowledgeAclPolicy aclPolicy;
    private final PromptInjectionGuard injectionGuard;
    private final PiiRedactionService redactionService;
    private final Map<String, StoredDocument> documents = new LinkedHashMap<>();
    private final java.util.function.Predicate<String> sourceEnabled;
    private final KnowledgeSourceRepository sourceRepository;
    private final KnowledgeVersionRepository versionRepository;
    private final EmbeddingGateway embeddingGateway;
    private final VectorIndexPort vectorIndex;

    private static final String EMBEDDING_MODEL = "fake-embedding-v1";
    private static final String RETRIEVAL_POLICY_VERSION = "knowledge-retrieval-policy-v1";
    private static final String VECTOR_INDEX_CODE = "knowledge-vector-index";
    private static final String FILTER_SUMMARY = "{\"scope\":\"document-version\","
            + "\"visibility\":\"PUBLIC_APPROVED_OR_EXPLICIT_ACL\","
            + "\"matchMode\":\"ANY_OR_ALL\",\"emptyAcl\":\"DENY\"}";

    public SafeKnowledgeService(
            KnowledgeAclPolicy aclPolicy,
            PromptInjectionGuard injectionGuard,
            PiiRedactionService redactionService) {
        this(aclPolicy, injectionGuard, redactionService, ignored -> true);
    }

    public SafeKnowledgeService(
            KnowledgeAclPolicy aclPolicy,
            PromptInjectionGuard injectionGuard,
            PiiRedactionService redactionService,
            java.util.function.Predicate<String> sourceEnabled) {
        this(aclPolicy, injectionGuard, redactionService, sourceEnabled, null, null, null, null);
    }

    public SafeKnowledgeService(
            KnowledgeAclPolicy aclPolicy,
            PromptInjectionGuard injectionGuard,
            PiiRedactionService redactionService,
            java.util.function.Predicate<String> sourceEnabled,
            KnowledgeSourceRepository sourceRepository,
            KnowledgeVersionRepository versionRepository,
            EmbeddingGateway embeddingGateway,
            VectorIndexPort vectorIndex) {
        this.aclPolicy = java.util.Objects.requireNonNull(aclPolicy);
        this.injectionGuard = java.util.Objects.requireNonNull(injectionGuard);
        this.redactionService = java.util.Objects.requireNonNull(redactionService);
        this.sourceEnabled = java.util.Objects.requireNonNull(sourceEnabled);
        this.sourceRepository = sourceRepository;
        this.versionRepository = versionRepository;
        this.embeddingGateway = embeddingGateway;
        this.vectorIndex = vectorIndex;
        boolean allPersistent = sourceRepository != null && versionRepository != null
                && embeddingGateway != null && vectorIndex != null;
        boolean nonePersistent = sourceRepository == null && versionRepository == null
                && embeddingGateway == null && vectorIndex == null;
        if (!allPersistent && !nonePersistent) {
            throw new IllegalArgumentException("知识检索持久化端口必须成组配置");
        }
    }

    public synchronized void registerApprovedText(KnowledgeDocument document) {
        validateDocument(document);
        aclPolicy.validatePermissions(document.requiredPermissions());
        if (!document.scannedClean() || injectionGuard.inspect(document.content()).blocked()) {
            throw new KnowledgeQuarantinedException("知识文本未通过安全检查");
        }
        try {
            PiiRedactionService.RedactionResult redacted = redactionService.redact(
                    document.content(), "knowledge:" + document.sourceId());
            String key = key(document.documentId(), document.documentVersionId());
            documents.put(key, new StoredDocument(document, redacted.redactedText(), true));
        } catch (SensitiveDataBlockedException exception) {
            throw new KnowledgeQuarantinedException("知识文本包含禁止进入 AI 的数据");
        }
    }

    public synchronized void retire(String documentId, String versionId) {
        String key = key(documentId, versionId);
        StoredDocument stored = documents.get(key);
        if (stored != null) documents.put(key, new StoredDocument(stored.document(), stored.redactedContent(), false));
    }

    public KnowledgeAnswer search(String query, Set<String> actorPermissions, int topK) {
        return searchWithTrace(query, actorPermissions, topK).answer();
    }

    public KnowledgeSearchResult searchWithTrace(String query, Set<String> actorPermissions, int topK) {
        if (query == null || query.isBlank() || topK < 1 || topK > 20) {
            throw new IllegalArgumentException("知识查询参数不合法");
        }
        if (versionRepository != null) {
            return searchPersisted(query, actorPermissions == null ? Set.of() : Set.copyOf(actorPermissions), topK);
        }
        long startedNanos = System.nanoTime();
        Set<String> permissions = actorPermissions == null ? Set.of() : Set.copyOf(actorPermissions);
        List<String> terms = terms(query);
        List<ScoredDocument> matches = new ArrayList<>();
        List<StoredDocument> snapshot;
        synchronized (this) {
            snapshot = List.copyOf(documents.values());
        }
        for (StoredDocument stored : snapshot) {
            KnowledgeDocument document = stored.document();
            if (!stored.active() || !sourceEnabled.test(document.sourceId())) continue;
            boolean allowedBefore = aclPolicy.canRead(document.visibility(), document.matchMode(),
                    document.requiredPermissions(), permissions, false);
            if (!allowedBefore) continue;
            int score = terms.stream().mapToInt(term -> occurrences(stored.redactedContent(), term)).sum();
            if (score > 0) matches.add(new ScoredDocument(stored, score));
        }
        matches.sort(Comparator.comparingInt(ScoredDocument::score).reversed());

        List<KnowledgeCitation> citations = matches.stream().limit(topK)
                .filter(match -> aclPolicy.canRead(match.stored().document().visibility(),
                        match.stored().document().matchMode(), match.stored().document().requiredPermissions(),
                        permissions, false))
                .map(match -> new KnowledgeCitation(
                        match.stored().document().sourceId(),
                        match.stored().document().documentVersionId(),
                        null,
                        match.stored().document().title(),
                        "全文",
                        match.stored().redactedContent(),
                        match.stored().document().contentHash()))
                .toList();
        if (citations.isEmpty()) {
            KnowledgeAnswer answer = new KnowledgeAnswer(false, "暂无可靠来源，无法确认", List.of(),
                    "AI_NO_GROUNDED_ANSWER", "keyword-fallback-v1");
            return new KnowledgeSearchResult(answer, trace(query, topK, matches.size(), 0, 0,
                    "keyword-fallback-v1", "in-memory-keyword", "memory-v1", "none",
                    startedNanos, "NO_RESULT"));
        }
        String answer = citations.stream().map(KnowledgeCitation::quote).reduce((left, right) -> left + "\n" + right)
                .orElse("暂无可靠来源，无法确认");
        KnowledgeAnswer grounded = new KnowledgeAnswer(true, answer, citations, null, "keyword-fallback-v1");
        return new KnowledgeSearchResult(grounded, trace(query, topK, matches.size(), citations.size(),
                citations.size(), "keyword-fallback-v1", "in-memory-keyword", "memory-v1", "none",
                startedNanos, "SUCCEEDED"));
    }

    /**
     * 将 MySQL 中已落盘的脱敏 chunk 投影到向量索引。MySQL 始终是事实源，索引可在进程重启后重建。
     */
    public synchronized void indexVersion(String versionPublicId) {
        if (versionRepository == null) return;
        KnowledgeVersionRepository.KnowledgeVersion version = versionRepository.findByPublicId(versionPublicId)
                .orElseThrow(() -> new IllegalArgumentException("知识版本不存在"));
        KnowledgeSourceRepository.KnowledgeSource source = sourceRepository.findByPublicId(version.sourcePublicId())
                .orElseThrow(() -> new IllegalStateException("知识来源不存在"));
        for (KnowledgeVersionRepository.KnowledgeChunk chunk : versionRepository.findChunks(versionPublicId)) {
            EmbeddingGateway.EmbeddingVector embedded = embeddingGateway.embed(
                    new EmbeddingGateway.EmbeddingRequest(List.of(chunk.contentRedacted()), EMBEDDING_MODEL))
                    .vectors().getFirst();
            vectorIndex.upsert(new VectorIndexPort.VectorDocument(chunk.publicId(), version.publicId(),
                    embedded.values(), Map.of(
                    "documentVersionPublicId", version.publicId(),
                    "sourcePublicId", source.publicId(),
                    "contentHash", chunk.contentHash())));
        }
    }

    private KnowledgeSearchResult searchPersisted(String query, Set<String> actorPermissions, int topK) {
        long startedNanos = System.nanoTime();
        String indexVersion = "unavailable";
        List<KnowledgeVersionRepository.KnowledgeVersion> allowedVersions = versionRepository.findActiveVersions()
                .stream()
                .filter(version -> sourceEnabled.test(version.sourcePublicId()))
                // ACL 前置：未授权版本绝不进入 embedding/index 搜索候选。
                .filter(version -> versionRepository.canRead(version.publicId(), actorPermissions))
                .toList();
        if (allowedVersions.isEmpty()) {
            KnowledgeAnswer answer = ungrounded("keyword-fallback-v1");
            return new KnowledgeSearchResult(answer, trace(query, topK, 0, 0, 0,
                    "keyword-fallback-v1", VECTOR_INDEX_CODE, indexVersion, EMBEDDING_MODEL,
                    startedNanos, "NO_RESULT"));
        }

        List<String> queryTerms = terms(query);
        Map<String, KnowledgeVersionRepository.KnowledgeChunk> chunksById = new LinkedHashMap<>();
        Map<String, KnowledgeVersionRepository.KnowledgeVersion> versionsByChunk = new LinkedHashMap<>();
        for (KnowledgeVersionRepository.KnowledgeVersion version : allowedVersions) {
            for (KnowledgeVersionRepository.KnowledgeChunk chunk : versionRepository.findChunks(version.publicId())) {
                chunksById.put(chunk.publicId(), chunk);
                versionsByChunk.put(chunk.publicId(), version);
            }
        }

        int aclPreFilterCount = chunksById.size();
        String retrievalMode = "vector-filtered-v1";
        List<String> rankedChunkIds = new ArrayList<>();
        try {
            VectorIndexPort.VectorIndexHealth health = vectorIndex.health();
            indexVersion = health.indexVersion() == null || health.indexVersion().isBlank()
                    ? "unknown" : health.indexVersion();
            if (!health.available()) {
                retrievalMode = "keyword-fallback-v1";
            } else {
                // 查询路径只 embed 一次 query；chunk 的 embedding/upsert 只允许在摄取或显式重建任务中执行。
                List<Double> queryVector = embeddingGateway.embed(
                        new EmbeddingGateway.EmbeddingRequest(List.of(query), EMBEDDING_MODEL))
                        .vectors().getFirst().values();
                for (KnowledgeVersionRepository.KnowledgeVersion version : allowedVersions) {
                    vectorIndex.search(new VectorIndexPort.VectorSearchRequest(queryVector,
                                    Map.of("documentVersionPublicId", version.publicId()), topK))
                            .forEach(hit -> rankedChunkIds.add(hit.chunkPublicId()));
                }
                if (rankedChunkIds.isEmpty()) retrievalMode = "keyword-fallback-v1";
            }
        } catch (RuntimeException unavailable) {
            // 向量是可重建投影；故障时只在已完成 ACL 前置的 MySQL 脱敏 chunk 上做关键词降级。
            retrievalMode = "keyword-fallback-v1";
        }

        // 首期安全阈值采用确定性词项命中；向量只负责候选排序，避免无关高相似度形成伪引用。
        Comparator<String> keywordRank = Comparator
                .<String>comparingInt(id -> queryTerms.stream()
                        .mapToInt(term -> occurrences(chunksById.get(id).contentRedacted(), term)).sum())
                .reversed().thenComparing(id -> id);
        java.util.LinkedHashSet<String> ordered = new java.util.LinkedHashSet<>(rankedChunkIds);
        chunksById.keySet().stream().sorted(keywordRank).forEach(ordered::add);

        List<String> postAclChunkIds = ordered.stream()
                .filter(chunksById::containsKey)
                .filter(id -> queryTerms.stream().anyMatch(term -> occurrences(
                        chunksById.get(id).contentRedacted(), term) > 0))
                // ACL 后置：索引中过期或竞态命中在返回正文前再次按 MySQL 当前事实裁剪。
                .filter(id -> versionRepository.canRead(versionsByChunk.get(id).publicId(), actorPermissions))
                .toList();
        List<KnowledgeCitation> citations = postAclChunkIds.stream().limit(topK)
                .map(id -> {
                    KnowledgeVersionRepository.KnowledgeChunk chunk = chunksById.get(id);
                    KnowledgeVersionRepository.KnowledgeVersion version = versionsByChunk.get(id);
                    KnowledgeSourceRepository.KnowledgeSource source = sourceRepository
                            .findByPublicId(version.sourcePublicId()).orElseThrow();
                    return new KnowledgeCitation(source.publicId(), version.publicId(), chunk.publicId(), source.name(),
                            chunk.locator(), chunk.contentRedacted(), chunk.contentHash());
                }).toList();
        if (citations.isEmpty()) {
            KnowledgeAnswer answer = ungrounded(retrievalMode);
            return new KnowledgeSearchResult(answer, trace(query, topK, aclPreFilterCount,
                    postAclChunkIds.size(), 0, retrievalMode, VECTOR_INDEX_CODE, indexVersion,
                    EMBEDDING_MODEL, startedNanos, "NO_RESULT"));
        }
        String answer = citations.stream().map(KnowledgeCitation::quote)
                .reduce((left, right) -> left + "\n" + right).orElseThrow();
        KnowledgeAnswer grounded = new KnowledgeAnswer(true, answer, citations, null, retrievalMode);
        return new KnowledgeSearchResult(grounded, trace(query, topK, aclPreFilterCount,
                postAclChunkIds.size(), citations.size(), retrievalMode, VECTOR_INDEX_CODE, indexVersion,
                EMBEDDING_MODEL, startedNanos,
                "keyword-fallback-v1".equals(retrievalMode) ? "DEGRADED" : "SUCCEEDED"));
    }

    private AiRunRecords.RetrievalTrace trace(
            String query,
            int topK,
            int aclPreFilterCount,
            int aclPostFilterCount,
            int returnedCount,
            String retrievalMode,
            String indexCode,
            String indexVersion,
            String embeddingModelVersion,
            long startedNanos,
            String state) {
        long latencyMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                Math.max(0, System.nanoTime() - startedNanos));
        String normalized = Normalizer.normalize(query, Normalizer.Form.NFKC).strip();
        return new AiRunRecords.RetrievalTrace(
                CanonicalJsonHasher.sha256("knowledge-query.v1|" + normalized),
                RETRIEVAL_POLICY_VERSION, retrievalMode, indexCode, indexVersion,
                embeddingModelVersion, FILTER_SUMMARY, topK, aclPreFilterCount,
                aclPostFilterCount, returnedCount, latencyMs, state);
    }

    private KnowledgeAnswer ungrounded(String retrievalMode) {
        return new KnowledgeAnswer(false, "暂无可靠来源，无法确认", List.of(),
                "AI_NO_GROUNDED_ANSWER", retrievalMode);
    }

    private void validateDocument(KnowledgeDocument document) {
        if (document == null || blank(document.sourceId()) || blank(document.documentId())
                || blank(document.documentVersionId()) || blank(document.contentHash())
                || blank(document.title()) || blank(document.content()) || document.visibility() == null
                || document.matchMode() == null) {
            throw new IllegalArgumentException("知识文档合同不完整");
        }
    }

    private List<String> terms(String query) {
        String normalized = Normalizer.normalize(query, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        java.util.LinkedHashSet<String> terms = new java.util.LinkedHashSet<>();
        for (String token : normalized.split("[\\s，。？！、,.!?；;：:]+")) {
            if (token.length() < 2) continue;
            terms.add(token);
            // 中文自然语言通常没有空格；加入有限 2/3-gram，避免把整句当成一个关键词而误拒答。
            if (token.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint)
                    == Character.UnicodeScript.HAN)) {
                for (int width : new int[]{2, 3}) {
                    for (int index = 0; index + width <= token.length(); index++) {
                        terms.add(token.substring(index, index + width));
                    }
                }
            }
        }
        return List.copyOf(terms);
    }

    private int occurrences(String text, String term) {
        String normalized = text.toLowerCase(Locale.ROOT);
        int count = 0;
        int cursor = 0;
        while ((cursor = normalized.indexOf(term, cursor)) >= 0) {
            count++;
            cursor += term.length();
        }
        return count;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String key(String documentId, String versionId) {
        return documentId + "|" + versionId;
    }

    public record KnowledgeDocument(
            String sourceId,
            String documentId,
            String documentVersionId,
            String contentHash,
            String title,
            String content,
            KnowledgeVisibility visibility,
            PermissionMatchMode matchMode,
            Set<String> requiredPermissions,
            boolean scannedClean) {
        public KnowledgeDocument {
            requiredPermissions = requiredPermissions == null ? Set.of() : Set.copyOf(requiredPermissions);
        }
    }

    public record KnowledgeCitation(
            String sourceId,
            String documentVersionId,
            String chunkPublicId,
            String label,
            String locator,
            String quote,
            String contentHash) {
    }

    public record KnowledgeAnswer(
            boolean grounded,
            String answerText,
            List<KnowledgeCitation> citations,
            String reasonCode,
            String retrievalMode) {
        public KnowledgeAnswer {
            citations = List.copyOf(citations);
        }
    }

    public record KnowledgeSearchResult(
            KnowledgeAnswer answer,
            AiRunRecords.RetrievalTrace retrievalTrace) {
        public KnowledgeSearchResult {
            java.util.Objects.requireNonNull(answer);
            java.util.Objects.requireNonNull(retrievalTrace);
        }
    }

    private record StoredDocument(KnowledgeDocument document, String redactedContent, boolean active) {
    }

    private record ScoredDocument(StoredDocument stored, int score) {
    }
}
