package com.example.dormitory.ai.domain.model;

public final class BusinessExecutionActor {

    private final ActorDescriptor descriptor;

    private BusinessExecutionActor(ActorDescriptor descriptor) {
        this.descriptor = descriptor;
    }

    public static BusinessExecutionActor from(ActorDescriptor descriptor) {
        if (descriptor == null || !descriptor.isBusinessExecutionEligible()) {
            throw new IllegalArgumentException("只有真实 USER actor 可以执行业务动作");
        }
        return new BusinessExecutionActor(descriptor);
    }

    public long userId() {
        return descriptor.actorUserId();
    }

    public ActorDescriptor descriptor() {
        return descriptor;
    }
}
