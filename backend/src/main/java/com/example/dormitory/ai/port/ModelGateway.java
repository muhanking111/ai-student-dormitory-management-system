package com.example.dormitory.ai.port;

import com.example.dormitory.ai.domain.model.ModelCapability;
import com.example.dormitory.ai.domain.model.ModelRequest;
import com.example.dormitory.ai.domain.model.ModelResult;

import java.util.Set;

public interface ModelGateway {

    String adapterCode();

    ModelResult complete(ModelRequest request);

    AiEventStream stream(ModelRequest request);

    Set<ModelCapability> supportedCapabilities();

    default boolean supports(ModelCapability capability) {
        return supportedCapabilities().contains(capability);
    }
}
