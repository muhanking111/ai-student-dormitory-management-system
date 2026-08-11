package com.example.dormitory.ai.risk;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class RiskCase {

    private final String publicId;
    private final String riskType;
    private final String subjectToken;
    private String signalPolicyVersion;
    private RiskCaseState state;
    private int version;
    private final List<RiskCaseEvent> events = new ArrayList<>();

    private RiskCase(String publicId, String riskType, String subjectToken, String signalPolicyVersion) {
        if (blank(publicId) || blank(riskType) || blank(subjectToken) || blank(signalPolicyVersion)) {
            throw new IllegalArgumentException("风险案例合同不完整");
        }
        this.publicId = publicId;
        this.riskType = riskType;
        this.subjectToken = subjectToken;
        this.signalPolicyVersion = signalPolicyVersion;
        this.state = RiskCaseState.OPEN;
        this.version = 0;
        append("OPENED", RiskActorKind.SYSTEM, "risk-rule", "确定性规则创建案例");
    }

    private RiskCase(
            String publicId,
            String riskType,
            String subjectToken,
            String signalPolicyVersion,
            RiskCaseState state,
            int version,
            List<RiskCaseEvent> restoredEvents) {
        if (blank(publicId) || blank(riskType) || blank(subjectToken) || blank(signalPolicyVersion)
                || state == null || version < 0 || restoredEvents == null || restoredEvents.isEmpty()) {
            throw new IllegalArgumentException("持久化风险案例合同不完整");
        }
        for (int index = 0; index < restoredEvents.size(); index++) {
            RiskCaseEvent event = restoredEvents.get(index);
            if (event.sequence() != index + 1 || event.caseVersion() < 0 || event.caseVersion() > version
                    || blank(event.eventType()) || event.actorKind() == null || blank(event.actorId())
                    || blank(event.detail()) || event.occurredAt() == null) {
                throw new IllegalArgumentException("持久化风险事件序列不合法");
            }
        }
        this.publicId = publicId;
        this.riskType = riskType;
        this.subjectToken = subjectToken;
        this.signalPolicyVersion = signalPolicyVersion;
        this.state = state;
        this.version = version;
        this.events.addAll(restoredEvents);
    }

    public static RiskCase open(String publicId, String riskType, String subjectToken, String signalPolicyVersion) {
        return new RiskCase(publicId, riskType, subjectToken, signalPolicyVersion);
    }

    public static RiskCase restore(
            String publicId,
            String riskType,
            String subjectToken,
            String signalPolicyVersion,
            RiskCaseState state,
            int version,
            List<RiskCaseEvent> events) {
        return new RiskCase(publicId, riskType, subjectToken, signalPolicyVersion, state, version, List.copyOf(events));
    }

    public synchronized void acknowledge(int expectedVersion, String actorId, String detail) {
        requireVersion(expectedVersion);
        requireState(RiskCaseState.OPEN);
        transition(RiskCaseState.ACKNOWLEDGED, RiskActorKind.USER, actorId, detail, "ACKNOWLEDGED");
    }

    public synchronized void resolve(int expectedVersion, String actorId, String detail) {
        requireVersion(expectedVersion);
        if (state != RiskCaseState.OPEN && state != RiskCaseState.ACKNOWLEDGED) {
            throw new RiskCaseConflictException("AI_RISK_CASE_STATE_CONFLICT", "只有活动风险可解决");
        }
        transition(RiskCaseState.RESOLVED, RiskActorKind.USER, actorId, detail, "RESOLVED");
    }

    public synchronized void dismiss(int expectedVersion, String actorId, String detail) {
        requireVersion(expectedVersion);
        if (state != RiskCaseState.OPEN && state != RiskCaseState.ACKNOWLEDGED) {
            throw new RiskCaseConflictException("AI_RISK_CASE_STATE_CONFLICT", "只有活动风险可驳回");
        }
        transition(RiskCaseState.DISMISSED, RiskActorKind.USER, actorId, detail, "DISMISSED");
    }

    public synchronized void reopenFromRule(int expectedVersion, String newPolicyVersion, String detail) {
        requireVersion(expectedVersion);
        if (state != RiskCaseState.RESOLVED && state != RiskCaseState.DISMISSED) {
            throw new RiskCaseConflictException("AI_RISK_CASE_STATE_CONFLICT", "只有终态风险可由新规则重新打开");
        }
        if (blank(newPolicyVersion)) throw new IllegalArgumentException("规则版本不能为空");
        signalPolicyVersion = newPolicyVersion;
        transition(RiskCaseState.OPEN, RiskActorKind.SYSTEM, "risk-rule", detail, "REOPENED");
    }

    public synchronized void transitionByActor(
            RiskCaseState target,
            int expectedVersion,
            RiskActorKind actorKind,
            String actorId,
            String detail) {
        if (actorKind != RiskActorKind.USER) {
            throw new IllegalArgumentException("模型或服务主体不能覆盖人工风险结论");
        }
        switch (target) {
            case ACKNOWLEDGED -> acknowledge(expectedVersion, actorId, detail);
            case RESOLVED -> resolve(expectedVersion, actorId, detail);
            case DISMISSED -> dismiss(expectedVersion, actorId, detail);
            default -> throw new IllegalArgumentException("人工不能直接重开风险");
        }
    }

    private void transition(RiskCaseState target, RiskActorKind kind, String actorId, String detail, String type) {
        if (blank(actorId) || blank(detail)) throw new IllegalArgumentException("风险事件 actor/detail 不能为空");
        state = target;
        version++;
        append(type, kind, actorId, detail);
    }

    private void append(String type, RiskActorKind kind, String actorId, String detail) {
        events.add(new RiskCaseEvent(events.size() + 1, type, kind, actorId, detail, version, Instant.now()));
    }

    private void requireVersion(int expectedVersion) {
        if (version != expectedVersion) throw new RiskCaseConflictException(
                "AI_RISK_CASE_VERSION_CONFLICT", "风险案例版本冲突");
    }

    private void requireState(RiskCaseState required) {
        if (state != required) throw new RiskCaseConflictException(
                "AI_RISK_CASE_STATE_CONFLICT", "风险案例前态不合法");
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public String publicId() {
        return publicId;
    }

    public String riskType() {
        return riskType;
    }

    public String subjectToken() {
        return subjectToken;
    }

    public String signalPolicyVersion() {
        return signalPolicyVersion;
    }

    public synchronized RiskCaseState state() {
        return state;
    }

    public synchronized int version() {
        return version;
    }

    public synchronized List<RiskCaseEvent> events() {
        return List.copyOf(events);
    }

    public record RiskCaseEvent(
            int sequence,
            String eventType,
            RiskActorKind actorKind,
            String actorId,
            String detail,
            int caseVersion,
            Instant occurredAt) {
    }
}
