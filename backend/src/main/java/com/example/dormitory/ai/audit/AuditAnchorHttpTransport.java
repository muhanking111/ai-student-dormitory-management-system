package com.example.dormitory.ai.audit;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

@FunctionalInterface
public interface AuditAnchorHttpTransport {

    Response send(Request request);

    record Request(
            URI endpoint,
            String body,
            Map<String, String> headers,
            Duration timeout,
            int maximumResponseBytes) {
        public Request {
            headers = Map.copyOf(headers);
        }
    }

    record Response(int statusCode, String body, Map<String, String> headers) {
        public Response {
            headers = Map.copyOf(headers);
        }
    }
}
