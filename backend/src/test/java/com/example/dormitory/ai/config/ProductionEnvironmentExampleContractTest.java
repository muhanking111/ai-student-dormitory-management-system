package com.example.dormitory.ai.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionEnvironmentExampleContractTest {
    private static final Set<String> PRODUCTION_AI_SECURITY_GATE_KEYS = Set.of(
            "DB_URL",
            "DB_USERNAME",
            "DB_PASSWORD",
            "REDIS_HOST",
            "REDIS_USERNAME",
            "REDIS_PASSWORD",
            "REDIS_SSL_ENABLED",
            "SESSION_COOKIE_SECURE",
            "AI_ENABLED",
            "AI_CAPABILITY_KNOWLEDGE_ENABLED",
            "AI_PROVIDER_ACTIVE",
            "AI_MODEL_BASE_URL",
            "AI_MODEL_API_KEY",
            "AI_MODEL_NAME",
            "AI_PROVIDER_MODEL_ALIAS",
            "AI_PROVIDER_ENDPOINT_ALLOWLIST",
            "AI_AUDIT_HMAC_KEY",
            "AI_AUDIT_HMAC_ACTIVE_VERSION",
            "AI_AUDIT_PREVIOUS_HMAC_KEY",
            "AI_AUDIT_PREVIOUS_HMAC_VERSION",
            "AI_TOKENIZATION_HMAC_KEY",
            "AI_TOKENIZATION_HMAC_ACTIVE_VERSION",
            "AI_STEP_UP_HMAC_KEY",
            "AI_STEP_UP_HMAC_ACTIVE_VERSION",
            "AI_STEP_UP_PREVIOUS_HMAC_KEY",
            "AI_STEP_UP_PREVIOUS_HMAC_VERSION",
            "AI_AUDIT_ANCHOR_ENABLED",
            "AI_AUDIT_ANCHOR_SINK",
            "AI_AUDIT_ANCHOR_ENDPOINT",
            "AI_AUDIT_ANCHOR_ENDPOINT_ALLOWLIST",
            "AI_AUDIT_ANCHOR_HMAC_KEY",
            "AI_REDIS_KEY_PREFIX",
            "AI_RATE_LIMIT_ENABLED",
            "AI_SECRET_MANAGER_PROVIDER",
            "AI_KMS_KEY_REFERENCE",
            "AI_REDIS_PRIVATE_NETWORK_CONFIRMED",
            "AI_BACKUP_ENCRYPTION_CONFIRMED"
    );

    @Test
    void environmentExampleDocumentsEveryProductionSecurityGateInput() throws IOException {
        Path environmentExample = Path.of("..", ".env.example").toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(environmentExample),
                () -> "Missing repository environment example: " + environmentExample);

        Set<String> documentedKeys = new TreeSet<>();
        for (String line : Files.readAllLines(environmentExample)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                continue;
            }
            documentedKeys.add(trimmed.substring(0, trimmed.indexOf('=')));
        }

        Set<String> missingKeys = new TreeSet<>(PRODUCTION_AI_SECURITY_GATE_KEYS);
        missingKeys.removeAll(documentedKeys);
        assertEquals(Set.of(), missingKeys,
                "Production security gate inputs missing from .env.example");
    }
}
