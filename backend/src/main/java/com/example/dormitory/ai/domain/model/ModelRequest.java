package com.example.dormitory.ai.domain.model;

import java.time.Duration;
import java.util.Set;

public record ModelRequest(
        AiCapability capability,
        String prompt,
        Set<String> allowedToolIds,
        String responseSchemaVersion,
        Duration timeout,
        int maxOutputTokens,
        BillingTrace billingTrace,
        String systemPrompt,
        java.util.List<UntrustedContent> untrustedContent) {

    public ModelRequest {
        if (capability == null) {
            throw new IllegalArgumentException("AI 能力不能为空");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("模型输入不能为空");
        }
        allowedToolIds = allowedToolIds == null ? Set.of() : Set.copyOf(allowedToolIds);
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("模型超时必须为正数");
        }
        if (maxOutputTokens < 1) {
            throw new IllegalArgumentException("最大输出 Token 必须大于 0");
        }
        if (systemPrompt != null && (systemPrompt.isBlank() || systemPrompt.length() > 100_000
                || systemPrompt.codePoints().anyMatch(code -> Character.isISOControl(code)
                && code != '\n' && code != '\t'))) {
            throw new IllegalArgumentException("System prompt 不合法");
        }
        untrustedContent = untrustedContent == null ? java.util.List.of() : java.util.List.copyOf(untrustedContent);
    }

    public ModelRequest(
            AiCapability capability,
            String prompt,
            Set<String> allowedToolIds,
            String responseSchemaVersion,
            Duration timeout,
            int maxOutputTokens,
            BillingTrace billingTrace) {
        this(capability, prompt, allowedToolIds, responseSchemaVersion, timeout, maxOutputTokens,
                billingTrace, null, java.util.List.of());
    }

    public ModelRequest(
            AiCapability capability,
            String prompt,
            Set<String> allowedToolIds,
            String responseSchemaVersion,
            Duration timeout,
            int maxOutputTokens) {
        this(capability, prompt, allowedToolIds, responseSchemaVersion, timeout, maxOutputTokens, null);
    }

    public static ModelRequest text(AiCapability capability, String prompt) {
        return new ModelRequest(capability, prompt, Set.of(), null, Duration.ofSeconds(60), 4_096, null);
    }

    public static ModelRequest roleSeparated(
            AiCapability capability,
            String systemPrompt,
            String userPrompt,
            java.util.List<UntrustedContent> untrustedContent,
            Set<String> allowedToolIds,
            String responseSchemaVersion,
            Duration timeout,
            int maxOutputTokens,
            BillingTrace billingTrace) {
        return new ModelRequest(capability, userPrompt, allowedToolIds, responseSchemaVersion, timeout,
                maxOutputTokens, billingTrace, systemPrompt, untrustedContent);
    }

    public long providerInputCodePoints() {
        long total = prompt.codePointCount(0, prompt.length());
        if (systemPrompt != null) total += systemPrompt.codePointCount(0, systemPrompt.length());
        for (UntrustedContent content : untrustedContent) {
            total += content.content().codePointCount(0, content.content().length());
        }
        return total;
    }

    public record UntrustedContent(String kind, String content) {
        private static final java.util.Set<String> KINDS = java.util.Set.of(
                "PAGE_CONTEXT_JSON", "RAG_CONTEXT_JSON", "TOOL_RESULT");

        public UntrustedContent {
            if (kind == null || !KINDS.contains(kind) || content == null || content.isBlank()
                    || content.length() > 200_000
                    || content.codePoints().anyMatch(code -> Character.isISOControl(code)
                    && code != '\n' && code != '\t')) {
                throw new IllegalArgumentException("不可信模型上下文不合法");
            }
        }
    }

    /** 仅供项目内部计费/追踪使用；adapter 不得把这些字段拼入 prompt 或 provider 请求。 */
    public record BillingTrace(String runPublicId, int requestSequenceNo, ActorDescriptor actor) {
        public BillingTrace {
            if (runPublicId == null || !runPublicId.matches(
                    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
                    || requestSequenceNo < 1 || actor == null) {
                throw new IllegalArgumentException("模型 billing trace 不合法");
            }
        }
    }
}
