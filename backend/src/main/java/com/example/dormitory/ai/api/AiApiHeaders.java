package com.example.dormitory.ai.api;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;

public final class AiApiHeaders {

    public static final String VARY_VALUE = "Authorization, Cookie, Origin";

    private AiApiHeaders() {
    }

    public static HttpHeaders privateNoStore() {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl(CacheControl.noStore().cachePrivate());
        headers.set(HttpHeaders.VARY, VARY_VALUE);
        return headers;
    }
}
