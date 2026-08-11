package com.example.dormitory.ai.evaluation;

import com.example.dormitory.ai.application.control.BillingSubject;
import com.example.dormitory.ai.approval.CanonicalJsonHasher;
import com.example.dormitory.ai.config.AiRuntimeControlService;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.AiCapability;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiBudgetService;
import com.example.dormitory.ai.infrastructure.runtime.AiRuntimeAuditWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class DeterministicEvaluationWorker {

    private static final Logger LOG = LoggerFactory.getLogger(DeterministicEvaluationWorker.class);
    private static final String WORKER = "ai-eval-worker";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final EvaluationDatasetRegistry datasets;
    private final JdbcAiBudgetService budgets;
    private final AiRuntimeAuditWriter audit;
    private final TransactionTemplate transactions;
    private final AiRuntimeControlService controls;
    private final DeterministicEvaluationEngine engine;

    public DeterministicEvaluationWorker(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            EvaluationDatasetRegistry datasets,
            JdbcAiBudgetService budgets,
            AiRuntimeAuditWriter audit,
            PlatformTransactionManager transactionManager,
            AiRuntimeControlService controls,
            DeterministicEvaluationEngine engine) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.datasets = datasets;
        this.budgets = budgets;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
        this.controls = controls;
        this.engine = engine;
    }

    public void process(String evalRunId, String outboxId) {
        if (!controls.capabilityEnabled(AiCapability.EVALUATION)) return;
        try {
            transactions.executeWithoutResult(status -> complete(evalRunId, outboxId));
        } catch (RuntimeException failure) {
            LOG.warn("Deterministic eval worker failed for {}: {}", evalRunId,
                    failure.getClass().getSimpleName());
            try {
                transactions.executeWithoutResult(status -> fail(evalRunId, outboxId));
            } catch (RuntimeException terminalFailure) {
                LOG.error("Unable to persist deterministic eval failure for {}", evalRunId, terminalFailure);
            }
        }
    }

    @Scheduled(
            initialDelayString = "${dormitory.ai.evaluation.worker-initial-delay:PT5S}",
            fixedDelayString = "${dormitory.ai.evaluation.worker-interval:PT10S}")
    public void recoverQueued() {
        if (!controls.capabilityEnabled(AiCapability.EVALUATION)) return;
        List<QueuedWork> queued = jdbcTemplate.query(
                "SELECT e.public_id AS eval_id,o.public_id AS outbox_id FROM ai_eval_run e "
                        + "JOIN ai_outbox_event o ON o.aggregate_type='AI_EVAL_RUN' "
                        + "AND o.aggregate_public_id=e.public_id AND o.event_type='EvalRunRequested.v1' "
                        + "WHERE e.state='QUEUED' AND o.state='PENDING' ORDER BY e.created_at,e.id LIMIT 20",
                (rs, row) -> new QueuedWork(rs.getString("eval_id"), rs.getString("outbox_id")));
        for (QueuedWork work : queued) process(work.evalRunId(), work.outboxId());
    }

    private void complete(String evalRunId, String outboxId) {
        audit.requireWritable();
        List<RunInput> rows = jdbcTemplate.query(
                "SELECT id,suite_name,dataset_version,initiated_by_user_id,version FROM ai_eval_run "
                        + "WHERE public_id=? AND state='QUEUED' FOR UPDATE",
                (rs, row) -> new RunInput(rs.getLong("id"), rs.getString("suite_name"),
                        rs.getString("dataset_version"), rs.getLong("initiated_by_user_id"),
                        rs.getLong("version")), evalRunId);
        if (rows.isEmpty()) return;
        RunInput run = rows.getFirst();
        int outboxClaimed = jdbcTemplate.update(
                "UPDATE ai_outbox_event SET state='PROCESSING',locked_by=?,locked_at=CURRENT_TIMESTAMP,"
                        + "version=version+1,updated_at=CURRENT_TIMESTAMP "
                        + "WHERE public_id=? AND aggregate_public_id=? AND event_type='EvalRunRequested.v1' "
                        + "AND state='PENDING'",
                WORKER, outboxId, evalRunId);
        if (outboxClaimed != 1) return;
        int claimed = jdbcTemplate.update("UPDATE ai_eval_run SET state='RUNNING',started_at=CURRENT_TIMESTAMP,"
                        + "version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND state='QUEUED' AND version=?",
                run.id(), run.version());
        if (claimed != 1) throw new IllegalStateException("eval run claim CAS 未命中");
        EvaluationDatasetRegistry.LoadedDataset dataset = datasets.load(run.suite(), run.datasetVersion());
        DeterministicEvaluationEngine.DatasetEvaluation evaluation = engine.evaluate(dataset);
        for (DeterministicEvaluationEngine.CaseEvaluation evaluationCase : evaluation.cases()) {
            Map<String, Object> metrics = new LinkedHashMap<>(evaluationCase.metrics());
            metrics.put("engineType", "DETERMINISTIC_CONTRACT");
            metrics.put("datasetSha256", dataset.manifest().sha256());
            jdbcTemplate.update("INSERT INTO ai_eval_result "
                            + "(eval_run_id,case_key,capability,state,metrics_text,failure_tags_text,artifact_path,"
                            + "created_at,updated_at) VALUES (?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    run.id(), evaluationCase.caseKey(), dataset.capability(), evaluationCase.state(), json(metrics),
                    json(evaluationCase.failureTags()), dataset.artifactPath());
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("engineType", "DETERMINISTIC_CONTRACT");
        summary.put("providerStatus", "NOT_RUN");
        summary.put("datasetSha256", dataset.manifest().sha256());
        summary.put("datasetReviewStatus", dataset.manifest().reviewStatus());
        summary.put("totalCases", dataset.cases().size());
        summary.put("passedCases", evaluation.passedCases());
        summary.put("failedCases", evaluation.failedCases());
        summary.put("expectedCompared", true);
        summary.put("semanticQualityClaimed", false);
        String summaryJson = json(summary);
        budgets.commit(new BillingSubject(BillingSubject.Kind.EVAL, evalRunId),
                dataset.cases().size(), BigDecimal.ZERO);
        String terminalState = evaluation.passed() ? "PASSED" : "FAILED";
        int completed = jdbcTemplate.update("UPDATE ai_eval_run SET state=?,summary_text=?,"
                        + "finished_at=CURRENT_TIMESTAMP,version=version+1,updated_at=CURRENT_TIMESTAMP "
                        + "WHERE id=? AND state='RUNNING'",
                terminalState, summaryJson, run.id());
        if (completed != 1) throw new IllegalStateException("eval run 完成 CAS 未命中");
        int outboxCompleted = jdbcTemplate.update(
                "UPDATE ai_outbox_event SET state='SUCCEEDED',published_at=CURRENT_TIMESTAMP,"
                        + "locked_by=NULL,locked_at=NULL,version=version+1,updated_at=CURRENT_TIMESTAMP "
                        + "WHERE public_id=? AND state='PROCESSING' AND locked_by=?",
                outboxId, WORKER);
        if (outboxCompleted != 1) throw new IllegalStateException("eval outbox 完成 CAS 未命中");
        audit.append("EVALUATION", "EVAL_RUN", evalRunId,
                evaluation.passed() ? "EVAL_RUN_PASSED" : "EVAL_RUN_FAILED",
                ActorDescriptor.service(WORKER, run.initiatedByUserId(), run.initiatedByUserId()),
                null, 1, null, CanonicalJsonHasher.sha256(summaryJson), UUID.randomUUID().toString());
    }

    private void fail(String evalRunId, String outboxId) {
        audit.requireWritable();
        List<Long> initiators = jdbcTemplate.queryForList(
                "SELECT initiated_by_user_id FROM ai_eval_run WHERE public_id=?", Long.class, evalRunId);
        if (initiators.isEmpty()) return;
        Map<String, Object> summary = Map.of(
                "engineType", "DETERMINISTIC_CONTRACT",
                "providerStatus", "NOT_RUN",
                "failureCode", "AI_EVAL_CONTRACT_FAILED",
                "semanticQualityClaimed", false);
        String summaryJson = json(summary);
        int failed = jdbcTemplate.update("UPDATE ai_eval_run SET state='FAILED',summary_text=?,finished_at=CURRENT_TIMESTAMP,"
                        + "version=version+1,updated_at=CURRENT_TIMESTAMP WHERE public_id=? "
                        + "AND state IN ('QUEUED','RUNNING')",
                summaryJson, evalRunId);
        if (failed != 1) return;
        budgets.releaseIfPresent(new BillingSubject(BillingSubject.Kind.EVAL, evalRunId));
        int outboxFailed = jdbcTemplate.update(
                "UPDATE ai_outbox_event SET state='SUCCEEDED',published_at=CURRENT_TIMESTAMP,"
                        + "locked_by=NULL,locked_at=NULL,last_error_code='AI_EVAL_CONTRACT_FAILED',"
                        + "version=version+1,updated_at=CURRENT_TIMESTAMP WHERE public_id=? "
                        + "AND (state='PENDING' OR (state='PROCESSING' AND locked_by=?))",
                outboxId, WORKER);
        if (outboxFailed != 1) throw new IllegalStateException("eval failure outbox CAS 未命中");
        audit.append("EVALUATION", "EVAL_RUN", evalRunId, "EVAL_RUN_FAILED",
                ActorDescriptor.service(WORKER, initiators.getFirst(), initiators.getFirst()),
                null, 1, null, CanonicalJsonHasher.sha256(summaryJson), UUID.randomUUID().toString());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("eval JSON 序列化失败", exception);
        }
    }

    private record RunInput(long id, String suite, String datasetVersion, long initiatedByUserId, long version) {
    }

    private record QueuedWork(String evalRunId, String outboxId) {
    }
}
