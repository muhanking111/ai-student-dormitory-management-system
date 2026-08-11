package com.example.dormitory.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionAiSecurityGateTest {
    @Test
    void unsafeProductionDependenciesFailClosedButCompleteSecureConfigPasses() {
        AiProperties properties = secureProperties();
        MockEnvironment unsafe = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:mysql://localhost/db?useSSL=false")
                .withProperty("spring.datasource.username", "root")
                .withProperty("spring.data.redis.host", "localhost");
        var failures = new ProductionAiSecurityGate(unsafe, properties).validate();
        assertTrue(failures.contains("MYSQL_TLS_VERIFY_REQUIRED"));
        assertTrue(failures.contains("REDIS_TLS_REQUIRED"));
        assertTrue(failures.contains("ENCRYPTED_BACKUP_EVIDENCE_REQUIRED"));
        assertTrue(failures.contains("SECURE_SESSION_COOKIE_REQUIRED"));
        assertTrue(failures.contains("REDIS_NAMESPACE_ISOLATION_REQUIRED"));
        assertTrue(failures.contains("AI_RATE_LIMIT_REQUIRED"));
        assertTrue(failures.contains("SECRET_MANAGER_EVIDENCE_REQUIRED"));

        MockEnvironment secure = secureEnvironment();
        assertTrue(new ProductionAiSecurityGate(secure, properties).validate().isEmpty());

        properties.getAudit().setHmacKey("");
        assertTrue(new ProductionAiSecurityGate(secure, properties).validate()
                .contains("AUDIT_KEY_REQUIRED"));
        properties.getAudit().setHmacKey("a".repeat(32));

        properties.getStepUp().setHmacKey("");
        assertTrue(new ProductionAiSecurityGate(secure, properties).validate()
                .contains("STEP_UP_KEY_REQUIRED"));
        properties.getStepUp().setHmacKey("c".repeat(32));

        properties.getAudit().setPreviousHmacKey("old-audit-key-that-is-at-least-32-bytes");
        properties.getAudit().setPreviousKeyVersion(properties.getAudit().getActiveKeyVersion());
        assertTrue(new ProductionAiSecurityGate(secure, properties).validate()
                .contains("AUDIT_KEYRING_INVALID"));
        properties.getAudit().setPreviousHmacKey("");
        properties.getAudit().setPreviousKeyVersion(0);

        properties.getProvider().setActive("spring-ai");
        secure.withProperty("spring.ai.openai.base-url", "http://127.0.0.1:11434")
                .withProperty("spring.ai.openai.api-key", "secret-manager-reference")
                .withProperty("spring.ai.openai.chat.options.model", "approved-model")
                .withProperty("dormitory.ai.production.provider-endpoint-allowlist", "https://api.openai.com");
        assertTrue(new ProductionAiSecurityGate(secure, properties).validate()
                .contains("PROVIDER_ENDPOINT_NOT_ALLOWLISTED"));
        secure.withProperty("spring.ai.openai.base-url", "https://api.openai.com");
        assertTrue(!new ProductionAiSecurityGate(secure, properties).validate()
                .contains("PROVIDER_ENDPOINT_NOT_ALLOWLISTED"));
        properties.getProvider().setActive("none");

        secure.withProperty("dormitory.ai.audit.anchor.enabled", "true")
                .withProperty("dormitory.ai.audit.anchor.sink", "fake");
        assertTrue(new ProductionAiSecurityGate(secure, properties).validate()
                .contains("FAKE_AUDIT_ANCHOR_FORBIDDEN"));

        properties.getCapabilities().setKnowledge(true);
        assertTrue(new ProductionAiSecurityGate(secure, properties).validate()
                .contains("KNOWLEDGE_PRODUCTION_ADAPTERS_REQUIRED"));
    }

    @Test
    void verifyAlwaysProtectsProductionSessionsAndUsesAiChecksOnlyWhenEnabled() {
        AiProperties properties = secureProperties();
        MockEnvironment unsafeProd = new MockEnvironment().withProperty("spring.profiles.active", "prod");
        unsafeProd.setActiveProfiles("prod");

        properties.setEnabled(false);
        IllegalStateException insecureSession = assertThrows(IllegalStateException.class,
                () -> new ProductionAiSecurityGate(unsafeProd, properties).verify());
        assertTrue(insecureSession.getMessage().contains("SECURE_SESSION_COOKIE_REQUIRED"));

        properties.setEnabled(true);
        assertDoesNotThrow(() -> new ProductionAiSecurityGate(new MockEnvironment(), properties).verify());
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new ProductionAiSecurityGate(unsafeProd, properties).verify());
        assertTrue(failure.getMessage().contains("MYSQL_TLS_VERIFY_REQUIRED"));

        MockEnvironment secureProd = secureEnvironment();
        secureProd.setActiveProfiles("prod");
        assertDoesNotThrow(() -> new ProductionAiSecurityGate(secureProd, properties).run(null));

        properties.setEnabled(false);
        assertDoesNotThrow(() -> new ProductionAiSecurityGate(secureProd, properties).verify());
    }

    @Test
    void validatesAlternativeTlsPrivateNetworkAndRedisAclBranches() {
        AiProperties properties = secureProperties();
        MockEnvironment environment = secureEnvironment()
                .withProperty("spring.datasource.url",
                        "jdbc:mysql://db.internal/dorm?useSSL=true&requireSSL=true&verifyServerCertificate=true")
                .withProperty("spring.data.redis.host", "redis.public.example")
                .withProperty("spring.data.redis.username", "")
                .withProperty("spring.data.redis.password", "");

        var failures = new ProductionAiSecurityGate(environment, properties).validate();
        assertFalse(failures.contains("MYSQL_TLS_VERIFY_REQUIRED"));
        assertTrue(failures.contains("REDIS_ACL_REQUIRED"));
        assertTrue(failures.contains("REDIS_PRIVATE_NETWORK_REQUIRED"));

        environment.withProperty("spring.data.redis.username", "cache-user")
                .withProperty("spring.data.redis.password", "cache-password")
                .withProperty("dormitory.ai.production.redis-private-network-confirmed", "true");
        failures = new ProductionAiSecurityGate(environment, properties).validate();
        assertFalse(failures.contains("REDIS_ACL_REQUIRED"));
        assertFalse(failures.contains("REDIS_PRIVATE_NETWORK_REQUIRED"));
    }

    @Test
    void validatesAuditKeyringActiveAndPreviousKeyCombinations() {
        AiProperties properties = secureProperties();
        MockEnvironment secure = secureEnvironment();

        properties.getAudit().setHmacKey("");
        properties.getAudit().setHmacKeyring(Map.of(7, "k".repeat(32)));
        properties.getAudit().setActiveKeyVersion(7);
        var failures = new ProductionAiSecurityGate(secure, properties).validate();
        assertFalse(failures.contains("AUDIT_KEY_REQUIRED"));
        assertFalse(failures.contains("AUDIT_KEYRING_INVALID"));

        properties.getAudit().setHmacKeyring(Map.of(7, "short"));
        failures = new ProductionAiSecurityGate(secure, properties).validate();
        assertTrue(failures.contains("AUDIT_KEY_REQUIRED"));
        assertTrue(failures.contains("AUDIT_KEYRING_INVALID"));

        properties.getAudit().setHmacKeyring(Map.of());
        properties.getAudit().setHmacKey("a".repeat(32));
        properties.getAudit().setActiveKeyVersion(1);
        properties.getAudit().setPreviousHmacKey("p".repeat(32));
        assertTrue(new ProductionAiSecurityGate(secure, properties).validate()
                .contains("AUDIT_KEYRING_INVALID"));

        properties.getAudit().setPreviousHmacKey("");
        properties.getAudit().setPreviousKeyVersion(2);
        assertTrue(new ProductionAiSecurityGate(secure, properties).validate()
                .contains("AUDIT_KEYRING_INVALID"));

        properties.getAudit().setPreviousHmacKey("short");
        assertTrue(new ProductionAiSecurityGate(secure, properties).validate()
                .contains("AUDIT_KEYRING_INVALID"));

        properties.getAudit().setPreviousHmacKey("p".repeat(32));
        assertFalse(new ProductionAiSecurityGate(secure, properties).validate()
                .contains("AUDIT_KEYRING_INVALID"));
    }

    @Test
    void providerAndAnchorEndpointsRequireExactCanonicalHttpsAllowlistEntries() {
        AiProperties properties = secureProperties();
        MockEnvironment secure = secureEnvironment();
        properties.getProvider().setActive("fake");
        assertTrue(new ProductionAiSecurityGate(secure, properties).validate()
                .contains("FAKE_PROVIDER_FORBIDDEN"));

        properties.getProvider().setActive("spring-ai");
        secure.withProperty("spring.ai.openai.base-url", "https://API.OPENAI.COM:443/v1/")
                .withProperty("dormitory.ai.production.provider-endpoint-allowlist",
                        "invalid, https://api.openai.com/v1")
                .withProperty("spring.ai.openai.api-key", "")
                .withProperty("spring.ai.openai.chat.options.model", "?");
        var failures = new ProductionAiSecurityGate(secure, properties).validate();
        assertFalse(failures.contains("PROVIDER_ENDPOINT_NOT_ALLOWLISTED"));
        assertTrue(failures.contains("AI_PROVIDER_CREDENTIAL_REQUIRED"));
        assertTrue(failures.contains("AI_PROVIDER_MODEL_ALIAS_REQUIRED"));

        secure.withProperty("spring.ai.openai.base-url", "https://user@api.openai.com/v1?tenant=x#fragment");
        assertTrue(new ProductionAiSecurityGate(secure, properties).validate()
                .contains("PROVIDER_ENDPOINT_NOT_ALLOWLISTED"));

        properties.getProvider().setActive("none");
        secure.withProperty("dormitory.ai.audit.anchor.endpoint", "https://anchor.example.edu/v1/../roots")
                .withProperty("dormitory.ai.audit.anchor.hmac-key", "short");
        failures = new ProductionAiSecurityGate(secure, properties).validate();
        assertTrue(failures.contains("AUDIT_ANCHOR_ENDPOINT_NOT_ALLOWLISTED"));
        assertTrue(failures.contains("AUDIT_ANCHOR_KEY_REQUIRED"));
    }

    private static AiProperties secureProperties() {
        AiProperties properties = new AiProperties();
        properties.setEnabled(true);
        properties.getAudit().setHmacKey("a".repeat(32));
        properties.getTokenization().setHmacKey("b".repeat(32));
        properties.getStepUp().setHmacKey("c".repeat(32));
        return properties;
    }

    private static MockEnvironment secureEnvironment() {
        return new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:mysql://db.internal/dorm?sslMode=VERIFY_IDENTITY")
                .withProperty("spring.datasource.username", "dormitory_app")
                .withProperty("spring.data.redis.ssl.enabled", "true")
                .withProperty("spring.data.redis.username", "dormitory_cache")
                .withProperty("spring.data.redis.password", "from-secret-manager")
                .withProperty("spring.data.redis.host", "10.20.0.8")
                .withProperty("sa-token.cookie.secure", "true")
                .withProperty("dormitory.ai.production.redis-key-prefix", "dormitory:ai:prod:")
                .withProperty("dormitory.ai.rate-limit.enabled", "true")
                .withProperty("dormitory.ai.production.secret-manager-provider", "vault")
                .withProperty("dormitory.ai.production.kms-key-reference", "kms://dormitory/ai/prod")
                .withProperty("dormitory.ai.production.backup-encryption-confirmed", "true")
                .withProperty("dormitory.ai.audit.anchor.enabled", "true")
                .withProperty("dormitory.ai.audit.anchor.sink", "https-hmac")
                .withProperty("dormitory.ai.audit.anchor.endpoint", "https://anchor.example.edu/v1/roots")
                .withProperty("dormitory.ai.audit.anchor.hmac-key", "anchor-hmac-from-secret-manager-32-bytes")
                .withProperty("dormitory.ai.production.audit-anchor-endpoint-allowlist",
                        "https://anchor.example.edu/v1/roots");
    }
}
