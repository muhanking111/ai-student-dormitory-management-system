package com.example.dormitory.ai.config;

import com.example.dormitory.ai.approval.ActionProposalRepository;
import com.example.dormitory.ai.approval.ActionProposalService;
import com.example.dormitory.ai.approval.SpringActionProposalTransactionRunner;
import com.example.dormitory.ai.dashboard.DashboardIntentParser;
import com.example.dormitory.ai.dashboard.DashboardQueryService;
import com.example.dormitory.ai.dashboard.MetricCatalog;
import com.example.dormitory.ai.dashboard.MetricQueryExecutor;
import com.example.dormitory.ai.infrastructure.business.ApprovedDormitoryBusinessActionAdapter;
import com.example.dormitory.ai.infrastructure.business.DormitoryBusinessReadAdapter;
import com.example.dormitory.ai.infrastructure.business.DormitoryBusinessSnapshotProvider;
import com.example.dormitory.ai.infrastructure.business.RbacRepairCandidateProvider;
import com.example.dormitory.ai.infrastructure.security.JdbcKnownPiiDictionaryAdapter;
import com.example.dormitory.ai.infrastructure.runtime.AiRuntimeAuditWriter;
import com.example.dormitory.ai.infrastructure.runtime.RuntimeAiAuditAdapter;
import com.example.dormitory.ai.knowledge.KnowledgeAclPolicy;
import com.example.dormitory.ai.knowledge.KnowledgeAssistantService;
import com.example.dormitory.ai.knowledge.SafeKnowledgeService;
import com.example.dormitory.ai.knowledge.KnowledgeSourceRepository;
import com.example.dormitory.ai.knowledge.KnowledgeVersionRepository;
import com.example.dormitory.ai.knowledge.KnowledgeIngestionJobRepository;
import com.example.dormitory.ai.knowledge.KnowledgeIngestionService;
import com.example.dormitory.ai.knowledge.UploadSessionService;
import com.example.dormitory.ai.knowledge.KnowledgeExternalKeyHasher;
import com.example.dormitory.ai.knowledge.KnowledgeFileScanner;
import com.example.dormitory.ai.knowledge.ControlledPlainTextFileScanner;
import com.example.dormitory.ai.knowledge.KnowledgeUploadSessionRepository;
import com.example.dormitory.ai.port.ObjectStoragePort;
import com.example.dormitory.ai.infrastructure.fake.InMemoryVersionedObjectStorage;
import com.example.dormitory.ai.infrastructure.fake.DeterministicFakeEmbeddingGateway;
import com.example.dormitory.ai.infrastructure.fake.DeterministicInMemoryVectorIndex;
import com.example.dormitory.ai.notice.NoticeDraftService;
import com.example.dormitory.ai.observability.AiOperationalMetrics;
import com.example.dormitory.ai.port.AiAuditPort;
import com.example.dormitory.ai.port.ApprovedBusinessActionPort;
import com.example.dormitory.ai.port.BusinessReadFacade;
import com.example.dormitory.ai.port.EmbeddingGateway;
import com.example.dormitory.ai.port.KnownPiiDictionaryPort;
import com.example.dormitory.ai.port.VectorIndexPort;
import com.example.dormitory.ai.tool.ToolCatalog;
import com.example.dormitory.ai.repair.RepairTriageService;
import com.example.dormitory.ai.security.ActorAuthorizationFacade;
import com.example.dormitory.ai.security.ActionAuthorizationPolicy;
import com.example.dormitory.ai.security.PiiClassificationService;
import com.example.dormitory.ai.security.PiiRedactionService;
import com.example.dormitory.ai.security.PromptInjectionGuard;
import com.example.dormitory.service.DormitoryQueryService;
import com.example.dormitory.service.OperationsService;
import com.example.dormitory.service.RbacService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.Set;

@Configuration
public class AiFeatureConfiguration {

    @Bean
    PromptInjectionGuard aiPromptInjectionGuard() {
        return new PromptInjectionGuard();
    }

    @Bean
    PiiRedactionService aiPiiRedactionService(AiProperties properties) {
        byte[] configured = properties.getTokenization().getHmacKey().getBytes(StandardCharsets.UTF_8);
        if (configured.length >= 32) {
            return new PiiRedactionService(configured,
                    "v" + properties.getTokenization().getActiveKeyVersion());
        }
        // AI 总开关关闭时仍需构造 Bean 以提供稳定 503 API；运行门会阻止使用这把进程内临时 key。
        byte[] ephemeral = new byte[32];
        new java.security.SecureRandom().nextBytes(ephemeral);
        return new PiiRedactionService(ephemeral, "disabled-ephemeral-v1");
    }

    @Bean
    KnownPiiDictionaryPort aiKnownPiiDictionaryPort(JdbcTemplate jdbcTemplate) {
        return new JdbcKnownPiiDictionaryAdapter(jdbcTemplate);
    }

    @Bean
    PiiClassificationService aiPiiClassificationService(
            PiiRedactionService redactionService,
            KnownPiiDictionaryPort dictionary) {
        return new PiiClassificationService(redactionService, dictionary);
    }

    @Bean
    KnowledgeAclPolicy knowledgeAclPolicy() {
        return new KnowledgeAclPolicy();
    }

    @Bean
    EmbeddingGateway knowledgeEmbeddingGateway() {
        return new DeterministicFakeEmbeddingGateway();
    }

    @Bean
    VectorIndexPort knowledgeVectorIndexPort() {
        return new DeterministicInMemoryVectorIndex("knowledge-index-v1");
    }

    @Bean
    ToolCatalog aiToolCatalog() {
        return ToolCatalog.standard();
    }

    @Bean
    AiOperationalMetrics.TagVocabulary aiOperationalMetricVocabulary(
            AiProperties properties,
            ToolCatalog tools) {
        Set<String> models = new LinkedHashSet<>();
        models.add("deterministic-fake-v1");
        String configured = properties.getProvider().getModelAlias();
        if (configured != null && configured.matches("[A-Za-z0-9][A-Za-z0-9._-]{1,127}")) {
            models.add(configured);
        }
        return new AiOperationalMetrics.TagVocabulary(
                Set.of("fake", "spring-ai"),
                Set.copyOf(models),
                tools.definitions().keySet(),
                Set.of("knowledge-vector-index", "knowledge-index-v1"));
    }

    @Bean
    AiOperationalMetrics aiOperationalMetrics(
            MeterRegistry meterRegistry,
            AiOperationalMetrics.TagVocabulary vocabulary) {
        return new AiOperationalMetrics(meterRegistry, vocabulary);
    }

    @Bean
    SafeKnowledgeService safeKnowledgeService(
            KnowledgeAclPolicy acl,
            PromptInjectionGuard guard,
            PiiRedactionService redaction,
            AiRuntimeControlService controls,
            KnowledgeSourceRepository sources,
            KnowledgeVersionRepository versions,
            EmbeddingGateway embeddings,
            VectorIndexPort vectorIndex) {
        return new SafeKnowledgeService(acl, guard, redaction,
                source -> controls.capabilityEnabled(com.example.dormitory.ai.domain.model.AiCapability.KNOWLEDGE)
                        && controls.sourceEnabled(source), sources, versions, embeddings, vectorIndex);
    }

    @Bean
    ObjectStoragePort knowledgeObjectStoragePort() {
        return new InMemoryVersionedObjectStorage(20L * 1024 * 1024);
    }

    @Bean
    KnowledgeFileScanner knowledgeFileScanner(PromptInjectionGuard guard) {
        return new ControlledPlainTextFileScanner(guard, UploadSessionService.MAXIMUM_TEXT_BYTES);
    }

    @Bean
    UploadSessionService uploadSessionService(
            ObjectStoragePort storage,
            KnowledgeUploadSessionRepository repository,
            KnowledgeFileScanner scanner) {
        return new UploadSessionService(storage, repository, scanner, java.time.Clock.systemUTC());
    }

    @Bean
    KnowledgeIngestionService knowledgeIngestionService(
            KnowledgeSourceRepository sources, KnowledgeVersionRepository versions,
            KnowledgeIngestionJobRepository jobs, ObjectStoragePort storage,
            PromptInjectionGuard guard, PiiClassificationService classification, AiRuntimeControlService controls,
            SafeKnowledgeService retrieval) {
        return new KnowledgeIngestionService(sources, versions, jobs, storage, guard, classification,
                java.time.Clock.systemUTC(),
                source -> controls.capabilityEnabled(com.example.dormitory.ai.domain.model.AiCapability.KNOWLEDGE)
                        && controls.sourceEnabled(source), retrieval);
    }

    @Bean
    KnowledgeExternalKeyHasher knowledgeExternalKeyHasher(AiProperties properties) {
        return new KnowledgeExternalKeyHasher(properties);
    }

    @Bean
    KnowledgeAssistantService knowledgeAssistantService(
            SafeKnowledgeService knowledge,
            PromptInjectionGuard guard,
            PiiClassificationService classification) {
        return new KnowledgeAssistantService(knowledge, guard, classification);
    }

    @Bean
    DormitoryBusinessSnapshotProvider dormitoryBusinessSnapshotProvider(
            OperationsService operations,
            ObjectMapper objectMapper) {
        return new DormitoryBusinessSnapshotProvider(operations, objectMapper);
    }

    @Bean
    ApprovedBusinessActionPort approvedBusinessActionPort(
            OperationsService operations,
            RbacService rbac,
            DormitoryBusinessSnapshotProvider snapshots,
            ObjectMapper objectMapper,
            AiRuntimeControlService runtimeControls) {
        return new ApprovedDormitoryBusinessActionAdapter(
                operations, rbac, snapshots, snapshots, objectMapper,
                runtimeControls::writeExecutionEnabled);
    }

    @Bean
    AiAuditPort aiAuditPort(AiRuntimeAuditWriter writer) {
        return new RuntimeAiAuditAdapter(writer);
    }

    @Bean
    ActionProposalService actionProposalService(
            ActionProposalRepository repository,
            ApprovedBusinessActionPort actionPort,
            DormitoryBusinessSnapshotProvider snapshots,
            AiAuditPort audit,
            AiRuntimeControlService runtimeControls,
            SpringActionProposalTransactionRunner transactions,
            ActionAuthorizationPolicy actionAuthorization) {
        return new ActionProposalService(repository, actionPort, snapshots, audit,
                runtimeControls::writeExecutionEnabled, transactions,
                (actor, proposal, ignoredRequestSnapshot) ->
                        actionAuthorization.requireFreshExecutionAccess(actor, proposal));
    }

    @Bean
    DormitoryBusinessReadAdapter dormitoryBusinessReadFacade(
            ActorAuthorizationFacade authorization,
            DormitoryQueryService dashboard,
            OperationsService operations,
            ObjectMapper objectMapper,
            PiiClassificationService classification,
            RbacService rbacService,
            Clock clock) {
        return new DormitoryBusinessReadAdapter(authorization, dashboard, operations, objectMapper, classification,
                new RbacRepairCandidateProvider(rbacService), clock);
    }

    @Bean
    MetricCatalog metricCatalog() {
        return MetricCatalog.defaults();
    }

    @Bean
    DashboardQueryService dashboardQueryService(
            MetricCatalog catalog,
            DormitoryBusinessReadAdapter reads,
            Clock clock) {
        return new DashboardQueryService(new DashboardIntentParser(catalog),
                new MetricQueryExecutor(catalog, reads, clock));
    }

    @Bean
    NoticeDraftService noticeDraftService(
            ActionProposalService proposals,
            PromptInjectionGuard guard,
            PiiClassificationService classification,
            ObjectMapper objectMapper) {
        return new NoticeDraftService(proposals, guard, classification, objectMapper);
    }

    @Bean
    RepairTriageService repairTriageService(
            BusinessReadFacade reads,
            ActionProposalService proposals,
            PromptInjectionGuard guard,
            ObjectMapper objectMapper) {
        return new RepairTriageService(reads, proposals, guard, objectMapper);
    }
}
