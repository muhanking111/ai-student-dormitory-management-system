package com.example.dormitory.ai.erasure;

import org.springframework.stereotype.Component;

/** 首期默认无 hold；生产法务规则接入时替换此策略，API 调用方不能自行关闭 hold。 */
@Component
public class ErasureRetentionPolicy {
    public boolean retentionHold(long ownerUserId, String conversationPublicId) {
        return false;
    }
}
