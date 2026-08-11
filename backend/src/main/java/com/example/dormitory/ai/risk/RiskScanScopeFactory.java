package com.example.dormitory.ai.risk;

import java.time.Instant;
import java.util.Set;

/** 从可信 RBAC 与当前行级业务事实生成扫描范围；不得接受浏览器提交的 scope。 */
public interface RiskScanScopeFactory {

    RiskScanScope capture(
            long effectiveSubjectUserId,
            Set<String> roleCodes,
            Set<String> permissionCodes,
            Instant capturedAt);
}
