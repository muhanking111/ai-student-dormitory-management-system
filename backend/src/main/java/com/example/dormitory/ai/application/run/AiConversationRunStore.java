package com.example.dormitory.ai.application.run;

import com.example.dormitory.ai.application.run.AiRunRecords.AuditRun;
import com.example.dormitory.ai.application.run.AiRunRecords.AuditRunDetail;
import com.example.dormitory.ai.application.run.AiRunRecords.AuditRunContent;
import com.example.dormitory.ai.application.run.AiRunRecords.Conversation;
import com.example.dormitory.ai.application.run.AiRunRecords.ConversationDetail;
import com.example.dormitory.ai.application.run.AiRunRecords.Event;
import com.example.dormitory.ai.application.run.AiRunRecords.Readiness;
import com.example.dormitory.ai.application.run.AiRunRecords.Run;
import com.example.dormitory.ai.application.run.AiRunRecords.RunCreation;
import com.example.dormitory.ai.application.run.AiRunRecords.RunMutation;
import com.example.dormitory.common.PageResponse;
import com.example.dormitory.ai.domain.model.AiCapability;
import com.example.dormitory.ai.domain.model.ModelUsage;

import java.util.List;

public interface AiConversationRunStore {

    Conversation createConversation(AiActorContext actor, String surface, String contextType, Long contextId);

    PageResponse<Conversation> listConversations(long ownerUserId, long page, long pageSize);

    ConversationDetail conversationDetail(long ownerUserId, String conversationId);

    /** 只读取 run 授权所需的 conversation 元数据，不加载历史消息正文。 */
    Conversation ownedConversation(long ownerUserId, String conversationId);

    void archiveConversation(AiActorContext actor, String conversationId);

    RunCreation createRun(
            AiActorContext actor,
            String conversationId,
            String clientRequestId,
            String requestHash,
            String redactedText,
            String classification,
            String providerCode);

    RunCreation createCommandRun(
            AiActorContext actor,
            AiCapability capability,
            String surface,
            String contextType,
            Long contextId,
            String clientRequestId,
            String requestHash,
            String redactedInput,
            String classification,
            String providerCode);

    AiRunRecords.RetrySource retrySource(long ownerUserId, String parentRunId);

    RunCreation createRetryRun(
            AiActorContext actor,
            String parentRunId,
            String idempotencyKey,
            String providerCode);

    Run ownedRun(long ownerUserId, String runId);

    List<Event> eventsAfter(long ownerUserId, String runId, long lastSequence);

    RunMutation markQueued(String runId);

    RunMutation markStarted(String runId, String providerAlias);

    /** 读取 run 创建时固定的 System prompt 内容，并验证其 content hash。 */
    String pinnedSystemPrompt(String runId);

    RunMutation appendDelta(String runId, String textDelta);

    void recordRetrievalTrace(String runId, AiActorContext actor, AiRunRecords.RetrievalTrace trace);

    RunMutation completeRun(
            String runId,
            String assistantText,
            ModelUsage usage,
            String providerCode,
            String modelAlias,
            boolean providerInvoked,
            boolean grounded,
            List<AiRunRecords.CitationCandidate> citations,
            java.util.Set<String> actorPermissionCodes);

    /**
     * 在同一事务内完成 grounded deterministic response，并把完整正文作为最终 delta 事件生成。
     * 调用方只能在方法成功返回后发布事件，避免 ACL 撤权卡在 delta 与完成事务之间。
     */
    RunMutation completeRunWithFinalDelta(
            String runId,
            String assistantText,
            ModelUsage usage,
            String providerCode,
            String modelAlias,
            boolean providerInvoked,
            boolean grounded,
            List<AiRunRecords.CitationCandidate> citations,
            java.util.Set<String> actorPermissionCodes);

    RunMutation completeCommandRun(String runId, java.util.Map<String, Object> result);

    /**
     * 完成带授权知识引用的 deterministic command。默认实现只允许空引用，
     * 兼容不支持 citation 持久化的窄测试/fake 实现并对误用快速失败。
     */
    default RunMutation completeCommandRun(
            String runId,
            java.util.Map<String, Object> result,
            List<AiRunRecords.CitationCandidate> citations,
            java.util.Set<String> actorPermissionCodes) {
        if (citations != null && !citations.isEmpty()) {
            throw new IllegalStateException("command store 不支持 citation 持久化");
        }
        return completeCommandRun(runId, result);
    }

    RunMutation failRun(String runId, String errorCode, boolean retryable);

    void forceFailWithoutEvent(String runId, String errorCode);

    RunMutation cancelRun(AiActorContext actor, String runId);

    void upsertFeedback(
            AiActorContext actor,
            String messageId,
            int rating,
            List<String> tags,
            String commentRedacted);

    default PageResponse<AuditRun> auditRuns(long page, long pageSize) {
        return auditRuns(AiRunRecords.AuditRunFilter.none(), page, pageSize);
    }

    PageResponse<AuditRun> auditRuns(AiRunRecords.AuditRunFilter filter, long page, long pageSize);

    AuditRunDetail auditRun(String runId);

    AuditRunContent auditRunContent(String runId);

    AiRunRecords.AuditAuthorizationScope auditAuthorizationScope(String runId);

    Readiness readiness(long actorUserId, String providerCode);

    int failRunsWithReconciliationAttempts();
}
