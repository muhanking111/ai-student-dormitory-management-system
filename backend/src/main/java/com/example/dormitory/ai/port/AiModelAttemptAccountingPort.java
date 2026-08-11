package com.example.dormitory.ai.port;

import com.example.dormitory.ai.domain.model.AiCapability;
import com.example.dormitory.ai.domain.model.ModelRequest;
import com.example.dormitory.ai.domain.model.ModelUsage;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

public interface AiModelAttemptAccountingPort {

    AttemptHandle begin(
            ModelRequest.BillingTrace trace,
            AiCapability capability,
            String providerCode,
            String modelName,
            String requestKind,
            int attemptNo,
            Instant startedAt);

    AttemptReceipt finish(
            AttemptHandle handle,
            ModelUsage usage,
            AttemptOutcome outcome,
            Duration duration,
            String failureCode);

    UsageTotals totals(String runPublicId);

    enum AttemptOutcome { SUCCEEDED, FAILED_RETRYABLE, FAILED_FATAL, TIMED_OUT, CANCELLED, UNKNOWN }

    record AttemptHandle(
            ModelRequest.BillingTrace trace,
            AiCapability capability,
            String providerCode,
            String modelName,
            String requestKind,
            int attemptNo,
            long pricingVersionId,
            String currency,
            BigDecimal inputCostPerMillion,
            BigDecimal outputCostPerMillion,
            Instant startedAt) {
    }

    record AttemptReceipt(
            String runPublicId,
            int requestSequenceNo,
            int attemptNo,
            long pricingVersionId,
            long inputTokens,
            long outputTokens,
            BigDecimal costAmount,
            String currency,
            ModelUsage.Source usageSource,
            AttemptOutcome outcome) {
    }

    record UsageTotals(
            int attemptCount,
            long inputTokens,
            long outputTokens,
            BigDecimal costAmount,
            String currency,
            int estimatedAttemptCount) {
        public long totalTokens() { return Math.addExact(inputTokens, outputTokens); }
        public boolean hasAttempts() { return attemptCount > 0; }
        public boolean estimated() { return estimatedAttemptCount > 0; }
    }
}
