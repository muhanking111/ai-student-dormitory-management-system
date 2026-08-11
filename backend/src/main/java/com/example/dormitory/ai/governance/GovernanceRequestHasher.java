package com.example.dormitory.ai.governance;

import com.example.dormitory.ai.approval.CanonicalJsonHasher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

public final class GovernanceRequestHasher {

    private GovernanceRequestHasher() {
    }

    public static String hash(ObjectMapper mapper, Map<String, ?> values) {
        try {
            return CanonicalJsonHasher.sha256(CanonicalJsonHasher.canonicalize(
                    mapper.writeValueAsString(new LinkedHashMap<>(values))));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("治理请求无法规范化", exception);
        }
    }

    public static String value(String value) {
        return value == null ? "" : value.trim();
    }
}
