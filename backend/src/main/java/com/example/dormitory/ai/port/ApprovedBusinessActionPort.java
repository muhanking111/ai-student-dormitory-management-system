package com.example.dormitory.ai.port;

import com.example.dormitory.ai.domain.model.BusinessExecutionActor;

public interface ApprovedBusinessActionPort {

    BusinessActionResult execute(BusinessExecutionActor actor, ApprovedBusinessAction action);

    record ApprovedBusinessAction(String actionType, String payloadJson, String payloadHash, String snapshotHash) {
    }

    record BusinessActionResult(String resourceType, long resourceId, String resultHash) {
    }
}
