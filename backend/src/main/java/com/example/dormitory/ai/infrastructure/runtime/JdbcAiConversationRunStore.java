package com.example.dormitory.ai.infrastructure.runtime;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.application.control.BillingSubject;
import com.example.dormitory.ai.application.control.BudgetExceededException;
import com.example.dormitory.ai.application.control.IdempotencyPayloadMismatchException;
import com.example.dormitory.ai.application.run.AiActorContext;
import com.example.dormitory.ai.application.run.AiConversationRunStore;
import com.example.dormitory.ai.application.run.AiRunRecords;
import com.example.dormitory.ai.application.run.AiRunRecords.AuditApproval;
import com.example.dormitory.ai.application.run.AiRunRecords.AuditCitation;
import com.example.dormitory.ai.application.run.AiRunRecords.AuditExecution;
import com.example.dormitory.ai.application.run.AiRunRecords.AuditHashEvent;
import com.example.dormitory.ai.application.run.AiRunRecords.AuditProposal;
import com.example.dormitory.ai.application.run.AiRunRecords.AuditRun;
import com.example.dormitory.ai.application.run.AiRunRecords.AuditRetrievalTrace;
import com.example.dormitory.ai.application.run.AiRunRecords.AuditRunDetail;
import com.example.dormitory.ai.application.run.AiRunRecords.AuditRunContent;
import com.example.dormitory.ai.application.run.AiRunRecords.AuditAuthorizationScope;
import com.example.dormitory.ai.application.run.AiRunRecords.AuditContentMessage;
import com.example.dormitory.ai.application.run.AiRunRecords.AuditStep;
import com.example.dormitory.ai.application.run.AiRunRecords.AuditToolCall;
import com.example.dormitory.ai.application.run.AiRunRecords.AuditUsage;
import com.example.dormitory.ai.application.run.AiRunRecords.Conversation;
import com.example.dormitory.ai.application.run.AiRunRecords.ConversationDetail;
import com.example.dormitory.ai.application.run.AiRunRecords.CitationCandidate;
import com.example.dormitory.ai.application.run.AiRunRecords.Event;
import com.example.dormitory.ai.application.run.AiRunRecords.Message;
import com.example.dormitory.ai.application.run.AiRunRecords.Readiness;
import com.example.dormitory.ai.application.run.AiRunRecords.Run;
import com.example.dormitory.ai.application.run.AiRunRecords.RunCreation;
import com.example.dormitory.ai.application.run.AiRunRecords.RunMutation;
import com.example.dormitory.ai.config.AiProperties;
import com.example.dormitory.ai.domain.model.AiCapability;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiBudgetService;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiUsageLedgerRepository;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiOutboxRepository;
import com.example.dormitory.ai.knowledge.KnowledgeVersionRepository;
import com.example.dormitory.common.PageResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Repository
public class JdbcAiConversationRunStore implements AiConversationRunStore {

    private static final Set<String> TERMINAL_STATES = Set.of("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT");
    private static final Set<String> ACTIVE_STATES = Set.of("ACCEPTED", "QUEUED", "RUNNING", "STREAMING");
    private static final String ACTIVE_STATES_SQL = "'ACCEPTED','QUEUED','RUNNING','STREAMING'";
    private static final String ASSISTANT_CONVERSATION_VISIBILITY_SQL =
            "(NOT EXISTS (SELECT 1 FROM ai_message m WHERE m.conversation_id = c.id) "
                    + "OR EXISTS (SELECT 1 FROM ai_run r WHERE r.conversation_id = c.id "
                    + "AND r.capability = 'ASSISTANT'))";
    private static final TypeReference<Map<String, Object>> EVENT_PAYLOAD = new TypeReference<>() { };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final JdbcAiBudgetService budgetService;
    private final JdbcAiUsageLedgerRepository usageLedgerRepository;
    private final AiRuntimeAuditWriter auditWriter;
    private final AiRuntimeCrypto crypto;
    private final AiProperties properties;
    private final KnowledgeVersionRepository knowledgeVersions;
    private final JdbcAiOutboxRepository outbox;

    public JdbcAiConversationRunStore(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            JdbcAiBudgetService budgetService,
            JdbcAiUsageLedgerRepository usageLedgerRepository,
            AiRuntimeAuditWriter auditWriter,
            AiRuntimeCrypto crypto,
            AiProperties properties,
            KnowledgeVersionRepository knowledgeVersions,
            JdbcAiOutboxRepository outbox) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.budgetService = budgetService;
        this.usageLedgerRepository = usageLedgerRepository;
        this.auditWriter = auditWriter;
        this.crypto = crypto;
        this.properties = properties;
        this.knowledgeVersions = knowledgeVersions;
        this.outbox = outbox;
    }

    @Override
    @Transactional
    public Conversation createConversation(AiActorContext actor, String surface, String contextType, Long contextId) {
        auditWriter.requireWritable();
        String publicId = UUID.randomUUID().toString();
        String correlationId = UUID.randomUUID().toString();
        String payload = json(Map.of("surface", surface, "contextType", contextType));
        auditWriter.append("CONVERSATION", "CONVERSATION", publicId, "CONVERSATION_CREATED",
                actor, crypto.sha256(payload), correlationId);
        jdbcTemplate.update("INSERT INTO ai_conversation "
                        + "(public_id, owner_user_id, surface, context_type, context_resource_id, status, "
                        + "created_operator_user_id, updated_operator_user_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                publicId, actor.userId(), surface, contextType, contextId, actor.userId(), actor.userId());
        return conversationByPublicId(actor.userId(), publicId);
    }

    @Override
    public PageResponse<Conversation> listConversations(long ownerUserId, long page, long pageSize) {
        long offset = (page - 1) * pageSize;
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_conversation c WHERE c.owner_user_id = ? AND c.status = 'ACTIVE' "
                        + "AND " + ASSISTANT_CONVERSATION_VISIBILITY_SQL,
                Long.class, ownerUserId);
        List<Conversation> rows = jdbcTemplate.query(
                "SELECT public_id, surface, context_type, context_resource_id, status, title_redacted, "
                        + "last_message_at, created_at FROM ai_conversation c WHERE c.owner_user_id = ? "
                        + "AND c.status = 'ACTIVE' "
                        + "AND " + ASSISTANT_CONVERSATION_VISIBILITY_SQL + " "
                        + "ORDER BY COALESCE(last_message_at, created_at) DESC, id DESC LIMIT ? OFFSET ?",
                this::mapConversation, ownerUserId, pageSize, offset);
        return new PageResponse<>(rows, total == null ? 0 : total, page, pageSize);
    }

    @Override
    public ConversationDetail conversationDetail(long ownerUserId, String conversationId) {
        Conversation conversation = conversationByPublicId(ownerUserId, conversationId);
        Integer visible = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_conversation c WHERE c.public_id = ? AND c.owner_user_id = ? "
                        + "AND " + ASSISTANT_CONVERSATION_VISIBILITY_SQL,
                Integer.class, conversationId, ownerUserId);
        if (visible == null || visible == 0) throw AiApiException.notFound();
        Map<String, List<String>> citationIdsByMessage = new LinkedHashMap<>();
        List<MessageCitationBinding> citationBindings = jdbcTemplate.query(
                "SELECT m.public_id AS message_public_id,c.public_id AS citation_public_id "
                        + "FROM ai_citation c JOIN ai_message m ON m.id=c.message_id "
                        + "JOIN ai_conversation conversation ON conversation.id=m.conversation_id "
                        + "WHERE conversation.public_id=? AND conversation.owner_user_id=? "
                        + "ORDER BY m.sequence_no,c.rank_no,c.id",
                (resultSet, rowNum) -> new MessageCitationBinding(
                        resultSet.getString("message_public_id"), resultSet.getString("citation_public_id")),
                conversationId, ownerUserId);
        for (MessageCitationBinding binding : citationBindings) {
            citationIdsByMessage.computeIfAbsent(binding.messageId(), ignored -> new ArrayList<>())
                    .add(binding.citationId());
        }
        List<Message> messages = jdbcTemplate.query(
                "SELECT m.public_id,m.role,m.content_redacted,m.classification,m.created_at,"
                        + "r.public_id AS run_public_id,r.state AS run_state "
                        + "FROM ai_message m JOIN ai_conversation c ON c.id = m.conversation_id "
                        + "LEFT JOIN ai_run r ON r.conversation_id=m.conversation_id "
                        + "AND r.request_message_id=m.parent_message_id "
                        + "WHERE c.public_id = ? AND c.owner_user_id = ? ORDER BY m.sequence_no",
                (resultSet, rowNum) -> new Message(
                        resultSet.getString("public_id"),
                        resultSet.getString("role"),
                        resultSet.getString("content_redacted"),
                        resultSet.getString("classification"),
                        instant(resultSet, "created_at"),
                        resultSet.getString("run_public_id"),
                        resultSet.getString("run_state"),
                        null,
                        citationIdsByMessage.getOrDefault(resultSet.getString("public_id"), List.of())),
                conversationId, ownerUserId);
        return new ConversationDetail(conversation, messages);
    }

    @Override
    public Conversation ownedConversation(long ownerUserId, String conversationId) {
        return conversationByPublicId(ownerUserId, conversationId);
    }

    @Override
    @Transactional
    public void archiveConversation(AiActorContext actor, String conversationId) {
        LockedConversation conversation = lockConversation(actor.userId(), conversationId);
        if ("ARCHIVED".equals(conversation.status())) return;
        int activeRuns = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_run WHERE conversation_id = ? AND state IN (" + ACTIVE_STATES_SQL + ")",
                Integer.class, conversation.id());
        if (activeRuns > 0) {
            throw new AiApiException(HttpStatus.CONFLICT, "AI_CONVERSATION_HAS_ACTIVE_RUN",
                    "会话仍有运行中的任务", false);
        }
        auditWriter.append("CONVERSATION", "CONVERSATION", conversationId, "CONVERSATION_ARCHIVED",
                actor, crypto.sha256("ARCHIVED"), UUID.randomUUID().toString());
        jdbcTemplate.update("UPDATE ai_conversation SET status = 'ARCHIVED', updated_operator_user_id = ?, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                actor.userId(), conversation.id());
    }

    @Override
    @Transactional
    public RunCreation createRun(
            AiActorContext actor,
            String conversationId,
            String clientRequestId,
            String requestHash,
            String redactedText,
            String classification,
            String providerCode) {
        LockedConversation conversation = lockConversation(actor.userId(), conversationId);
        if (!"ACTIVE".equals(conversation.status())) {
            throw new AiApiException(HttpStatus.CONFLICT, "AI_CONVERSATION_ARCHIVED",
                    "已归档会话不能创建新运行", false);
        }
        ExistingRun existing = existingRun(conversation.id(), clientRequestId);
        if (existing != null) {
            if (!existing.requestHash().equals(requestHash)) throw new IdempotencyPayloadMismatchException();
            return new RunCreation(runByPublicId(existing.runPublicId()), true);
        }

        auditWriter.requireWritable();
        ActiveConfig config = requireActiveConfig();
        List<BudgetCandidate> candidates = applicableBudgets(actor, providerCode);
        if (candidates.isEmpty()) {
            throw AiApiException.unavailable("AI_QUOTA_CONTROL_UNAVAILABLE", "AI 配额控制面未配置");
        }
        int concurrentLimit = candidates.stream().mapToInt(BudgetCandidate::concurrentRunLimit).min().orElse(0);
        Integer concurrent = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_run WHERE actor_user_id = ? AND state IN (" + ACTIVE_STATES_SQL + ")",
                Integer.class, actor.userId());
        if (concurrentLimit < 1 || concurrent >= concurrentLimit) throw new BudgetExceededException();

        String runId = UUID.randomUUID().toString();
        budgetService.reserve(
                new BillingSubject(BillingSubject.Kind.RUN, runId),
                candidates.stream().map(BudgetCandidate::bucketId).distinct().sorted().toList(),
                properties.getBudget().getReservedTokens(),
                properties.getBudget().getReservedCost(),
                Instant.now().plus(properties.getBudget().getReservationTtl()));

        long messageSequence = nextMessageSequence(conversation.id());
        String messageId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO ai_message "
                        + "(public_id, conversation_id, sequence_no, role, client_request_id, request_hash, "
                        + "content_redacted, classification, created_at) "
                        + "VALUES (?, ?, ?, 'USER', ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                messageId, conversation.id(), messageSequence, clientRequestId, requestHash,
                redactedText, classification);
        Long requestMessageId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_message WHERE public_id = ?", Long.class, messageId);
        String correlationId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO ai_run "
                        + "(public_id, conversation_id, request_message_id, capability, state, version, actor_user_id, "
                        + "session_fingerprint_hash, session_fingerprint_key_version, permission_digest, "
                        + "model_deployment_id, prompt_version_id, tool_catalog_version_id, retrieval_policy_version, "
                        + "redaction_policy_version, reserved_tokens, reserved_cost, correlation_id, cost_status, "
                        + "created_operator_user_id, updated_operator_user_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'ASSISTANT', 'ACCEPTED', 0, ?, ?, ?, ?, NULL, ?, ?, 'none.v1', "
                        + "'pii-redaction-v4', ?, ?, ?, 'RESERVED', ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                runId, conversation.id(), requestMessageId, actor.userId(), actor.sessionFingerprintHash(),
                actor.sessionFingerprintKeyVersion(), actor.permissionDigest(), config.promptId(), config.toolCatalogId(),
                properties.getBudget().getReservedTokens(), properties.getBudget().getReservedCost(), correlationId,
                actor.userId(), actor.userId());
        LockedRun run = lockRun(runId, null);
        Event accepted = appendEvent(run, "run.accepted", Map.of("capability", "ASSISTANT"));
        auditWriter.append("RUN", "RUN", runId, "RUN_ACCEPTED", actor,
                crypto.sha256(json(accepted.payload())), correlationId);
        jdbcTemplate.update("UPDATE ai_conversation SET last_message_at = CURRENT_TIMESTAMP, "
                        + "updated_operator_user_id = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                actor.userId(), conversation.id());
        return new RunCreation(runByPublicId(runId), false);
    }

    @Override
    @Transactional
    public RunCreation createCommandRun(
            AiActorContext actor,
            AiCapability capability,
            String surface,
            String contextType,
            Long contextId,
            String clientRequestId,
            String requestHash,
            String redactedInput,
            String classification,
            String providerCode) {
        auditWriter.requireWritable();
        String normalizedSurface = surface == null ? capability.name() : surface;
        String normalizedContextType = contextType == null || contextType.isBlank()
                ? "COMMAND" : contextType.trim().toUpperCase(java.util.Locale.ROOT);
        String conversationKey = "COMMAND:" + capability.name()
                + (contextId == null ? "" : ":" + contextId);
        List<Long> conversationIds = jdbcTemplate.queryForList(
                "SELECT id FROM ai_conversation WHERE owner_user_id = ? AND surface = ? "
                        + "AND context_type = ? AND ((context_resource_id IS NULL AND ? IS NULL) "
                        + "OR context_resource_id = ?) AND status = 'ACTIVE' ORDER BY id DESC LIMIT 1",
                Long.class, actor.userId(), normalizedSurface, normalizedContextType, contextId, contextId);
        long conversationId;
        if (conversationIds.isEmpty()) {
            String conversationPublicId = UUID.randomUUID().toString();
            jdbcTemplate.update("INSERT INTO ai_conversation "
                            + "(public_id, owner_user_id, surface, context_type, context_resource_id, status, title_redacted, "
                            + "created_operator_user_id, updated_operator_user_id, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    conversationPublicId, actor.userId(), normalizedSurface, normalizedContextType, contextId, conversationKey,
                    actor.userId(), actor.userId());
            Long createdId = jdbcTemplate.queryForObject(
                    "SELECT id FROM ai_conversation WHERE public_id = ?", Long.class, conversationPublicId);
            if (createdId == null) throw new IllegalStateException("无法创建 AI command conversation");
            conversationId = createdId;
        } else {
            conversationId = conversationIds.getFirst();
        }
        ExistingRun existing = existingRun(conversationId, clientRequestId);
        if (existing != null) {
            if (!existing.requestHash().equals(requestHash)) throw new IdempotencyPayloadMismatchException();
            return new RunCreation(runByPublicId(existing.runPublicId()), true);
        }
        ActiveConfig config = requireActiveConfig(capability);
        String runId = UUID.randomUUID().toString();
        long sequence = nextMessageSequence(conversationId);
        String messageId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO ai_message "
                        + "(public_id, conversation_id, sequence_no, role, client_request_id, request_hash, "
                        + "content_redacted, classification, created_at) VALUES (?, ?, ?, 'USER', ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                messageId, conversationId, sequence, clientRequestId, requestHash, redactedInput, classification);
        Long requestMessageId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_message WHERE public_id = ?", Long.class, messageId);
        String correlationId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO ai_run "
                        + "(public_id, conversation_id, request_message_id, capability, state, version, actor_user_id, "
                        + "session_fingerprint_hash, session_fingerprint_key_version, permission_digest, "
                        + "model_deployment_id, prompt_version_id, tool_catalog_version_id, retrieval_policy_version, "
                        + "redaction_policy_version, reserved_tokens, reserved_cost, correlation_id, cost_status, "
                        + "created_operator_user_id, updated_operator_user_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'ACCEPTED', 0, ?, ?, ?, ?, NULL, ?, ?, 'none.v1', "
                        + "'pii-redaction-v4', 0, 0, ?, 'FINAL', ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                runId, conversationId, requestMessageId, capability.name(), actor.userId(),
                actor.sessionFingerprintHash(), actor.sessionFingerprintKeyVersion(), actor.permissionDigest(),
                config.promptId(), config.toolCatalogId(), correlationId, actor.userId(), actor.userId());
        LockedRun run = lockRun(runId, null);
        Event accepted = appendEvent(run, "run.accepted", Map.of("capability", capability.name()));
        auditWriter.append("RUN", "RUN", runId, "RUN_ACCEPTED", actor,
                crypto.sha256(json(accepted.payload())), correlationId);
        return new RunCreation(runByPublicId(runId), false);
    }

    @Override
    public AiRunRecords.RetrySource retrySource(long ownerUserId, String parentRunId) {
        Run parent = ownedRun(ownerUserId, parentRunId);
        if (!TERMINAL_STATES.contains(parent.state())) {
            throw new AiApiException(HttpStatus.CONFLICT, "AI_RUN_RETRY_REQUIRES_TERMINAL",
                    "只有终态运行可以显式重试", false);
        }
        Conversation conversation = conversationByPublicId(ownerUserId, parent.conversationId());
        List<RetryInput> inputs = jdbcTemplate.query(
                "SELECT m.content_redacted,m.classification,m.request_hash FROM ai_message m "
                        + "JOIN ai_run r ON r.request_message_id=m.id WHERE r.public_id=? AND m.role='USER'",
                (resultSet, rowNum) -> new RetryInput(
                        resultSet.getString("content_redacted"), resultSet.getString("classification"),
                        resultSet.getString("request_hash")), parentRunId);
        if (inputs.size() != 1) throw new IllegalStateException("AI retry 原输入事实不完整");
        RetryInput input = inputs.getFirst();
        return new AiRunRecords.RetrySource(parent, conversation, input.contentRedacted(),
                input.classification(), input.requestHash());
    }

    @Override
    @Transactional
    public RunCreation createRetryRun(
            AiActorContext actor,
            String parentRunId,
            String idempotencyKey,
            String providerCode) {
        LockedRun parent = lockRun(parentRunId, actor.userId());
        if (!TERMINAL_STATES.contains(parent.state())) {
            throw new AiApiException(HttpStatus.CONFLICT, "AI_RUN_RETRY_REQUIRES_TERMINAL",
                    "只有终态运行可以显式重试", false);
        }
        if (!AiCapability.ASSISTANT.name().equals(parent.capability())) {
            throw new AiApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AI_RUN_RETRY_UNSUPPORTED",
                    "该能力尚不支持通用运行重试", false);
        }
        LockedConversation conversation = lockConversation(actor.userId(), parent.conversationPublicId());
        if (!"ACTIVE".equals(conversation.status())) {
            throw new AiApiException(HttpStatus.CONFLICT, "AI_CONVERSATION_ARCHIVED",
                    "已归档会话不能重试运行", false);
        }
        List<RetryInput> inputs = jdbcTemplate.query(
                "SELECT content_redacted,classification,request_hash FROM ai_message "
                        + "WHERE id=? AND role='USER' FOR UPDATE",
                (resultSet, rowNum) -> new RetryInput(
                        resultSet.getString("content_redacted"), resultSet.getString("classification"),
                        resultSet.getString("request_hash")), parent.requestMessageId());
        if (inputs.size() != 1) throw new IllegalStateException("AI retry 原输入事实不完整");
        RetryInput input = inputs.getFirst();
        String retryClientRequestId = "retry:" + crypto.sha256(
                "ai-run-retry.v1|" + parentRunId + "|" + idempotencyKey);
        ExistingRun existing = existingRun(conversation.id(), retryClientRequestId);
        if (existing != null) {
            if (!existing.requestHash().equals(input.requestHash())) {
                throw new IdempotencyPayloadMismatchException();
            }
            return new RunCreation(runByPublicId(existing.runPublicId()), true);
        }

        auditWriter.requireWritable();
        ActiveConfig config = requireActiveConfig();
        List<BudgetCandidate> candidates = applicableBudgets(actor, providerCode);
        if (candidates.isEmpty()) {
            throw AiApiException.unavailable("AI_QUOTA_CONTROL_UNAVAILABLE", "AI 配额控制面未配置");
        }
        int concurrentLimit = candidates.stream().mapToInt(BudgetCandidate::concurrentRunLimit).min().orElse(0);
        Integer concurrent = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_run WHERE actor_user_id=? AND state IN (" + ACTIVE_STATES_SQL + ")",
                Integer.class, actor.userId());
        if (concurrentLimit < 1 || concurrent >= concurrentLimit) throw new BudgetExceededException();

        String runId = UUID.randomUUID().toString();
        budgetService.reserve(new BillingSubject(BillingSubject.Kind.RUN, runId),
                candidates.stream().map(BudgetCandidate::bucketId).distinct().sorted().toList(),
                properties.getBudget().getReservedTokens(), properties.getBudget().getReservedCost(),
                Instant.now().plus(properties.getBudget().getReservationTtl()));
        long messageSequence = nextMessageSequence(conversation.id());
        String messageId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO ai_message "
                        + "(public_id,conversation_id,sequence_no,role,client_request_id,request_hash,"
                        + "content_redacted,classification,created_at) "
                        + "VALUES (?,?,?,'USER',?,?,?,?,CURRENT_TIMESTAMP)",
                messageId, conversation.id(), messageSequence, retryClientRequestId, input.requestHash(),
                input.contentRedacted(), input.classification());
        Long requestMessageId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_message WHERE public_id=?", Long.class, messageId);
        String correlationId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO ai_run "
                        + "(public_id,parent_run_id,conversation_id,request_message_id,capability,state,version,actor_user_id,"
                        + "session_fingerprint_hash,session_fingerprint_key_version,permission_digest,model_deployment_id,"
                        + "prompt_version_id,tool_catalog_version_id,retrieval_policy_version,redaction_policy_version,"
                        + "reserved_tokens,reserved_cost,correlation_id,cost_status,created_operator_user_id,"
                        + "updated_operator_user_id,created_at,updated_at) "
                        + "VALUES (?,?,?,?,'ASSISTANT','ACCEPTED',0,?,?,?,?,NULL,?,?,'none.v1','pii-redaction-v4',"
                        + "?,?,?,'RESERVED',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                runId, parent.id(), conversation.id(), requestMessageId, actor.userId(),
                actor.sessionFingerprintHash(), actor.sessionFingerprintKeyVersion(), actor.permissionDigest(),
                config.promptId(), config.toolCatalogId(), properties.getBudget().getReservedTokens(),
                properties.getBudget().getReservedCost(), correlationId, actor.userId(), actor.userId());
        LockedRun run = lockRun(runId, null);
        Event accepted = appendEvent(run, "run.accepted", Map.of(
                "capability", "ASSISTANT", "parentRunId", parentRunId));
        auditWriter.append("RUN", "RUN", runId, "RUN_ACCEPTED", actor,
                crypto.sha256(json(accepted.payload())), correlationId);
        jdbcTemplate.update("UPDATE ai_conversation SET last_message_at=CURRENT_TIMESTAMP,"
                        + "updated_operator_user_id=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                actor.userId(), conversation.id());
        return new RunCreation(runByPublicId(runId), false);
    }

    @Override
    public Run ownedRun(long ownerUserId, String runId) {
        Run run = runByPublicId(runId);
        if (run.ownerUserId() != ownerUserId) throw AiApiException.notFound();
        return run;
    }

    @Override
    public List<Event> eventsAfter(long ownerUserId, String runId, long lastSequence) {
        Run run = ownedRun(ownerUserId, runId);
        return jdbcTemplate.query(
                "SELECT e.sequence_no, e.event_type, e.payload_redacted, e.created_at "
                        + "FROM ai_run_event e JOIN ai_run r ON r.id = e.run_id "
                        + "WHERE r.public_id = ? AND e.sequence_no > ? ORDER BY e.sequence_no",
                (resultSet, rowNum) -> new Event(
                        Long.toString(resultSet.getLong("sequence_no")),
                        run.id(),
                        resultSet.getLong("sequence_no"),
                        resultSet.getString("event_type"),
                        instant(resultSet, "created_at"),
                        readJson(resultSet.getString("payload_redacted"))),
                runId, lastSequence);
    }

    @Override
    @Transactional
    public RunMutation markQueued(String runId) {
        LockedRun run = lockRun(runId, null);
        if (!"ACCEPTED".equals(run.state())) return RunMutation.unchanged();
        int updated = jdbcTemplate.update("UPDATE ai_run SET state='QUEUED',version=version+1,"
                        + "updated_at=CURRENT_TIMESTAMP WHERE id=? AND state='ACCEPTED'",
                run.id());
        if (updated != 1) return RunMutation.unchanged();
        Event event = appendEvent(run, "run.queued", Map.of("capability", run.capability()));
        auditRunEvent(run, "RUN_QUEUED", event);
        return new RunMutation(true, List.of(event));
    }

    @Override
    @Transactional
    public RunMutation markStarted(String runId, String providerAlias) {
        LockedRun run = lockRun(runId, null);
        if (!"QUEUED".equals(run.state())) return RunMutation.unchanged();
        int updated = jdbcTemplate.update("UPDATE ai_run SET state = 'RUNNING', version = version + 1, started_at = CURRENT_TIMESTAMP, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE id = ? AND state = 'QUEUED'",
                run.id());
        if (updated != 1) return RunMutation.unchanged();
        Event event = appendEvent(run, "run.started", Map.of(
                "modelAlias", providerAlias,
                "promptVersion", run.promptVersion()));
        auditRunEvent(run, "RUN_STARTED", event);
        return new RunMutation(true, List.of(event));
    }

    @Override
    public String pinnedSystemPrompt(String runId) {
        List<PinnedPrompt> prompts = jdbcTemplate.query(
                "SELECT p.content,p.content_hash FROM ai_run r "
                        + "JOIN ai_prompt_version p ON p.id=r.prompt_version_id WHERE r.public_id=?",
                (resultSet, rowNum) -> new PinnedPrompt(
                        resultSet.getString("content"), resultSet.getString("content_hash")), runId);
        if (prompts.isEmpty()) throw AiApiException.notFound();
        PinnedPrompt prompt = prompts.getFirst();
        if (prompt.content() == null || prompt.content().isBlank()
                || !crypto.sha256(prompt.content()).equals(prompt.contentHash())) {
            throw new IllegalStateException("固定 System prompt 内容或 hash 已损坏");
        }
        return prompt.content();
    }

    @Override
    @Transactional
    public RunMutation appendDelta(String runId, String textDelta) {
        LockedRun run = lockRun(runId, null);
        return appendDeltaLocked(run, textDelta);
    }

    private RunMutation appendDeltaLocked(LockedRun run, String textDelta) {
        if (!Set.of("RUNNING", "STREAMING").contains(run.state())) return RunMutation.unchanged();
        if ("RUNNING".equals(run.state())) {
            int transitioned = jdbcTemplate.update("UPDATE ai_run SET state='STREAMING',"
                            + "first_token_at=COALESCE(first_token_at,CURRENT_TIMESTAMP),version=version+1,"
                            + "updated_at=CURRENT_TIMESTAMP WHERE id=? AND state='RUNNING'",
                    run.id());
            if (transitioned != 1) return RunMutation.unchanged();
        }
        Event event = appendEvent(run, "message.delta", Map.of("textDelta", textDelta));
        auditRunEvent(run, "MESSAGE_DELTA_PERSISTED", event);
        return new RunMutation(true, List.of(event));
    }

    @Override
    @Transactional
    public void recordRetrievalTrace(
            String runId,
            AiActorContext actor,
            com.example.dormitory.ai.application.run.AiRunRecords.RetrievalTrace trace) {
        if (actor == null || trace == null) throw new IllegalArgumentException("检索轨迹上下文不完整");
        LockedRun run = lockRun(runId, null);
        if (run.actorUserId() != actor.userId()
                || !run.sessionFingerprintHash().equals(actor.sessionFingerprintHash())
                || !run.permissionDigest().equals(actor.permissionDigest())) {
            throw new SecurityException("检索轨迹 actor 与 run 不一致");
        }
        if (!ACTIVE_STATES.contains(run.state())) {
            throw new IllegalStateException("终态 run 不得新增检索轨迹");
        }
        String traceId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO ai_retrieval_trace "
                        + "(public_id,run_id,query_hash,retrieval_policy_version,retrieval_mode,index_code,"
                        + "index_version,embedding_model_version,filter_redacted,top_k,acl_pre_filter_count,"
                        + "acl_post_filter_count,returned_count,latency_ms,state,created_operator_user_id,"
                        + "updated_operator_user_id,created_at,updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                traceId, run.id(), trace.queryHash(), trace.retrievalPolicyVersion(), trace.retrievalMode(),
                trace.indexCode(), trace.indexVersion(), trace.embeddingModelVersion(), trace.filterRedacted(),
                trace.topK(), trace.aclPreFilterCount(), trace.aclPostFilterCount(), trace.returnedCount(),
                trace.latencyMs(), trace.state(), actor.userId(), actor.userId());
        int updated = jdbcTemplate.update("UPDATE ai_run SET retrieval_policy_version=?,version=version+1,"
                        + "updated_operator_user_id=?,updated_at=CURRENT_TIMESTAMP "
                        + "WHERE id=? AND state IN (" + ACTIVE_STATES_SQL + ")",
                trace.retrievalPolicyVersion(), actor.userId(), run.id());
        if (updated != 1) throw new IllegalStateException("检索策略版本未绑定到 run");
        auditWriter.append("RUN", "RUN", runId, "RETRIEVAL_RECORDED", actor,
                crypto.sha256(json(Map.of(
                        "traceId", traceId,
                        "queryHash", trace.queryHash(),
                        "policyVersion", trace.retrievalPolicyVersion(),
                        "mode", trace.retrievalMode(),
                        "returnedCount", trace.returnedCount()))), run.correlationId());
    }

    @Override
    @Transactional
    public RunMutation completeRun(
            String runId,
            String assistantText,
            com.example.dormitory.ai.domain.model.ModelUsage usage,
            String providerCode,
            String modelAlias,
            boolean providerInvoked,
            boolean grounded,
            List<CitationCandidate> citations,
            Set<String> actorPermissionCodes) {
        return completeRunInternal(runId, assistantText, usage, providerCode, modelAlias,
                providerInvoked, grounded, citations, actorPermissionCodes, false);
    }

    @Override
    @Transactional
    public RunMutation completeRunWithFinalDelta(
            String runId,
            String assistantText,
            com.example.dormitory.ai.domain.model.ModelUsage usage,
            String providerCode,
            String modelAlias,
            boolean providerInvoked,
            boolean grounded,
            List<CitationCandidate> citations,
            Set<String> actorPermissionCodes) {
        return completeRunInternal(runId, assistantText, usage, providerCode, modelAlias,
                providerInvoked, grounded, citations, actorPermissionCodes, true);
    }

    private RunMutation completeRunInternal(
            String runId,
            String assistantText,
            com.example.dormitory.ai.domain.model.ModelUsage usage,
            String providerCode,
            String modelAlias,
            boolean providerInvoked,
            boolean grounded,
            List<CitationCandidate> citations,
            Set<String> actorPermissionCodes,
            boolean includeFinalDelta) {
        LockedRun run = lockRun(runId, null);
        if (!Set.of("RUNNING", "STREAMING").contains(run.state())) return RunMutation.unchanged();
        if (usage == null || providerCode == null || providerCode.isBlank()
                || modelAlias == null || modelAlias.isBlank()) {
            throw new IllegalArgumentException("AI 完成用量元数据不合法");
        }
        List<CitationCandidate> safeCitations = citations == null ? List.of() : List.copyOf(citations);
        Set<String> permissions = actorPermissionCodes == null ? Set.of() : Set.copyOf(actorPermissionCodes);
        if (grounded != !safeCitations.isEmpty()) {
            throw new IllegalArgumentException("grounded 与 citation 事实不一致");
        }
        if (!safeCitations.isEmpty()) {
            lockCitationSourcesAndAuthorize(safeCitations, permissions);
        }
        BillingSubject subject = new BillingSubject(BillingSubject.Kind.RUN, runId);
        UsageSettlement settlement = providerInvoked
                ? settleProviderUsage(subject, providerCode, modelAlias)
                : settleDeterministicUsage(subject, usage);
        long inputTokens = settlement.inputTokens();
        long outputTokens = settlement.outputTokens();
        String messageId = UUID.randomUUID().toString();
        long sequence = nextMessageSequence(run.conversationDatabaseId());
        jdbcTemplate.update("INSERT INTO ai_message "
                        + "(public_id, conversation_id, sequence_no, role, content_redacted, classification, "
                        + "parent_message_id, created_at) VALUES (?, ?, ?, 'ASSISTANT', ?, ?, ?, CURRENT_TIMESTAMP)",
                messageId, run.conversationDatabaseId(), sequence, assistantText,
                !safeCitations.isEmpty() || assistantText.contains("[") ? "L2" : "L1", run.requestMessageId());
        Long assistantMessageId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_message WHERE public_id = ?", Long.class, messageId);
        if (assistantMessageId == null) throw new IllegalStateException("AI 助手消息落盘失败");
        jdbcTemplate.update("UPDATE ai_run SET state = 'SUCCEEDED', version = version + 1, "
                        + "first_token_at = COALESCE(first_token_at, CURRENT_TIMESTAMP), finished_at = CURRENT_TIMESTAMP, "
                        + "input_tokens = ?, output_tokens = ?, estimated_cost = ?, cost_status = 'FINAL', "
                        + "updated_at = CURRENT_TIMESTAMP WHERE id = ? AND state IN ('RUNNING','STREAMING')",
                inputTokens, outputTokens, settlement.costAmount(), run.id());
        List<Event> events = new ArrayList<>();
        if (includeFinalDelta) {
            Event delta = appendEvent(run, "message.delta", Map.of("textDelta", assistantText));
            auditRunEvent(run, "MESSAGE_DELTA_PERSISTED", delta);
            events.add(delta);
        }
        for (CitationCandidate citation : safeCitations) {
            CitationTarget target = requireCitationTarget(citation, permissions);
            String citationId = UUID.randomUUID().toString();
            jdbcTemplate.update("INSERT INTO ai_citation "
                            + "(public_id,run_id,message_id,citation_type,document_version_id,chunk_id,"
                            + "rank_no,score,quote_redacted,locator_text,content_hash,created_at) "
                            + "VALUES (?,?,?,'KNOWLEDGE',?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                    citationId, run.id(), assistantMessageId, target.documentVersionId(), target.chunkId(),
                    citation.rank(), citation.score(), citation.quoteRedacted(), citation.locator(),
                    citation.contentHash().toLowerCase(java.util.Locale.ROOT));
            Event citationAdded = appendEvent(run, "citation.added", Map.of(
                    "citationId", citationId,
                    "label", citation.label(),
                    "locator", citation.locator(),
                    "rank", citation.rank()));
            auditRunEvent(run, "CITATION_RECORDED", citationAdded);
            events.add(citationAdded);
        }
        events.add(appendEvent(run, "usage.updated", Map.of(
                "inputTokens", inputTokens,
                "outputTokens", outputTokens,
                "costEstimate", settlement.costAmount(),
                "currency", settlement.currency(),
                "estimateFlag", settlement.estimated())));
        Instant completedAt = Instant.now();
        events.add(appendEvent(run, "run.completed", Map.of(
                "messageId", messageId,
                "finishReason", "STOP",
                "grounded", grounded,
                "citationCount", safeCitations.size(),
                // 这是本次授权检索/运行完成的事实时间，不冒充业务数据时间或模型置信度。
                "asOf", completedAt.toString())));
        auditRunEvent(run, "RUN_COMPLETED", events.getLast());
        outbox.enqueueOnce("ai-run-completed|" + runId,
                new JdbcAiOutboxRepository.OutboxDraft(
                        "AI_RUN", runId, "AiRunCompleted.v1",
                        "{\"schemaVersion\":\"ai-run-completed.v1\",\"capability\":\""
                                + run.capability() + "\",\"grounded\":" + grounded
                                + ",\"citationCount\":" + safeCitations.size() + "}",
                        ActorDescriptor.service("ai-runtime", run.actorUserId(), run.actorUserId()),
                        Instant.now()));
        return new RunMutation(true, events);
    }

    /**
     * 知识来源更新会先修改同一 source 行；在这里持有 FOR UPDATE，确保撤权与正文 delta/完成提交
     * 具有明确的先后关系。若撤权先提交，当前事务看到拒绝并不会写入正文；若本事务先提交，
     * 撤权只能在线性化点之后生效，已授权的事件不会被误标成越权写入。
     */
    private void lockCitationSourcesAndAuthorize(
            List<CitationCandidate> citations,
            Set<String> actorPermissionCodes) {
        if (citations.size() > 20) throw new IllegalArgumentException("知识 citation 过多");
        List<String> sourceIds = citations.stream()
                .map(CitationCandidate::sourcePublicId)
                .distinct()
                .sorted()
                .toList();
        for (String sourceId : sourceIds) {
            List<String> statuses = jdbcTemplate.query(
                    "SELECT status FROM ai_knowledge_source WHERE public_id=? FOR UPDATE",
                    (resultSet, rowNum) -> resultSet.getString(1), sourceId);
            if (statuses.size() != 1 || !"ACTIVE".equals(statuses.getFirst())) {
                throw citationAccessRevoked();
            }
        }
        for (CitationCandidate citation : citations) {
            try {
                requireCitationTarget(citation, actorPermissionCodes);
            } catch (IllegalStateException denied) {
                throw citationAccessRevoked();
            }
        }
    }

    private AiApiException citationAccessRevoked() {
        return new AiApiException(HttpStatus.NOT_FOUND, "AI_CONTEXT_ACCESS_REVOKED",
                "当前知识授权已撤销", false);
    }

    private CitationTarget requireCitationTarget(CitationCandidate citation, Set<String> actorPermissionCodes) {
        if (citation.chunkPublicId() == null || citation.chunkPublicId().isBlank()) {
            throw new IllegalArgumentException("知识 citation 缺少 chunk 绑定");
        }
        List<CitationTarget> rows = jdbcTemplate.query(
                "SELECT v.id AS version_id,c.id AS chunk_id,c.content_hash,c.content_redacted,c.locator_text "
                        + "FROM ai_document_chunk c JOIN ai_document_version v ON v.id=c.document_version_id "
                        + "JOIN ai_document d ON d.id=v.document_id JOIN ai_knowledge_source s ON s.id=d.source_id "
                        + "WHERE s.public_id=? AND v.public_id=? AND c.public_id=? "
                        + "AND s.status='ACTIVE' AND v.status='ACTIVE' AND c.status='READY' FOR UPDATE",
                (resultSet, rowNum) -> new CitationTarget(
                        resultSet.getLong("version_id"), resultSet.getLong("chunk_id"),
                        resultSet.getString("content_hash"), resultSet.getString("content_redacted"),
                        resultSet.getString("locator_text")),
                citation.sourcePublicId(), citation.documentVersionPublicId(), citation.chunkPublicId());
        if (rows.size() != 1) throw new IllegalStateException("citation 目标已失效");
        if (!knowledgeVersions.canRead(citation.documentVersionPublicId(), actorPermissionCodes)) {
            throw new IllegalStateException("citation 当前 ACL 已拒绝");
        }
        CitationTarget target = rows.getFirst();
        if (!target.contentHash().equalsIgnoreCase(citation.contentHash())
                || !target.quoteRedacted().equals(citation.quoteRedacted())
                || !target.locator().equals(citation.locator())) {
            throw new IllegalStateException("citation 内容绑定已变化");
        }
        return target;
    }

    @Override
    @Transactional
    public RunMutation completeCommandRun(String runId, Map<String, Object> result) {
        return completeCommandRun(runId, result, List.of(), Set.of());
    }

    @Override
    @Transactional
    public RunMutation completeCommandRun(
            String runId,
            Map<String, Object> result,
            List<CitationCandidate> citations,
            Set<String> actorPermissionCodes) {
        LockedRun run = lockRun(runId, null);
        if (!"RUNNING".equals(run.state())) return RunMutation.unchanged();
        List<CitationCandidate> safeCitations = citations == null ? List.of() : List.copyOf(citations);
        if (AiCapability.KNOWLEDGE.name().equals(run.capability())) {
            requireKnowledgeCommandCitationContract(result, safeCitations);
        } else if (!safeCitations.isEmpty()) {
            throw new IllegalArgumentException("只有 KNOWLEDGE command 可以绑定知识 citation");
        }
        if (safeCitations.size() > 20) {
            throw new IllegalArgumentException("command citation 过多");
        }
        Set<Integer> ranks = new java.util.HashSet<>();
        if (safeCitations.stream().anyMatch(citation -> !ranks.add(citation.rank()))) {
            throw new IllegalArgumentException("command citation rank 重复");
        }
        Set<String> permissions = actorPermissionCodes == null ? Set.of() : Set.copyOf(actorPermissionCodes);
        List<CitationBinding> citationBindings = safeCitations.stream()
                .map(citation -> new CitationBinding(citation, requireCitationTarget(citation, permissions)))
                .toList();

        String assistantMessageId = null;
        Long assistantMessageDatabaseId = null;
        if (!citationBindings.isEmpty()) {
            Object answerValue = result == null ? null : result.get("answerText");
            if (!(answerValue instanceof String answerText) || answerText.isBlank()) {
                throw new IllegalArgumentException("带 citation 的 command 缺少安全回答正文");
            }
            assistantMessageId = UUID.randomUUID().toString();
            long sequence = nextMessageSequence(run.conversationDatabaseId());
            jdbcTemplate.update("INSERT INTO ai_message "
                            + "(public_id, conversation_id, sequence_no, role, content_redacted, classification, "
                            + "parent_message_id, created_at) VALUES (?, ?, ?, 'ASSISTANT', ?, 'L2', ?, CURRENT_TIMESTAMP)",
                    assistantMessageId, run.conversationDatabaseId(), sequence, answerText, run.requestMessageId());
            assistantMessageDatabaseId = jdbcTemplate.queryForObject(
                    "SELECT id FROM ai_message WHERE public_id = ?", Long.class, assistantMessageId);
            if (assistantMessageDatabaseId == null) {
                throw new IllegalStateException("AI command 助手消息落盘失败");
            }
        }
        budgetService.releaseIfPresent(new BillingSubject(BillingSubject.Kind.RUN, runId));
        jdbcTemplate.update("UPDATE ai_run SET state = 'SUCCEEDED', version = version + 1, "
                        + "started_at = COALESCE(started_at, CURRENT_TIMESTAMP), finished_at = CURRENT_TIMESTAMP, "
                        + "input_tokens = 0, output_tokens = 0, estimated_cost = 0, cost_status = 'FINAL', "
                        + "updated_at = CURRENT_TIMESTAMP WHERE id = ? AND state = 'RUNNING'",
                run.id());
        List<Event> events = new ArrayList<>();
        if (assistantMessageDatabaseId != null) {
            for (CitationBinding binding : citationBindings) {
                CitationCandidate citation = binding.citation();
                CitationTarget target = binding.target();
                String citationId = UUID.randomUUID().toString();
                jdbcTemplate.update("INSERT INTO ai_citation "
                                + "(public_id,run_id,message_id,citation_type,document_version_id,chunk_id,"
                                + "rank_no,score,quote_redacted,locator_text,content_hash,created_at) "
                                + "VALUES (?,?,?,'KNOWLEDGE',?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                        citationId, run.id(), assistantMessageDatabaseId, target.documentVersionId(), target.chunkId(),
                        citation.rank(), citation.score(), citation.quoteRedacted(), citation.locator(),
                        citation.contentHash().toLowerCase(java.util.Locale.ROOT));
                Event citationAdded = appendEvent(run, "citation.added", Map.of(
                        "citationId", citationId,
                        "label", citation.label(),
                        "locator", citation.locator(),
                        "rank", citation.rank()));
                auditRunEvent(run, "CITATION_RECORDED", citationAdded);
                events.add(citationAdded);
            }
        }
        Map<String, Object> completedPayload = assistantMessageId == null
                ? Map.of("finishReason", "DETERMINISTIC",
                        "result", result == null ? Map.of() : result)
                : Map.of("finishReason", "DETERMINISTIC",
                        "messageId", assistantMessageId,
                        "citationCount", safeCitations.size(),
                        "result", result == null ? Map.of() : result);
        Event completed = appendEvent(run, "run.completed", completedPayload);
        auditRunEvent(run, "RUN_COMPLETED", completed);
        events.add(completed);
        return new RunMutation(true, events);
    }

    private void requireKnowledgeCommandCitationContract(
            Map<String, Object> result,
            List<CitationCandidate> citations) {
        if (result == null || !(result.get("grounded") instanceof Boolean grounded)
                || !(result.get("citations") instanceof List<?> resultCitations)
                || grounded != !citations.isEmpty()
                || resultCitations.size() != citations.size()) {
            throw new IllegalArgumentException("知识 command 结果与 citation 绑定不一致");
        }
        for (int index = 0; index < citations.size(); index++) {
            Object raw = resultCitations.get(index);
            if (!(raw instanceof Map<?, ?> resultCitation)
                    || !matchesKnowledgeCitation(resultCitation, citations.get(index))) {
                throw new IllegalArgumentException("知识 command 结果与 citation 绑定不一致");
            }
        }
    }

    private static boolean matchesKnowledgeCitation(
            Map<?, ?> resultCitation,
            CitationCandidate citation) {
        return java.util.Objects.equals(resultCitation.get("sourceId"), citation.sourcePublicId())
                && java.util.Objects.equals(resultCitation.get("documentVersionId"),
                        citation.documentVersionPublicId())
                && java.util.Objects.equals(resultCitation.get("chunkPublicId"), citation.chunkPublicId())
                && java.util.Objects.equals(resultCitation.get("label"), citation.label())
                && java.util.Objects.equals(resultCitation.get("locator"), citation.locator())
                && java.util.Objects.equals(resultCitation.get("quote"), citation.quoteRedacted())
                && resultCitation.get("contentHash") instanceof String contentHash
                && contentHash.equalsIgnoreCase(citation.contentHash());
    }

    @Override
    @Transactional
    public RunMutation failRun(String runId, String errorCode, boolean retryable) {
        LockedRun run = lockRun(runId, null);
        if (!ACTIVE_STATES.contains(run.state())) return RunMutation.unchanged();
        UsageSettlement settlement = settleTerminalUsage(
                new BillingSubject(BillingSubject.Kind.RUN, runId));
        String terminalState = "AI_PROVIDER_TIMEOUT".equals(errorCode) ? "TIMED_OUT" : "FAILED";
        int updated = jdbcTemplate.update("UPDATE ai_run SET state = ?, version = version + 1, "
                        + "failure_code = ?, finished_at = CURRENT_TIMESTAMP, cost_status = ?, "
                        + "input_tokens=?,output_tokens=?,estimated_cost=?, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE id = ? AND state IN (" + ACTIVE_STATES_SQL + ")",
                terminalState, errorCode, settlement.costStatus(), settlement.inputTokens(),
                settlement.outputTokens(), settlement.costAmount(), run.id());
        if (updated != 1) return RunMutation.unchanged();
        Event event = appendEvent(run, "run.failed", Map.of(
                "errorCode", errorCode,
                "retryable", retryable,
                "safeMessage", "AI 运行未完成"));
        auditRunEvent(run, "RUN_FAILED", event);
        return new RunMutation(true, List.of(event));
    }

    @Override
    public void forceFailWithoutEvent(String runId, String errorCode) {
        String costStatus = budgetService.needsAttemptReconciliation(
                new BillingSubject(BillingSubject.Kind.RUN, runId))
                ? "NEEDS_RECONCILIATION" : "UNKNOWN";
        jdbcTemplate.update("UPDATE ai_run SET state = 'FAILED', version = version + 1, failure_code = ?, "
                        + "finished_at = CURRENT_TIMESTAMP, cost_status = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE public_id = ? AND state IN (" + ACTIVE_STATES_SQL + ")",
                errorCode, costStatus, runId);
    }

    @Override
    @Transactional
    public RunMutation cancelRun(AiActorContext actor, String runId) {
        LockedRun run = lockRun(runId, actor.userId());
        if (!ACTIVE_STATES.contains(run.state())) return RunMutation.unchanged();
        UsageSettlement settlement = settleTerminalUsage(
                new BillingSubject(BillingSubject.Kind.RUN, runId));
        int updated = jdbcTemplate.update("UPDATE ai_run SET state = 'CANCELLED', version = version + 1, "
                        + "failure_code = 'AI_RUN_CANCELLED', finished_at = CURRENT_TIMESTAMP, "
                        + "cost_status = ?,input_tokens=?,output_tokens=?,estimated_cost=?,updated_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND state IN (" + ACTIVE_STATES_SQL + ")",
                settlement.costStatus(), settlement.inputTokens(), settlement.outputTokens(),
                settlement.costAmount(), run.id());
        if (updated != 1) return RunMutation.unchanged();
        Event event = appendEvent(run, "run.failed", Map.of(
                "errorCode", "AI_RUN_CANCELLED",
                "retryable", false,
                "safeMessage", "运行已取消"));
        auditWriter.append("RUN", "RUN", runId, "RUN_CANCELLED", actor,
                crypto.sha256(json(event.payload())), run.correlationId());
        return new RunMutation(true, List.of(event));
    }

    private UsageSettlement settleProviderUsage(
            BillingSubject subject,
            String providerCode,
            String modelAlias) {
        if (budgetService.needsAttemptReconciliation(subject)) {
            throw new JdbcAiBudgetService.AttemptReconciliationRequiredException();
        }
        JdbcAiUsageLedgerRepository.UsageTotals totals = usageLedgerRepository.totals(subject);
        if (totals.attemptCount() < 1) {
            throw new IllegalStateException("provider 已调用但没有物理 attempt ledger");
        }
        Integer matching = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_usage_ledger WHERE billing_subject_kind=? "
                        + "AND billing_subject_public_id=? AND provider_code=? AND model_name=?",
                Integer.class, subject.kind().name(), subject.publicId(), providerCode, modelAlias);
        if (matching == null || matching != totals.attemptCount()) {
            throw new IllegalStateException("provider attempt ledger 与 run 模型身份不一致");
        }
        budgetService.commit(subject, Math.addExact(totals.inputTokens(), totals.outputTokens()),
                totals.costAmount());
        return UsageSettlement.from(totals, "FINAL");
    }

    private UsageSettlement settleDeterministicUsage(
            BillingSubject subject,
            com.example.dormitory.ai.domain.model.ModelUsage usage) {
        if (budgetService.needsAttemptReconciliation(subject)) {
            throw new JdbcAiBudgetService.AttemptReconciliationRequiredException();
        }
        JdbcAiUsageLedgerRepository.UsageTotals totals = usageLedgerRepository.totals(subject);
        if (totals.attemptCount() != 0) {
            throw new IllegalStateException("确定性降级 run 不得包含 provider attempt ledger");
        }
        budgetService.releaseIfPresent(subject);
        return new UsageSettlement(0, usage.inputTokens(), usage.outputTokens(), BigDecimal.ZERO,
                "CNY", usage.source() != com.example.dormitory.ai.domain.model.ModelUsage.Source.PROVIDER,
                "FINAL");
    }

    private UsageSettlement settleTerminalUsage(BillingSubject subject) {
        JdbcAiUsageLedgerRepository.UsageTotals totals = usageLedgerRepository.totals(subject);
        if (budgetService.needsAttemptReconciliation(subject)) {
            return UsageSettlement.from(totals, "NEEDS_RECONCILIATION");
        }
        if (totals.attemptCount() > 0) {
            budgetService.commit(subject, Math.addExact(totals.inputTokens(), totals.outputTokens()),
                    totals.costAmount());
            return UsageSettlement.from(totals, "FINAL");
        }
        boolean released = budgetService.releaseIfPresent(subject);
        return new UsageSettlement(0, 0, 0, BigDecimal.ZERO, "CNY", false,
                released ? "RELEASED" : "FINAL");
    }

    @Override
    @Transactional
    public void upsertFeedback(
            AiActorContext actor,
            String messageId,
            int rating,
            List<String> tags,
            String commentRedacted) {
        MessageOwner message = requireOwnedAssistantMessage(actor.userId(), messageId);
        List<Long> feedbackIds = jdbcTemplate.queryForList(
                "SELECT id FROM ai_feedback WHERE message_id = ? AND user_id = ? FOR UPDATE",
                Long.class, message.messageDatabaseId(), actor.userId());
        String tagsText = json(tags);
        if (feedbackIds.isEmpty()) {
            jdbcTemplate.update("INSERT INTO ai_feedback "
                            + "(run_id, message_id, user_id, rating, tags_text, comment_redacted, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                    message.runDatabaseId(), message.messageDatabaseId(), actor.userId(), rating, tagsText, commentRedacted);
        } else {
            jdbcTemplate.update("UPDATE ai_feedback SET rating = ?, tags_text = ?, comment_redacted = ? WHERE id = ?",
                    rating, tagsText, commentRedacted, feedbackIds.getFirst());
        }
        auditWriter.append("MESSAGE", "MESSAGE", messageId, "FEEDBACK_RECORDED", actor,
                crypto.sha256(json(Map.of("rating", rating, "tags", tags))), UUID.randomUUID().toString());
        Integer feedbackRevision = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_audit_event WHERE chain_scope='MESSAGE' "
                        + "AND aggregate_type='MESSAGE' AND aggregate_public_id=? "
                        + "AND event_type='FEEDBACK_RECORDED' AND actor_user_id=?",
                Integer.class, messageId, actor.userId());
        if (feedbackRevision == null || feedbackRevision < 1) {
            throw new IllegalStateException("反馈审计版本未生成");
        }
        outbox.enqueueOnce("feedback-recorded|" + messageId + "|" + actor.userId() + "|" + feedbackRevision,
                new JdbcAiOutboxRepository.OutboxDraft(
                        "AI_MESSAGE", messageId, "FeedbackRecorded.v1",
                        "{\"schemaVersion\":\"feedback-recorded.v1\",\"revision\":"
                                + feedbackRevision + ",\"rating\":" + rating + "}",
                        ActorDescriptor.service("ai-feedback", actor.userId(), actor.userId()), Instant.now()));
    }

    @Override
    public PageResponse<AuditRun> auditRuns(AiRunRecords.AuditRunFilter filter, long page, long pageSize) {
        java.util.Objects.requireNonNull(filter);
        long offset = (page - 1) * pageSize;
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> parameters = new ArrayList<>();
        addAuditFilter(where, parameters, "r.created_at >= ?", filter.from());
        addAuditFilter(where, parameters, "r.created_at <= ?", filter.to());
        addAuditFilter(where, parameters, "r.capability = ?", filter.capability());
        addAuditFilter(where, parameters, "r.state = ?", filter.state());
        if (filter.providerCode() != null) {
            where.append(" AND COALESCE(md.provider_code, 'fake') = ?");
            parameters.add(filter.providerCode());
        }
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_run r LEFT JOIN ai_model_deployment md ON md.id=r.model_deployment_id"
                        + where, Long.class, parameters.toArray());
        List<Object> pageParameters = new ArrayList<>(parameters);
        pageParameters.add(pageSize);
        pageParameters.add(offset);
        List<AuditRun> records = jdbcTemplate.query(auditRunSelect() + where
                        + " ORDER BY r.created_at DESC, r.id DESC LIMIT ? OFFSET ?",
                this::mapAuditRun, pageParameters.toArray());
        return new PageResponse<>(records, total == null ? 0 : total, page, pageSize);
    }

    private void addAuditFilter(
            StringBuilder where, List<Object> parameters, String predicate, Object value) {
        if (value == null) return;
        where.append(" AND ").append(predicate);
        parameters.add(value instanceof Instant instant ? Timestamp.from(instant) : value);
    }

    @Override
    public AuditRunDetail auditRun(String runId) {
        List<AuditRun> runs = jdbcTemplate.query(auditRunSelect() + " WHERE r.public_id = ?",
                this::mapAuditRun, runId);
        if (runs.isEmpty()) throw AiApiException.notFound();
        List<AuditStep> steps = jdbcTemplate.query(
                "SELECT e.sequence_no, e.event_type, e.created_at FROM ai_run_event e "
                        + "JOIN ai_run r ON r.id = e.run_id WHERE r.public_id = ? ORDER BY e.sequence_no",
                (resultSet, rowNum) -> new AuditStep(
                        resultSet.getLong("sequence_no"), resultSet.getString("event_type"),
                        instant(resultSet, "created_at")), runId);
        List<AuditRetrievalTrace> retrievals = jdbcTemplate.query(
                "SELECT t.public_id,t.query_hash,t.retrieval_policy_version,t.retrieval_mode,t.index_code,"
                        + "t.index_version,t.embedding_model_version,t.top_k,t.acl_pre_filter_count,"
                        + "t.acl_post_filter_count,t.returned_count,t.latency_ms,t.state,t.created_at "
                        + "FROM ai_retrieval_trace t JOIN ai_run r ON r.id=t.run_id "
                        + "WHERE r.public_id=? ORDER BY t.created_at,t.id",
                (resultSet, rowNum) -> new AuditRetrievalTrace(
                        resultSet.getString("public_id"), resultSet.getString("query_hash"),
                        resultSet.getString("retrieval_policy_version"), resultSet.getString("retrieval_mode"),
                        resultSet.getString("index_code"), resultSet.getString("index_version"),
                        resultSet.getString("embedding_model_version"), resultSet.getInt("top_k"),
                        resultSet.getInt("acl_pre_filter_count"), resultSet.getInt("acl_post_filter_count"),
                        resultSet.getInt("returned_count"), resultSet.getLong("latency_ms"),
                        resultSet.getString("state"), instant(resultSet, "created_at")), runId);
        return new AuditRunDetail(runs.getFirst(), steps, retrievals,
                auditTools(runId), auditCitations(runId), auditProposals(runId), auditApprovals(runId),
                auditExecutions(runId), auditUsage(runId), auditHashChain(runId));
    }

    private List<AuditToolCall> auditTools(String runId) {
        return jdbcTemplate.query(
                "SELECT t.public_id,t.sequence_no,t.tool_name,t.tool_version,t.authorization_decision,t.state,"
                        + "t.error_code,t.started_at,t.finished_at FROM ai_tool_call t "
                        + "JOIN ai_run r ON r.id=t.run_id WHERE r.public_id=? ORDER BY t.sequence_no",
                (rs, rowNum) -> new AuditToolCall(
                        rs.getString("public_id"), rs.getLong("sequence_no"), rs.getString("tool_name"),
                        rs.getString("tool_version"), rs.getString("authorization_decision"), rs.getString("state"),
                        rs.getString("error_code"), nullableInstant(rs, "started_at"),
                        nullableInstant(rs, "finished_at")), runId);
    }

    private List<AuditCitation> auditCitations(String runId) {
        return jdbcTemplate.query(
                "SELECT c.public_id,c.citation_type,v.public_id document_version_public_id,"
                        + "ch.public_id chunk_public_id,c.metric_id,c.rank_no,c.score,c.content_hash,c.created_at "
                        + "FROM ai_citation c JOIN ai_run r ON r.id=c.run_id "
                        + "LEFT JOIN ai_document_version v ON v.id=c.document_version_id "
                        + "LEFT JOIN ai_document_chunk ch ON ch.id=c.chunk_id "
                        + "WHERE r.public_id=? ORDER BY c.rank_no,c.id",
                (rs, rowNum) -> new AuditCitation(
                        rs.getString("public_id"), rs.getString("citation_type"),
                        rs.getString("document_version_public_id"), rs.getString("chunk_public_id"),
                        rs.getString("metric_id"), rs.getInt("rank_no"), rs.getBigDecimal("score"),
                        rs.getString("content_hash"), instant(rs, "created_at")), runId);
    }

    private List<AuditProposal> auditProposals(String runId) {
        return jdbcTemplate.query(
                "SELECT p.public_id,p.action_type,p.target_type,p.payload_hash,p.business_snapshot_hash,"
                        + "p.approval_policy_version,p.required_approval_count,p.approved_count,p.risk_level,p.state,"
                        + "p.proposer_user_id,p.expires_at,p.created_at FROM ai_action_proposal p "
                        + "JOIN ai_run r ON r.id=p.run_id WHERE r.public_id=? ORDER BY p.created_at,p.id",
                (rs, rowNum) -> new AuditProposal(
                        rs.getString("public_id"), rs.getString("action_type"), rs.getString("target_type"),
                        rs.getString("payload_hash"), rs.getString("business_snapshot_hash"),
                        rs.getString("approval_policy_version"), rs.getInt("required_approval_count"),
                        rs.getInt("approved_count"), rs.getString("risk_level"), rs.getString("state"),
                        rs.getLong("proposer_user_id"), instant(rs, "expires_at"), instant(rs, "created_at")), runId);
    }

    private List<AuditApproval> auditApprovals(String runId) {
        return jdbcTemplate.query(
                "SELECT p.public_id proposal_public_id,a.proposal_version,a.decision,a.reviewer_user_id,"
                        + "a.payload_hash,a.business_snapshot_hash,a.created_at FROM ai_action_approval a "
                        + "JOIN ai_action_proposal p ON p.id=a.proposal_id JOIN ai_run r ON r.id=p.run_id "
                        + "WHERE r.public_id=? ORDER BY a.created_at,a.id",
                (rs, rowNum) -> new AuditApproval(
                        rs.getString("proposal_public_id"), rs.getLong("proposal_version"),
                        rs.getString("decision"), rs.getLong("reviewer_user_id"), rs.getString("payload_hash"),
                        rs.getString("business_snapshot_hash"), instant(rs, "created_at")), runId);
    }

    private List<AuditExecution> auditExecutions(String runId) {
        return jdbcTemplate.query(
                "SELECT e.public_id,p.public_id proposal_public_id,e.state,e.version,e.handler_name,"
                        + "e.executed_by_user_id,e.reconfirmed_by_user_id,e.result_resource_type,e.error_code,"
                        + "e.started_at,e.finished_at FROM ai_action_execution e "
                        + "JOIN ai_action_proposal p ON p.id=e.proposal_id JOIN ai_run r ON r.id=p.run_id "
                        + "WHERE r.public_id=? ORDER BY e.created_at,e.id",
                (rs, rowNum) -> new AuditExecution(
                        rs.getString("public_id"), rs.getString("proposal_public_id"), rs.getString("state"),
                        rs.getLong("version"), rs.getString("handler_name"), rs.getLong("executed_by_user_id"),
                        nullableLong(rs, "reconfirmed_by_user_id"), rs.getString("result_resource_type"),
                        rs.getString("error_code"), nullableInstant(rs, "started_at"),
                        nullableInstant(rs, "finished_at")), runId);
    }

    private List<AuditUsage> auditUsage(String runId) {
        return jdbcTemplate.query(
                "SELECT request_sequence_no,attempt_no,request_kind,actor_kind,actor_user_id,"
                        + "service_principal_code,initiated_by_user_id,capability,provider_code,model_name,"
                        + "input_tokens,output_tokens,cost_amount,currency,usage_source,occurred_at "
                        + "FROM ai_usage_ledger WHERE billing_subject_kind='RUN' AND billing_subject_public_id=? "
                        + "ORDER BY request_sequence_no,attempt_no,id",
                (rs, rowNum) -> new AuditUsage(
                        rs.getInt("request_sequence_no"), rs.getInt("attempt_no"), rs.getString("request_kind"),
                        rs.getString("actor_kind"), nullableLong(rs, "actor_user_id"),
                        rs.getString("service_principal_code"), nullableLong(rs, "initiated_by_user_id"),
                        rs.getString("capability"), rs.getString("provider_code"), rs.getString("model_name"),
                        rs.getLong("input_tokens"), rs.getLong("output_tokens"), rs.getBigDecimal("cost_amount"),
                        rs.getString("currency"), rs.getString("usage_source"), instant(rs, "occurred_at")), runId);
    }

    private List<AuditHashEvent> auditHashChain(String runId) {
        return jdbcTemplate.query(
                "SELECT a.public_id,a.chain_scope,a.aggregate_type,a.aggregate_public_id,a.sequence_no,a.event_type,"
                        + "a.actor_kind,a.actor_user_id,a.service_principal_code,a.initiated_by_user_id,"
                        + "a.effective_subject_user_id,a.payload_redacted_hash,a.previous_event_hash,a.event_hash,"
                        + "a.integrity_alg,a.integrity_key_version,a.canonicalization_version,a.correlation_id,a.occurred_at "
                        + "FROM ai_audit_event a WHERE "
                        + "(a.aggregate_type='RUN' AND a.aggregate_public_id=?) OR "
                        + "(a.aggregate_type='PROPOSAL' AND EXISTS (SELECT 1 FROM ai_action_proposal p "
                        + "JOIN ai_run r ON r.id=p.run_id WHERE r.public_id=? AND p.public_id=a.aggregate_public_id)) OR "
                        + "(a.aggregate_type='EXECUTION' AND EXISTS (SELECT 1 FROM ai_action_execution e "
                        + "JOIN ai_action_proposal p ON p.id=e.proposal_id JOIN ai_run r ON r.id=p.run_id "
                        + "WHERE r.public_id=? AND e.public_id=a.aggregate_public_id)) "
                        + "ORDER BY a.occurred_at,a.id",
                (rs, rowNum) -> new AuditHashEvent(
                        rs.getString("public_id"), rs.getString("chain_scope"), rs.getString("aggregate_type"),
                        rs.getString("aggregate_public_id"), rs.getLong("sequence_no"), rs.getString("event_type"),
                        rs.getString("actor_kind"), nullableLong(rs, "actor_user_id"),
                        rs.getString("service_principal_code"), nullableLong(rs, "initiated_by_user_id"),
                        nullableLong(rs, "effective_subject_user_id"), rs.getString("payload_redacted_hash"),
                        rs.getString("previous_event_hash"), rs.getString("event_hash"), rs.getString("integrity_alg"),
                        rs.getInt("integrity_key_version"), rs.getString("canonicalization_version"),
                        rs.getString("correlation_id"), instant(rs, "occurred_at")), runId, runId, runId);
    }

    @Override
    public AuditRunContent auditRunContent(String runId) {
        List<AuditContentMessage> messages = jdbcTemplate.query(
                "SELECT m.public_id, m.role, m.content_redacted, m.classification, m.created_at "
                        + "FROM ai_message m JOIN ai_run r ON r.conversation_id = m.conversation_id "
                        + "WHERE r.public_id = ? AND (m.id = r.request_message_id "
                        + "OR m.parent_message_id = r.request_message_id) ORDER BY m.sequence_no",
                (resultSet, rowNum) -> new AuditContentMessage(
                        resultSet.getString("public_id"), resultSet.getString("role"),
                        resultSet.getString("content_redacted"), resultSet.getString("classification"),
                        instant(resultSet, "created_at")), runId);
        if (messages.isEmpty()) throw AiApiException.notFound();
        return new AuditRunContent(runId, messages);
    }

    @Override
    public AuditAuthorizationScope auditAuthorizationScope(String runId) {
        List<AuditAuthorizationScope> scopes = jdbcTemplate.query(
                "SELECT c.owner_user_id,c.surface,c.context_type,c.context_resource_id FROM ai_run r "
                        + "JOIN ai_conversation c ON c.id=r.conversation_id WHERE r.public_id=?",
                (resultSet, rowNum) -> new AuditAuthorizationScope(
                        resultSet.getLong("owner_user_id"),
                        resultSet.getString("surface"), resultSet.getString("context_type"),
                        nullableLong(resultSet, "context_resource_id"), List.of()), runId);
        if (scopes.isEmpty()) throw AiApiException.notFound();
        List<String> citationVersions = jdbcTemplate.queryForList(
                "SELECT DISTINCT v.public_id FROM ai_citation c JOIN ai_run r ON r.id=c.run_id "
                        + "JOIN ai_document_version v ON v.id=c.document_version_id "
                        + "WHERE r.public_id=? AND c.citation_type='KNOWLEDGE' ORDER BY v.public_id",
                String.class, runId);
        AuditAuthorizationScope scope = scopes.getFirst();
        return new AuditAuthorizationScope(scope.ownerUserId(), scope.surface(), scope.contextType(),
                scope.contextId(), citationVersions);
    }

    @Override
    public Readiness readiness(long actorUserId, String providerCode) {
        boolean prompt = count("SELECT COUNT(*) FROM ai_prompt_version WHERE prompt_key = 'assistant.system' "
                + "AND status = 'ACTIVE' AND active_slot_key = 'assistant.system'") > 0;
        boolean tool = count("SELECT COUNT(*) FROM ai_tool_catalog_version WHERE status = 'ACTIVE' "
                + "AND active_slot_key = 'runtime'") > 0;
        boolean budget = !applicableBudgetsFor(actorUserId, List.of(), providerCode).isEmpty()
                || count("SELECT COUNT(*) FROM ai_budget_bucket WHERE scope_type = 'GLOBAL' AND scope_key = '*' "
                + "AND capability = 'ASSISTANT' AND provider_code = ? AND period_start <= CURRENT_TIMESTAMP "
                + "AND period_end > CURRENT_TIMESTAMP", providerCode) > 0;
        return new Readiness(auditWriter.writable(), prompt, tool, budget);
    }

    @Override
    @Transactional
    public int failRunsWithReconciliationAttempts() {
        List<String> runIds = jdbcTemplate.queryForList(
                "SELECT r.public_id FROM ai_run r WHERE r.state IN (" + ACTIVE_STATES_SQL + ") "
                        + "AND EXISTS (SELECT 1 FROM ai_provider_attempt a "
                        + "WHERE a.billing_subject_kind='RUN' AND a.billing_subject_public_id=r.public_id "
                        + "AND a.state='NEEDS_RECONCILIATION') "
                        + "AND NOT EXISTS (SELECT 1 FROM ai_provider_attempt a "
                        + "WHERE a.billing_subject_kind='RUN' AND a.billing_subject_public_id=r.public_id "
                        + "AND a.state='STARTED') ORDER BY r.id",
                String.class);
        for (String runId : runIds) {
            failRun(runId, "AI_PROCESS_RESTARTED", true);
        }
        return runIds.size();
    }

    private Conversation conversationByPublicId(long ownerUserId, String publicId) {
        List<Conversation> rows = jdbcTemplate.query(
                "SELECT public_id, surface, context_type, context_resource_id, status, title_redacted, "
                        + "last_message_at, created_at FROM ai_conversation "
                        + "WHERE public_id = ? AND owner_user_id = ?",
                this::mapConversation, publicId, ownerUserId);
        if (rows.isEmpty()) throw AiApiException.notFound();
        return rows.getFirst();
    }

    private Conversation mapConversation(ResultSet resultSet, int rowNum) throws SQLException {
        return new Conversation(
                resultSet.getString("public_id"), resultSet.getString("surface"),
                resultSet.getString("context_type"), nullableLong(resultSet, "context_resource_id"),
                resultSet.getString("status"), resultSet.getString("title_redacted"),
                nullableInstant(resultSet, "last_message_at"), instant(resultSet, "created_at"));
    }

    private LockedConversation lockConversation(long ownerUserId, String publicId) {
        List<LockedConversation> rows = jdbcTemplate.query(
                "SELECT id, status FROM ai_conversation WHERE public_id = ? AND owner_user_id = ? FOR UPDATE",
                (resultSet, rowNum) -> new LockedConversation(
                        resultSet.getLong("id"), resultSet.getString("status")), publicId, ownerUserId);
        if (rows.isEmpty()) throw AiApiException.notFound();
        return rows.getFirst();
    }

    private ExistingRun existingRun(long conversationId, String clientRequestId) {
        List<ExistingRun> rows = jdbcTemplate.query(
                "SELECT m.request_hash, r.public_id FROM ai_message m "
                        + "JOIN ai_run r ON r.request_message_id = m.id "
                        + "WHERE m.conversation_id = ? AND m.client_request_id = ?",
                (resultSet, rowNum) -> new ExistingRun(
                        resultSet.getString("request_hash"), resultSet.getString("public_id")),
                conversationId, clientRequestId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private ActiveConfig requireActiveConfig() {
        return requireActiveConfig(AiCapability.ASSISTANT);
    }

    private ActiveConfig requireActiveConfig(AiCapability capability) {
        String promptKey = capability.name().toLowerCase(java.util.Locale.ROOT) + ".system";
        List<VersionRow> prompts = jdbcTemplate.query(
                "SELECT id, version FROM ai_prompt_version WHERE prompt_key = ? "
                        + "AND status = 'ACTIVE' AND active_slot_key = ? "
                        + "ORDER BY activated_at DESC, id DESC LIMIT 1",
                (resultSet, rowNum) -> new VersionRow(resultSet.getLong("id"), resultSet.getString("version")),
                promptKey, promptKey);
        List<VersionRow> tools = jdbcTemplate.query(
                "SELECT id, version FROM ai_tool_catalog_version WHERE status = 'ACTIVE' "
                        + "AND active_slot_key = 'runtime' ORDER BY activated_at DESC, id DESC LIMIT 1",
                (resultSet, rowNum) -> new VersionRow(resultSet.getLong("id"), resultSet.getString("version")));
        if (prompts.isEmpty() || tools.isEmpty()) {
            throw AiApiException.unavailable("AI_RUNTIME_CONFIG_INACTIVE", "AI 运行版本尚未激活");
        }
        return new ActiveConfig(prompts.getFirst().id(), prompts.getFirst().version(), tools.getFirst().id());
    }

    private List<BudgetCandidate> applicableBudgets(AiActorContext actor, String providerCode) {
        return applicableBudgetsFor(actor.userId(), actor.roleCodes(), providerCode);
    }

    private List<BudgetCandidate> applicableBudgetsFor(long userId, List<String> roles, String providerCode) {
        List<BudgetCandidate> rows = jdbcTemplate.query(
                "SELECT b.id, b.scope_type, b.scope_key, q.concurrent_run_limit "
                        + "FROM ai_budget_bucket b JOIN ai_quota_policy q ON q.id = b.quota_policy_id "
                        + "WHERE b.capability = 'ASSISTANT' AND b.provider_code = ? "
                        + "AND b.period_start <= CURRENT_TIMESTAMP AND b.period_end > CURRENT_TIMESTAMP "
                        + "AND q.status = 'ACTIVE' AND q.effective_from <= CURRENT_TIMESTAMP ORDER BY b.id",
                (resultSet, rowNum) -> new BudgetCandidate(
                        resultSet.getLong("id"), resultSet.getString("scope_type"),
                        resultSet.getString("scope_key"), resultSet.getInt("concurrent_run_limit")),
                providerCode);
        String userKey = Long.toString(userId);
        return rows.stream().filter(row -> switch (row.scopeType()) {
            case "GLOBAL" -> "*".equals(row.scopeKey());
            case "USER" -> userKey.equals(row.scopeKey());
            case "ROLE" -> roles.contains(row.scopeKey());
            case "CAPABILITY" -> "ASSISTANT".equals(row.scopeKey());
            case "PROVIDER" -> providerCode.equals(row.scopeKey());
            default -> false;
        }).toList();
    }

    private long nextMessageSequence(long conversationId) {
        Long sequence = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(sequence_no), 0) + 1 FROM ai_message WHERE conversation_id = ?",
                Long.class, conversationId);
        return sequence == null ? 1 : sequence;
    }

    private LockedRun lockRun(String runId, Long ownerUserId) {
        String sql = "SELECT r.id, r.public_id, r.conversation_id, r.request_message_id, r.capability, r.state, r.actor_user_id, "
                + "r.session_fingerprint_hash, r.session_fingerprint_key_version, r.permission_digest, "
                + "r.correlation_id, p.version AS prompt_version, c.public_id AS conversation_public_id, "
                + "c.owner_user_id FROM ai_run r JOIN ai_conversation c ON c.id = r.conversation_id "
                + "JOIN ai_prompt_version p ON p.id = r.prompt_version_id WHERE r.public_id = ?"
                + (ownerUserId == null ? "" : " AND c.owner_user_id = ?") + " FOR UPDATE";
        Object[] args = ownerUserId == null ? new Object[]{runId} : new Object[]{runId, ownerUserId};
        List<LockedRun> rows = jdbcTemplate.query(sql, (resultSet, rowNum) -> new LockedRun(
                resultSet.getLong("id"), resultSet.getString("public_id"),
                resultSet.getLong("conversation_id"), resultSet.getLong("request_message_id"),
                resultSet.getString("capability"), resultSet.getString("state"), resultSet.getLong("actor_user_id"),
                resultSet.getString("session_fingerprint_hash"),
                resultSet.getInt("session_fingerprint_key_version"),
                resultSet.getString("permission_digest"), resultSet.getString("correlation_id"),
                resultSet.getString("prompt_version"), resultSet.getString("conversation_public_id"),
                resultSet.getLong("owner_user_id")), args);
        if (rows.isEmpty()) throw AiApiException.notFound();
        return rows.getFirst();
    }

    private Run runByPublicId(String runId) {
        List<Run> rows = jdbcTemplate.query(
                "SELECT r.public_id,parent.public_id AS parent_run_public_id,"
                        + "c.public_id AS conversation_public_id, c.owner_user_id, r.capability, "
                        + "r.state, r.permission_digest, r.session_fingerprint_hash, p.version AS prompt_version, "
                        + "r.created_at, r.finished_at, r.failure_code FROM ai_run r "
                        + "LEFT JOIN ai_run parent ON parent.id=r.parent_run_id "
                        + "JOIN ai_conversation c ON c.id = r.conversation_id "
                        + "JOIN ai_prompt_version p ON p.id = r.prompt_version_id WHERE r.public_id = ?",
                (resultSet, rowNum) -> new Run(
                        resultSet.getString("public_id"), resultSet.getString("parent_run_public_id"),
                        resultSet.getString("conversation_public_id"),
                        resultSet.getLong("owner_user_id"), resultSet.getString("capability"),
                        resultSet.getString("state"), resultSet.getString("permission_digest"),
                        resultSet.getString("session_fingerprint_hash"), resultSet.getString("prompt_version"),
                        instant(resultSet, "created_at"), nullableInstant(resultSet, "finished_at"),
                        resultSet.getString("failure_code")), runId);
        if (rows.isEmpty()) throw AiApiException.notFound();
        return rows.getFirst();
    }

    private Event appendEvent(LockedRun run, String type, Map<String, Object> payload) {
        Long next = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(sequence_no), 0) + 1 FROM ai_run_event WHERE run_id = ?",
                Long.class, run.id());
        long sequence = next == null ? 1 : next;
        Instant now = Instant.now();
        String payloadJson = json(payload);
        jdbcTemplate.update("INSERT INTO ai_run_event "
                        + "(run_id, sequence_no, event_type, payload_redacted, created_at) VALUES (?, ?, ?, ?, ?)",
                run.id(), sequence, type, payloadJson, Timestamp.from(now));
        return new Event(Long.toString(sequence), run.publicId(), sequence, type, now, payload);
    }

    private void auditRunEvent(LockedRun run, String auditType, Event event) {
        auditWriter.append("RUN", "RUN", run.publicId(), auditType,
                ActorDescriptor.user(run.actorUserId()), run.sessionFingerprintHash(),
                run.sessionFingerprintKeyVersion(), run.permissionDigest(),
                crypto.sha256(json(event.payload())), run.correlationId());
    }

    private MessageOwner requireOwnedAssistantMessage(long ownerUserId, String messageId) {
        List<MessageOwner> rows = jdbcTemplate.query(
                "SELECT m.id AS message_id, r.id AS run_id FROM ai_message m "
                        + "JOIN ai_conversation c ON c.id = m.conversation_id "
                        + "JOIN ai_run r ON r.request_message_id = m.parent_message_id "
                        + "WHERE m.public_id = ? AND m.role = 'ASSISTANT' AND c.owner_user_id = ? FOR UPDATE",
                (resultSet, rowNum) -> new MessageOwner(
                        resultSet.getLong("message_id"), resultSet.getLong("run_id")), messageId, ownerUserId);
        if (rows.isEmpty()) throw AiApiException.notFound();
        return rows.getFirst();
    }

    private String auditRunSelect() {
        return "SELECT r.public_id,parent.public_id AS parent_run_public_id,r.capability,r.state,"
                + "COALESCE(md.provider_code, 'fake') AS provider_alias, "
                + "p.version AS prompt_version, "
                + "(SELECT COUNT(*) FROM ai_citation c WHERE c.run_id=r.id) AS citation_count, "
                + "(SELECT h.last_event_hash FROM ai_audit_chain_head h "
                + "WHERE h.chain_scope='RUN' AND h.aggregate_type='RUN' "
                + "AND h.aggregate_public_id=r.public_id) AS chain_hash, "
                + "r.input_tokens, r.output_tokens, r.estimated_cost, "
                + "r.failure_code, r.created_at, r.finished_at FROM ai_run r "
                + "LEFT JOIN ai_run parent ON parent.id=r.parent_run_id "
                + "JOIN ai_prompt_version p ON p.id = r.prompt_version_id "
                + "LEFT JOIN ai_model_deployment md ON md.id = r.model_deployment_id";
    }

    private AuditRun mapAuditRun(ResultSet resultSet, int rowNum) throws SQLException {
        return new AuditRun(
                resultSet.getString("public_id"), resultSet.getString("parent_run_public_id"),
                resultSet.getString("capability"),
                resultSet.getString("state"), resultSet.getString("provider_alias"),
                resultSet.getString("prompt_version"), resultSet.getLong("citation_count"),
                resultSet.getString("chain_hash"), resultSet.getLong("input_tokens"),
                resultSet.getLong("output_tokens"), resultSet.getBigDecimal("estimated_cost"),
                resultSet.getString("failure_code"), instant(resultSet, "created_at"),
                nullableInstant(resultSet, "finished_at"));
    }

    private int count(String sql, Object... args) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("无法序列化 AI 控制面数据", exception);
        }
    }

    private Map<String, Object> readJson(String value) {
        try {
            return objectMapper.readValue(value, EVENT_PAYLOAD);
        } catch (Exception exception) {
            throw new IllegalStateException("AI 事件载荷损坏", exception);
        }
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getTimestamp(column).toInstant();
    }

    private static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private record LockedConversation(long id, String status) { }
    private record ExistingRun(String requestHash, String runPublicId) { }

    private record PinnedPrompt(String content, String contentHash) { }
    private record RetryInput(String contentRedacted, String classification, String requestHash) { }
    private record MessageCitationBinding(String messageId, String citationId) { }
    private record VersionRow(long id, String version) { }
    private record ActiveConfig(long promptId, String promptVersion, long toolCatalogId) { }
    private record BudgetCandidate(long bucketId, String scopeType, String scopeKey, int concurrentRunLimit) { }
    private record UsageSettlement(
            int attemptCount,
            long inputTokens,
            long outputTokens,
            BigDecimal costAmount,
            String currency,
            boolean estimated,
            String costStatus) {

        private UsageSettlement {
            if (attemptCount < 0 || inputTokens < 0 || outputTokens < 0 || costAmount == null
                    || costAmount.signum() < 0 || currency == null || !currency.matches("[A-Z]{3}")
                    || !Set.of("FINAL", "RELEASED", "NEEDS_RECONCILIATION").contains(costStatus)) {
                throw new IllegalArgumentException("run usage settlement 不合法");
            }
        }

        private static UsageSettlement from(
                JdbcAiUsageLedgerRepository.UsageTotals totals,
                String costStatus) {
            return new UsageSettlement(totals.attemptCount(), totals.inputTokens(), totals.outputTokens(),
                    totals.costAmount(), totals.currency(), totals.estimatedAttemptCount() > 0, costStatus);
        }
    }
    private record MessageOwner(long messageDatabaseId, long runDatabaseId) { }
    private record CitationTarget(
            long documentVersionId,
            long chunkId,
            String contentHash,
            String quoteRedacted,
            String locator) { }
    private record CitationBinding(CitationCandidate citation, CitationTarget target) { }
    private record LockedRun(
            long id,
            String publicId,
            long conversationDatabaseId,
            long requestMessageId,
            String capability,
            String state,
            long actorUserId,
            String sessionFingerprintHash,
            int sessionFingerprintKeyVersion,
            String permissionDigest,
            String correlationId,
            String promptVersion,
            String conversationPublicId,
            long ownerUserId) { }
}
