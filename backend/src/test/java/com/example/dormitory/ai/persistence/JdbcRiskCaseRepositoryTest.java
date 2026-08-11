package com.example.dormitory.ai.persistence;

import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.BusinessExecutionActor;
import com.example.dormitory.ai.infrastructure.persistence.JdbcRiskCaseRepository;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiIdempotencyRepository;
import com.example.dormitory.ai.approval.IdempotencyConflictException;
import com.example.dormitory.ai.risk.RiskCaseRepository;
import com.example.dormitory.ai.risk.RiskCaseService;
import com.example.dormitory.ai.risk.RiskCaseState;
import com.example.dormitory.ai.risk.RiskExplanationBasis;
import com.example.dormitory.ai.risk.RiskExplanationEvidence;
import com.example.dormitory.ai.risk.RiskSignal;
import com.example.dormitory.ai.security.PiiRedactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdbcRiskCaseRepositoryTest {

    private JdbcTemplate jdbcTemplate;

    private JdbcRiskCaseRepository repository;

    @BeforeEach
    void clean() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:risk-repository-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql"),
                new ClassPathResource("ai-schema.sql")).execute(dataSource);
        jdbcTemplate = new JdbcTemplate(dataSource);
        repository = new JdbcRiskCaseRepository(jdbcTemplate, new ObjectMapper(),
                new JdbcAiIdempotencyRepository(jdbcTemplate));
    }

    @Test
    void persistsEventsAndRehydratesCaseAcrossServiceInstancesIncludingVersionedReopen() {
        RiskCaseService first = service();
        RiskSignal v1 = signal("repair-backlog-v1");
        RiskCaseService.CaseView opened = first.ingest(v1);
        RiskCaseService.CaseView resolved = first.transition(opened.publicId(), RiskCaseState.RESOLVED,
                opened.version(), BusinessExecutionActor.from(ActorDescriptor.user(7)),
                Set.of("ai:risk:read", "ai:risk:manage"), "人工复核后解决", "resolve-1", hash('a'));

        RiskCaseService second = service();
        RiskCaseService.CaseView replay = second.transition(opened.publicId(), RiskCaseState.RESOLVED,
                opened.version(), BusinessExecutionActor.from(ActorDescriptor.user(7)),
                Set.of("ai:risk:read", "ai:risk:manage"), "人工复核后解决", "resolve-1", hash('a'));
        assertEquals(resolved.version(), replay.version());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_idempotency_record WHERE route_code = 'AI_RISK_RESOLVED' "
                        + "AND state = 'COMPLETED'", Integer.class));
        RiskCaseService.CaseView unchanged = second.ingest(v1);
        assertEquals(RiskCaseState.RESOLVED, unchanged.state());
        assertEquals(resolved.version(), unchanged.version());

        RiskCaseService.CaseView reopened = second.ingest(signal("repair-backlog-v2"));
        assertEquals(opened.publicId(), reopened.publicId());
        assertEquals(RiskCaseState.OPEN, reopened.state());
        assertEquals("repair-backlog-v2", reopened.signalPolicyVersion());
        assertEquals(3, reopened.events().size());
        assertEquals(3, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_risk_case_event WHERE case_id = "
                        + "(SELECT id FROM ai_risk_case WHERE public_id = ?)", Integer.class, opened.publicId()));
    }

    @Test
    void persistsSeparateEvidenceExplanationRunAssigneeAndDueAtColumns() {
        String runPublicId = "00000000-0000-0000-0000-000000000091";
        String conversationPublicId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO ai_conversation "
                        + "(public_id,owner_user_id,surface,context_type,status,created_at,updated_at) "
                        + "VALUES (?,7,'RISK','RISK','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                conversationPublicId);
        long conversationId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_conversation WHERE public_id=?", Long.class, conversationPublicId);
        String messagePublicId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO ai_message "
                        + "(public_id,conversation_id,sequence_no,role,content_redacted,classification,created_at) "
                        + "VALUES (?,?,1,'USER','{\"riskCase\":\"tokenized\"}','L1',CURRENT_TIMESTAMP)",
                messagePublicId, conversationId);
        long messageId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_message WHERE public_id=?", Long.class, messagePublicId);
        jdbcTemplate.update("INSERT INTO ai_run "
                        + "(public_id,conversation_id,request_message_id,capability,state,version,actor_user_id,"
                        + "session_fingerprint_hash,session_fingerprint_key_version,permission_digest,"
                        + "prompt_version_id,tool_catalog_version_id,retrieval_policy_version,redaction_policy_version,"
                        + "reserved_tokens,reserved_cost,correlation_id,started_at,finished_at,cost_status,created_at,updated_at) "
                        + "VALUES (?,?,?,'RISK','SUCCEEDED',0,7,?,1,?,1,1,'none.v1','pii-redaction-v3',"
                        + "0,0,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'FINAL',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                runPublicId, conversationId, messageId, hash('c'), hash('d'), UUID.randomUUID().toString());
        long runId = jdbcTemplate.queryForObject("SELECT id FROM ai_run WHERE public_id=?", Long.class, runPublicId);
        RiskCaseService service = service();
        RiskCaseService.CaseView opened = service.ingest(signal("repair-backlog-v1"));
        service.recordModelExplanation(opened.publicId(), "去标识模型解释", ActorDescriptor.model("model", 7L),
                runId, runPublicId);
        service.transition(opened.publicId(), RiskCaseState.ACKNOWLEDGED, opened.version(),
                BusinessExecutionActor.from(ActorDescriptor.user(7)), Set.of("ai:risk:read", "ai:risk:manage"),
                "已确认并接手", "ack-columns", hash('e'), Instant.parse("2026-07-14T08:00:00Z"));

        assertEquals(runId, jdbcTemplate.queryForObject(
                "SELECT explanation_run_id FROM ai_risk_case WHERE public_id=?", Long.class, opened.publicId()));
        assertEquals(7L, jdbcTemplate.queryForObject(
                "SELECT assignee_user_id FROM ai_risk_case WHERE public_id=?", Long.class, opened.publicId()));
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT business_snapshot_redacted FROM ai_risk_case WHERE public_id=?", String.class,
                opened.publicId()).contains("subject-token-42"));
        assertEquals("MODEL", jdbcTemplate.queryForObject(
                "SELECT explanation_basis FROM ai_risk_case WHERE public_id=?", String.class, opened.publicId()));
        RiskCaseService.CaseView restored = service().get(opened.publicId());
        assertEquals(runPublicId, restored.explanationEvidence().runPublicId());
        assertEquals(Instant.parse("2026-07-14T08:00:00Z"), restored.dueAt());
    }

    @Test
    void activeDedupKeyAllowsOnlyOneConcurrentCreate() throws Exception {
        RiskSignal signal = signal("repair-backlog-v1");
        RiskCaseRepository.StoredRiskCase one = stored(UUID.randomUUID().toString(), signal);
        RiskCaseRepository.StoredRiskCase two = stored(UUID.randomUUID().toString(), signal);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var futures = java.util.List.of(one, two).stream().map(candidate -> pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    repository.create(candidate);
                    return true;
                } catch (IllegalStateException expectedConflict) {
                    return false;
                }
            })).toList();
            ready.await();
            start.countDown();
            long success = 0;
            for (var future : futures) if (future.get()) success++;
            assertEquals(1, success);
            assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_risk_case", Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void compareAndSetRejectsStaleConcurrentMutationAndSameSignalIsIdempotent() {
        RiskCaseService service = service();
        RiskCaseService.CaseView opened = service.ingest(signal("repair-backlog-v1"));
        RiskCaseRepository.StoredRiskCase stale = repository.findByPublicId(opened.publicId()).orElseThrow();

        service.transition(opened.publicId(), RiskCaseState.ACKNOWLEDGED, 0,
                BusinessExecutionActor.from(ActorDescriptor.user(7)),
                Set.of("ai:risk:read", "ai:risk:manage"), "已确认", "ack-1", hash('b'));
        stale.riskCase().resolve(0, "8", "并发旧快照");

        assertThrows(IllegalStateException.class, () -> repository.save(stale, 0, null));
        assertEquals(1, service.ingest(signal("repair-backlog-v1")).version());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_risk_case", Integer.class));
    }

    @Test
    void repositoryRejectsEveryCasAndPaginationBoundaryAndNormalizesTransitionHashes() {
        RiskCaseRepository.StoredRiskCase versionZero = stored(UUID.randomUUID().toString(), signal("policy-v1"));
        assertThrows(IllegalArgumentException.class, () -> repository.save(versionZero, -1, null));
        assertThrows(IllegalArgumentException.class, () -> repository.save(versionZero, 1, null));

        RiskCaseRepository.StoredRiskCase versionTwo = stored(UUID.randomUUID().toString(), signal("policy-v2"));
        versionTwo.riskCase().acknowledge(0, "7", "已确认");
        versionTwo.riskCase().resolve(1, "7", "已解决");
        assertThrows(IllegalArgumentException.class, () -> repository.save(versionTwo, 0, null));

        assertThrows(IllegalArgumentException.class,
                () -> repository.findByCriteria(null, Set.of(), -1, 10));
        assertThrows(IllegalArgumentException.class,
                () -> repository.findByCriteria(null, Set.of(), 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> repository.findByCriteria(null, Set.of(), 0, 101));
        assertThrows(IllegalArgumentException.class,
                () -> repository.countByCriteria(null, Set.of("a", "b", "c", "d", "e", "f", "g")));
        assertEquals(0, repository.findByCriteria(null, null, 0, 10).size());
        assertEquals(0, repository.countByCriteria(null, null));

        repository.releaseTransition(0);
        repository.releaseTransition(-1);
        String caseId = UUID.randomUUID().toString();
        RiskCaseRepository.TransitionReservation normalized = repository.reserveTransition(
                7L, caseId, RiskCaseState.ACKNOWLEDGED, "normalized", "short-request");
        assertTrue(normalized.recordId() > 0);
        repository.releaseTransition(normalized.recordId());

        RiskCaseRepository.TransitionReservation upperHash = repository.reserveTransition(
                7L, caseId, RiskCaseState.RESOLVED, "upper-hash", "A".repeat(64));
        assertThrows(IllegalStateException.class, () -> repository.reserveTransition(
                7L, caseId, RiskCaseState.RESOLVED, "upper-hash", "A".repeat(64)));
        assertThrows(IdempotencyConflictException.class, () -> repository.reserveTransition(
                7L, caseId, RiskCaseState.RESOLVED, "upper-hash", "B".repeat(64)));
        repository.releaseTransition(upperHash.recordId());
    }

    @Test
    void legacyAndDamagedSnapshotsRestoreOnlyThroughSafeFallbacks() {
        RiskCaseService.CaseView opened = service().ingest(signal("snapshot-v1"));
        String originalSignal = jdbcTemplate.queryForObject(
                "SELECT signal_snapshot_redacted FROM ai_risk_case WHERE public_id=?",
                String.class, opened.publicId());
        String originalBusiness = jdbcTemplate.queryForObject(
                "SELECT business_snapshot_redacted FROM ai_risk_case WHERE public_id=?",
                String.class, opened.publicId());

        jdbcTemplate.execute("ALTER TABLE ai_risk_case ALTER COLUMN explanation_text_redacted DROP NOT NULL");
        jdbcTemplate.execute("ALTER TABLE ai_risk_case ALTER COLUMN explanation_basis DROP NOT NULL");
        jdbcTemplate.execute("ALTER TABLE ai_risk_case ALTER COLUMN explanation_policy_version DROP NOT NULL");
        String legacy = "{\"evidence\":{\"ageHours\":96,\"status\":\"OPEN\"},"
                + "\"asOf\":\"2026-07-11T08:00:00Z\"}";
        jdbcTemplate.update("UPDATE ai_risk_case SET signal_snapshot_redacted=?,business_snapshot_redacted='',"
                        + "explanation_text_redacted=NULL,explanation_basis=NULL,explanation_policy_version=NULL "
                        + "WHERE public_id=?",
                legacy, opened.publicId());
        RiskCaseRepository.StoredRiskCase legacyRestored = repository.findByPublicId(opened.publicId()).orElseThrow();
        assertEquals(96, legacyRestored.signalEvidence().facts().get("ageHours"));
        assertEquals("subject-token-42", legacyRestored.businessSnapshot().subjectToken());
        assertTrue(legacyRestored.explanationDegraded());
        assertFalse(legacyRestored.explanation().isBlank());

        jdbcTemplate.update("UPDATE ai_risk_case SET signal_snapshot_redacted=?,business_snapshot_redacted=?,"
                        + "explanation_basis='BROKEN',explanation_text_redacted='fallback',"
                        + "explanation_policy_version='fallback-v1' WHERE public_id=?",
                originalSignal, originalBusiness, opened.publicId());
        assertEquals(RiskExplanationBasis.DETERMINISTIC_DEGRADED,
                repository.findByPublicId(opened.publicId()).orElseThrow().explanationEvidence().basis());

        jdbcTemplate.update("UPDATE ai_risk_case SET explanation_basis='MODEL',explanation_run_id=NULL "
                + "WHERE public_id=?", opened.publicId());
        assertEquals(RiskExplanationBasis.DETERMINISTIC_DEGRADED,
                repository.findByPublicId(opened.publicId()).orElseThrow().explanationEvidence().basis());

        jdbcTemplate.update("UPDATE ai_risk_case SET signal_snapshot_redacted='not-json' WHERE public_id=?",
                opened.publicId());
        assertThrows(IllegalStateException.class, () -> repository.findByPublicId(opened.publicId()));
        jdbcTemplate.update("UPDATE ai_risk_case SET signal_snapshot_redacted=?,"
                + "business_snapshot_redacted='not-json' WHERE public_id=?", originalSignal, opened.publicId());
        assertThrows(IllegalStateException.class, () -> repository.findByPublicId(opened.publicId()));
    }

    @Test
    void tokenKeyVersionsAndDismissedTerminalTimestampArePersisted() {
        RiskSignal versioned = signalWithToken("token-v1", "risk_k2_subject-token");
        RiskCaseRepository.StoredRiskCase versionedStored = stored(UUID.randomUUID().toString(), versioned);
        repository.create(versionedStored);
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT subject_token_key_version FROM ai_risk_case WHERE public_id=?",
                Integer.class, versionedStored.riskCase().publicId()));

        RiskSignal malformedVersion = signalWithToken("token-v2", "risk_kx_subject-token");
        RiskCaseRepository.StoredRiskCase malformedStored = stored(UUID.randomUUID().toString(), malformedVersion);
        repository.create(malformedStored);
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT subject_token_key_version FROM ai_risk_case WHERE public_id=?",
                Integer.class, malformedStored.riskCase().publicId()));

        RiskCaseService service = service();
        RiskCaseService.CaseView opened = service.ingest(signal("dismiss-v1"));
        service.transition(opened.publicId(), RiskCaseState.DISMISSED, 0,
                BusinessExecutionActor.from(ActorDescriptor.user(7)),
                Set.of("ai:risk:read", "ai:risk:manage"), "误报，驳回", "dismiss-key", hash('f'));
        assertEquals(RiskCaseState.DISMISSED,
                repository.findByPublicId(opened.publicId()).orElseThrow().riskCase().state());
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT resolved_at IS NOT NULL FROM ai_risk_case WHERE public_id=?",
                Boolean.class, opened.publicId()));
        assertEquals(1, repository.findByCriteria(
                RiskCaseState.DISMISSED, Set.of("repair-backlog"), 0, 10).size());
        assertEquals(1, repository.countByCriteria(
                RiskCaseState.DISMISSED, Set.of("repair-backlog")));
    }

    @Test
    void snapshotSerializationAndUnboundModelExplanationFailClosed() throws Exception {
        RiskCaseRepository.StoredRiskCase stored = stored(UUID.randomUUID().toString(), signal("broken-v1"));
        ObjectMapper brokenSignal = mock(ObjectMapper.class);
        when(brokenSignal.writeValueAsString(any())).thenThrow(new com.fasterxml.jackson.core.JsonProcessingException(
                "signal broken") { });
        JdbcRiskCaseRepository signalRepository = new JdbcRiskCaseRepository(
                jdbcTemplate, brokenSignal, new JdbcAiIdempotencyRepository(jdbcTemplate));
        assertThrows(IllegalStateException.class, () -> signalRepository.create(stored));

        ObjectMapper brokenBusiness = mock(ObjectMapper.class);
        when(brokenBusiness.writeValueAsString(any())).thenReturn("{}").thenThrow(
                new com.fasterxml.jackson.core.JsonProcessingException("business broken") { });
        JdbcRiskCaseRepository businessRepository = new JdbcRiskCaseRepository(
                jdbcTemplate, brokenBusiness, new JdbcAiIdempotencyRepository(jdbcTemplate));
        assertThrows(IllegalStateException.class,
                () -> businessRepository.create(stored(UUID.randomUUID().toString(), signal("broken-v2"))));

        RiskCaseRepository.StoredRiskCase model = stored(UUID.randomUUID().toString(), signal("model-v1"));
        model.explanation(new RiskExplanationEvidence("模型解释", RiskExplanationBasis.MODEL,
                "model-v1", 999L, UUID.randomUUID().toString(), null, false));
        assertThrows(IllegalArgumentException.class, () -> repository.create(model));
    }

    private RiskCaseService service() {
        return new RiskCaseService(repository,
                new PiiRedactionService("risk-case-test-key".getBytes(StandardCharsets.UTF_8), "test-v1"));
    }

    private RiskCaseRepository.StoredRiskCase stored(String id, RiskSignal signal) {
        return new RiskCaseRepository.StoredRiskCase(
                com.example.dormitory.ai.risk.RiskCase.open(id, signal.riskType(), signal.subjectToken(),
                        signal.policyVersion()), signal);
    }

    private RiskSignal signal(String version) {
        return signalWithToken(version, "subject-token-42");
    }

    private RiskSignal signalWithToken(String version, String token) {
        return new RiskSignal("repair-backlog", "REPAIR_ORDER", 42L, token, "HIGH", version,
                Map.of("ageHours", 96, "status", "待处理", "ruleThreshold", 72),
                Instant.parse("2026-07-11T08:00:00Z"));
    }

    private String hash(char value) {
        return String.valueOf(value).repeat(64);
    }
}
