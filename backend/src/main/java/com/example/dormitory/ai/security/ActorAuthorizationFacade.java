package com.example.dormitory.ai.security;

import com.example.dormitory.service.RbacService;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class ActorAuthorizationFacade {

    private final RbacService rbacService;

    public ActorAuthorizationFacade(RbacService rbacService) {
        this.rbacService = rbacService;
    }

    public RepairAccessPolicy.RepairActorAccess repairAccessForUser(long userId) {
        RbacService.AuthorizationSnapshot snapshot = rbacService.authorizationSnapshotForUser(userId);
        return new RepairAccessPolicy.RepairActorAccess(userId, snapshot.enabled(),
                Set.copyOf(snapshot.roleCodes()), Set.copyOf(snapshot.permissionCodes()));
    }

    public RbacService.AuthorizationSnapshot snapshot(long userId) {
        return rbacService.authorizationSnapshotForUser(userId);
    }

    public RbacService.AuthorizationSnapshot snapshotForUpdate(long userId) {
        return rbacService.authorizationSnapshotForUserForUpdate(userId);
    }
}
