package com.example.dormitory.ai.infrastructure.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

public final class AiToolCallIntegrity {

    private static final String VERSION = "tool-call-audit.v1";

    private AiToolCallIntegrity() {
    }

    public static String payloadHash(ToolFact fact) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, VERSION);
        append(canonical, fact.publicId());
        append(canonical, fact.runPublicId());
        append(canonical, fact.sequenceNo());
        append(canonical, fact.toolName());
        append(canonical, fact.toolVersion());
        append(canonical, sha256(fact.requestRedacted()));
        append(canonical, fact.responseRedacted() == null ? null : sha256(fact.responseRedacted()));
        append(canonical, fact.requiredPermissionsJson());
        append(canonical, fact.authorizationDecision());
        append(canonical, fact.state());
        append(canonical, fact.errorCode());
        append(canonical, fact.actorUserId());
        append(canonical, fact.updatedActorUserId());
        append(canonical, normalize(fact.startedAt()));
        append(canonical, normalize(fact.finishedAt()));
        return sha256(canonical.toString());
    }

    public static Instant normalize(Instant value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MICROS);
    }

    public static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) return left == null && right == null;
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算工具审计 SHA-256", exception);
        }
    }

    private static void append(StringBuilder target, Object raw) {
        String value = raw == null ? "" : raw.toString();
        target.append(value.length()).append(':').append(value).append('|');
    }

    public record ToolFact(
            String publicId,
            String runPublicId,
            long sequenceNo,
            String toolName,
            String toolVersion,
            String requestRedacted,
            String responseRedacted,
            String requiredPermissionsJson,
            String authorizationDecision,
            String state,
            String errorCode,
            long actorUserId,
            long updatedActorUserId,
            Instant startedAt,
            Instant finishedAt) {
    }
}
