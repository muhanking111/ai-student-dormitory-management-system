package com.example.dormitory.ai.infrastructure.persistence;

import com.example.dormitory.ai.approval.ActionProposal;
import com.example.dormitory.ai.approval.ActionProposalRepository;
import com.example.dormitory.ai.approval.ActionType;
import com.example.dormitory.ai.approval.CanonicalJsonHasher;
import com.example.dormitory.ai.approval.ProposalConflictException;
import com.example.dormitory.ai.approval.ProposalOrigin;
import com.example.dormitory.ai.approval.ProposalPreview;
import com.example.dormitory.ai.approval.ProposalState;
import com.example.dormitory.ai.port.ApprovedBusinessActionPort;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.infrastructure.runtime.AiRuntimeAuditWriter;
import com.example.dormitory.ai.infrastructure.runtime.AiToolCallIntegrity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcActionProposalRepository implements ActionProposalRepository {
    private static final String PROPOSAL_SELECT = "SELECT p.*, r.public_id AS run_public_id "
            + "FROM ai_action_proposal p LEFT JOIN ai_run r ON r.id = p.run_id ";

    private final JdbcTemplate jdbcTemplate;
    private final JdbcAiOutboxRepository outbox;
    private final JdbcAiIdempotencyRepository idempotency;
    private final AiRuntimeAuditWriter toolAuditWriter;

    @Autowired
    public JdbcActionProposalRepository(
            JdbcTemplate jdbcTemplate,
            JdbcAiOutboxRepository outbox,
            JdbcAiIdempotencyRepository idempotency,
            AiRuntimeAuditWriter toolAuditWriter) {
        this.jdbcTemplate = jdbcTemplate;
        this.outbox = outbox;
        this.idempotency = idempotency;
        this.toolAuditWriter = toolAuditWriter;
    }

    public JdbcActionProposalRepository(
            JdbcTemplate jdbcTemplate,
            JdbcAiOutboxRepository outbox,
            JdbcAiIdempotencyRepository idempotency) {
        this(jdbcTemplate, outbox, idempotency, null);
    }

    public JdbcActionProposalRepository(JdbcTemplate jdbcTemplate, JdbcAiOutboxRepository outbox) {
        this(jdbcTemplate, outbox, new JdbcAiIdempotencyRepository(jdbcTemplate));
    }

    public JdbcActionProposalRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new JdbcAiOutboxRepository(jdbcTemplate));
    }

    @Override
    @Transactional
    public CreateResult createOrReplay(StoredProposal stored, ProposalOrigin origin, CreateRequest request) {
        ActionProposal proposal = stored.proposal();
        origin.requireMatches(proposal.actionType());
        if (stored.runId() != null && !stored.runId().equals(origin.runPublicId())) {
            throw new ProposalConflictException("AI_PROPOSAL_ORIGIN_CONFLICT",
                    "提案绑定的 origin run 与请求 origin 不一致");
        }
        JdbcAiIdempotencyRepository.Reservation reservation = idempotency.reserve(
                new JdbcAiIdempotencyRepository.Scope(request.actorUserId(), "AI_PROPOSAL_CREATE",
                        origin.runPublicId(), request.idempotencyKey()),
                request.requestHash(), request.expiresAt());
        if (reservation.status() == JdbcAiIdempotencyRepository.ReservationStatus.REPLAY) {
            JdbcAiIdempotencyRepository.CompletedResponse completed = idempotency
                    .completedResponse(reservation.recordId())
                    .orElseThrow(() -> new ProposalConflictException(
                            "AI_PROPOSAL_CREATE_IN_PROGRESS", "提案创建请求正在处理中"));
            return new CreateResult(findByPublicId(completed.resourcePublicId()).orElseThrow(), true);
        }

        RunOrigin run = lockOriginRun(origin.runPublicId());
        requireOriginRun(run, origin, request.actorUserId(), proposal.actionType());
        String toolPublicId = UUID.nameUUIDFromBytes(("proposal-origin.v1|" + origin.runPublicId()
                + "|" + origin.toolName() + "|" + origin.toolVersion()).getBytes(StandardCharsets.UTF_8)).toString();
        Long existingToolId = jdbcTemplate.query("SELECT id FROM ai_tool_call WHERE public_id = ?",
                (resultSet, rowNum) -> resultSet.getLong(1), toolPublicId).stream().findFirst().orElse(null);
        if (existingToolId != null) {
            StoredProposal existing = findByOriginToolId(existingToolId).orElseThrow(() ->
                    new ProposalConflictException("AI_PROPOSAL_ORIGIN_INCOMPLETE",
                            "origin tool 已存在但提案未完整提交"));
            if (!existing.proposal().payloadHash().equals(proposal.payloadHash())) {
                throw new ProposalConflictException("AI_PROPOSAL_ORIGIN_CONFLICT",
                        "同一 origin tool 不能创建不同提案");
            }
            idempotency.complete(reservation.recordId(), 201, existing.proposal().publicId());
            return new CreateResult(existing, true);
        }

        OriginTool originTool = insertOriginTool(run.id(), toolPublicId, origin, stored, request.actorUserId());
        long toolCallId = originTool.id();
        jdbcTemplate.update("INSERT INTO ai_action_proposal "
                        + "(public_id, run_id, origin_tool_call_id, action_type, target_type, target_resource_id, "
                        + "payload_text, preview_text, payload_hash, business_snapshot_hash, "
                        + "required_business_permission, approval_policy_version, required_approval_count, "
                        + "approved_count, risk_level, state, proposer_user_id, expires_at, version, "
                        + "created_operator_user_id, updated_operator_user_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'approval-policy-v1', ?, 0, ?, ?, ?, ?, ?, "
                        + "?, ?, ?, ?)",
                proposal.publicId(), run.id(), toolCallId, proposal.actionType().name(), stored.targetType(),
                stored.targetResourceId(), proposal.canonicalPayload(), stored.previewText(), proposal.payloadHash(),
                proposal.businessSnapshotHash(), proposal.requiredBusinessPermission(),
                proposal.requiredApprovalCount(), stored.riskLevel(), proposal.state().name(), stored.proposerUserId(),
                Timestamp.from(proposal.expiresAt()), proposal.version(), stored.proposerUserId(),
                stored.proposerUserId(), Timestamp.from(stored.createdAt()), Timestamp.from(stored.createdAt()));
        String toolResponse = "{\"proposalId\":\"" + proposal.publicId()
                + "\",\"state\":\"PENDING_APPROVAL\"}";
        Instant toolFinishedAt = AiToolCallIntegrity.normalize(Instant.now());
        jdbcTemplate.update("UPDATE ai_tool_call SET response_redacted=?, state='SUCCEEDED', version=version+1, "
                        + "finished_at=?, updated_operator_user_id=?, updated_at=CURRENT_TIMESTAMP "
                        + "WHERE id=? AND state='RUNNING'",
                toolResponse, Timestamp.from(toolFinishedAt), request.actorUserId(), toolCallId);
        if (toolAuditWriter != null) {
            toolAuditWriter.append("RUN", "RUN", origin.runPublicId(), "TOOL_CALL_RECORDED",
                    ActorDescriptor.user(request.actorUserId()), null, 1, null,
                    AiToolCallIntegrity.payloadHash(new AiToolCallIntegrity.ToolFact(
                            toolPublicId, origin.runPublicId(), originTool.sequenceNo(), origin.toolName(),
                            origin.toolVersion(), originTool.requestRedacted(), toolResponse,
                            originTool.requiredPermissionsJson(), "ALLOWED", "SUCCEEDED", null,
                            request.actorUserId(), request.actorUserId(), originTool.startedAt(), toolFinishedAt)),
                    toolPublicId);
        }
        enqueueStateEvent(stored, "ActionProposalCreated.v1", stored.proposerUserId());
        idempotency.complete(reservation.recordId(), 201, proposal.publicId());
        stored.markPersisted();
        return new CreateResult(stored, false);
    }

    @Override
    public Optional<StoredProposal> findCreateReplay(ProposalOrigin origin, CreateRequest request) {
        List<CreateReplay> rows = jdbcTemplate.query("SELECT request_hash,state,response_resource_public_id "
                        + "FROM ai_idempotency_record WHERE actor_user_id=? AND route_code='AI_PROPOSAL_CREATE' "
                        + "AND aggregate_public_id=? AND idempotency_key=?",
                (resultSet, rowNum) -> new CreateReplay(resultSet.getString("request_hash"),
                        resultSet.getString("state"), resultSet.getString("response_resource_public_id")),
                request.actorUserId(), origin.runPublicId(), request.idempotencyKey());
        if (rows.isEmpty()) return Optional.empty();
        CreateReplay replay = rows.getFirst();
        if (!request.requestHash().equals(replay.requestHash())) {
            throw new ProposalConflictException("AI_IDEMPOTENCY_PAYLOAD_MISMATCH",
                    "相同提案幂等键对应不同请求");
        }
        if (!"COMPLETED".equals(replay.state()) || replay.proposalPublicId() == null) {
            throw new ProposalConflictException("AI_PROPOSAL_CREATE_IN_PROGRESS", "提案创建请求正在处理中");
        }
        return Optional.of(findByPublicId(replay.proposalPublicId()).orElseThrow());
    }

    @Override
    @Transactional
    public void update(StoredProposal stored) {
        ActionProposal proposal = stored.proposal();
        long operatorUserId = eventActor(stored);
        int updated = jdbcTemplate.update("UPDATE ai_action_proposal SET state = ?, approved_count = ?, "
                        + "version = ?, updated_operator_user_id = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE public_id = ? AND version = ?",
                proposal.state().name(), proposal.approvals().size(), proposal.version(),
                operatorUserId, proposal.publicId(), stored.persistedVersion());
        if (updated != 1) {
            throw new ProposalConflictException("AI_PROPOSAL_VERSION_CONFLICT", "提案已被其他请求更新");
        }
        persistApprovals(stored);
        persistExecution(stored);
        enqueueStateEvent(stored, "ActionProposalStateChanged.v1", operatorUserId);
        if (proposal.state() == ProposalState.SUCCEEDED) {
            enqueueStateEvent(stored, "ActionExecutionSucceeded.v1", operatorUserId);
        }
        stored.markPersisted();
    }

    @Override
    public Optional<StoredProposal> findByPublicId(String publicId) {
        List<StoredProposal> values = jdbcTemplate.query(
                PROPOSAL_SELECT + "WHERE p.public_id = ?",
                (resultSet, rowNum) -> restore(resultSet), publicId);
        return values.stream().findFirst();
    }

    @Override
    public Optional<StoredProposal> findByExecutionPublicId(String executionPublicId) {
        List<StoredProposal> values = jdbcTemplate.query(
                PROPOSAL_SELECT + "JOIN ai_action_execution e ON e.proposal_id=p.id "
                        + "WHERE e.public_id=?",
                (resultSet, rowNum) -> restore(resultSet), executionPublicId);
        return values.stream().findFirst();
    }

    @Override
    public List<StoredProposal> findByState(ProposalState state, int offset, int limit) {
        return findByStateAndActionType(state, null, offset, limit);
    }

    @Override
    public List<StoredProposal> findByStateAndActionType(
            ProposalState state, ActionType actionType, int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("分页参数不合法");
        requireAllowlisted(actionType);
        if (state == null && actionType == null) {
            return jdbcTemplate.query(PROPOSAL_SELECT + "ORDER BY p.created_at DESC, p.id DESC LIMIT ? OFFSET ?",
                    (resultSet, rowNum) -> restore(resultSet), limit, offset);
        }
        if (state == null) {
            return jdbcTemplate.query(PROPOSAL_SELECT + "WHERE p.action_type = ? "
                            + "ORDER BY p.created_at DESC, p.id DESC LIMIT ? OFFSET ?",
                    (resultSet, rowNum) -> restore(resultSet), actionType.name(), limit, offset);
        }
        if (actionType == null) {
            return jdbcTemplate.query(PROPOSAL_SELECT + "WHERE p.state = ? "
                            + "ORDER BY p.created_at DESC, p.id DESC LIMIT ? OFFSET ?",
                    (resultSet, rowNum) -> restore(resultSet), state.name(), limit, offset);
        }
        return jdbcTemplate.query(PROPOSAL_SELECT + "WHERE p.state = ? "
                        + "AND p.action_type = ? ORDER BY p.created_at DESC, p.id DESC LIMIT ? OFFSET ?",
                (resultSet, rowNum) -> restore(resultSet), state.name(), actionType.name(), limit, offset);
    }

    @Override
    public long countByState(ProposalState state) {
        return countByStateAndActionType(state, null);
    }

    @Override
    public long countByStateAndActionType(ProposalState state, ActionType actionType) {
        requireAllowlisted(actionType);
        Long count;
        if (state == null && actionType == null) {
            count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_action_proposal", Long.class);
        } else if (state == null) {
            count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ai_action_proposal WHERE action_type = ?",
                    Long.class, actionType.name());
        } else if (actionType == null) {
            count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ai_action_proposal WHERE state = ?",
                    Long.class, state.name());
        } else {
            count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ai_action_proposal WHERE state = ? AND action_type = ?",
                    Long.class, state.name(), actionType.name());
        }
        return count == null ? 0 : count;
    }

    @Override
    @Transactional
    public List<StoredProposal> expireDue(Instant now) {
        if (now == null) throw new IllegalArgumentException("提案过期检查时间不能为空");
        List<StoredProposal> candidates = jdbcTemplate.query(
                PROPOSAL_SELECT + "WHERE p.state IN ('PENDING_APPROVAL','APPROVED') "
                        + "AND p.expires_at <= ? ORDER BY p.id FOR UPDATE",
                (resultSet, rowNum) -> restore(resultSet), Timestamp.from(now));
        List<StoredProposal> expired = new ArrayList<>();
        for (StoredProposal stored : candidates) {
            if (!stored.proposal().expireIfDue(now)) continue;
            update(stored);
            expired.add(stored);
        }
        return List.copyOf(expired);
    }

    private RunOrigin lockOriginRun(String runPublicId) {
        return jdbcTemplate.query("SELECT id,actor_user_id,capability,state FROM ai_run "
                        + "WHERE public_id=? FOR UPDATE",
                (resultSet, rowNum) -> new RunOrigin(resultSet.getLong("id"),
                        resultSet.getLong("actor_user_id"), resultSet.getString("capability"),
                        resultSet.getString("state")), runPublicId).stream().findFirst()
                .orElseThrow(() -> new ProposalConflictException(
                        "AI_PROPOSAL_ORIGIN_NOT_FOUND", "提案 origin run 不存在"));
    }

    private void requireOriginRun(
            RunOrigin run, ProposalOrigin origin, long actorUserId, ActionType actionType) {
        String expectedCapability = actionType == ActionType.REPAIR_ASSIGN ? "REPAIR" : "NOTICE";
        if (run.actorUserId() != actorUserId || !expectedCapability.equals(run.capability())
                || !List.of("PENDING", "RUNNING", "SUCCEEDED").contains(run.state())) {
            throw new SecurityException("提案 origin run 与 actor/capability/state 不匹配");
        }
        origin.requireMatches(actionType);
    }

    private OriginTool insertOriginTool(
            long runId,
            String toolPublicId,
            ProposalOrigin origin,
            StoredProposal stored,
            long actorUserId) {
        Integer sequence = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(sequence_no),0)+1 FROM ai_tool_call WHERE run_id=?", Integer.class, runId);
        if (sequence == null || sequence < 1) throw new IllegalStateException("无法分配 origin tool 序号");
        String permissions = stored.proposal().actionType() == ActionType.REPAIR_ASSIGN
                ? "[\"ai:repair:triage\",\"repair:read\"]"
                : "[\"ai:notice:draft\",\"notice:read\"]";
        Instant startedAt = AiToolCallIntegrity.normalize(Instant.now());
        String requestRedacted = "{\"payloadHash\":\"" + stored.proposal().payloadHash()
                + "\",\"targetType\":\"" + stored.targetType() + "\"}";
        jdbcTemplate.update("INSERT INTO ai_tool_call "
                        + "(public_id,run_id,sequence_no,tool_name,tool_version,request_redacted,response_redacted,"
                        + "required_permissions_text,authorization_decision,state,version,started_at,finished_at,"
                        + "created_operator_user_id,updated_operator_user_id,created_at,updated_at) "
                        + "VALUES (?,?,?,?,?,?,NULL,?,'ALLOWED','RUNNING',0,?,NULL,?,? ,"
                        + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                toolPublicId, runId, sequence, origin.toolName(), origin.toolVersion(),
                requestRedacted, permissions, Timestamp.from(startedAt), actorUserId, actorUserId);
        Long id = jdbcTemplate.queryForObject("SELECT id FROM ai_tool_call WHERE public_id=?", Long.class,
                toolPublicId);
        if (id == null) throw new IllegalStateException("无法取得 origin tool ID");
        return new OriginTool(id, origin.toolName(), origin.toolVersion(), sequence, requestRedacted,
                permissions, startedAt);
    }

    private Optional<StoredProposal> findByOriginToolId(long toolCallId) {
        List<StoredProposal> values = jdbcTemplate.query(
                PROPOSAL_SELECT + "WHERE p.origin_tool_call_id=?",
                (resultSet, rowNum) -> restore(resultSet), toolCallId);
        return values.stream().findFirst();
    }

    @Override
    @Transactional
    public List<String> markStaleExecutionsNeedsReview(Instant staleBefore) {
        if (staleBefore == null) throw new IllegalArgumentException("执行恢复截止时间不能为空");
        List<RecoveryRow> rows = jdbcTemplate.query("SELECT p.id,p.public_id,e.executed_by_user_id "
                        + "FROM ai_action_proposal p "
                        + "JOIN ai_action_execution e ON e.proposal_id=p.id "
                        + "WHERE p.state='EXECUTING' AND e.state='EXECUTING' AND e.started_at < ?",
                (resultSet, rowNum) -> new RecoveryRow(
                        resultSet.getLong("id"), resultSet.getString("public_id"),
                        resultSet.getLong("executed_by_user_id")),
                Timestamp.from(staleBefore));
        List<String> recovered = new ArrayList<>();
        for (RecoveryRow row : rows) {
            int proposal = jdbcTemplate.update("UPDATE ai_action_proposal SET state='NEEDS_REVIEW', "
                            + "version=version+1, updated_at=CURRENT_TIMESTAMP "
                            + "WHERE id=? AND state='EXECUTING'", row.id());
            int execution = jdbcTemplate.update("UPDATE ai_action_execution SET state='NEEDS_REVIEW', "
                            + "error_code='AI_EXECUTION_OUTCOME_UNKNOWN', "
                            + "error_summary='执行租约超时，禁止自动重放，等待人工对账', version=version+1, "
                            + "updated_at=CURRENT_TIMESTAMP WHERE proposal_id=? AND state='EXECUTING'",
                    row.id());
            if (execution == 1 && proposal == 1) {
                recovered.add(row.publicId());
                enqueueStateEvent(findByPublicId(row.publicId()).orElseThrow(),
                        "ActionProposalStateChanged.v1", row.executedByUserId());
            }
            else if (execution != proposal) throw new IllegalStateException("执行恢复状态更新不一致");
        }
        return List.copyOf(recovered);
    }

    private StoredProposal restore(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        long proposalId = resultSet.getLong("id");
        String publicId = resultSet.getString("public_id");
        List<ActionProposal.ApprovalEvent> approvals = new ArrayList<>();
        List<ActionProposal.RestoredIdempotency> idempotency = new ArrayList<>();
        jdbcTemplate.query("SELECT a.*, i.idempotency_key, i.request_hash FROM ai_action_approval a "
                        + "JOIN ai_idempotency_record i ON i.id = a.idempotency_record_id "
                        + "WHERE a.proposal_id = ? ORDER BY a.id",
                row -> {
                    String decision = row.getString("decision");
                    int eventVersion = row.getInt("proposal_version");
                    int approvalCount = approvals.size() + 1;
                    ActionProposal.ApprovalEvent event = new ActionProposal.ApprovalEvent(
                            row.getLong("reviewer_user_id"), decision, row.getString("payload_hash"),
                            row.getString("business_snapshot_hash"), row.getString("idempotency_key"),
                            row.getString("request_hash"), row.getString("comment_redacted"),
                            row.getTimestamp("created_at").toInstant());
                    approvals.add(event);
                    ProposalState responseState = "REJECT".equals(decision)
                            ? ProposalState.REJECTED
                            : approvalCount >= resultSet.getInt("required_approval_count")
                            ? ProposalState.APPROVED : ProposalState.PENDING_APPROVAL;
                    idempotency.add(new ActionProposal.RestoredIdempotency(
                            event.reviewerUserId(), decision, event.idempotencyKey(), event.requestHash(),
                            new ActionProposal.ApprovalDecision(responseState, eventVersion, approvalCount)));
                }, proposalId);

        ExecutionData execution = loadExecution(proposalId);
        ActionProposal proposal = ActionProposal.restore(publicId,
                ActionType.valueOf(resultSet.getString("action_type")), resultSet.getString("payload_text"),
                resultSet.getString("payload_hash"), resultSet.getString("business_snapshot_hash"),
                resultSet.getString("required_business_permission"), resultSet.getInt("required_approval_count"),
                resultSet.getTimestamp("expires_at").toInstant(), ProposalState.valueOf(resultSet.getString("state")),
                resultSet.getInt("version"), approvals, idempotency,
                execution == null ? null : execution.lease());
        StoredProposal stored = new StoredProposal(proposal, resultSet.getString("run_public_id"),
                resultSet.getString("target_type"),
                nullableLong(resultSet, "target_resource_id"),
                ProposalPreview.fromStored(resultSet.getString("preview_text")),
                resultSet.getString("risk_level"), resultSet.getLong("proposer_user_id"),
                resultSet.getTimestamp("created_at").toInstant());
        if (execution != null) {
            stored.executionPublicId(execution.publicId());
            stored.restoredResult(execution.result());
            stored.restoredExecutionFailure(execution.errorCode(), execution.errorSummary());
        }
        return stored;
    }

    private void persistApprovals(StoredProposal stored) {
        Long proposalId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_action_proposal WHERE public_id = ?", Long.class, stored.proposal().publicId());
        if (proposalId == null) throw new IllegalStateException("提案不存在");
        List<ActionProposal.ApprovalEvent> events = stored.proposal().approvals();
        for (int index = 0; index < events.size(); index++) {
            ActionProposal.ApprovalEvent event = events.get(index);
            Long existing = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ai_action_approval WHERE proposal_id = ? AND reviewer_user_id = ? "
                            + "AND decision = ? AND idempotency_record_id IN "
                            + "(SELECT id FROM ai_idempotency_record WHERE idempotency_key = ?)",
                    Long.class, proposalId, event.reviewerUserId(), event.decision(), event.idempotencyKey());
            if (existing != null && existing > 0) continue;
            long idempotencyId = ensureIdempotency(stored, event);
            jdbcTemplate.update("INSERT INTO ai_action_approval "
                            + "(proposal_id, proposal_version, decision, reviewer_user_id, idempotency_record_id, "
                            + "payload_hash, business_snapshot_hash, comment_redacted, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    proposalId, index + 1, event.decision(), event.reviewerUserId(), idempotencyId,
                    event.payloadHash(), event.snapshotHash(), event.commentRedacted(),
                    Timestamp.from(event.occurredAt()));
        }
    }

    private long ensureIdempotency(StoredProposal stored, ActionProposal.ApprovalEvent event) {
        String route = "AI_PROPOSAL_" + event.decision();
        try {
            jdbcTemplate.update("INSERT INTO ai_idempotency_record "
                            + "(actor_user_id, route_code, aggregate_public_id, idempotency_key, request_hash, "
                            + "state, response_status, response_resource_public_id, expires_at, version, "
                            + "created_operator_user_id, updated_operator_user_id, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, 'COMPLETED', 200, ?, ?, 0, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    event.reviewerUserId(), route, stored.proposal().publicId(), event.idempotencyKey(),
                    event.requestHash(), stored.proposal().publicId(), Timestamp.from(stored.proposal().expiresAt()),
                    event.reviewerUserId(), event.reviewerUserId());
        } catch (DuplicateKeyException duplicate) {
            String existingHash = jdbcTemplate.queryForObject("SELECT request_hash FROM ai_idempotency_record "
                            + "WHERE actor_user_id = ? AND route_code = ? AND aggregate_public_id = ? "
                            + "AND idempotency_key = ?", String.class,
                    event.reviewerUserId(), route, stored.proposal().publicId(), event.idempotencyKey());
            if (!event.requestHash().equals(existingHash)) {
                throw new ProposalConflictException("AI_IDEMPOTENCY_PAYLOAD_MISMATCH", "审批幂等 payload 冲突");
            }
        }
        Long id = jdbcTemplate.queryForObject("SELECT id FROM ai_idempotency_record WHERE actor_user_id = ? "
                        + "AND route_code = ? AND aggregate_public_id = ? AND idempotency_key = ?", Long.class,
                event.reviewerUserId(), route, stored.proposal().publicId(), event.idempotencyKey());
        if (id == null) throw new IllegalStateException("幂等记录不存在");
        return id;
    }

    private void persistExecution(StoredProposal stored) {
        ActionProposal.ExecutionLease lease = stored.proposal().executionLease();
        if (lease == null) return;
        Long proposalId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_action_proposal WHERE public_id = ?", Long.class, stored.proposal().publicId());
        if (proposalId == null) throw new IllegalStateException("提案不存在");
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_action_execution WHERE proposal_id = ?", Long.class, proposalId);
        ApprovedBusinessActionPort.BusinessActionResult result = stored.result();
        if (count == null || count == 0) {
            String executionPublicId = stored.executionPublicId() == null
                    ? UUID.randomUUID().toString() : stored.executionPublicId();
            jdbcTemplate.update("INSERT INTO ai_action_execution "
                            + "(public_id, proposal_id, state, version, handler_name, execution_key, lease_token_hash, "
                            + "executed_by_user_id, result_resource_type, result_resource_id, response_redacted, "
                            + "started_at, finished_at, error_code, error_summary, "
                            + "created_operator_user_id, updated_operator_user_id, "
                            + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                            + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    executionPublicId, proposalId, stored.proposal().state().name(), lease.leaseVersion(),
                    stored.proposal().actionType().name(),
                    CanonicalJsonHasher.sha256(stored.proposal().publicId() + "|" + stored.proposal().actionType()),
                    lease.leaseTokenHash(), lease.executedByUserId(),
                    result == null ? null : result.resourceType(), result == null ? null : result.resourceId(),
                    result == null ? null : result.resultHash(), Timestamp.from(lease.acquiredAt()),
                    terminal(stored.proposal().state()) ? Timestamp.from(Instant.now()) : null,
                    stored.executionErrorCode(), stored.executionErrorSummary(),
                    lease.executedByUserId(), lease.executedByUserId());
            stored.executionPublicId(executionPublicId);
        } else {
            String expectedState = jdbcTemplate.queryForObject(
                    "SELECT state FROM ai_action_execution WHERE proposal_id=? FOR UPDATE", String.class, proposalId);
            if (!validExecutionTransition(expectedState, stored.proposal().state())) {
                throw new ProposalConflictException("AI_EXECUTION_STATE_CONFLICT",
                        "execution 前态不允许当前迁移");
            }
            int updated = jdbcTemplate.update("UPDATE ai_action_execution SET state = ?, version = version + 1, "
                            + "reconfirmed_by_user_id = ?, "
                            + "lease_token_hash = ?, executed_by_user_id = COALESCE(?, executed_by_user_id), "
                            + "started_at = COALESCE(?, started_at), "
                            + "result_resource_type = ?, result_resource_id = ?, response_redacted = ?, "
                            + "finished_at = ?, error_code = ?, error_summary = ?, "
                            + "updated_operator_user_id = ?, updated_at = CURRENT_TIMESTAMP "
                            + "WHERE proposal_id = ? AND state = ?",
                    stored.proposal().state().name(),
                            lease.leaseVersion() > 1 ? lease.executedByUserId() : null,
                    lease.leaseTokenHash(),
                    stored.proposal().state() == ProposalState.EXECUTING ? lease.executedByUserId() : null,
                    stored.proposal().state() == ProposalState.EXECUTING ? Timestamp.from(lease.acquiredAt()) : null,
                    result == null ? null : result.resourceType(), result == null ? null : result.resourceId(),
                    result == null ? null : result.resultHash(),
                    terminal(stored.proposal().state()) ? Timestamp.from(Instant.now()) : null,
                    stored.executionErrorCode(), stored.executionErrorSummary(),
                    lease.executedByUserId(), proposalId, expectedState);
            if (updated != 1) {
                throw new ProposalConflictException("AI_EXECUTION_VERSION_CONFLICT",
                        "execution 状态已被其他请求更新");
            }
        }
    }

    private ExecutionData loadExecution(long proposalId) {
        List<ExecutionData> rows = jdbcTemplate.query("SELECT * FROM ai_action_execution WHERE proposal_id = ?",
                (resultSet, rowNum) -> {
                    ActionProposal.ExecutionLease lease = new ActionProposal.ExecutionLease(
                            null, resultSet.getString("lease_token_hash"), resultSet.getLong("executed_by_user_id"),
                            resultSet.getInt("version"), resultSet.getTimestamp("started_at").toInstant());
                    Long resourceId = nullableLong(resultSet, "result_resource_id");
                    ApprovedBusinessActionPort.BusinessActionResult result = resourceId == null ? null
                            : new ApprovedBusinessActionPort.BusinessActionResult(
                            resultSet.getString("result_resource_type"), resourceId,
                            resultSet.getString("response_redacted"));
                    return new ExecutionData(resultSet.getString("public_id"), lease, result,
                            resultSet.getString("error_code"),
                            resultSet.getString("error_summary"));
                }, proposalId);
        return rows.stream().findFirst().orElse(null);
    }

    private Long nullableLong(java.sql.ResultSet resultSet, String column) throws java.sql.SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private boolean terminal(ProposalState state) {
        return state == ProposalState.SUCCEEDED || state == ProposalState.FAILED
                || state == ProposalState.CANCELLED;
    }

    private boolean validExecutionTransition(String from, ProposalState to) {
        if (from == null || to == null) return false;
        return switch (to) {
            case EXECUTING -> ProposalState.NEEDS_REVIEW.name().equals(from);
            case NEEDS_REVIEW -> ProposalState.EXECUTING.name().equals(from);
            case SUCCEEDED, FAILED -> ProposalState.EXECUTING.name().equals(from)
                    || ProposalState.NEEDS_REVIEW.name().equals(from);
            default -> false;
        };
    }

    private long eventActor(StoredProposal stored) {
        ActionProposal.ExecutionLease lease = stored.proposal().executionLease();
        if (lease != null) return lease.executedByUserId();
        List<ActionProposal.ApprovalEvent> approvals = stored.proposal().approvals();
        return approvals.isEmpty() ? stored.proposerUserId() : approvals.getLast().reviewerUserId();
    }

    private void enqueueStateEvent(StoredProposal stored, String eventType, long initiatedByUserId) {
        ActionProposal proposal = stored.proposal();
        String state = proposal.state().name();
        String payload = "{\"schemaVersion\":\"action-state.v1\",\"actionType\":\""
                + proposal.actionType().name() + "\",\"state\":\"" + state
                + "\",\"version\":" + proposal.version() + "}";
        outbox.enqueueOnce("action-proposal|" + proposal.publicId() + "|" + eventType + "|"
                        + proposal.version() + "|" + state,
                new JdbcAiOutboxRepository.OutboxDraft(
                        "ACTION_PROPOSAL", proposal.publicId(), eventType, payload,
                        ActorDescriptor.service("action-proposal", initiatedByUserId, initiatedByUserId),
                        Instant.now()));
    }

    private void requireAllowlisted(ActionType actionType) {
        if (actionType != null && !ActionType.allowlisted().contains(actionType)) {
            throw new IllegalArgumentException("提案动作类型不在白名单");
        }
    }

    private record ExecutionData(
            String publicId,
            ActionProposal.ExecutionLease lease,
            ApprovedBusinessActionPort.BusinessActionResult result,
            String errorCode,
            String errorSummary) {
    }

    private record RecoveryRow(long id, String publicId, long executedByUserId) { }

    private record RunOrigin(long id, long actorUserId, String capability, String state) { }

    private record OriginTool(
            long id,
            String toolName,
            String toolVersion,
            long sequenceNo,
            String requestRedacted,
            String requiredPermissionsJson,
            Instant startedAt) { }

    private record CreateReplay(String requestHash, String state, String proposalPublicId) { }
}
