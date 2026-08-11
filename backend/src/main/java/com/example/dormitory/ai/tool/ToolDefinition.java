package com.example.dormitory.ai.tool;

import java.time.Duration;
import java.util.Set;

public record ToolDefinition(
        String id,
        String schemaVersion,
        String description,
        Kind kind,
        String inputSchemaJson,
        String outputSchemaJson,
        Set<String> requiredPermissions,
        int maxResponseBytes,
        Duration timeout,
        int maxCallsPerRun,
        DataClassification dataClassification,
        boolean producesProposal) {

    public ToolDefinition {
        if (id == null || !id.matches("[a-z]+(?:[._][a-z]+)*\\.v[1-9][0-9]*")) {
            throw new IllegalArgumentException("工具 ID 必须为固定版本化标识");
        }
        if (schemaVersion == null || !schemaVersion.matches("v[1-9][0-9]*")
                || !id.endsWith("." + schemaVersion)) {
            throw new IllegalArgumentException("工具版本与 ID 不一致");
        }
        if (description == null || description.isBlank() || description.length() > 200
                || kind == null
                || inputSchemaJson == null || inputSchemaJson.isBlank()
                || outputSchemaJson == null || outputSchemaJson.isBlank()
                || requiredPermissions == null || requiredPermissions.isEmpty()
                || requiredPermissions.stream().anyMatch(permission -> permission == null
                || permission.isBlank() || permission.equals("*"))
                || maxResponseBytes < 1 || maxResponseBytes > 1_048_576
                || timeout == null || timeout.isZero() || timeout.isNegative()
                || maxCallsPerRun < 1
                || dataClassification == null
                || producesProposal != (kind == Kind.PROPOSAL)) {
            throw new IllegalArgumentException("工具定义不完整");
        }
        requiredPermissions = Set.copyOf(requiredPermissions);
    }

    public enum Kind {
        READ,
        PROPOSAL
    }

    public enum DataClassification {
        L0,
        L1,
        L2,
        L3
    }
}
