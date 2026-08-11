package com.example.dormitory.ai.domain.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public record BusinessActorScope(
        ActorDescriptor actor,
        Set<String> permissionCodes,
        Map<String, Set<Long>> resourceIds) {

    public BusinessActorScope {
        if (actor == null) {
            throw new IllegalArgumentException("业务读取 actor 不能为空");
        }
        permissionCodes = permissionCodes == null ? Set.of() : Set.copyOf(permissionCodes);
        Map<String, Set<Long>> copiedResources = new LinkedHashMap<>();
        if (resourceIds != null) {
            resourceIds.forEach((type, ids) -> {
                if (type == null || type.isBlank() || ids == null) {
                    throw new IllegalArgumentException("资源范围不合法");
                }
                copiedResources.put(type, Set.copyOf(ids));
            });
        }
        resourceIds = Collections.unmodifiableMap(copiedResources);
    }

    public boolean hasAllPermissions(Set<String> requiredPermissions) {
        return permissionCodes.containsAll(requiredPermissions);
    }

    public boolean canRead(String resourceType, long resourceId) {
        return resourceIds.getOrDefault(resourceType, Set.of()).contains(resourceId);
    }
}
