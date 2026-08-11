package com.example.dormitory.ai.risk;

import com.example.dormitory.ai.approval.IdempotencyConflictException;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.BusinessExecutionActor;
import com.example.dormitory.ai.security.PiiRedactionService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskBranchCoverageTest {

    private static final BusinessExecutionActor REVIEWER =
            BusinessExecutionActor.from(ActorDescriptor.user(7));

    @Test
    void transitionByActorCoversDismissedAndRejectsNonUserActors() {
        RiskCase riskCase = RiskCase.open("risk-dismiss", "repair-backlog", "subject-token", "v1");

        riskCase.transitionByActor(
                RiskCaseState.DISMISSED, 0, RiskActorKind.USER, "user-7", "false positive");

        assertEquals(RiskCaseState.DISMISSED, riskCase.state());
        assertEquals("DISMISSED", riskCase.events().getLast().eventType());
        assertThrows(IllegalArgumentException.class, () -> RiskCase.open(
                null, "repair-backlog", "subject-token", "v1"));
        assertThrows(IllegalArgumentException.class, () -> RiskCase.open(
                "risk", "repair-backlog", " ", "v1"));
    }

    @Test
    void repositoryRejectsActiveDuplicateAndDuplicatePublicId() {
        InMemoryRiskCaseRepository repository = new InMemoryRiskCaseRepository();
        RiskCaseRepository.StoredRiskCase first = stored("risk-one", signal(1L, "subject-one", "v1"));
        repository.create(first);

        assertThrows(ActiveRiskCaseConflictException.class,
                () -> repository.create(stored("risk-two", signal(1L, "subject-one", "v1"))));

        first.riskCase().dismiss(0, "user-7", "closed");
        repository.save(first, 0, null);
        RiskCaseRepository.StoredRiskCase reusedDedup =
                stored("risk-one", signal(1L, "subject-one", "v2"));
        assertThrows(IllegalStateException.class, () -> repository.create(reusedDedup));
    }

    @Test
    void repositorySaveEnforcesPresenceAndVersionCasWindow() {
        InMemoryRiskCaseRepository repository = new InMemoryRiskCaseRepository();
        RiskCaseRepository.StoredRiskCase value = stored("risk-save", signal(2L, "subject-two", "v1"));

        assertThrows(IllegalStateException.class, () -> repository.save(value, 0, null));
        repository.create(value);
        assertThrows(IllegalStateException.class, () -> repository.save(value, 2, null));

        value.riskCase().acknowledge(0, "user-7", "owned");
        repository.save(value, 0, 11L);
        assertThrows(IllegalStateException.class, () -> repository.save(value, -2, null));
    }

    @Test
    void repositoryTransitionReservationIsReplayableConflictingAndReleasable() {
        InMemoryRiskCaseRepository repository = new InMemoryRiskCaseRepository();

        RiskCaseRepository.TransitionReservation first = repository.reserveTransition(
                7L, "risk", RiskCaseState.ACKNOWLEDGED, "key", "request-a");
        RiskCaseRepository.TransitionReservation replay = repository.reserveTransition(
                7L, "risk", RiskCaseState.ACKNOWLEDGED, "key", "request-a");

        assertFalse(first.replay());
        assertTrue(replay.replay());
        assertEquals(first.recordId(), replay.recordId());
        assertThrows(IdempotencyConflictException.class, () -> repository.reserveTransition(
                7L, "risk", RiskCaseState.ACKNOWLEDGED, "key", "request-b"));

        repository.releaseTransition(first.recordId());
        RiskCaseRepository.TransitionReservation afterRelease = repository.reserveTransition(
                7L, "risk", RiskCaseState.ACKNOWLEDGED, "key", "request-b");
        assertFalse(afterRelease.replay());
        assertThrows(IllegalArgumentException.class,
                () -> new RiskCaseRepository.TransitionReservation(-1, false));
    }

    @Test
    void repositoryCriteriaSupportNullEmptyAndSpecificFilters() {
        InMemoryRiskCaseRepository repository = new InMemoryRiskCaseRepository();
        RiskCaseRepository.StoredRiskCase older = stored("risk-old", signal(3L, "subject-old", "v1"));
        RiskCaseRepository.StoredRiskCase newer = stored("risk-new", signal(4L, "subject-new", "v1"));
        repository.create(older);
        repository.create(newer);

        assertEquals(2, repository.findByState(null, 0, 10).size());
        assertEquals(2, repository.countByState(null));
        assertEquals(2, repository.countByCriteria(null, null));
        assertEquals(2, repository.findByCriteria(null, Set.of(), 0, 10).size());
        assertEquals(2, repository.findByCriteria(null, Set.of("repair-backlog"), 0, 10).size());
        assertTrue(repository.findByCriteria(null, Set.of("repeat-repair"), 0, 10).isEmpty());
        assertTrue(repository.findLatestByDedupKey("missing").isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> repository.findByCriteria(null, Set.of(), -1, 10));
    }

    @Test
    void serviceRejectsInvalidModelExplanationContracts() {
        RiskCaseService service = service(new InMemoryRiskCaseRepository());
        RiskCaseService.CaseView opened = service.ingest(signal(5L, "subject-five", "v1"));
        ActorDescriptor model = ActorDescriptor.model("risk-model", 7L);

        assertThrows(IllegalArgumentException.class,
                () -> service.recordExplanation(opened.publicId(), "explanation", model));
        assertThrows(IllegalArgumentException.class,
                () -> service.recordModelExplanation(opened.publicId(), "explanation", model, 0L, "bad"));
        assertThrows(IllegalArgumentException.class,
                () -> service.recordModelExplanation(opened.publicId(), " ", model, 1L,
                        "00000000-0000-0000-0000-000000000001"));
        assertThrows(IllegalArgumentException.class,
                () -> service.recordModelExplanation(opened.publicId(), "x".repeat(4_001), model, 1L,
                        "00000000-0000-0000-0000-000000000001"));
    }

    @Test
    void serviceRejectsInvalidTargetsPermissionsPagingAndFilters() {
        RiskCaseService service = service(new InMemoryRiskCaseRepository());
        RiskCaseService.CaseView opened = service.ingest(signal(6L, "subject-six", "v1"));

        assertThrows(SecurityException.class, () -> service.get(opened.publicId(), Set.of()));
        assertThrows(SecurityException.class,
                () -> service.list(null, Set.of(), 1, 10, Set.of("ai:risk:manage")));
        assertThrows(IllegalArgumentException.class, () -> service.transition(
                opened.publicId(), RiskCaseState.OPEN, opened.version(), REVIEWER,
                Set.of("ai:risk:read", "ai:risk:manage"), "detail", "key", "request"));
        assertThrows(IllegalArgumentException.class,
                () -> service.list(null, Set.of(), 0, 10));
        assertThrows(IllegalArgumentException.class,
                () -> service.list(null, Set.of("unknown-risk"), 1, 10));
        assertThrows(IllegalArgumentException.class,
                () -> service.listAuthorized(null, Set.of(), 1, 10, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.get("missing"));
    }

    @Test
    void serviceAuthorizedScopeCoversCheckInAndUnknownSubjectTypes() {
        RiskCaseService service = service(new InMemoryRiskCaseRepository());
        RiskSignal checkIn = new RiskSignal(
                "long-pending-operation", "CHECK_IN_APPLICATION", 44L, "checkin-token-44",
                "HIGH", "v1", Map.of("ageHours", 48), Instant.parse("2026-07-11T08:00:00Z"));
        RiskCaseService.CaseView value = service.ingest(checkIn);
        RiskScanScope allowed = RiskScanScope.restricted(
                7L, Set.of("checkin:read"), Set.of(), Set.of(), Set.of(44L), Instant.now());
        RiskScanScope denied = RiskScanScope.restricted(
                7L, Set.of("checkin:read"), Set.of(), Set.of(), Set.of(45L), Instant.now());

        assertEquals(value.publicId(), service.get(value.publicId(), allowed).publicId());
        assertThrows(IllegalArgumentException.class, () -> service.get(value.publicId(), denied));
    }

    private static RiskCaseService service(InMemoryRiskCaseRepository repository) {
        return new RiskCaseService(repository,
                new PiiRedactionService("risk-coverage-key".getBytes(StandardCharsets.UTF_8), "test-v1"));
    }

    private static RiskCaseRepository.StoredRiskCase stored(String publicId, RiskSignal signal) {
        return new RiskCaseRepository.StoredRiskCase(
                RiskCase.open(publicId, signal.riskType(), signal.subjectToken(), signal.policyVersion()), signal);
    }

    private static RiskSignal signal(long resourceId, String subjectToken, String version) {
        return new RiskSignal(
                "repair-backlog", "REPAIR_ORDER", resourceId, subjectToken, "HIGH", version,
                Map.of("ageHours", 96, "status", "pending"), Instant.parse("2026-07-11T08:00:00Z"));
    }
}
