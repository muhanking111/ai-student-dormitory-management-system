package com.example.dormitory.ai.knowledge;

import com.example.dormitory.ai.security.PiiClassificationService;
import com.example.dormitory.ai.security.PromptInjectionGuard;
import com.example.dormitory.ai.security.SensitiveDataBlockedException;

import java.util.List;
import java.util.Set;

/**
 * 安全知识助手的确定性编排层。首期无可靠来源时拒答，不让模型补写事实。
 */
public final class KnowledgeAssistantService {

    private static final String POLICY_VERSION = "knowledge-assistant-policy-v1";

    private final SafeKnowledgeService knowledgeService;
    private final PromptInjectionGuard injectionGuard;
    private final PiiClassificationService classificationService;

    public KnowledgeAssistantService(
            SafeKnowledgeService knowledgeService,
            PromptInjectionGuard injectionGuard,
            PiiClassificationService classificationService) {
        this.knowledgeService = java.util.Objects.requireNonNull(knowledgeService);
        this.injectionGuard = java.util.Objects.requireNonNull(injectionGuard);
        this.classificationService = java.util.Objects.requireNonNull(classificationService);
    }

    public AssistantAnswer answer(String query, Set<String> actorPermissions, int topK) {
        return answerWithTrace(query, actorPermissions, topK).answer();
    }

    public TracedAssistantAnswer answerWithTrace(String query, Set<String> actorPermissions, int topK) {
        Set<String> permissions = actorPermissions == null ? Set.of() : Set.copyOf(actorPermissions);
        if (!permissions.contains("ai:assistant:use")) {
            return withoutRetrieval(refused("AI_ASSISTANT_FORBIDDEN", "无权使用 AI 助手", "FORBIDDEN"));
        }
        if (query == null || query.isBlank() || query.length() > 2_000 || topK < 1 || topK > 20) {
            return withoutRetrieval(refused("AI_ASSISTANT_INPUT_INVALID", "问题格式不合法", "REFUSED_INVALID_INPUT"));
        }
        PromptInjectionGuard.Inspection inspection = injectionGuard.inspect(query);
        if (inspection.blocked()) {
            return withoutRetrieval(refused("AI_PROMPT_INJECTION_BLOCKED", "问题包含不受支持的指令", "REFUSED_UNSAFE_INPUT"));
        }

        final String redactedQuery;
        try {
            redactedQuery = classificationService.redact(query, "knowledge-assistant-query").redactedText();
        } catch (SensitiveDataBlockedException exception) {
            return withoutRetrieval(refused("AI_SENSITIVE_INPUT_BLOCKED", "问题包含禁止进入 AI 的敏感数据", "REFUSED_SENSITIVE_INPUT"));
        }

        SafeKnowledgeService.KnowledgeSearchResult search = knowledgeService.searchWithTrace(
                redactedQuery, permissions, topK);
        SafeKnowledgeService.KnowledgeAnswer answer = search.answer();
        if (!answer.grounded()) {
            return new TracedAssistantAnswer(new AssistantAnswer(false, answer.answerText(), List.of(),
                    answer.reasonCode(), "REFUSED_UNGROUNDED", POLICY_VERSION, answer.retrievalMode()),
                    search.retrievalTrace());
        }
        return new TracedAssistantAnswer(new AssistantAnswer(true, answer.answerText(), answer.citations(), null,
                "SAFE_GROUNDED", POLICY_VERSION, answer.retrievalMode()), search.retrievalTrace());
    }

    private TracedAssistantAnswer withoutRetrieval(AssistantAnswer answer) {
        return new TracedAssistantAnswer(answer, null);
    }

    private AssistantAnswer refused(String reasonCode, String text, String safetyState) {
        return new AssistantAnswer(false, text, List.of(), reasonCode, safetyState,
                POLICY_VERSION, "none");
    }

    public record AssistantAnswer(
            boolean grounded,
            String answerText,
            List<SafeKnowledgeService.KnowledgeCitation> citations,
            String reasonCode,
            String safetyState,
            String policyVersion,
            String retrievalMode) {
        public AssistantAnswer {
            citations = citations == null ? List.of() : List.copyOf(citations);
        }
    }

    public record TracedAssistantAnswer(
            AssistantAnswer answer,
            com.example.dormitory.ai.application.run.AiRunRecords.RetrievalTrace retrievalTrace) {
        public TracedAssistantAnswer {
            java.util.Objects.requireNonNull(answer);
        }
    }
}
