package com.example.dormitory.ai.knowledge;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeAclPolicyTest {

    private final KnowledgeAclPolicy policy = new KnowledgeAclPolicy();

    @Test
    void explicitAclIsDenyByDefaultAndSupportsAnyAllWithoutOwnerBypass() {
        assertFalse(policy.canRead(KnowledgeVisibility.EXPLICIT_ACL, PermissionMatchMode.ANY,
                Set.of(), Set.of("notice:read"), false));
        assertTrue(policy.canRead(KnowledgeVisibility.EXPLICIT_ACL, PermissionMatchMode.ANY,
                Set.of("notice:read", "repair:read"), Set.of("repair:read"), false));
        assertFalse(policy.canRead(KnowledgeVisibility.EXPLICIT_ACL, PermissionMatchMode.ALL,
                Set.of("notice:read", "repair:read"), Set.of("repair:read"), false));
        assertTrue(policy.canRead(KnowledgeVisibility.EXPLICIT_ACL, PermissionMatchMode.ALL,
                Set.of("notice:read", "repair:read"), Set.of("repair:read", "notice:read"), false));
        assertFalse(policy.canRead(KnowledgeVisibility.EXPLICIT_ACL, PermissionMatchMode.ANY,
                Set.of(), Set.of(), true));
    }

    @Test
    void publicRequiresVersionBoundApprovalAndWildcardAclIsInvalid() {
        assertTrue(policy.canRead(KnowledgeVisibility.PUBLIC_APPROVED, PermissionMatchMode.ANY,
                Set.of(), Set.of(), false));
        assertThrows(IllegalArgumentException.class,
                () -> policy.validatePermissions(Set.of("*")));
        assertThrows(IllegalArgumentException.class,
                () -> policy.validatePublicApproval("hash-a", "hash-b", "L0", false));
        assertTrue(policy.validatePublicApproval("hash-a", "hash-a", "L0", true));
    }
}
