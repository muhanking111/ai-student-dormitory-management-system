package com.example.dormitory.ai.domain.model;

public record ActorDescriptor(
        ActorKind kind,
        Long actorUserId,
        String servicePrincipalCode,
        Long initiatedByUserId,
        Long effectiveSubjectUserId) {

    public ActorDescriptor {
        if (kind == null) {
            throw new IllegalArgumentException("Actor kind 不能为空");
        }
        validatePositive(actorUserId, "actorUserId");
        validatePositive(initiatedByUserId, "initiatedByUserId");
        validatePositive(effectiveSubjectUserId, "effectiveSubjectUserId");
        if (kind == ActorKind.USER) {
            if (actorUserId == null || servicePrincipalCode != null) {
                throw new IllegalArgumentException("USER 必须且只能绑定真实用户 ID");
            }
        } else if (actorUserId != null || servicePrincipalCode == null || servicePrincipalCode.isBlank()) {
            throw new IllegalArgumentException("后台 Actor 必须且只能绑定 service principal code");
        }
    }

    public static ActorDescriptor user(long userId) {
        return new ActorDescriptor(ActorKind.USER, userId, null, userId, userId);
    }

    public static ActorDescriptor service(String code, Long initiatedByUserId, Long effectiveSubjectUserId) {
        return new ActorDescriptor(ActorKind.SERVICE, null, code, initiatedByUserId, effectiveSubjectUserId);
    }

    public static ActorDescriptor model(String code, Long initiatedByUserId) {
        return new ActorDescriptor(ActorKind.MODEL, null, code, initiatedByUserId, null);
    }

    public static ActorDescriptor system(String code) {
        return new ActorDescriptor(ActorKind.SYSTEM, null, code, null, null);
    }

    public boolean isBusinessExecutionEligible() {
        return kind == ActorKind.USER;
    }

    private static void validatePositive(Long value, String field) {
        if (value != null && value < 1) {
            throw new IllegalArgumentException(field + " 必须为正数");
        }
    }
}
