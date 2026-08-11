package com.example.dormitory.ai.api.dto;

import com.example.dormitory.ai.application.run.AiRunRecords;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.function.Function;

public final class AiConversationDtos {

    private static final String REVOKED_HISTORY_MESSAGE =
            "历史回答的授权来源已不可访问，请重新提问或联系管理员。";

    private AiConversationDtos() {
    }

    public record CreateConversationRequest(
            @NotBlank @Size(max = 32) String surface,
            @Size(max = 32) String contextType,
            Long contextId) {
    }

    public record ConversationResponse(
            String id,
            String surface,
            String contextType,
            Long contextId,
            String status,
            String title,
            Instant lastMessageAt,
            Instant createdAt) {
        public static ConversationResponse from(AiRunRecords.Conversation value) {
            return new ConversationResponse(value.id(), value.surface(), value.contextType(), value.contextId(),
                    value.status(), value.title(), value.lastMessageAt(), value.createdAt());
        }
    }

    public record MessageResponse(
            String id,
            String role,
            String text,
            String classification,
            Instant createdAt,
            String runId,
            String runState,
            Boolean grounded,
            Instant asOf,
            List<CitationSummaryResponse> citations) {
        public MessageResponse {
            citations = citations == null ? List.of() : List.copyOf(citations);
        }

        public static MessageResponse from(AiRunRecords.Message value) {
            return from(value, CitationSummaryResponse::denied);
        }

        public static MessageResponse from(
                AiRunRecords.Message value,
                Function<String, CitationSummaryResponse> citationResolver) {
            List<CitationSummaryResponse> citations = value.citationIds().stream().map(citationResolver).toList();
            boolean assistant = "ASSISTANT".equals(value.role());
            boolean revokedHistory = assistant && !value.citationIds().isEmpty()
                    && citations.stream().anyMatch(citation -> !"available".equals(citation.access()));
            Boolean grounded = assistant
                    ? !revokedHistory && citations.stream().anyMatch(citation -> "available".equals(citation.access()))
                    : null;
            String text = revokedHistory ? REVOKED_HISTORY_MESSAGE : value.text();
            return new MessageResponse(value.id(), value.role(), text, value.classification(),
                    value.createdAt(), value.runId(), value.runState(), grounded, value.asOf(), citations);
        }
    }

    public record CitationSummaryResponse(
            String id,
            String label,
            String locator,
            String version,
            String access) {
        public static CitationSummaryResponse denied(String id) {
            return new CitationSummaryResponse(id, "受限来源", "当前不可访问", "", "denied");
        }
    }

    public record ConversationDetailResponse(
            String id,
            String surface,
            String contextType,
            Long contextId,
            String status,
            String title,
            Instant lastMessageAt,
            Instant createdAt,
            List<MessageResponse> messages) {
        public static ConversationDetailResponse from(AiRunRecords.ConversationDetail value) {
            return from(value, CitationSummaryResponse::denied);
        }

        public static ConversationDetailResponse from(
                AiRunRecords.ConversationDetail value,
                Function<String, CitationSummaryResponse> citationResolver) {
            AiRunRecords.Conversation conversation = value.conversation();
            return new ConversationDetailResponse(
                    conversation.id(), conversation.surface(), conversation.contextType(), conversation.contextId(),
                    conversation.status(), conversation.title(), conversation.lastMessageAt(), conversation.createdAt(),
                    value.messages().stream().map(message -> MessageResponse.from(message, citationResolver)).toList());
        }
    }

    public record CreateMessageRequest(
            @NotBlank @Size(max = 4_000) String text,
            @NotBlank @Size(max = 128) String clientRequestId) {
    }

    public record RunAcceptedResponse(String runId, String eventsUrl) {
    }
}
