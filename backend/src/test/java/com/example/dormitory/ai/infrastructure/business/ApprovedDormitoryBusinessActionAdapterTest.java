package com.example.dormitory.ai.infrastructure.business;

import cn.dev33.satoken.stp.StpUtil;
import com.example.dormitory.ai.approval.ActionProposalService;
import com.example.dormitory.ai.approval.CanonicalJsonHasher;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.BusinessExecutionActor;
import com.example.dormitory.ai.port.ApprovedBusinessActionPort;
import com.example.dormitory.domain.Notice;
import com.example.dormitory.domain.RepairOrder;
import com.example.dormitory.dto.NoticeRequest;
import com.example.dormitory.service.OperationsService;
import com.example.dormitory.service.RbacService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class ApprovedDormitoryBusinessActionAdapterTest {

    private final OperationsService operations = mock(OperationsService.class);
    private final RbacService rbac = mock(RbacService.class);
    private final ActionProposalService.BusinessSnapshotProvider snapshots = mock(
            ActionProposalService.BusinessSnapshotProvider.class);
    private final ApprovedDormitoryBusinessActionAdapter adapter = new ApprovedDormitoryBusinessActionAdapter(
            operations, rbac, snapshots, new ObjectMapper(), () -> true);
    private final BusinessExecutionActor actor = BusinessExecutionActor.from(ActorDescriptor.user(7));

    @Test
    void noticeActionForcesDraftAndCurrentUserPublisherThroughExistingService() {
        String payload = CanonicalJsonHasher.canonicalize(
                "{\"title\":\"消防通知\",\"type\":\"安全通知\",\"status\":\"草稿\",\"content\":\"请保持通道畅通\"}");
        when(rbac.executionIdentityForUser(7L)).thenReturn(Optional.of(
                new RbacService.ExecutionIdentity(7L, true, "当前管理员")));
        when(snapshots.currentSnapshotHash(any(), any())).thenReturn(hash('b'));
        when(operations.createNotice(any())).thenReturn(
                new Notice(9L, "消防通知", "安全通知", "2026-07-11", "当前管理员", "草稿", "请保持通道畅通", null));

        try (MockedStatic<StpUtil> stp = loggedInAdmin()) {
            ApprovedBusinessActionPort.BusinessActionResult result = adapter.execute(actor,
                    new ApprovedBusinessActionPort.ApprovedBusinessAction(
                            "NOTICE_CREATE_DRAFT", payload, CanonicalJsonHasher.sha256(payload), hash('b')));
            assertEquals("NOTICE", result.resourceType());
            assertEquals(9L, result.resourceId());
            verify(operations).createNotice(new NoticeRequest(
                    "消防通知", "安全通知", "当前管理员", "草稿", "请保持通道畅通"));
        }
    }

    @Test
    void writeKillSwitchIsRecheckedAtLastBoundaryBeforeBusinessService() {
        ApprovedDormitoryBusinessActionAdapter disabled = new ApprovedDormitoryBusinessActionAdapter(
                operations, rbac, snapshots, new ObjectMapper(), () -> false);
        String payload = CanonicalJsonHasher.canonicalize(
                "{\"title\":\"消防通知\",\"type\":\"安全通知\",\"status\":\"草稿\",\"content\":\"请保持通道畅通\"}");
        when(rbac.executionIdentityForUser(7L)).thenReturn(Optional.of(
                new RbacService.ExecutionIdentity(7L, true, "当前管理员")));
        when(snapshots.currentSnapshotHash(any(), any())).thenReturn(hash('b'));
        try (MockedStatic<StpUtil> stp = loggedInAdmin()) {
            assertThrows(SecurityException.class, () -> disabled.execute(actor,
                    new ApprovedBusinessActionPort.ApprovedBusinessAction(
                            "NOTICE_CREATE_DRAFT", payload, CanonicalJsonHasher.sha256(payload), hash('b'))));
            verify(operations, never()).createNotice(any());
        }
    }

    @Test
    void repairActionReauthorizesAdminAndRejectsHashOrAllowlistBypass() {
        String payload = CanonicalJsonHasher.canonicalize("{\"repairOrderId\":42,\"assigneeUserId\":9}");
        when(rbac.executionIdentityForUser(7L)).thenReturn(Optional.of(
                new RbacService.ExecutionIdentity(7L, true, "管理员")));
        when(snapshots.currentSnapshotHash(any(), any())).thenReturn(hash('c'));
        RepairOrder repaired = new RepairOrder(42L, "WX42", "报修人", "1号楼101室", "水电", "2026-07-11", "待处理", "插座故障", 9L);
        when(operations.assignRepairOrder(42L, 9L)).thenReturn(repaired);

        try (MockedStatic<StpUtil> stp = loggedInAdmin()) {
            ApprovedBusinessActionPort.BusinessActionResult result = adapter.execute(actor,
                    new ApprovedBusinessActionPort.ApprovedBusinessAction(
                            "REPAIR_ASSIGN", payload, CanonicalJsonHasher.sha256(payload), hash('c')));
            assertEquals(42L, result.resourceId());
            verify(operations).assignRepairOrder(42L, 9L);

            assertThrows(SecurityException.class, () -> adapter.execute(actor,
                    new ApprovedBusinessActionPort.ApprovedBusinessAction(
                            "REPAIR_ASSIGN", payload, hash('x'), hash('c'))));
            assertThrows(IllegalArgumentException.class, () -> adapter.execute(actor,
                    new ApprovedBusinessActionPort.ApprovedBusinessAction(
                            "DELETE_ALL", "{}", CanonicalJsonHasher.sha256("{}"), hash('c'))));
        }
    }

    private MockedStatic<StpUtil> loggedInAdmin() {
        MockedStatic<StpUtil> stp = mockStatic(StpUtil.class);
        stp.when(StpUtil::isLogin).thenReturn(true);
        stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
        stp.when(() -> StpUtil.hasRole("ADMIN")).thenReturn(true);
        return stp;
    }

    private String hash(char value) {
        return String.valueOf(value).repeat(64);
    }
}
