package com.example.dormitory.ai.port;

import com.example.dormitory.ai.domain.model.BusinessActorScope;

import java.time.Instant;
import java.util.Map;

public interface BusinessReadFacade {

    BusinessReadResult read(BusinessActorScope scope, BusinessReadRequest request);

    record BusinessReadRequest(String queryId, Map<String, String> parameters) {
        public BusinessReadRequest {
            parameters = Map.copyOf(parameters);
        }
    }

    record BusinessReadResult(String schemaVersion, String payloadJson, Instant asOf) {
    }
}
