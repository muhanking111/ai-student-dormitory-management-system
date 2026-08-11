package com.example.dormitory.ai.approval;

import java.util.Set;

public enum ActionType {
    NOTICE_CREATE_DRAFT,
    REPAIR_ASSIGN;

    private static final Set<ActionType> ALLOWLIST = Set.of(NOTICE_CREATE_DRAFT, REPAIR_ASSIGN);

    public static Set<ActionType> allowlisted() {
        return ALLOWLIST;
    }
}
