package com.example.dormitory.ai.risk;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RiskCaseStateMachineTest {

    @Test
    void preservesAppendOnlyHumanConclusionAcrossReopen() {
        RiskCase riskCase = RiskCase.open("risk-1", "repair-backlog", "subject-token", "v1");
        riskCase.acknowledge(0, "user-1", "正在核验");
        riskCase.resolve(1, "user-1", "已安排处理");
        riskCase.reopenFromRule(2, "v2", "再次超过阈值");

        assertEquals(RiskCaseState.OPEN, riskCase.state());
        assertEquals(3, riskCase.version());
        assertEquals(4, riskCase.events().size());
        assertEquals("已安排处理", riskCase.events().get(2).detail());
        assertEquals(RiskActorKind.SYSTEM, riskCase.events().get(3).actorKind());
        assertThrows(IllegalStateException.class,
                () -> riskCase.dismiss(1, "user-2", "过期版本"));
    }

    @Test
    void modelActorCannotAcknowledgeResolveOrDismiss() {
        RiskCase riskCase = RiskCase.open("risk-2", "occupancy-consistency", "subject-token", "v1");
        assertThrows(IllegalArgumentException.class,
                () -> riskCase.transitionByActor(RiskCaseState.ACKNOWLEDGED, 0,
                        RiskActorKind.MODEL, "model", "不能覆盖人工结论"));
    }

    @Test
    void transitionsRejectWrongStateVersionActorAndBlankEventFacts() {
        RiskCase riskCase = RiskCase.open("risk-3", "repair-backlog", "subject-token", "v1");
        assertThrows(RiskCaseConflictException.class,
                () -> riskCase.acknowledge(1, "user", "detail"));
        assertThrows(IllegalArgumentException.class,
                () -> riskCase.acknowledge(0, " ", "detail"));
        assertThrows(IllegalArgumentException.class,
                () -> riskCase.acknowledge(0, "user", " "));
        assertThrows(RiskCaseConflictException.class,
                () -> riskCase.reopenFromRule(0, "v2", "detail"));

        riskCase.dismiss(0, "user", "误报");
        assertThrows(RiskCaseConflictException.class,
                () -> riskCase.resolve(1, "user", "already final"));
        assertThrows(RiskCaseConflictException.class,
                () -> riskCase.dismiss(1, "user", "already final"));
        assertThrows(IllegalArgumentException.class,
                () -> riskCase.reopenFromRule(1, " ", "detail"));
        assertThrows(IllegalArgumentException.class,
                () -> riskCase.transitionByActor(RiskCaseState.OPEN, 1,
                        RiskActorKind.USER, "user", "manual reopen forbidden"));
        assertThrows(IllegalArgumentException.class,
                () -> riskCase.transitionByActor(RiskCaseState.RESOLVED, 1,
                        RiskActorKind.SERVICE, "service", "service cannot decide"));
    }

    @Test
    void restoreValidatesEveryPersistedCaseAndEventInvariant() {
        Instant now = Instant.now();
        RiskCase.RiskCaseEvent valid = new RiskCase.RiskCaseEvent(
                1, "OPENED", RiskActorKind.SYSTEM, "risk-rule", "opened", 0, now);
        RiskCase restored = RiskCase.restore(
                "risk-restored", "repair-backlog", "subject-token", "v1",
                RiskCaseState.OPEN, 0, List.of(valid));
        assertEquals(RiskCaseState.OPEN, restored.state());

        for (String invalid : java.util.Arrays.asList(null, " ")) {
            assertThrows(RuntimeException.class, () -> RiskCase.restore(
                    invalid, "repair-backlog", "subject-token", "v1",
                    RiskCaseState.OPEN, 0, List.of(valid)));
            assertThrows(RuntimeException.class, () -> RiskCase.restore(
                    "risk", invalid, "subject-token", "v1",
                    RiskCaseState.OPEN, 0, List.of(valid)));
            assertThrows(RuntimeException.class, () -> RiskCase.restore(
                    "risk", "repair-backlog", invalid, "v1",
                    RiskCaseState.OPEN, 0, List.of(valid)));
            assertThrows(RuntimeException.class, () -> RiskCase.restore(
                    "risk", "repair-backlog", "subject-token", invalid,
                    RiskCaseState.OPEN, 0, List.of(valid)));
        }
        assertThrows(IllegalArgumentException.class, () -> RiskCase.restore(
                "risk", "repair-backlog", "subject-token", "v1", null, 0, List.of(valid)));
        assertThrows(IllegalArgumentException.class, () -> RiskCase.restore(
                "risk", "repair-backlog", "subject-token", "v1", RiskCaseState.OPEN, -1,
                List.of(valid)));
        assertThrows(NullPointerException.class, () -> RiskCase.restore(
                "risk", "repair-backlog", "subject-token", "v1", RiskCaseState.OPEN, 0, null));
        assertThrows(IllegalArgumentException.class, () -> RiskCase.restore(
                "risk", "repair-backlog", "subject-token", "v1", RiskCaseState.OPEN, 0, List.of()));

        List<RiskCase.RiskCaseEvent> invalidEvents = new ArrayList<>();
        invalidEvents.add(new RiskCase.RiskCaseEvent(2, "OPENED", RiskActorKind.SYSTEM,
                "risk-rule", "opened", 0, now));
        assertRestoreRejected(invalidEvents);
        assertRestoreRejected(List.of(new RiskCase.RiskCaseEvent(1, "OPENED", RiskActorKind.SYSTEM,
                "risk-rule", "opened", -1, now)));
        assertRestoreRejected(List.of(new RiskCase.RiskCaseEvent(1, "OPENED", RiskActorKind.SYSTEM,
                "risk-rule", "opened", 1, now)));
        assertRestoreRejected(List.of(new RiskCase.RiskCaseEvent(1, " ", RiskActorKind.SYSTEM,
                "risk-rule", "opened", 0, now)));
        assertRestoreRejected(List.of(new RiskCase.RiskCaseEvent(1, "OPENED", null,
                "risk-rule", "opened", 0, now)));
        assertRestoreRejected(List.of(new RiskCase.RiskCaseEvent(1, "OPENED", RiskActorKind.SYSTEM,
                " ", "opened", 0, now)));
        assertRestoreRejected(List.of(new RiskCase.RiskCaseEvent(1, "OPENED", RiskActorKind.SYSTEM,
                "risk-rule", " ", 0, now)));
        assertRestoreRejected(List.of(new RiskCase.RiskCaseEvent(1, "OPENED", RiskActorKind.SYSTEM,
                "risk-rule", "opened", 0, null)));
    }

    private static void assertRestoreRejected(List<RiskCase.RiskCaseEvent> events) {
        assertThrows(IllegalArgumentException.class, () -> RiskCase.restore(
                "risk", "repair-backlog", "subject-token", "v1",
                RiskCaseState.OPEN, 0, events));
    }
}
