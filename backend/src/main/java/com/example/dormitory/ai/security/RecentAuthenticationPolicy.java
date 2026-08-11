package com.example.dormitory.ai.security;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.config.AiProperties;
import com.example.dormitory.ai.port.SessionValidityPort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class RecentAuthenticationPolicy {

    private static final Set<String> ALLOWED_ACTIONS = Set.of(
            "AUDIT_CONTENT_READ",
            "PROMPT_ACTIVATE",
            "MODEL_ACTIVATE",
            "MODEL_ALIAS_ACTIVATE",
            "CONFIG_ACTIVATE",
            "KILL_SWITCH_CLEAR",
            "KNOWLEDGE_PUBLIC_APPROVE",
            "PROPOSAL_APPROVE",
            "PROPOSAL_RECONFIRM");

    private final StepUpGrantRepository repository;
    private final StepUpProofCrypto crypto;
    private final SessionValidityPort sessionValidity;
    private final Clock clock;
    private final Duration proofTtl;

    @Autowired
    public RecentAuthenticationPolicy(
            StepUpGrantRepository repository,
            StepUpProofCrypto crypto,
            SessionValidityPort sessionValidity,
            AiProperties properties) {
        this(repository, crypto, sessionValidity, Clock.systemUTC(), properties.getStepUp().getProofTtl());
    }

    RecentAuthenticationPolicy(
            StepUpGrantRepository repository,
            StepUpProofCrypto crypto,
            SessionValidityPort sessionValidity,
            Clock clock,
            Duration proofTtl) {
        this.repository = repository;
        this.crypto = crypto;
        this.sessionValidity = sessionValidity;
        this.clock = clock;
        if (proofTtl == null || proofTtl.isNegative() || proofTtl.isZero()
                || proofTtl.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("step-up proof TTL 必须在 0 到 5 分钟内");
        }
        this.proofTtl = proofTtl;
    }

    public IssuedProof issue(
            AuthenticatedRunContext context,
            String actionCode,
            String resourcePublicId,
            String requestHash) {
        long userId = requireUser(context);
        String action = normalizeAction(actionCode);
        String resource = normalizeResource(resourcePublicId);
        String request = normalizeHash(requestHash);
        requireValidSession(context, userId);
        Instant authenticatedAt = clock.instant();
        Instant expiresAt = authenticatedAt.plus(proofTtl);
        StepUpProofCrypto.GeneratedProof generated = crypto.generate();
        String publicId = UUID.randomUUID().toString();
        repository.create(new StepUpGrantRepository.NewGrant(
                publicId, generated.hmac(), generated.keyVersion(), context.sessionFingerprintHash(),
                context.sessionFingerprintKeyVersion(), userId, action, resource, request, "PASSWORD",
                authenticatedAt, expiresAt));
        return new IssuedProof(publicId, generated.proof(), authenticatedAt, expiresAt, "PASSWORD");
    }

    public void consume(
            String proof,
            AuthenticatedRunContext context,
            String actionCode,
            String resourcePublicId,
            String requestHash) {
        long userId = requireUser(context);
        requireValidSession(context, userId);
        StepUpProofCrypto.ParsedProof parsed = crypto.parseAndHash(proof);
        boolean consumed = repository.consume(new StepUpGrantRepository.ConsumeGrant(
                parsed.hmac(), parsed.keyVersion(), context.sessionFingerprintHash(),
                context.sessionFingerprintKeyVersion(), userId, normalizeAction(actionCode),
                normalizeResource(resourcePublicId), normalizeHash(requestHash), clock.instant()));
        if (!consumed) throw invalidProof();
    }

    private void requireValidSession(AuthenticatedRunContext context, long userId) {
        SessionValidityPort.SessionValidity validity = sessionValidity.check(new SessionValidityPort.SessionReference(
                userId, context.sessionFingerprintHash(), context.sessionFingerprintKeyVersion(),
                context.permissionDigest()));
        if (!validity.valid() || !validity.accountEnabled()) {
            throw new AiApiException(HttpStatus.UNAUTHORIZED, "AI_STEP_UP_SESSION_INVALID",
                    "当前会话、账号或授权快照已失效", false);
        }
    }

    private static long requireUser(AuthenticatedRunContext context) {
        if (context == null) throw userActorRequired();
        return context.requireUserActorId();
    }

    private static String normalizeAction(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_ACTIONS.contains(normalized)) {
            throw new AiApiException(HttpStatus.BAD_REQUEST, "AI_STEP_UP_ACTION_UNSUPPORTED",
                    "不支持的 step-up 操作", false);
        }
        return normalized;
    }

    private static String normalizeResource(String value) {
        if (value == null || value.isBlank()) return null;
        try { return UUID.fromString(value.trim()).toString(); }
        catch (IllegalArgumentException exception) {
            throw new AiApiException(HttpStatus.BAD_REQUEST, "AI_STEP_UP_RESOURCE_INVALID",
                    "step-up 资源标识不合法", false);
        }
    }

    private static String normalizeHash(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new AiApiException(HttpStatus.BAD_REQUEST, "AI_STEP_UP_REQUEST_HASH_INVALID",
                    "step-up 请求摘要不合法", false);
        }
        return normalized;
    }

    static AiApiException userActorRequired() {
        return new AiApiException(HttpStatus.FORBIDDEN, "AI_STEP_UP_USER_ACTOR_REQUIRED",
                "step-up 仅允许当前真实用户执行", false);
    }

    private static AiApiException invalidProof() {
        return new AiApiException(HttpStatus.FORBIDDEN, "AI_STEP_UP_PROOF_INVALID",
                "step-up 证明无效、过期或已使用", false);
    }

    public record IssuedProof(
            String grantPublicId,
            String proof,
            Instant authenticatedAt,
            Instant expiresAt,
            String authMethod) { }
}
