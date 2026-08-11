package com.example.dormitory.ai.application.run;

import com.example.dormitory.ai.domain.model.ModelRequest;
import com.example.dormitory.ai.port.AiEventStream;

public interface AiModelRuntimePort {

    String providerCode();

    String modelAlias();

    AiEventStream stream(ModelRequest request);

    long invocationCount();
}
