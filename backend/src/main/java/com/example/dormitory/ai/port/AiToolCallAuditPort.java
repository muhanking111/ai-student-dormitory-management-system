package com.example.dormitory.ai.port;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** 持久化与具体供应商无关的工具授权及执行审计事实。 */
public interface AiToolCallAuditPort {

    void append(ToolCallAudit audit);

    enum AuthorizationDecision {
        ALLOWED,
        DENIED
    }

    enum State {
        SUCCEEDED,
        DENIED,
        FAILED
    }

    record ToolCallAudit(
            String runId,
            long actorUserId,
            String toolName,
            String toolVersion,
            String requestRedacted,
            String responseRedacted,
            Set<String> requiredPermissions,
            AuthorizationDecision authorizationDecision,
            State state,
            String errorCode,
            Instant startedAt,
            Instant finishedAt) {

        public ToolCallAudit {
            try {
                UUID.fromString(runId);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("工具审计 runId 不合法", exception);
            }
            if (actorUserId < 1
                    || toolName == null
                    || !toolName.matches("[a-z]+(?:[._][a-z]+)*\\.v[1-9][0-9]*")
                    || toolVersion == null
                    || !toolVersion.matches("v[1-9][0-9]*")
                    || !toolName.endsWith("." + toolVersion)
                    || requestRedacted == null
                    || requestRedacted.isBlank()
                    || requiredPermissions == null
                    || requiredPermissions.isEmpty()
                    || requiredPermissions.stream().anyMatch(permission -> permission == null
                    || permission.isBlank() || "*".equals(permission))
                    || authorizationDecision == null
                    || state == null
                    || startedAt == null
                    || finishedAt == null
                    || finishedAt.isBefore(startedAt)) {
                throw new IllegalArgumentException("工具审计事实不完整");
            }
            boolean allowedSuccess = authorizationDecision == AuthorizationDecision.ALLOWED
                    && state == State.SUCCEEDED && errorCode == null && responseRedacted != null;
            boolean allowedFailure = authorizationDecision == AuthorizationDecision.ALLOWED
                    && state == State.FAILED && errorCode != null && !errorCode.isBlank();
            boolean denied = authorizationDecision == AuthorizationDecision.DENIED
                    && state == State.DENIED && errorCode != null && !errorCode.isBlank()
                    && responseRedacted == null;
            if (!allowedSuccess && !allowedFailure && !denied) {
                throw new IllegalArgumentException("工具审计决策与状态不一致");
            }
            requiredPermissions = Set.copyOf(requiredPermissions);
        }
    }
}
