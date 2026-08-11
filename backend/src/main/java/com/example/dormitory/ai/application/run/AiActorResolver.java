package com.example.dormitory.ai.application.run;

import cn.dev33.satoken.stp.StpUtil;
import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.config.AiProperties;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.service.RbacService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

@Component
public class AiActorResolver {

    private final RbacService rbacService;
    private final AiProperties properties;

    public AiActorResolver(RbacService rbacService, AiProperties properties) {
        this.rbacService = rbacService;
        this.properties = properties;
    }

    public AiActorContext current(String requiredPermission) {
        if (!StpUtil.isLogin()) {
            throw new AiApiException(HttpStatus.UNAUTHORIZED, "AI_AUTHENTICATION_REQUIRED",
                    "未登录或登录已过期", false);
        }
        long userId = StpUtil.getLoginIdAsLong();
        String token = StpUtil.getTokenValue();
        RbacService.AuthorizationSnapshot snapshot = rbacService.authorizationSnapshotForUser(userId);
        if (!snapshot.enabled()) {
            throw new AiApiException(HttpStatus.UNAUTHORIZED, "AI_ACCOUNT_DISABLED", "账号已停用", false);
        }
        if (requiredPermission != null && !snapshot.permissionCodes().contains(requiredPermission)) {
            throw new AiApiException(HttpStatus.FORBIDDEN, "AI_PERMISSION_DENIED", "无权执行该操作", false);
        }
        byte[] key = tokenizationKey();
        String permissionDigest = permissionDigest(snapshot.permissionCodes());
        return new AiActorContext(
                userId,
                token,
                hmacHex(key, "session-fingerprint-v1|" + token),
                properties.getTokenization().getActiveKeyVersion(),
                permissionDigest,
                snapshot.roleCodes(),
                snapshot.permissionCodes(),
                ActorDescriptor.user(userId));
    }

    public boolean stillValid(AiActorContext actor, String requiredPermission) {
        if (actor == null || actor.sessionToken() == null) return false;
        Object loginId = StpUtil.getStpLogic().getLoginIdByToken(actor.sessionToken());
        if (loginId == null || !Long.toString(actor.userId()).equals(loginId.toString())) return false;
        RbacService.AuthorizationSnapshot snapshot = rbacService.authorizationSnapshotForUser(actor.userId());
        return snapshot.enabled()
                && (requiredPermission == null || snapshot.permissionCodes().contains(requiredPermission))
                && MessageDigest.isEqual(
                        actor.permissionDigest().getBytes(StandardCharsets.US_ASCII),
                        permissionDigest(snapshot.permissionCodes()).getBytes(StandardCharsets.US_ASCII));
    }

    public String permissionDigest(List<String> permissions) {
        String canonical = permissions.stream().sorted(Comparator.naturalOrder())
                .reduce(new StringBuilder(), (builder, value) -> builder
                        .append(value.length()).append(':').append(value).append('|'), StringBuilder::append)
                .toString();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private byte[] tokenizationKey() {
        byte[] key = properties.getTokenization().getHmacKey().getBytes(StandardCharsets.UTF_8);
        if (key.length < 32 || properties.getTokenization().getActiveKeyVersion() < 1) {
            throw AiApiException.unavailable("AI_TOKENIZATION_CONTROL_UNAVAILABLE", "AI 脱敏控制面不可用");
        }
        return key;
    }

    private String hmacHex(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("HMAC-SHA256 不可用", exception);
        }
    }
}
