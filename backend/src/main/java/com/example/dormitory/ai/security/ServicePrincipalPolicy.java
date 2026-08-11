package com.example.dormitory.ai.security;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ServicePrincipalPolicy {

    public long requireBusinessExecutionUser(ActorDescriptor actor) {
        if (actor == null || !actor.isBusinessExecutionEligible() || actor.actorUserId() == null) {
            throw new AiApiException(HttpStatus.FORBIDDEN, "AI_SERVICE_ACTOR_WRITE_FORBIDDEN",
                    "后台服务主体不能执行用户业务写操作", false);
        }
        return actor.actorUserId();
    }
}
