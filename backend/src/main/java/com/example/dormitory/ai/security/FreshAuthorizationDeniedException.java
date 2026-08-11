package com.example.dormitory.ai.security;

import com.example.dormitory.ai.api.AiApiException;

/** 标记 fresh 授权拒绝；拒绝审计提交后，调用方再抛出对外隐藏的 404。 */
public final class FreshAuthorizationDeniedException extends RuntimeException {

    private final AiApiException hiddenFailure;

    public FreshAuthorizationDeniedException(AiApiException hiddenFailure) {
        super("fresh authorization denied");
        this.hiddenFailure = java.util.Objects.requireNonNull(hiddenFailure);
    }

    public AiApiException hiddenFailure() {
        return hiddenFailure;
    }
}
