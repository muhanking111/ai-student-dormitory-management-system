package com.example.dormitory.ai.approval;

import java.util.UUID;

/** A real, persisted command-run/tool identity that produced one proposal. */
public record ProposalOrigin(String runPublicId, String toolName, String toolVersion) {

    public ProposalOrigin {
        try {
            UUID.fromString(runPublicId);
        } catch (Exception exception) {
            throw new IllegalArgumentException("提案 origin run 必须是 UUID", exception);
        }
        if (toolName == null || !toolName.matches("[a-z][a-z0-9_.-]{1,127}")
                || toolVersion == null || !toolVersion.matches("v[0-9]{1,8}")) {
            throw new IllegalArgumentException("提案 origin tool 身份不合法");
        }
    }

    public static ProposalOrigin forAction(String runPublicId, ActionType actionType) {
        if (actionType == null) throw new IllegalArgumentException("提案动作不能为空");
        return new ProposalOrigin(runPublicId, switch (actionType) {
            case REPAIR_ASSIGN -> "repair.propose_assignment.v1";
            case NOTICE_CREATE_DRAFT -> "notice.propose_draft.v1";
        }, "v1");
    }

    public void requireMatches(ActionType actionType) {
        ProposalOrigin expected = forAction(runPublicId, actionType);
        if (!expected.toolName.equals(toolName) || !expected.toolVersion.equals(toolVersion)) {
            throw new IllegalArgumentException("提案 origin tool 与动作不匹配");
        }
    }
}
