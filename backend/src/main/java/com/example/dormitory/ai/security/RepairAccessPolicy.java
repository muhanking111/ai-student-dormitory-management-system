package com.example.dormitory.ai.security;

import com.example.dormitory.common.BusinessException;
import com.example.dormitory.domain.RepairOrder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class RepairAccessPolicy {

    public void requireRead(RepairActorAccess actor) {
        if (actor == null || !actor.enabled() || !actor.permissions().contains("repair:read")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "无权读取维修业务");
        }
    }

    public void requireOrderRead(RepairActorAccess actor, RepairOrder order) {
        requireRead(actor);
        if (order == null) throw new BusinessException(HttpStatus.NOT_FOUND, "维修单不存在");
        if (isRestrictedRepairer(actor)
                && !java.util.Objects.equals(order.getAssigneeUserId(), actor.userId())) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "维修单不存在");
        }
    }

    public boolean isRestrictedRepairer(RepairActorAccess actor) {
        return actor.roles().contains("REPAIRER") && !actor.roles().contains("ADMIN");
    }

    public record RepairActorAccess(
            long userId,
            boolean enabled,
            Set<String> roles,
            Set<String> permissions) {
        public RepairActorAccess {
            if (userId < 1) throw new IllegalArgumentException("actor userId 不合法");
            roles = roles == null ? Set.of() : Set.copyOf(roles);
            permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        }
    }
}
