package com.example.dormitory.ai.knowledge;

import java.util.Set;

public class KnowledgeAclPolicy {

    public boolean canRead(
            KnowledgeVisibility visibility,
            PermissionMatchMode matchMode,
            Set<String> requiredPermissions,
            Set<String> actorPermissions,
            boolean owner) {
        if (visibility == null || matchMode == null) return false;
        Set<String> required = requiredPermissions == null ? Set.of() : Set.copyOf(requiredPermissions);
        Set<String> actor = actorPermissions == null ? Set.of() : Set.copyOf(actorPermissions);
        validatePermissions(required);
        if (visibility == KnowledgeVisibility.PUBLIC_APPROVED) return true;
        if (required.isEmpty()) return false;
        return matchMode == PermissionMatchMode.ALL
                ? actor.containsAll(required)
                : required.stream().anyMatch(actor::contains);
    }

    public void validatePermissions(Set<String> permissions) {
        if (permissions == null) return;
        if (permissions.stream().anyMatch(code -> code == null || code.isBlank() || "*".equals(code))) {
            throw new IllegalArgumentException("知识 ACL 只能使用已登记的显式权限码");
        }
    }

    public boolean validatePublicApproval(
            String approvedContentHash,
            String currentContentHash,
            String classification,
            boolean governanceApproved) {
        if (!governanceApproved || !"L0".equals(classification)
                || approvedContentHash == null || !approvedContentHash.equals(currentContentHash)) {
            throw new IllegalArgumentException("公开批准必须绑定当前 L0 内容快照");
        }
        return true;
    }
}
