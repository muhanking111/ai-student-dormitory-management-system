package com.example.dormitory.ai.evaluation;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.application.control.BillingSubject;
import com.example.dormitory.ai.application.control.BudgetExceededException;
import com.example.dormitory.ai.application.run.AiActorContext;
import com.example.dormitory.ai.config.AiRuntimeControlService;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.AiCapability;
import com.example.dormitory.ai.governance.AiConfigurationGovernanceService;
import com.example.dormitory.ai.governance.GovernanceRequestHasher;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiBudgetService;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiIdempotencyRepository;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiOutboxRepository;
import com.example.dormitory.ai.infrastructure.runtime.AiRuntimeAuditWriter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AiEvaluationRunService {

    private static final Logger LOG = LoggerFactory.getLogger(AiEvaluationRunService.class);
    private static final String WORKER_PRINCIPAL = "ai-eval-worker";
    private static final TypeReference<Map<String, Object>> SUMMARY_TYPE = new TypeReference<>() { };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final EvaluationDatasetRegistry datasets;
    private final AiConfigurationGovernanceService configurations;
    private final JdbcAiIdempotencyRepository idempotency;
    private final JdbcAiBudgetService budgets;
    private final JdbcAiOutboxRepository outbox;
    private final DeterministicEvaluationWorker worker;
    private final TaskExecutor executor;
    private final AiRuntimeControlService controls;
    private final AiRuntimeAuditWriter audit;

    public AiEvaluationRunService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            EvaluationDatasetRegistry datasets,
            AiConfigurationGovernanceService configurations,
            JdbcAiIdempotencyRepository idempotency,
            JdbcAiBudgetService budgets,
            JdbcAiOutboxRepository outbox,
            DeterministicEvaluationWorker worker,
            @Qualifier("aiEvaluationTaskExecutor") TaskExecutor executor,
            AiRuntimeControlService controls,
            AiRuntimeAuditWriter audit) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.datasets = datasets;
        this.configurations = configurations;
        this.idempotency = idempotency;
        this.budgets = budgets;
        this.outbox = outbox;
        this.worker = worker;
        this.executor = executor;
        this.controls = controls;
        this.audit = audit;
    }

    @Transactional
    public EvalRunView request(
            AiActorContext actor,
            EvalRequest request,
            String idempotencyKey,
            String requestHash) {
        if (!controls.capabilityEnabled(AiCapability.EVALUATION)) {
            throw AiApiException.unavailable("AI_EVALUATION_DISABLED", "AI 离线评测能力未启用");
        }
        audit.requireWritable();
        EvaluationDatasetRegistry.LoadedDataset dataset = datasets.load(
                request.suiteName(), request.datasetVersion());
        requireDatasetAccess(actor, dataset);
        PromptDatabaseRef prompt = requirePrompt(request.promptId());
        long deploymentId = requireDeployment(request.modelDeploymentId());
        String aggregate = AiConfigurationGovernanceService.resourceId(
                "eval-request", dataset.suiteName() + "|" + dataset.datasetVersion());
        JdbcAiIdempotencyRepository.Reservation reservation = idempotency.reserve(
                new JdbcAiIdempotencyRepository.Scope(actor.userId(), "AI_EVAL_RUN_CREATE", aggregate,
                        idempotencyKey), requestHash, Instant.now().plusSeconds(86_400));
        if (reservation.status() == JdbcAiIdempotencyRepository.ReservationStatus.REPLAY) {
            String existingId = idempotency.completedResponse(reservation.recordId())
                    .map(JdbcAiIdempotencyRepository.CompletedResponse::resourcePublicId)
                    .orElseThrow(() -> new AiApiException(HttpStatus.CONFLICT,
                            "AI_EVAL_REQUEST_IN_PROGRESS", "相同评测请求仍在处理中", true));
            return requireVisible(existingId, actor);
        }

        try {
            List<EvalBudgetCandidate> budgetCandidates = evaluationBudgetCandidates();
            if (budgetCandidates.isEmpty()) {
                throw AiApiException.unavailable("AI_EVAL_BUDGET_UNAVAILABLE", "独立评测预算未配置");
            }
            int concurrentLimit = budgetCandidates.stream()
                    .mapToInt(EvalBudgetCandidate::concurrentLimit).min().orElse(0);
            Integer active = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ai_eval_run WHERE service_principal_code=? "
                            + "AND state IN ('QUEUED','RUNNING')",
                    Integer.class, WORKER_PRINCIPAL);
            if (concurrentLimit < 1 || active == null || active >= concurrentLimit) {
                throw new BudgetExceededException();
            }
            String publicId = UUID.randomUUID().toString();
            budgets.reserve(new BillingSubject(BillingSubject.Kind.EVAL, publicId),
                    budgetCandidates.stream().map(EvalBudgetCandidate::bucketId).distinct().sorted().toList(),
                    dataset.cases().size(), BigDecimal.ZERO, Instant.now().plusSeconds(600));
            jdbcTemplate.update("INSERT INTO ai_eval_run "
                            + "(public_id,suite_name,dataset_version,prompt_version_id,model_deployment_id,"
                            + "code_revision,state,version,actor_kind,service_principal_code,initiated_by_user_id,"
                            + "effective_subject_user_id,created_operator_user_id,updated_operator_user_id,"
                            + "created_at,updated_at) VALUES (?,?,?,?,?,?,'QUEUED',0,'SERVICE',?,?,?,?,?,"
                            + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    publicId, dataset.suiteName(), dataset.datasetVersion(), prompt.id(), deploymentId,
                    normalizeCodeRevision(request.codeRevision()), WORKER_PRINCIPAL,
                    actor.userId(), actor.userId(), actor.userId(), actor.userId());
            String outboxId = outbox.enqueue(new JdbcAiOutboxRepository.OutboxDraft(
                    "AI_EVAL_RUN", publicId, "EvalRunRequested.v1",
                    "{\"schemaVersion\":\"eval-request.v1\",\"datasetVersion\":\""
                            + dataset.datasetVersion() + "\",\"datasetSha256\":\""
                            + dataset.manifest().sha256() + "\",\"datasetReviewStatus\":\""
                            + dataset.manifest().reviewStatus() + "\"}",
                    ActorDescriptor.service(WORKER_PRINCIPAL, actor.userId(), actor.userId()), Instant.now()));
            audit.append("EVALUATION", "EVAL_RUN", publicId, "EVAL_RUN_QUEUED",
                    actor, requestHash, UUID.randomUUID().toString());
            idempotency.complete(reservation.recordId(), HttpStatus.ACCEPTED.value(), publicId);
            scheduleAfterCommit(publicId, outboxId);
            return requireVisible(publicId, actor);
        } catch (RuntimeException failure) {
            idempotency.releasePending(reservation.recordId());
            throw failure;
        }
    }

    public EvalRunView requireVisible(String publicId, AiActorContext actor) {
        String id = requireUuid(publicId);
        List<EvalRunRow> rows = jdbcTemplate.query(
                "SELECT e.id,e.public_id,e.suite_name,e.dataset_version,e.code_revision,e.state,e.version,"
                        + "e.service_principal_code,e.initiated_by_user_id,e.started_at,e.finished_at,"
                        + "e.summary_text,e.created_at,p.prompt_key,p.version AS prompt_version,"
                        + "p.content_hash AS prompt_content_hash,"
                        + "d.public_id AS deployment_public_id "
                        + "FROM ai_eval_run e JOIN ai_prompt_version p ON p.id=e.prompt_version_id "
                        + "LEFT JOIN ai_model_deployment d ON d.id=e.model_deployment_id WHERE e.public_id=?",
                this::mapRun, id);
        if (rows.isEmpty()) throw AiApiException.notFound();
        EvalRunRow row = rows.getFirst();
        if (row.initiatedByUserId() == null
                || (row.initiatedByUserId() != actor.userId() && !actor.roleCodes().contains("ADMIN"))) {
            throw AiApiException.notFound();
        }
        requireDatasetAccess(actor, datasets.load(row.suiteName(), row.datasetVersion()));
        List<EvalResultView> results = jdbcTemplate.query(
                "SELECT case_key,capability,state,metrics_text,failure_tags_text,artifact_path "
                        + "FROM ai_eval_result WHERE eval_run_id=? ORDER BY case_key",
                (rs, number) -> new EvalResultView(rs.getString("case_key"), rs.getString("capability"),
                        rs.getString("state"), parseMap(rs.getString("metrics_text")),
                        parseStringList(rs.getString("failure_tags_text")), rs.getString("artifact_path")),
                row.id());
        return new EvalRunView(row.publicId(), row.suiteName(), row.datasetVersion(), row.promptPublicId(),
                row.promptKey(), row.promptVersion(), row.deploymentPublicId(), row.codeRevision(), row.state(),
                row.version(), row.servicePrincipalCode(), row.initiatedByUserId(), row.startedAt(),
                row.finishedAt(), parseMap(row.summaryText()), results, row.createdAt());
    }

    private PromptDatabaseRef requirePrompt(String promptPublicId) {
        configurations.requirePrompt(promptPublicId);
        return jdbcTemplate.query("SELECT id,prompt_key,version,content_hash FROM ai_prompt_version ORDER BY id",
                        (rs, row) -> new PromptDatabaseRef(rs.getLong("id"),
                                AiConfigurationGovernanceService.promptPublicId(rs.getString("prompt_key"),
                                        rs.getString("version"), rs.getString("content_hash"))))
                .stream().filter(value -> value.publicId().equals(promptPublicId)).findFirst()
                .orElseThrow(AiApiException::notFound);
    }

    private long requireDeployment(String deploymentPublicId) {
        String id = requireUuid(deploymentPublicId);
        List<Long> rows = jdbcTemplate.queryForList(
                "SELECT id FROM ai_model_deployment WHERE public_id=? AND enabled=TRUE", Long.class, id);
        if (rows.isEmpty()) throw AiApiException.notFound();
        return rows.getFirst();
    }

    private List<EvalBudgetCandidate> evaluationBudgetCandidates() {
        return jdbcTemplate.query(
                "SELECT b.id,q.concurrent_run_limit FROM ai_budget_bucket b "
                        + "JOIN ai_quota_policy q ON q.id=b.quota_policy_id "
                        + "WHERE b.capability='EVALUATION' AND b.provider_code='offline' "
                        + "AND b.period_start<=CURRENT_TIMESTAMP AND b.period_end>CURRENT_TIMESTAMP "
                        + "AND q.status='ACTIVE' AND q.effective_from<=CURRENT_TIMESTAMP "
                        + "AND ((b.scope_type='SERVICE' AND b.scope_key=?) "
                        + "OR (b.scope_type='GLOBAL' AND b.scope_key='*') "
                        + "OR (b.scope_type='CAPABILITY' AND b.scope_key='EVALUATION') "
                        + "OR (b.scope_type='PROVIDER' AND b.scope_key='offline')) "
                        + "ORDER BY b.id FOR UPDATE",
                (rs, row) -> new EvalBudgetCandidate(
                        rs.getLong("id"), rs.getInt("concurrent_run_limit")), WORKER_PRINCIPAL);
    }

    private static void requireDatasetAccess(
            AiActorContext actor,
            EvaluationDatasetRegistry.LoadedDataset dataset) {
        if (!actor.permissionCodes().containsAll(dataset.requiredPermissions())) {
            throw new AiApiException(HttpStatus.FORBIDDEN, "AI_EVAL_DATASET_FORBIDDEN",
                    "无权访问对应评测数据集", false);
        }
    }

    private void scheduleAfterCommit(String evalRunId, String outboxId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("评测创建必须在事务中调度");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    executor.execute(() -> worker.process(evalRunId, outboxId));
                } catch (RuntimeException rejected) {
                    // 持久化 QUEUED/outbox 事实已经提交；周期恢复器会安全重试，不把已提交的 202 变成 500。
                    LOG.warn("Eval executor rejected queued run {}; scheduled recovery will retry", evalRunId);
                }
            }
        });
    }

    private EvalRunRow mapRun(ResultSet rs, int row) throws SQLException {
        String promptKey = rs.getString("prompt_key");
        String promptVersion = rs.getString("prompt_version");
        Long initiator = nullableLong(rs, "initiated_by_user_id");
        return new EvalRunRow(rs.getLong("id"), rs.getString("public_id"), rs.getString("suite_name"),
                rs.getString("dataset_version"),
                AiConfigurationGovernanceService.promptPublicId(
                        promptKey, promptVersion, rs.getString("prompt_content_hash")),
                promptKey, promptVersion, rs.getString("deployment_public_id"),
                rs.getString("code_revision"), rs.getString("state"), rs.getLong("version"),
                rs.getString("service_principal_code"), initiator, nullableInstant(rs, "started_at"),
                nullableInstant(rs, "finished_at"), rs.getString("summary_text"), instant(rs, "created_at"));
    }

    private Map<String, Object> parseMap(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(value, SUMMARY_TYPE);
        } catch (Exception exception) {
            throw new IllegalStateException("评测摘要 JSON 损坏", exception);
        }
    }

    private List<String> parseStringList(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return objectMapper.readValue(value, new TypeReference<List<String>>() { });
        } catch (Exception exception) {
            throw new IllegalStateException("评测失败标签 JSON 损坏", exception);
        }
    }

    private static String normalizeCodeRevision(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._/-]{2,127}")) {
            throw new IllegalArgumentException("codeRevision 不合法");
        }
        return normalized;
    }

    private static String requireUuid(String value) {
        try {
            return UUID.fromString(value == null ? "" : value.trim()).toString();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("eval run 资源 ID 必须是 UUID", exception);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public record EvalRequest(
            String suiteName,
            String datasetVersion,
            String promptId,
            String modelDeploymentId,
            String codeRevision) {
    }

    public record EvalRunView(
            String id,
            String suiteName,
            String datasetVersion,
            String promptId,
            String promptKey,
            String promptVersion,
            String modelDeploymentId,
            String codeRevision,
            String state,
            long version,
            String servicePrincipalCode,
            Long initiatedByUserId,
            Instant startedAt,
            Instant finishedAt,
            Map<String, Object> summary,
            List<EvalResultView> results,
            Instant createdAt) {
        public EvalRunView {
            summary = Map.copyOf(summary);
            results = List.copyOf(results);
        }
    }

    public record EvalResultView(
            String caseKey,
            String capability,
            String state,
            Map<String, Object> metrics,
            List<String> failureTags,
            String artifactPath) {
        public EvalResultView {
            // JSON 指标允许用 null 明确表达“确定性执行器未产生该值”。
            // Map.copyOf 会拒绝这种合法 JSON，导致读取已完成评测时返回 500。
            metrics = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(metrics));
            failureTags = List.copyOf(failureTags);
        }
    }

    private record PromptDatabaseRef(long id, String publicId) {
    }

    private record EvalBudgetCandidate(long bucketId, int concurrentLimit) {
    }

    private record EvalRunRow(
            long id,
            String publicId,
            String suiteName,
            String datasetVersion,
            String promptPublicId,
            String promptKey,
            String promptVersion,
            String deploymentPublicId,
            String codeRevision,
            String state,
            long version,
            String servicePrincipalCode,
            Long initiatedByUserId,
            Instant startedAt,
            Instant finishedAt,
            String summaryText,
            Instant createdAt) {
    }
}
