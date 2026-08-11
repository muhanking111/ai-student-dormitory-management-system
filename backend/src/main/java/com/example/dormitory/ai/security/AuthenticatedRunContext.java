package com.example.dormitory.ai.security;

import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.ActorKind;
import com.example.dormitory.ai.application.run.AiActorContext;

public record AuthenticatedRunContext(
        ActorDescriptor actor,
        String sessionFingerprintHash,
        int sessionFingerprintKeyVersion,
        String permissionDigest) {

    public AuthenticatedRunContext {
        if (actor == null || sessionFingerprintKeyVersion < 1
                || sessionFingerprintHash == null || !sessionFingerprintHash.matches("[0-9a-f]{64}")
                || permissionDigest == null || !permissionDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("认证运行上下文不合法");
        }
    }

    public long requireUserActorId() {
        if (actor.kind() != ActorKind.USER || actor.actorUserId() == null) {
            throw RecentAuthenticationPolicy.userActorRequired();
        }
        return actor.actorUserId();
    }

    public static AuthenticatedRunContext from(AiActorContext context) {
        if (context == null) throw new IllegalArgumentException("AI actor context 不能为空");
        return new AuthenticatedRunContext(context.actor(), context.sessionFingerprintHash(),
                context.sessionFingerprintKeyVersion(), context.permissionDigest());
    }
}
