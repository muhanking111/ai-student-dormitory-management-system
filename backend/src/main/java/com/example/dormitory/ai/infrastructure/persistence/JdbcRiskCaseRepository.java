package com.example.dormitory.ai.infrastructure.persistence;

import com.example.dormitory.ai.risk.RiskActorKind;
import com.example.dormitory.ai.risk.ActiveRiskCaseConflictException;
import com.example.dormitory.ai.risk.RiskCase;
import com.example.dormitory.ai.risk.RiskCaseRepository;
import com.example.dormitory.ai.risk.RiskCaseState;
import com.example.dormitory.ai.risk.RiskBusinessSnapshot;
import com.example.dormitory.ai.risk.RiskExplanationBasis;
import com.example.dormitory.ai.risk.RiskExplanationEvidence;
import com.example.dormitory.ai.risk.RiskSignalEvidence;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** MySQL/H2 兼容的风险案例事实仓储；所有写入均使用参数化 SQL 和版本 CAS。 */
@Repository
@Primary
public class JdbcRiskCaseRepository implements RiskCaseRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final JdbcAiIdempotencyRepository idempotencyRepository;

    public JdbcRiskCaseRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            JdbcAiIdempotencyRepository idempotencyRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.idempotencyRepository = idempotencyRepository;
    }

    @Override
    @Transactional
    public void create(StoredRiskCase stored) {
        RiskCase riskCase = stored.riskCase();
        Instant openedAt = riskCase.events().getFirst().occurredAt();
        try {
            verifyExplanationRun(stored.explanationEvidence());
            jdbcTemplate.update("INSERT INTO ai_risk_case "
                            + "(public_id, dedup_key, active_dedup_key, risk_type, subject_type, subject_resource_id, "
                            + "subject_token, subject_token_key_version, severity, state, signal_policy_version, "
                            + "signal_snapshot_redacted, business_snapshot_redacted, explanation_text_redacted, "
                            + "explanation_basis, explanation_policy_version, explanation_run_id, assignee_user_id, "
                            + "opened_at, due_at, version, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                            + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    riskCase.publicId(), stored.dedupKey(), activeKey(stored), riskCase.riskType(),
                    stored.subjectType(), stored.subjectResourceId(), riskCase.subjectToken(), tokenKeyVersion(riskCase),
                    stored.severity(), riskCase.state().name(), riskCase.signalPolicyVersion(), signalSnapshot(stored),
                    businessSnapshot(stored), stored.explanation(), stored.explanationEvidence().basis().name(),
                    stored.explanationEvidence().policyVersion(), stored.explanationEvidence().runDatabaseId(),
                    stored.assigneeUserId(), Timestamp.from(openedAt), timestamp(stored.dueAt()), riskCase.version());
            long caseId = requireCaseId(riskCase.publicId());
            for (RiskCase.RiskCaseEvent event : riskCase.events()) insertEvent(caseId, event, null);
        } catch (DuplicateKeyException duplicate) {
            throw new ActiveRiskCaseConflictException("同一活动风险去重键已存在", duplicate);
        }
    }

    @Override
    @Transactional
    public void save(StoredRiskCase stored, int expectedVersion, Long idempotencyRecordId) {
        RiskCase riskCase = stored.riskCase();
        if (expectedVersion < 0 || riskCase.version() < expectedVersion
                || riskCase.version() > expectedVersion + 1) {
            throw new IllegalArgumentException("风险案例 CAS 版本不合法");
        }
        int updated;
        try {
            verifyExplanationRun(stored.explanationEvidence());
            updated = jdbcTemplate.update("UPDATE ai_risk_case SET active_dedup_key = ?, severity = ?, state = ?, "
                            + "signal_policy_version = ?, signal_snapshot_redacted = ?, business_snapshot_redacted = ?, "
                            + "explanation_text_redacted = ?, explanation_basis = ?, explanation_policy_version = ?, "
                            + "explanation_run_id = ?, assignee_user_id = ?, due_at = ?, version = ?, resolved_at = ?, "
                            + "updated_at = CURRENT_TIMESTAMP WHERE public_id = ? AND version = ?",
                    activeKey(stored), stored.severity(), riskCase.state().name(), riskCase.signalPolicyVersion(),
                    signalSnapshot(stored), businessSnapshot(stored), stored.explanation(),
                    stored.explanationEvidence().basis().name(), stored.explanationEvidence().policyVersion(),
                    stored.explanationEvidence().runDatabaseId(), stored.assigneeUserId(), timestamp(stored.dueAt()),
                    riskCase.version(), terminalAt(riskCase), riskCase.publicId(), expectedVersion);
        } catch (DuplicateKeyException duplicate) {
            throw new ActiveRiskCaseConflictException("同一活动风险去重键已存在", duplicate);
        }
        if (updated != 1) throw new com.example.dormitory.ai.risk.RiskCaseConflictException(
                "AI_RISK_CASE_VERSION_CONFLICT", "风险案例版本冲突");
        if (riskCase.version() == expectedVersion + 1) {
            insertEvent(requireCaseId(riskCase.publicId()), riskCase.events().getLast(), idempotencyRecordId);
        }
        if (idempotencyRecordId != null) {
            int completed = jdbcTemplate.update("UPDATE ai_idempotency_record SET state = 'COMPLETED', "
                            + "response_status = 200, response_resource_public_id = ?, version = version + 1, "
                            + "updated_at = CURRENT_TIMESTAMP WHERE id = ? AND state = 'PENDING'",
                    riskCase.publicId(), idempotencyRecordId);
            if (completed != 1) throw new IllegalStateException("风险处置幂等记录无法完成");
        }
    }

    @Override
    public TransitionReservation reserveTransition(
            long actorUserId,
            String publicId,
            RiskCaseState target,
            String idempotencyKey,
            String requestHash) {
        String normalizedHash = requestHash != null && requestHash.matches("[0-9a-fA-F]{64}")
                ? requestHash.toLowerCase(java.util.Locale.ROOT) : sha256(requestHash == null ? "" : requestHash);
        try {
            JdbcAiIdempotencyRepository.Reservation reservation = idempotencyRepository.reserve(
                    new JdbcAiIdempotencyRepository.Scope(actorUserId, "AI_RISK_" + target.name(), publicId,
                            idempotencyKey), normalizedHash, Instant.now().plus(24, ChronoUnit.HOURS));
            if (reservation.status() == JdbcAiIdempotencyRepository.ReservationStatus.REPLAY
                    && !"COMPLETED".equals(reservation.state())) {
                throw new IllegalStateException("风险处置同键请求仍在执行");
            }
            return new TransitionReservation(reservation.recordId(),
                    reservation.status() == JdbcAiIdempotencyRepository.ReservationStatus.REPLAY);
        } catch (com.example.dormitory.ai.application.control.IdempotencyPayloadMismatchException mismatch) {
            throw new com.example.dormitory.ai.approval.IdempotencyConflictException("风险处置幂等 payload 冲突");
        }
    }

    @Override
    public void releaseTransition(long recordId) {
        if (recordId < 1) return;
        jdbcTemplate.update("DELETE FROM ai_idempotency_record WHERE id = ? AND state = 'PENDING'", recordId);
    }

    @Override
    public Optional<StoredRiskCase> findByPublicId(String publicId) {
        List<CaseRow> rows = jdbcTemplate.query(selectCases() + " WHERE c.public_id = ?",
                this::mapCaseRow, publicId);
        return rows.stream().findFirst().map(this::restore);
    }

    @Override
    public Optional<StoredRiskCase> findLatestByDedupKey(String dedupKey) {
        List<CaseRow> rows = jdbcTemplate.query(selectCases() + " WHERE c.dedup_key = ? "
                        + "ORDER BY c.opened_at DESC, c.id DESC LIMIT 1", this::mapCaseRow, dedupKey);
        return rows.stream().findFirst().map(this::restore);
    }

    @Override
    public List<StoredRiskCase> findByState(RiskCaseState state, int offset, int limit) {
        return findByCriteria(state, java.util.Set.of(), offset, limit);
    }

    @Override
    public List<StoredRiskCase> findByCriteria(
            RiskCaseState state, java.util.Set<String> riskTypes, int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("风险分页参数不合法");
        Criteria criteria = criteria(state, riskTypes);
        List<CaseRow> rows = jdbcTemplate.query(selectCases()
                        + " WHERE (? IS NULL OR c.state = ?) "
                        + "AND (? IS NULL OR c.risk_type IN (?, ?, ?, ?, ?, ?)) "
                        + "ORDER BY c.opened_at DESC, c.id DESC LIMIT ? OFFSET ?", this::mapCaseRow,
                criteria.state(), criteria.state(), criteria.typeMarker(), criteria.type1(), criteria.type2(),
                criteria.type3(), criteria.type4(), criteria.type5(), criteria.type6(), limit, offset);
        return rows.stream().map(this::restore).toList();
    }

    @Override
    public long countByState(RiskCaseState state) {
        return countByCriteria(state, java.util.Set.of());
    }

    @Override
    public long countByCriteria(RiskCaseState state, java.util.Set<String> riskTypes) {
        Criteria criteria = criteria(state, riskTypes);
        Long value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_risk_case "
                        + "WHERE (? IS NULL OR state = ?) AND (? IS NULL OR risk_type IN (?, ?, ?, ?, ?, ?))",
                Long.class, criteria.state(), criteria.state(), criteria.typeMarker(), criteria.type1(),
                criteria.type2(), criteria.type3(), criteria.type4(), criteria.type5(), criteria.type6());
        return value == null ? 0 : value;
    }

    private Criteria criteria(RiskCaseState state, java.util.Set<String> riskTypes) {
        List<String> values = riskTypes == null ? List.of() : riskTypes.stream().sorted().toList();
        if (values.size() > 6) throw new IllegalArgumentException("风险类型过滤不合法");
        String[] slots = new String[6];
        for (int index = 0; index < values.size(); index++) slots[index] = values.get(index);
        return new Criteria(state == null ? null : state.name(), values.isEmpty() ? null : "FILTER",
                slots[0], slots[1], slots[2], slots[3], slots[4], slots[5]);
    }

    private StoredRiskCase restore(CaseRow row) {
        List<RiskCase.RiskCaseEvent> events = jdbcTemplate.query(
                "SELECT sequence_no, event_type, actor_kind, actor_user_id, service_principal_code, "
                        + "case_version, detail_redacted, created_at FROM ai_risk_case_event "
                        + "WHERE case_id = ? ORDER BY sequence_no",
                (resultSet, rowNum) -> new RiskCase.RiskCaseEvent(
                        resultSet.getInt("sequence_no"), resultSet.getString("event_type"),
                        RiskActorKind.valueOf(resultSet.getString("actor_kind")), actorId(resultSet),
                        resultSet.getString("detail_redacted"), resultSet.getInt("case_version"),
                        resultSet.getTimestamp("created_at").toInstant()), row.id());
        RiskSignalEvidence signalEvidence = parseSignalSnapshot(row);
        RiskBusinessSnapshot businessSnapshot = parseBusinessSnapshot(row, signalEvidence);
        RiskExplanationEvidence explanationEvidence = parseExplanation(row);
        RiskCase riskCase = RiskCase.restore(row.publicId(), row.riskType(), row.subjectToken(),
                row.policyVersion(), RiskCaseState.valueOf(row.state()), row.version(), events);
        return StoredRiskCase.restore(riskCase, row.dedupKey(), row.subjectType(), row.subjectResourceId(),
                signalEvidence, businessSnapshot, explanationEvidence, row.assigneeUserId(), row.dueAt());
    }

    private CaseRow mapCaseRow(ResultSet resultSet, int rowNum) throws SQLException {
        return new CaseRow(resultSet.getLong("id"), resultSet.getString("public_id"),
                resultSet.getString("dedup_key"), resultSet.getString("risk_type"),
                resultSet.getString("subject_type"), resultSet.getLong("subject_resource_id"),
                resultSet.getString("subject_token"), resultSet.getString("severity"),
                resultSet.getString("state"), resultSet.getString("signal_policy_version"),
                resultSet.getString("signal_snapshot_redacted"),
                resultSet.getString("business_snapshot_redacted"),
                resultSet.getString("explanation_text_redacted"), resultSet.getString("explanation_basis"),
                resultSet.getString("explanation_policy_version"), nullableLong(resultSet, "explanation_run_id"),
                resultSet.getString("explanation_run_public_id"), nullableLong(resultSet, "assignee_user_id"),
                nullableInstant(resultSet, "due_at"), resultSet.getInt("version"));
    }

    private void insertEvent(long caseId, RiskCase.RiskCaseEvent event, Long idempotencyRecordId) {
        Long actorUserId = event.actorKind() == RiskActorKind.USER ? Long.valueOf(event.actorId()) : null;
        String serviceCode = event.actorKind() == RiskActorKind.SYSTEM ? event.actorId() : null;
        jdbcTemplate.update("INSERT INTO ai_risk_case_event "
                        + "(case_id, sequence_no, event_type, actor_kind, actor_user_id, service_principal_code, "
                        + "idempotency_record_id, case_version, detail_redacted, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                caseId, event.sequence(), event.eventType(), event.actorKind().name(), actorUserId, serviceCode,
                idempotencyRecordId, event.caseVersion(), event.detail(), Timestamp.from(event.occurredAt()));
    }

    private String actorId(ResultSet resultSet) throws SQLException {
        return RiskActorKind.USER.name().equals(resultSet.getString("actor_kind"))
                ? Long.toString(resultSet.getLong("actor_user_id"))
                : resultSet.getString("service_principal_code");
    }

    private long requireCaseId(String publicId) {
        Long value = jdbcTemplate.queryForObject("SELECT id FROM ai_risk_case WHERE public_id = ?", Long.class,
                publicId);
        if (value == null) throw new IllegalStateException("风险案例不存在");
        return value;
    }

    private String signalSnapshot(StoredRiskCase stored) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "riskType", stored.signalEvidence().riskType(),
                    "policyVersion", stored.signalEvidence().policyVersion(),
                    "severity", stored.signalEvidence().severity(),
                    "observedAt", stored.signalEvidence().observedAt().toString(),
                    "facts", stored.signalEvidence().facts()));
        } catch (Exception exception) {
            throw new IllegalStateException("风险信号快照序列化失败", exception);
        }
    }

    private String businessSnapshot(StoredRiskCase stored) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "subjectType", stored.businessSnapshot().subjectType(),
                    "subjectToken", stored.businessSnapshot().subjectToken(),
                    "capturedAt", stored.businessSnapshot().capturedAt().toString(),
                    "facts", stored.businessSnapshot().facts()));
        } catch (Exception exception) {
            throw new IllegalStateException("风险业务快照序列化失败", exception);
        }
    }

    private RiskSignalEvidence parseSignalSnapshot(CaseRow row) {
        try {
            JsonNode root = objectMapper.readTree(row.signalSnapshot());
            boolean legacy = !root.has("facts") && root.has("evidence");
            JsonNode factsNode = legacy ? root.path("evidence") : root.path("facts");
            Map<String, Object> facts = objectMapper.convertValue(factsNode, MAP_TYPE);
            String observedAt = legacy ? root.path("asOf").asText() : root.path("observedAt").asText();
            return new RiskSignalEvidence(row.riskType(), row.policyVersion(), row.severity(),
                    Instant.parse(observedAt), facts);
        } catch (Exception exception) {
            throw new IllegalStateException("风险信号快照反序列化失败", exception);
        }
    }

    private RiskBusinessSnapshot parseBusinessSnapshot(CaseRow row, RiskSignalEvidence signal) {
        if (row.businessSnapshot() == null || row.businessSnapshot().isBlank()) {
            return new RiskBusinessSnapshot(row.subjectType(), row.subjectToken(), signal.observedAt(), signal.facts());
        }
        try {
            JsonNode root = objectMapper.readTree(row.businessSnapshot());
            return new RiskBusinessSnapshot(root.path("subjectType").asText(row.subjectType()),
                    root.path("subjectToken").asText(row.subjectToken()),
                    Instant.parse(root.path("capturedAt").asText()),
                    objectMapper.convertValue(root.path("facts"), MAP_TYPE));
        } catch (Exception exception) {
            throw new IllegalStateException("风险业务快照反序列化失败", exception);
        }
    }

    private RiskExplanationEvidence parseExplanation(CaseRow row) {
        RiskExplanationBasis basis;
        try {
            basis = row.explanationBasis() == null ? RiskExplanationBasis.DETERMINISTIC_DEGRADED
                    : RiskExplanationBasis.valueOf(row.explanationBasis());
        } catch (IllegalArgumentException invalid) {
            basis = RiskExplanationBasis.DETERMINISTIC_DEGRADED;
        }
        if (basis == RiskExplanationBasis.MODEL
                && (row.explanationRunId() == null || row.explanationRunPublicId() == null)) {
            basis = RiskExplanationBasis.DETERMINISTIC_DEGRADED;
        }
        String text = row.explanationText();
        if (text == null || text.isBlank()) {
            text = "确定性规则证据已命中；模型解释当前不可用，请人工核验业务事实后再记录处置结论。";
        }
        String policy = row.explanationPolicyVersion();
        if (policy == null || policy.isBlank()) policy = "risk-explanation-deterministic.v1";
        return basis == RiskExplanationBasis.MODEL
                ? new RiskExplanationEvidence(text, basis, policy, row.explanationRunId(),
                        row.explanationRunPublicId(), null, false)
                : new RiskExplanationEvidence(text, RiskExplanationBasis.DETERMINISTIC_DEGRADED, policy,
                        null, null, null, true);
    }

    private String activeKey(StoredRiskCase stored) {
        RiskCaseState state = stored.riskCase().state();
        return state == RiskCaseState.OPEN || state == RiskCaseState.ACKNOWLEDGED ? stored.dedupKey() : null;
    }

    private Timestamp terminalAt(RiskCase riskCase) {
        return riskCase.state() == RiskCaseState.RESOLVED || riskCase.state() == RiskCaseState.DISMISSED
                ? Timestamp.from(riskCase.events().getLast().occurredAt()) : null;
    }

    private String selectCases() {
        return "SELECT c.*, r.public_id AS explanation_run_public_id FROM ai_risk_case c "
                + "LEFT JOIN ai_run r ON r.id = c.explanation_run_id";
    }

    private void verifyExplanationRun(RiskExplanationEvidence explanation) {
        if (explanation.basis() != RiskExplanationBasis.MODEL) return;
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_run WHERE id = ? AND public_id = ? "
                        + "AND capability = 'RISK' AND state = 'SUCCEEDED'",
                Integer.class, explanation.runDatabaseId(), explanation.runPublicId());
        if (count == null || count != 1) {
            throw new IllegalArgumentException("模型风险解释未绑定成功的受控 RISK run");
        }
    }

    private Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private int tokenKeyVersion(RiskCase riskCase) {
        String token = riskCase.subjectToken();
        if (token.startsWith("risk_k")) {
            int end = token.indexOf('_', 6);
            if (end > 6) {
                try { return Integer.parseInt(token.substring(6, end)); } catch (NumberFormatException ignored) { }
            }
        }
        return 1;
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("风险处置哈希不可用", exception);
        }
    }

    private record CaseRow(long id, String publicId, String dedupKey, String riskType, String subjectType,
                           Long subjectResourceId, String subjectToken, String severity, String state,
                           String policyVersion, String signalSnapshot, String businessSnapshot,
                           String explanationText, String explanationBasis, String explanationPolicyVersion,
                           Long explanationRunId, String explanationRunPublicId, Long assigneeUserId,
                           Instant dueAt, int version) { }
    private record Criteria(
            String state,
            String typeMarker,
            String type1,
            String type2,
            String type3,
            String type4,
            String type5,
            String type6) { }
}
