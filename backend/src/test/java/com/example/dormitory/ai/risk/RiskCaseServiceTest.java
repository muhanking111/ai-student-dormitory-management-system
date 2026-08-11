package com.example.dormitory.ai.risk;

import com.example.dormitory.ai.approval.IdempotencyConflictException;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.BusinessExecutionActor;
import com.example.dormitory.ai.security.PiiRedactionService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskCaseServiceTest {

    private final RiskCaseService service = new RiskCaseService(
            new InMemoryRiskCaseRepository(),
            new PiiRedactionService("risk-case-test-key".getBytes(StandardCharsets.UTF_8), "test-v1"));
    private final BusinessExecutionActor reviewer = BusinessExecutionActor.from(ActorDescriptor.user(7));

    @Test
    void ingestsSeparateSignalBusinessAndDeterministicExplanationLayersWithoutFakeConfidence() {
        RiskSignal signal = signal("repair-backlog-v1");
        RiskCaseService.CaseView first = service.ingest(signal);
        RiskCaseService.CaseView replay = service.ingest(signal);
        assertEquals(first.publicId(), replay.publicId());
        assertEquals(1, service.list(null, 1, 10).total());

        assertEquals("repair-backlog-v1", first.signalEvidence().policyVersion());
        assertEquals(96, first.signalEvidence().facts().get("ageHours"));
        assertEquals("REPAIR_ORDER", first.businessSnapshot().subjectType());
        assertEquals("subject-token-42", first.businessSnapshot().subjectToken());
        assertEquals(RiskExplanationBasis.DETERMINISTIC_DEGRADED, first.explanationEvidence().basis());
        assertTrue(first.explanationEvidence().degraded());
        assertEquals(null, first.explanationEvidence().runPublicId());
        assertEquals(null, first.explanationEvidence().confidence());
        assertEquals(null, first.assigneeUserId());
        assertEquals(null, first.dueAt());

        int versionBefore = first.version();
        assertThrows(IllegalArgumentException.class, () -> service.recordExplanation(
                first.publicId(), "未绑定受控 run 的解释", ActorDescriptor.model("fake-model", 7L)));
        RiskCaseService.CaseView explained = service.recordModelExplanation(
                first.publicId(), "建议联系 13800138000 并优先处理", ActorDescriptor.model("fake-model", 7L),
                91L, "00000000-0000-0000-0000-000000000091");
        assertFalse(explained.explanation().contains("13800138000"));
        assertTrue(explained.explanation().contains("[PHONE:"));
        assertEquals(RiskExplanationBasis.MODEL, explained.explanationEvidence().basis());
        assertEquals("00000000-0000-0000-0000-000000000091", explained.explanationEvidence().runPublicId());
        assertEquals(null, explained.explanationEvidence().confidence());
        assertEquals(RiskCaseState.OPEN, explained.state());
        assertEquals(versionBefore, explained.version());
        assertEquals(1, explained.events().size());

        assertThrows(IllegalArgumentException.class,
                () -> service.recordModelExplanation(first.publicId(), "解释", ActorDescriptor.user(7),
                        91L, "00000000-0000-0000-0000-000000000091"));
    }

    @Test
    void humanTransitionsArePermissionedVersionedAndIdempotentAndNewRuleCanReopen() {
        RiskCaseService.CaseView opened = service.ingest(signal("repair-backlog-v1"));
        RiskCaseService.CaseView acknowledged = service.transition(
                opened.publicId(), RiskCaseState.ACKNOWLEDGED, opened.version(), reviewer,
                Set.of("ai:risk:manage", "ai:risk:read"), "已人工确认维修积压",
                "same-key", "request-a", Instant.parse("2026-07-14T08:00:00Z"));
        RiskCaseService.CaseView replay = service.transition(
                opened.publicId(), RiskCaseState.ACKNOWLEDGED, opened.version(), reviewer,
                Set.of("ai:risk:manage", "ai:risk:read"), "已人工确认维修积压",
                "same-key", "request-a", Instant.parse("2026-07-14T08:00:00Z"));
        assertEquals(acknowledged.version(), replay.version());
        assertEquals(7L, acknowledged.assigneeUserId());
        assertEquals(Instant.parse("2026-07-14T08:00:00Z"), acknowledged.dueAt());
        assertEquals(2, replay.events().size());
        assertThrows(IdempotencyConflictException.class, () -> service.transition(
                opened.publicId(), RiskCaseState.ACKNOWLEDGED, opened.version(), reviewer,
                Set.of("ai:risk:manage", "ai:risk:read"), "不同内容", "same-key", "request-b"));

        RiskCaseService.CaseView resolved = service.transition(
                opened.publicId(), RiskCaseState.RESOLVED, acknowledged.version(), reviewer,
                Set.of("ai:risk:manage", "ai:risk:read"), "已完成维修并复核",
                "resolve-key", "request-resolve");
        assertEquals(RiskCaseState.RESOLVED, resolved.state());
        assertThrows(SecurityException.class, () -> service.transition(
                opened.publicId(), RiskCaseState.DISMISSED, resolved.version(), reviewer,
                Set.of("ai:risk:read"), "无权限", "deny", "deny-request"));

        RiskCaseService.CaseView reopened = service.ingest(signal("repair-backlog-v2"));
        assertEquals(opened.publicId(), reopened.publicId());
        assertEquals(RiskCaseState.OPEN, reopened.state());
        assertEquals("repair-backlog-v2", reopened.signalPolicyVersion());
    }

    @Test
    void listAndDirectReadApplyTheSameBottomResourceScope() {
        RiskCaseService.CaseView assigned = service.ingest(signal(42L, "subject-token-42"));
        RiskCaseService.CaseView foreign = service.ingest(signal(99L, "subject-token-99"));
        RiskScanScope scope = RiskScanScope.restricted(7L, Set.of("repair:read"),
                Set.of(42L), Set.of(), Set.of(), Instant.now());

        RiskCaseService.PageResult visible = service.listAuthorized(null, Set.of(), 1, 10, scope);

        assertEquals(1, visible.total());
        assertEquals(assigned.publicId(), visible.records().getFirst().publicId());
        assertEquals(assigned.publicId(), service.get(assigned.publicId(), scope).publicId());
        assertThrows(IllegalArgumentException.class, () -> service.get(foreign.publicId(), scope));
    }

    @Test
    void resourceCheckInAggregateRequiresFullCheckInScopeForListAndDetail() {
        RiskCaseService.CaseView aggregate = service.ingest(new RiskSignal(
                "resource-checkin-inconsistency", "DORMITORY", 42L, "subject-token-dormitory-42", "HIGH",
                "resource-checkin-v1", Map.of("configuredBeds", 4, "actualBeds", 3),
                Instant.parse("2026-07-11T08:00:00Z")));
        RiskScanScope restricted = RiskScanScope.restricted(7L, Set.of("dormitory:read", "checkin:read"),
                Set.of(), Set.of(42L), Set.of(), Set.of(900L), Instant.now());

        assertEquals(0, service.listAuthorized(null, Set.of(), 1, 10, restricted).total());
        assertThrows(IllegalArgumentException.class, () -> service.get(aggregate.publicId(), restricted));

        RiskScanScope full = RiskScanScope.full(7L, Set.of("dormitory:read", "checkin:read"), Instant.now());
        assertEquals(aggregate.publicId(), service.get(aggregate.publicId(), full).publicId());
    }

    @Test
    void transitionRejectsWhenFreshScopeChangesAfterInitialVisibilityWithoutAppendingEvent() {
        RiskCaseService.CaseView opened = service.ingest(signal(42L, "subject-token-42"));
        RiskScanScope initiallyVisible = RiskScanScope.restricted(7L, Set.of("repair:read"),
                Set.of(42L), Set.of(), Set.of(), Instant.now());
        assertEquals(opened.publicId(), service.get(opened.publicId(), initiallyVisible).publicId());

        RiskScanScope revoked = RiskScanScope.restricted(7L, Set.of("repair:read"),
                Set.of(), Set.of(), Set.of(), Instant.now());
        assertThrows(RiskCaseService.RiskAccessDeniedException.class, () -> service.transition(
                opened.publicId(), RiskCaseState.ACKNOWLEDGED, opened.version(), reviewer,
                Set.of("ai:risk:manage", "ai:risk:read"), "范围已撤销，不应处置",
                "fresh-scope-race", "fresh-scope-race-request", null,
                () -> transitionAuthorization(revoked)));

        RiskCaseService.CaseView unchanged = service.get(opened.publicId());
        assertEquals(RiskCaseState.OPEN, unchanged.state());
        assertEquals(1, unchanged.events().size());

        RiskCaseService.CaseView acknowledged = service.transition(
                opened.publicId(), RiskCaseState.ACKNOWLEDGED, opened.version(), reviewer,
                Set.of("ai:risk:manage", "ai:risk:read"), "重新授权后人工确认",
                "fresh-scope-race", "fresh-scope-race-request", null,
                () -> transitionAuthorization(initiallyVisible));
        assertEquals(RiskCaseState.ACKNOWLEDGED, acknowledged.state());
        assertEquals(2, acknowledged.events().size());
    }

    private RiskCaseService.TransitionAuthorization transitionAuthorization(RiskScanScope scope) {
        return new RiskCaseService.TransitionAuthorization(7L, true,
                Set.of("ai:risk:manage", "ai:risk:read"), scope);
    }

    private RiskSignal signal(String version) {
        return new RiskSignal("repair-backlog", "REPAIR_ORDER", 42L, "subject-token-42", "HIGH", version,
                Map.of("ageHours", 96, "status", "待处理"), Instant.parse("2026-07-11T08:00:00Z"));
    }

    private RiskSignal signal(long resourceId, String token) {
        return new RiskSignal("repair-backlog", "REPAIR_ORDER", resourceId, token, "HIGH",
                "repair-backlog-v1", Map.of("ageHours", 96, "status", "待处理"),
                Instant.parse("2026-07-11T08:00:00Z"));
    }
}
