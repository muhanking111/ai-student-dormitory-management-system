package com.example.dormitory.ai.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 生产环境始终校验会话 Cookie；启用 AI 时再校验其 MySQL/Redis/密钥/备份配置。 */
@Component
@Order(25)
public class ProductionAiSecurityGate implements ApplicationRunner {
    private final Environment environment;
    private final AiProperties properties;

    public ProductionAiSecurityGate(Environment environment, AiProperties properties) {
        this.environment = environment;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        verify();
    }

    public void verify() {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) return;
        List<String> failures = properties.isEnabled() ? validate() : validateSessionSecurity();
        if (!failures.isEmpty()) {
            throw new IllegalStateException("生产 AI 安全配置不满足启动门: " + String.join(", ", failures));
        }
    }

    private List<String> validateSessionSecurity() {
        List<String> failures = new ArrayList<>();
        if (!Boolean.parseBoolean(value("sa-token.cookie.secure"))) {
            failures.add("SECURE_SESSION_COOKIE_REQUIRED");
        }
        return List.copyOf(failures);
    }

    public List<String> validate() {
        List<String> failures = new ArrayList<>();
        String dbUrl = value("spring.datasource.url").toLowerCase(Locale.ROOT);
        String dbUser = value("spring.datasource.username");
        if (!(dbUrl.contains("sslmode=verify_identity")
                || dbUrl.contains("usessl=true") && dbUrl.contains("requiressl=true")
                && dbUrl.contains("verifyservercertificate=true"))) {
            failures.add("MYSQL_TLS_VERIFY_REQUIRED");
        }
        if (dbUser.isBlank() || "root".equalsIgnoreCase(dbUser)) failures.add("MYSQL_LEAST_PRIVILEGE_USER_REQUIRED");
        if (!Boolean.parseBoolean(value("spring.data.redis.ssl.enabled"))) failures.add("REDIS_TLS_REQUIRED");
        if (value("spring.data.redis.username").isBlank() || value("spring.data.redis.password").isBlank()) {
            failures.add("REDIS_ACL_REQUIRED");
        }
        if (!isPrivateHost(value("spring.data.redis.host"))
                && !Boolean.parseBoolean(value("dormitory.ai.production.redis-private-network-confirmed"))) {
            failures.add("REDIS_PRIVATE_NETWORK_REQUIRED");
        }
        if (!Boolean.parseBoolean(value("dormitory.ai.production.backup-encryption-confirmed"))) {
            failures.add("ENCRYPTED_BACKUP_EVIDENCE_REQUIRED");
        }
        if (!Boolean.parseBoolean(value("sa-token.cookie.secure"))) {
            failures.add("SECURE_SESSION_COOKIE_REQUIRED");
        }
        String redisKeyPrefix = value("dormitory.ai.production.redis-key-prefix");
        if (!redisKeyPrefix.matches("[a-zA-Z0-9:_-]{8,128}")
                || redisKeyPrefix.toLowerCase(Locale.ROOT).startsWith("satoken:")) {
            failures.add("REDIS_NAMESPACE_ISOLATION_REQUIRED");
        }
        if (!Boolean.parseBoolean(value("dormitory.ai.rate-limit.enabled"))) {
            failures.add("AI_RATE_LIMIT_REQUIRED");
        }
        String secretManagerProvider = value("dormitory.ai.production.secret-manager-provider");
        String kmsKeyReference = value("dormitory.ai.production.kms-key-reference");
        if (!secretManagerProvider.matches("[a-z0-9._-]{2,64}")
                || !kmsKeyReference.matches("[a-zA-Z0-9:/._-]{8,256}")) {
            failures.add("SECRET_MANAGER_EVIDENCE_REQUIRED");
        }
        AiProperties.Audit audit = properties.getAudit();
        java.util.Map<Integer, String> retainedAuditKeys = audit.getHmacKeyring();
        String activeAuditKey = retainedAuditKeys.getOrDefault(
                audit.getActiveKeyVersion(), audit.getHmacKey());
        if (activeAuditKey.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32
                || audit.getActiveKeyVersion() < 1) {
            failures.add("AUDIT_KEY_REQUIRED");
        }
        if (retainedAuditKeys.entrySet().stream().anyMatch(entry -> entry.getKey() == null || entry.getKey() < 1
                || entry.getValue() == null
                || entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32)) {
            failures.add("AUDIT_KEYRING_INVALID");
        }
        boolean previousAuditKeyConfigured = !audit.getPreviousHmacKey().isBlank();
        boolean previousAuditVersionConfigured = audit.getPreviousKeyVersion() > 0;
        if (previousAuditKeyConfigured != previousAuditVersionConfigured
                || previousAuditKeyConfigured
                && (audit.getPreviousHmacKey().getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32
                || audit.getPreviousKeyVersion() == audit.getActiveKeyVersion())) {
            failures.add("AUDIT_KEYRING_INVALID");
        }
        if (properties.getTokenization().getHmacKey().getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            failures.add("TOKENIZATION_KEY_REQUIRED");
        }
        if (properties.getStepUp().getHmacKey().getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            failures.add("STEP_UP_KEY_REQUIRED");
        }
        if ("fake".equals(properties.getProvider().getActive())) failures.add("FAKE_PROVIDER_FORBIDDEN");
        if ("spring-ai".equals(properties.getProvider().getActive())) {
            if (!providerEndpointAllowed(value("spring.ai.openai.base-url"),
                    value("dormitory.ai.production.provider-endpoint-allowlist"))) {
                failures.add("PROVIDER_ENDPOINT_NOT_ALLOWLISTED");
            }
            if (value("spring.ai.openai.api-key").isBlank()) failures.add("AI_PROVIDER_CREDENTIAL_REQUIRED");
            if (!value("spring.ai.openai.chat.options.model").matches("[a-zA-Z0-9][a-zA-Z0-9._:/-]{1,127}")) {
                failures.add("AI_PROVIDER_MODEL_ALIAS_REQUIRED");
            }
        }
        boolean auditAnchorEnabled = Boolean.parseBoolean(value("dormitory.ai.audit.anchor.enabled"));
        String auditAnchorSink = value("dormitory.ai.audit.anchor.sink");
        if (!auditAnchorEnabled) {
            failures.add("AUDIT_ANCHOR_REQUIRED");
        } else if (!"https-hmac".equalsIgnoreCase(auditAnchorSink)) {
            failures.add("FAKE_AUDIT_ANCHOR_FORBIDDEN");
        } else {
            if (!providerEndpointAllowed(value("dormitory.ai.audit.anchor.endpoint"),
                    value("dormitory.ai.production.audit-anchor-endpoint-allowlist"))) {
                failures.add("AUDIT_ANCHOR_ENDPOINT_NOT_ALLOWLISTED");
            }
            if (value("dormitory.ai.audit.anchor.hmac-key")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
                failures.add("AUDIT_ANCHOR_KEY_REQUIRED");
            }
        }
        if (properties.getCapabilities().isKnowledge()) {
            // 首期只落盘了受控 Fake object/vector adapter；生产 adapter 通过独立 P0.2 评审前禁止误启用。
            failures.add("KNOWLEDGE_PRODUCTION_ADAPTERS_REQUIRED");
        }
        return List.copyOf(failures);
    }

    private String value(String key) {
        return environment.getProperty(key, "").trim();
    }

    private boolean isPrivateHost(String host) {
        if (host.isBlank() || "localhost".equalsIgnoreCase(host)) return false;
        try {
            InetAddress address = InetAddress.getByName(host);
            return address.isSiteLocalAddress() && !address.isLoopbackAddress();
        } catch (Exception exception) {
            return false;
        }
    }

    private boolean providerEndpointAllowed(String endpoint, String configuredAllowlist) {
        String canonicalEndpoint = canonicalHttpsEndpoint(endpoint);
        if (canonicalEndpoint == null || configuredAllowlist.isBlank()) return false;
        return java.util.Arrays.stream(configuredAllowlist.split(","))
                .map(String::trim)
                .map(this::canonicalHttpsEndpoint)
                .anyMatch(canonicalEndpoint::equals);
    }

    private String canonicalHttpsEndpoint(String value) {
        try {
            URI uri = URI.create(value).normalize();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                return null;
            }
            int port = uri.getPort() < 0 ? 443 : uri.getPort();
            String path = uri.getPath() == null || uri.getPath().isBlank() ? "" : uri.getPath();
            while (path.endsWith("/") && !path.isEmpty()) path = path.substring(0, path.length() - 1);
            if (path.contains("..")) return null;
            return "https://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port + path;
        } catch (RuntimeException invalid) {
            return null;
        }
    }
}
