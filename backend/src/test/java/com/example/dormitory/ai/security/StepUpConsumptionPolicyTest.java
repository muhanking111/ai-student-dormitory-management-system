package com.example.dormitory.ai.security;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.api.AiProposalController;
import com.example.dormitory.ai.application.run.AiActorContext;
import com.example.dormitory.ai.application.run.AiActorResolver;
import com.example.dormitory.ai.application.run.AiModelRuntimePort;
import com.example.dormitory.ai.application.run.AiRunEventPublisher;
import com.example.dormitory.ai.application.run.AiRunRecords;
import com.example.dormitory.ai.application.run.AiRunService;
import com.example.dormitory.ai.application.run.AiRuntimeGate;
import com.example.dormitory.ai.application.run.AiConversationRunStore;
import com.example.dormitory.ai.approval.ActionProposalService;
import com.example.dormitory.ai.approval.ActionType;
import com.example.dormitory.ai.approval.ProposalState;
import com.example.dormitory.ai.approval.ProposalPreview;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.infrastructure.runtime.AiRuntimeAuditWriter;
import com.example.dormitory.ai.infrastructure.runtime.AiRuntimeCrypto;
import com.example.dormitory.ai.port.AiAuditPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StepUpConsumptionPolicyTest {

    private static final String ID = "11111111-1111-1111-1111-111111111111";
    private static final String PAYLOAD_HASH = "a".repeat(64);
    private static final String SNAPSHOT_HASH = "b".repeat(64);
    private static final String PROOF = "sup1.1.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    @Test
    void proposalApprovalConsumesBoundProofBeforeCallingWriteService() {
        ActionProposalService proposals = mock(ActionProposalService.class);
        AiActorResolver actors = mock(AiActorResolver.class);
        AiAuditPort audit = mock(AiAuditPort.class);
        RecentAuthenticationPolicy recent = mock(RecentAuthenticationPolicy.class);
        AiActorContext actor = actor(Set.of("ai:approval:review", "repair:write"));
        ActionProposalService.ProposalView view = proposal();
        when(actors.current("ai:approval:review")).thenReturn(actor);
        when(proposals.get(ID)).thenReturn(view);
        when(proposals.approve(any(), anyInt(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(view);
        when(audit.writable()).thenReturn(true);
        ActionAuthorizationPolicy authorization = mock(ActionAuthorizationPolicy.class);
        when(authorization.canAccess(any(), any())).thenReturn(true);
        AiProposalController controller = new AiProposalController(
                proposals, actors, audit, recent, authorization, redaction());
        AiProposalController.ApproveRequest request = new AiProposalController.ApproveRequest(
                0, PAYLOAD_HASH, SNAPSHOT_HASH, "确认执行");
        String requestHash = AiProposalController.approvalRequestHash(ID, request);

        controller.approve(ID, "idem-1", PROOF, request);

        verify(recent).consume(PROOF, AuthenticatedRunContext.from(actor),
                "PROPOSAL_APPROVE", ID, requestHash);
        verify(proposals).approve(eq(ID), eq(0), eq(PAYLOAD_HASH), eq(SNAPSHOT_HASH),
                any(), any(), eq("idem-1"), eq(requestHash), eq("确认执行"));
    }

    @Test
    void rejectedProofPreventsProposalStateMutation() {
        ActionProposalService proposals = mock(ActionProposalService.class);
        AiActorResolver actors = mock(AiActorResolver.class);
        RecentAuthenticationPolicy recent = mock(RecentAuthenticationPolicy.class);
        AiActorContext actor = actor(Set.of("ai:approval:review", "repair:write"));
        when(actors.current("ai:approval:review")).thenReturn(actor);
        when(proposals.get(ID)).thenReturn(proposal());
        doThrow(new AiApiException(org.springframework.http.HttpStatus.FORBIDDEN,
                "AI_STEP_UP_PROOF_INVALID", "invalid", false))
                .when(recent).consume(eq(PROOF), any(), eq("PROPOSAL_APPROVE"), eq(ID), any());
        ActionAuthorizationPolicy authorization = mock(ActionAuthorizationPolicy.class);
        when(authorization.canAccess(any(), any())).thenReturn(true);
        AiProposalController controller = new AiProposalController(
                proposals, actors, mock(AiAuditPort.class), recent, authorization, redaction());

        assertThrows(AiApiException.class, () -> controller.approve(ID, "idem-1", PROOF,
                new AiProposalController.ApproveRequest(0, PAYLOAD_HASH, SNAPSHOT_HASH, null)));
        verify(proposals, never()).approve(any(), anyInt(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void approvalHashBindsProposalIdAndComment() {
        AiProposalController.ApproveRequest first = new AiProposalController.ApproveRequest(
                0, PAYLOAD_HASH, SNAPSHOT_HASH, "同意执行");
        AiProposalController.ApproveRequest changedComment = new AiProposalController.ApproveRequest(
                0, PAYLOAD_HASH, SNAPSHOT_HASH, "同意执行并复核");

        org.junit.jupiter.api.Assertions.assertNotEquals(
                AiProposalController.approvalRequestHash(ID, first),
                AiProposalController.approvalRequestHash(
                        "22222222-2222-2222-2222-222222222222", first));
        org.junit.jupiter.api.Assertions.assertNotEquals(
                AiProposalController.approvalRequestHash(ID, first),
                AiProposalController.approvalRequestHash(ID, changedComment));
    }

    @Test
    void auditContentRequiresIndependentAndBottomPermissionsConsumesProofThenAuditsRead() {
        AiConversationRunStore store = mock(AiConversationRunStore.class);
        AiActorResolver actors = mock(AiActorResolver.class);
        RecentAuthenticationPolicy recent = mock(RecentAuthenticationPolicy.class);
        AiRuntimeAuditWriter auditWriter = mock(AiRuntimeAuditWriter.class);
        AiActorContext actor = actor(Set.of(
                "ai:audit:read", "ai:audit:content:read", "ai:repair:triage", "repair:read"));
        when(actors.current("ai:audit:content:read")).thenReturn(actor);
        AiRunRecords.AuditRun metadata = new AiRunRecords.AuditRun(
                ID, "REPAIR", "SUCCEEDED", "fake", "v1", 0, 0,
                BigDecimal.ZERO, null, Instant.now(), Instant.now());
        when(store.auditRun(ID)).thenReturn(new AiRunRecords.AuditRunDetail(metadata, List.of(), List.of()));
        AiRunRecords.AuditRunContent content = new AiRunRecords.AuditRunContent(ID, List.of(
                new AiRunRecords.AuditContentMessage(ID, "USER", "已脱敏正文", "L1", Instant.now())));
        when(store.auditRunContent(ID)).thenReturn(content);
        AiRunService service = service(store, actors, recent, auditWriter);
        String reason = "核查维修分诊异常处理记录";
        String hash = AiRunService.auditContentRequestHash(ID, reason);

        assertSame(content, service.auditRunContent(ID, reason, PROOF));

        verify(recent).consume(PROOF, AuthenticatedRunContext.from(actor),
                "AUDIT_CONTENT_READ", ID, hash);
        verify(auditWriter).requireValidChain("RUN", "RUN", ID);
        verify(auditWriter).append(eq("SECURITY"), eq("AI_RUN"), eq(ID),
                eq("AUDIT_CONTENT_READ"), eq(actor), eq(hash), any());
    }

    @Test
    void auditMetadataDoesNotConsumeProofAndMissingBottomScopeFailsClosed() {
        AiConversationRunStore store = mock(AiConversationRunStore.class);
        AiActorResolver actors = mock(AiActorResolver.class);
        RecentAuthenticationPolicy recent = mock(RecentAuthenticationPolicy.class);
        AiRunRecords.AuditRun metadata = new AiRunRecords.AuditRun(
                ID, "REPAIR", "SUCCEEDED", "fake", "v1", 0, 0,
                BigDecimal.ZERO, null, Instant.now(), Instant.now());
        AiRunRecords.AuditRunDetail detail = new AiRunRecords.AuditRunDetail(metadata, List.of(), List.of());
        when(store.auditRun(ID)).thenReturn(detail);
        when(actors.current("ai:audit:read")).thenReturn(actor(Set.of("ai:audit:read")));
        AiRunService service = service(store, actors, recent, mock(AiRuntimeAuditWriter.class));

        assertSame(detail, service.auditRun(ID));
        verify(recent, never()).consume(any(), any(), any(), any(), any());

        when(actors.current("ai:audit:content:read")).thenReturn(actor(Set.of(
                "ai:audit:read", "ai:audit:content:read", "ai:repair:triage")));
        AiApiException error = assertThrows(AiApiException.class,
                () -> service.auditRunContent(ID, "核查维修分诊异常处理记录", PROOF));
        assertEquals("AI_RESOURCE_NOT_FOUND", error.errorCode());
        verify(recent, never()).consume(any(), any(), any(), any(), any());
    }

    @Test
    void auditContentHashBindsRunAndNormalizedReason() {
        String reason = "核查维修分诊异常处理记录";
        assertEquals(AiRunService.auditContentRequestHash(ID, reason),
                AiRunService.auditContentRequestHash(ID, "  " + reason + "  "));
        org.junit.jupiter.api.Assertions.assertNotEquals(
                AiRunService.auditContentRequestHash(ID, reason),
                AiRunService.auditContentRequestHash(ID, "核查维修分诊另一项处理记录"));
    }

    private static AiRunService service(
            AiConversationRunStore store,
            AiActorResolver actors,
            RecentAuthenticationPolicy recent,
            AiRuntimeAuditWriter auditWriter) {
        AiRuntimeGate gate = mock(AiRuntimeGate.class);
        TaskExecutor executor = Runnable::run;
        return new AiRunService(gate, actors, store, mock(AiModelRuntimePort.class),
                mock(AiRunEventPublisher.class), mock(AiRuntimeCrypto.class), executor,
                new ObjectMapper(), recent, auditWriter,
                mock(com.example.dormitory.ai.observability.AiObservability.class));
    }

    private static AiActorContext actor(Set<String> permissions) {
        return new AiActorContext(7L, "session", "c".repeat(64), 1, "d".repeat(64),
                List.of("AUDITOR"), permissions.stream().sorted().toList(), ActorDescriptor.user(7L));
    }

    private static ActionProposalService.ProposalView proposal() {
        return new ActionProposalService.ProposalView(ID, ActionType.REPAIR_ASSIGN, "REPAIR_ORDER", 9L,
                ProposalPreview.legacy("preview"), PAYLOAD_HASH, SNAPSHOT_HASH, "repair:write", 1, 0, "HIGH",
                ProposalState.PENDING_APPROVAL, 0, Instant.now().plusSeconds(300), null);
    }

    private static PiiClassificationService redaction() {
        return new PiiClassificationService(
                new PiiRedactionService("step-up-redaction-test-key-32bytes"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8), "test-v1"),
                java.util.List::of);
    }
}
