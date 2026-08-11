package com.example.dormitory.ai.governance;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.application.run.AiActorContext;
import com.example.dormitory.ai.approval.CanonicalJsonHasher;
import com.example.dormitory.ai.config.AiRuntimeControlService;
import com.example.dormitory.ai.dashboard.MetricCatalog;
import com.example.dormitory.ai.dashboard.MetricDefinition;
import com.example.dormitory.ai.knowledge.KnowledgeVersionRepository;
import com.example.dormitory.ai.infrastructure.runtime.AiRuntimeAuditWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class AiCitationQueryService {

    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeVersionRepository versions;
    private final MetricCatalog metrics;
    private final AiRuntimeControlService controls;
    private final AiRuntimeAuditWriter auditWriter;

    public AiCitationQueryService(
            JdbcTemplate jdbcTemplate,
            KnowledgeVersionRepository versions,
            MetricCatalog metrics,
            AiRuntimeControlService controls,
            AiRuntimeAuditWriter auditWriter) {
        this.jdbcTemplate = jdbcTemplate;
        this.versions = versions;
        this.metrics = metrics;
        this.controls = controls;
        this.auditWriter = auditWriter;
    }

    public CitationView requireVisible(String publicId, AiActorContext actor) {
        String id = requireUuid(publicId);
        try {
            CitationView view = resolveVisible(id, actor);
            auditAccess(id, actor, "CITATION_READ_GRANTED");
            return view;
        } catch (RuntimeException denied) {
            auditAccess(id, actor, "CITATION_READ_DENIED");
            throw denied;
        }
    }

    public CitationSummary summarizeCurrentAccess(String publicId, AiActorContext actor) {
        String id = requireUuid(publicId);
        try {
            CitationView view = resolveVisible(id, actor);
            return new CitationSummary(view.id(), view.label(), view.locator(), view.version(), "available");
        } catch (AiApiException denied) {
            if (denied.status() != HttpStatus.NOT_FOUND) throw denied;
            return CitationSummary.denied(id);
        }
    }

    private CitationView resolveVisible(String id, AiActorContext actor) {
        List<BaseCitation> base = jdbcTemplate.query(
                "SELECT public_id,citation_type,metric_id,rank_no,score,quote_redacted,locator_text,"
                        + "content_hash,created_at FROM ai_citation WHERE public_id=?",
                this::mapBase, id);
        if (base.isEmpty()) throw AiApiException.notFound();
        BaseCitation citation = base.getFirst();
        return switch (citation.citationType()) {
            case "KNOWLEDGE" -> knowledgeCitation(citation, actor);
            case "METRIC" -> metricCitation(citation, actor);
            default -> throw AiApiException.notFound();
        };
    }

    private CitationView knowledgeCitation(BaseCitation citation, AiActorContext actor) {
        if (!actor.permissionCodes().contains("ai:knowledge:read")) throw AiApiException.notFound();
        List<KnowledgeBinding> bindings = jdbcTemplate.query(
                "SELECT s.public_id AS source_public_id,s.status AS source_status,"
                        + "d.public_id AS document_public_id,d.title AS document_title,d.status AS document_status,"
                        + "v.public_id AS version_public_id,v.version AS version_label,v.status AS version_status,"
                        + "ch.public_id AS chunk_public_id,ch.status AS chunk_status,"
                        + "ch.content_hash AS chunk_content_hash "
                        + "FROM ai_citation c JOIN ai_document_version v ON v.id=c.document_version_id "
                        + "JOIN ai_document_chunk ch ON ch.id=c.chunk_id AND ch.document_version_id=v.id "
                        + "JOIN ai_document d ON d.id=v.document_id JOIN ai_knowledge_source s ON s.id=d.source_id "
                        + "WHERE c.public_id=?",
                (rs, row) -> new KnowledgeBinding(rs.getString("source_public_id"),
                        rs.getString("source_status"), rs.getString("document_public_id"),
                        rs.getString("document_title"), rs.getString("document_status"),
                        rs.getString("version_public_id"), rs.getString("version_label"),
                        rs.getString("version_status"), rs.getString("chunk_public_id"),
                        rs.getString("chunk_status"), rs.getString("chunk_content_hash")),
                citation.publicId());
        if (bindings.isEmpty()) throw AiApiException.notFound();
        KnowledgeBinding binding = bindings.getFirst();
        if (!controls.sourceEnabled(binding.sourcePublicId())
                || !"ACTIVE".equals(binding.sourceStatus()) || !"ACTIVE".equals(binding.documentStatus())
                || !"ACTIVE".equals(binding.versionStatus())
                || !"READY".equals(binding.chunkStatus())
                || citation.contentHash() == null
                || !citation.contentHash().equals(binding.chunkContentHash())) {
            throw AiApiException.notFound();
        }
        try {
            if (!versions.canRead(binding.versionPublicId(), Set.copyOf(actor.permissionCodes()))) {
                throw AiApiException.notFound();
            }
        } catch (AiApiException denied) {
            throw denied;
        } catch (RuntimeException corruptedAclOrApproval) {
            throw AiApiException.notFound();
        }
        return new CitationView(citation.publicId(), citation.citationType(), binding.documentTitle(),
                binding.versionLabel(), binding.sourcePublicId(), binding.documentPublicId(),
                binding.versionPublicId(), binding.chunkPublicId(), null,
                citation.rankNo(), citation.score(), citation.quote(), citation.locator(),
                citation.contentHash(), citation.createdAt());
    }

    private void auditAccess(String citationId, AiActorContext actor, String eventType) {
        String payloadHash = CanonicalJsonHasher.sha256(
                "citation-read.v1|" + citationId + "|" + eventType);
        auditWriter.append("SECURITY", "CITATION", citationId, eventType, actor,
                payloadHash, UUID.randomUUID().toString());
    }

    private CitationView metricCitation(BaseCitation citation, AiActorContext actor) {
        if (!actor.permissionCodes().contains("ai:dashboard:query") || citation.metricId() == null) {
            throw AiApiException.notFound();
        }
        MetricDefinition metric;
        try {
            metric = metrics.require(citation.metricId());
        } catch (RuntimeException unsupported) {
            throw AiApiException.notFound();
        }
        if (!actor.permissionCodes().containsAll(metric.requiredPermissions())) throw AiApiException.notFound();
        return new CitationView(citation.publicId(), citation.citationType(), metric.label(), metric.version(),
                null, null, null, null, citation.metricId(), citation.rankNo(), citation.score(), citation.quote(),
                citation.locator(), citation.contentHash(), citation.createdAt());
    }

    private BaseCitation mapBase(ResultSet rs, int row) throws SQLException {
        return new BaseCitation(rs.getString("public_id"), rs.getString("citation_type"),
                rs.getString("metric_id"), rs.getInt("rank_no"), rs.getBigDecimal("score"),
                rs.getString("quote_redacted"), rs.getString("locator_text"),
                rs.getString("content_hash"), rs.getTimestamp("created_at").toInstant());
    }

    private static String requireUuid(String value) {
        try {
            return UUID.fromString(value == null ? "" : value.trim()).toString();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("citation ID 必须为 UUID", exception);
        }
    }

    public record CitationView(
            String id,
            String type,
            String label,
            String version,
            String sourceId,
            String documentId,
            String documentVersionId,
            String chunkId,
            String metricId,
            int rank,
            BigDecimal score,
            String quote,
            String locator,
            String contentHash,
            Instant createdAt) {
    }

    public record CitationSummary(String id, String label, String locator, String version, String access) {
        public static CitationSummary denied(String id) {
            return new CitationSummary(id, "受限来源", "当前不可访问", "", "denied");
        }
    }

    private record BaseCitation(
            String publicId,
            String citationType,
            String metricId,
            int rankNo,
            BigDecimal score,
            String quote,
            String locator,
            String contentHash,
            Instant createdAt) {
    }

    private record KnowledgeBinding(
            String sourcePublicId,
            String sourceStatus,
            String documentPublicId,
            String documentTitle,
            String documentStatus,
            String versionPublicId,
            String versionLabel,
            String versionStatus,
            String chunkPublicId,
            String chunkStatus,
            String chunkContentHash) {
    }
}
