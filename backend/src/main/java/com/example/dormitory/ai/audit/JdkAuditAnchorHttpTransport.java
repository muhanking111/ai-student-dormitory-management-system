package com.example.dormitory.ai.audit;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** 固定 endpoint 的窄 HTTP adapter；禁用 redirect，响应体有硬上限。 */
public final class JdkAuditAnchorHttpTransport implements AuditAnchorHttpTransport {

    private final HttpClient client;

    public JdkAuditAnchorHttpTransport() {
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build());
    }

    JdkAuditAnchorHttpTransport(HttpClient client) {
        this.client = java.util.Objects.requireNonNull(client);
    }

    @Override
    public Response send(Request request) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(request.endpoint())
                    .timeout(request.timeout())
                    .POST(HttpRequest.BodyPublishers.ofString(request.body(), StandardCharsets.UTF_8));
            request.headers().forEach(builder::header);
            HttpResponse<InputStream> response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (InputStream input = response.body()) {
                bytes = input.readNBytes(request.maximumResponseBytes() + 1);
            }
            if (bytes.length > request.maximumResponseBytes()) {
                throw new IllegalStateException("审计外锚响应超过上限");
            }
            Map<String, String> headers = new LinkedHashMap<>();
            response.headers().map().forEach((name, values) -> {
                if (!values.isEmpty()) headers.put(name.toLowerCase(Locale.ROOT), values.getFirst());
            });
            return new Response(response.statusCode(), new String(bytes, StandardCharsets.UTF_8), headers);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AuditAnchorUnavailableException(interrupted);
        } catch (AuditAnchorUnavailableException unavailable) {
            throw unavailable;
        } catch (Exception failure) {
            throw new AuditAnchorUnavailableException(failure);
        }
    }
}
