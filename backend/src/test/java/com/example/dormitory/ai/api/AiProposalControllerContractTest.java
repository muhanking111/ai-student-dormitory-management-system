package com.example.dormitory.ai.api;

import com.example.dormitory.ai.approval.ActionProposalService;
import com.example.dormitory.ai.approval.ActionType;
import com.example.dormitory.ai.approval.ProposalPreview;
import com.example.dormitory.ai.approval.ProposalState;
import com.example.dormitory.ai.port.AiAuditPort;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiProposalControllerContractTest {

    @Test
    void repairProposalResponseExposesOriginRunAndFullDisplayPermissionContract() throws Exception {
        AiProposalController controller = new AiProposalController(
                null, null, writableAudit(), null, null, null);
        ActionProposalService.ProposalView view = new ActionProposalService.ProposalView(
                "proposal-1", ActionType.REPAIR_ASSIGN, "REPAIR_ORDER", 42L,
                preview(), "a".repeat(64), "b".repeat(64), "repair:write",
                2, 1, "HIGH", ProposalState.PENDING_APPROVAL, 3, Instant.parse("2026-07-20T08:00:00Z"),
                null, null, "run-origin-42");

        AiProposalController.ProposalResponse response = invokeResponse(controller, view);

        assertEquals("run-origin-42", response.runId());
        assertEquals("ai:approval:review + ADMIN + repair:write + 当前对象范围仍可分配",
                response.requiredPermission());
    }

    @Test
    void noticeProposalResponseKeepsNoticeDisplayPermissionContract() throws Exception {
        AiProposalController controller = new AiProposalController(
                null, null, writableAudit(), null, null, null);
        ActionProposalService.ProposalView view = new ActionProposalService.ProposalView(
                "proposal-2", ActionType.NOTICE_CREATE_DRAFT, "NOTICE", null,
                preview(), "c".repeat(64), "d".repeat(64), "notice:write",
                1, 0, "MEDIUM", ProposalState.PENDING_APPROVAL, 1, Instant.parse("2026-07-20T09:00:00Z"),
                null, null, "run-origin-notice");

        AiProposalController.ProposalResponse response = invokeResponse(controller, view);

        assertEquals("run-origin-notice", response.runId());
        assertEquals("ai:approval:review + notice:write", response.requiredPermission());
    }

    private AiProposalController.ProposalResponse invokeResponse(
            AiProposalController controller,
            ActionProposalService.ProposalView view) throws Exception {
        Method method = AiProposalController.class.getDeclaredMethod(
                "response", ActionProposalService.ProposalView.class);
        method.setAccessible(true);
        return (AiProposalController.ProposalResponse) method.invoke(controller, view);
    }

    private ProposalPreview preview() {
        return new ProposalPreview(
                "当前值", "建议值", "影响说明", Instant.parse("2026-07-20T07:30:00Z"),
                ProposalPreview.EvidenceBasis.DETERMINISTIC, null,
                List.of(new ProposalPreview.Citation(
                        "USER_COMMAND", "RUN:test", "测试命令", "e".repeat(64))));
    }

    private AiAuditPort writableAudit() {
        return new AiAuditPort() {
            @Override
            public boolean writable() {
                return true;
            }

            @Override
            public void append(AiAuditEvent event) {
            }
        };
    }
}
