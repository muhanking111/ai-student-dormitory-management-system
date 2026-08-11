package com.example.dormitory.ai.application.run;

import com.example.dormitory.ai.domain.model.ActorDescriptor;

import java.util.List;

public record AiActorContext(
        long userId,
        String sessionToken,
        String sessionFingerprintHash,
        int sessionFingerprintKeyVersion,
        String permissionDigest,
        List<String> roleCodes,
        List<String> permissionCodes,
        ActorDescriptor actor) {

    public AiActorContext {
        roleCodes = List.copyOf(roleCodes);
        permissionCodes = List.copyOf(permissionCodes);
    }
}
