package com.example.dormitory.ai.repair;

import com.example.dormitory.ai.approval.ActionProposalService;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.BusinessActorScope;
import com.example.dormitory.ai.domain.model.BusinessExecutionActor;
import com.example.dormitory.ai.port.BusinessReadFacade;
import com.example.dormitory.ai.security.PromptInjectionGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RepairTriageServiceBoundaryTest {

    private static final long REPAIR_ID = 42L;
    private static final Instant AS_OF = Instant.parse("2026-07-13T12:00:00Z");
    private static final BusinessExecutionActor ACTOR = BusinessExecutionActor.from(ActorDescriptor.user(7));

    @Test
    void triageRejectsInvalidResourceScopeActorAndEachMissingReadPermission() {
        RepairTriageService service = service("{}", null, mock(ActionProposalService.class), new AtomicInteger());
        BusinessActorScope valid = scope("ai:repair:triage", "repair:read");

        assertAll(
                () -> assertThrows(SecurityException.class,
                        () -> triage(service, 0, valid, ACTOR)),
                () -> assertThrows(SecurityException.class,
                        () -> triage(service, REPAIR_ID, null, ACTOR)),
                () -> assertThrows(SecurityException.class,
                        () -> triage(service, REPAIR_ID, valid, null)),
                () -> assertThrows(SecurityException.class,
                        () -> triage(service, REPAIR_ID, scope("repair:read"), ACTOR)),
                () -> assertThrows(SecurityException.class,
                        () -> triage(service, REPAIR_ID, scope("ai:repair:triage"), ACTOR)));
    }

    @Test
    void triageRejectsMalformedOrMismatchedServerContext() {
        RepairTriageService malformed = service("{", null, mock(ActionProposalService.class), new AtomicInteger());
        RepairTriageService mismatched = service(
                context(99, "待处理", "综合", "普通故障", null), null,
                mock(ActionProposalService.class), new AtomicInteger());

        assertAll(
                () -> assertThrows(IllegalStateException.class,
                        () -> triage(malformed, REPAIR_ID, scope("ai:repair:triage", "repair:read"), ACTOR)),
                () -> assertThrows(SecurityException.class,
                        () -> triage(mismatched, REPAIR_ID, scope("ai:repair:triage", "repair:read"), ACTOR)));
    }

    @Test
    void completedRepairWithMissingDescriptionStaysReadOnlyAndRequestsMissingInformation() {
        AtomicInteger candidateReads = new AtomicInteger();
        RepairTriageService service = service(
                "{\"repairOrderId\":42,\"type\":\"门锁\",\"status\":\"已完成\"}",
                null, mock(ActionProposalService.class), candidateReads);

        RepairTriageService.TriageResult result = triage(service, REPAIR_ID,
                scope("ai:repair:triage", "repair:read", "repair:write"), ACTOR);

        assertEquals("设施维修", result.category());
        assertEquals("综合设施组", result.recommendedTeam());
        assertEquals("LOW", result.urgency());
        assertTrue(result.missingInformation().contains("故障现象与安全影响"));
        assertNull(result.assignmentCandidateUserId());
        assertNull(result.assignmentCandidateName());
        assertNull(result.proposal());
        assertEquals(0, candidateReads.get());
    }

    @Test
    void mediumPlumbingRepairWithoutWritePermissionNeverReadsCandidates() {
        AtomicInteger candidateReads = new AtomicInteger();
        RepairTriageService service = service(
                context(REPAIR_ID, null, "其他", "水管漏水", null),
                null, mock(ActionProposalService.class), candidateReads);

        RepairTriageService.TriageResult result = triage(service, REPAIR_ID,
                scope("ai:repair:triage", "repair:read"), ACTOR);

        assertEquals("给排水维修", result.category());
        assertEquals("水电维修组", result.recommendedTeam());
        assertEquals("MEDIUM", result.urgency());
        assertNull(result.proposal());
        assertEquals(0, candidateReads.get());
    }

    @Test
    void missingOrEmptyCandidateCollectionsProduceNoAssignment() {
        ActionProposalService proposals = mock(ActionProposalService.class);
        String context = context(REPAIR_ID, "待处理", "综合", "普通故障", null);
        RepairTriageService missing = service(context, "{}", proposals, new AtomicInteger());
        RepairTriageService empty = service(context, "{\"candidates\":[]}", proposals, new AtomicInteger());
        BusinessActorScope scope = scope("ai:repair:triage", "repair:read", "repair:write");

        assertAll(
                () -> assertNull(triage(missing, REPAIR_ID, scope, ACTOR).assignmentCandidateUserId()),
                () -> assertNull(triage(empty, REPAIR_ID, scope, ACTOR).assignmentCandidateUserId()));
    }

    @Test
    void invalidCandidateIdentifiersAndNamesFailClosed() {
        String context = context(REPAIR_ID, "待处理", "综合", "普通故障", null);
        RepairTriageService invalidId = service(context,
                "{\"candidates\":[{\"userId\":0,\"displayName\":\"维修员\"}]}",
                mock(ActionProposalService.class), new AtomicInteger());
        RepairTriageService blankName = service(context,
                "{\"candidates\":[{\"userId\":9,\"displayName\":\"  \"}]}",
                mock(ActionProposalService.class), new AtomicInteger());
        BusinessActorScope scope = scope("ai:repair:triage", "repair:read", "repair:write");

        assertAll(
                () -> assertThrows(IllegalStateException.class,
                        () -> triage(invalidId, REPAIR_ID, scope, ACTOR)),
                () -> assertThrows(IllegalStateException.class,
                        () -> triage(blankName, REPAIR_ID, scope, ACTOR)));
    }

    @Test
    void existingAssigneeAndMediumUrgencyArePreservedInTheApprovalPreview() {
        ActionProposalService proposals = mock(ActionProposalService.class);
        RepairTriageService service = service(
                context(REPAIR_ID, "待处理", "水管", "水管漏水", 5L),
                "{\"candidates\":[{\"userId\":9,\"displayName\":\"维修员 A\"}]}",
                proposals, new AtomicInteger());

        RepairTriageService.TriageResult result = triage(service, REPAIR_ID,
                scope("ai:repair:triage", "repair:read", "repair:write"), ACTOR);

        ArgumentCaptor<ActionProposalService.CreateProposalCommand> command =
                ArgumentCaptor.forClass(ActionProposalService.CreateProposalCommand.class);
        verify(proposals).create(command.capture(), eq("triage-key"), eq("request-hash"), eq(ACTOR));
        assertEquals("MEDIUM", command.getValue().riskLevel());
        assertTrue(command.getValue().preview().currentValue().contains("用户 #5"));
        assertEquals(9L, result.assignmentCandidateUserId());
        assertEquals("建议 2 个工作日内处理，待真实数据校准", result.slaSuggestion());
    }

    private RepairTriageService service(
            String contextJson,
            String candidatesJson,
            ActionProposalService proposals,
            AtomicInteger candidateReads) {
        BusinessReadFacade reads = (scope, request) -> {
            if ("repair.context.v1".equals(request.queryId())) {
                return new BusinessReadFacade.BusinessReadResult("repair-ai-context.v1", contextJson, AS_OF);
            }
            if ("repair.assignment-candidates.v1".equals(request.queryId())) {
                candidateReads.incrementAndGet();
                if (candidatesJson == null) throw new AssertionError("candidate query must not run");
                return new BusinessReadFacade.BusinessReadResult(
                        "repair-assignment-candidates.v1", candidatesJson, AS_OF);
            }
            throw new AssertionError("unexpected query: " + request.queryId());
        };
        return new RepairTriageService(reads, proposals, new PromptInjectionGuard(), new ObjectMapper());
    }

    private RepairTriageService.TriageResult triage(
            RepairTriageService service,
            long repairId,
            BusinessActorScope scope,
            BusinessExecutionActor actor) {
        return service.triage(repairId, scope, actor, "triage-key", "request-hash",
                "00000000-0000-0000-0000-000000000042");
    }

    private BusinessActorScope scope(String... permissions) {
        return new BusinessActorScope(ActorDescriptor.user(7), Set.of(permissions),
                Map.of("REPAIR_ORDER", Set.of(REPAIR_ID)));
    }

    private String context(
            long repairId,
            String status,
            String type,
            String description,
            Long assigneeUserId) {
        try {
            Map<String, Object> value = new java.util.LinkedHashMap<>();
            value.put("repairOrderId", repairId);
            value.put("status", status);
            value.put("type", type);
            value.put("description", description);
            value.put("assigneeUserId", assigneeUserId);
            return new ObjectMapper().writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
