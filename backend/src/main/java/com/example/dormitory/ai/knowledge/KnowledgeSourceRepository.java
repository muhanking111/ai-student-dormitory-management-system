package com.example.dormitory.ai.knowledge;

import com.example.dormitory.ai.security.DataClassification;

import java.util.Optional;
import java.util.Set;

public interface KnowledgeSourceRepository {

    KnowledgeSource create(CreateSource command);

    Optional<KnowledgeSource> findByPublicId(String publicId);

    java.util.List<KnowledgeSource> findAll();

    KnowledgeSource update(UpdateSource command);

    KnowledgeSource replaceAcl(String publicId, long expectedAclVersion, Set<String> permissions, long actorUserId);

    boolean canRead(String publicId, Set<String> actorPermissions);

    record CreateSource(
            String publicId,
            String name,
            String sourceType,
            long ownerUserId,
            DataClassification classification,
            PermissionMatchMode matchMode,
            String objectStoreCode,
            Set<String> permissions,
            long actorUserId) {
        public CreateSource {
            permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        }
    }

    record UpdateSource(
            String publicId,
            long expectedAclVersion,
            String name,
            DataClassification classification,
            PermissionMatchMode matchMode,
            String status,
            Set<String> permissions,
            long actorUserId) {
        public UpdateSource {
            permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        }
    }

    record KnowledgeSource(
            long id,
            String publicId,
            String name,
            String sourceType,
            long ownerUserId,
            DataClassification classification,
            PermissionMatchMode matchMode,
            String objectStoreCode,
            long aclVersion,
            String status,
            Set<String> permissions) {
        public KnowledgeSource {
            permissions = Set.copyOf(permissions);
        }
    }
}
