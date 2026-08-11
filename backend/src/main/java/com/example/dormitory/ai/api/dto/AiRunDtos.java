package com.example.dormitory.ai.api.dto;

import com.example.dormitory.ai.application.run.AiRunRecords;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class AiRunDtos {

    private AiRunDtos() {
    }

    public record RunResponse(
            String id,
            String parentRunId,
            String conversationId,
            String capability,
            String state,
            String promptVersion,
            Instant createdAt,
            Instant finishedAt,
            String failureCode) {
        public static RunResponse from(AiRunRecords.Run run) {
            return new RunResponse(run.id(), run.parentRunId(), run.conversationId(), run.capability(), run.state(),
                    run.promptVersion(), run.createdAt(), run.finishedAt(), run.failureCode());
        }
    }

    public record FeedbackRequest(
            @Min(-1) @Max(1) int rating,
            @Size(max = 10) List<@Size(max = 32) String> tags,
            @Size(max = 500) String comment) {
    }
}
