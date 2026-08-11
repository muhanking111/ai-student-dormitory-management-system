package com.example.dormitory.ai.config;

import cn.dev33.satoken.stp.StpUtil;
import com.example.dormitory.ai.approval.CanonicalJsonHasher;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.BusinessExecutionActor;
import com.example.dormitory.ai.infrastructure.business.DormitoryBusinessSnapshotProvider;
import com.example.dormitory.ai.port.ApprovedBusinessActionPort;
import com.example.dormitory.domain.RepairOrder;
import com.example.dormitory.service.OperationsService;
import com.example.dormitory.service.RbacService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiFeatureConfigurationBusinessWiringTest {

    @Test
    void productionBusinessActionBeanUsesTheLockedRepairSnapshotBoundary() {
        OperationsService operations = mock(OperationsService.class);
        RbacService rbac = mock(RbacService.class);
        DormitoryBusinessSnapshotProvider snapshots = mock(DormitoryBusinessSnapshotProvider.class);
        AiRuntimeControlService controls = mock(AiRuntimeControlService.class);
        when(controls.writeExecutionEnabled()).thenReturn(true);
        when(rbac.executionIdentityForUser(7L)).thenReturn(Optional.of(
                new RbacService.ExecutionIdentity(7L, true, "管理员")));
        String expectedSnapshot = "c".repeat(64);
        OperationsService.RepairAssignmentSnapshot locked = new OperationsService.RepairAssignmentSnapshot(
                42L, "待处理", null, LocalDateTime.parse("2026-07-16T02:00:00"));
        RepairOrder assigned = new RepairOrder(
                42L, "WX42", "报修人", "1号楼101室", "水电", "2026-07-16",
                "待处理", "插座故障", 9L);
        when(operations.assignRepairOrder(org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.eq(9L), any())).thenAnswer(invocation -> {
            OperationsService.RepairAssignmentPrecondition precondition = invocation.getArgument(2);
            precondition.verify(locked);
            return assigned;
        });
        ApprovedBusinessActionPort port = new AiFeatureConfiguration().approvedBusinessActionPort(
                operations, rbac, snapshots, new ObjectMapper(), controls);
        String payload = CanonicalJsonHasher.canonicalize(
                "{\"repairOrderId\":42,\"assigneeUserId\":9}");

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::isLogin).thenReturn(true);
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            stp.when(() -> StpUtil.hasRole("ADMIN")).thenReturn(true);
            var result = port.execute(BusinessExecutionActor.from(ActorDescriptor.user(7L)),
                    new ApprovedBusinessActionPort.ApprovedBusinessAction(
                            "REPAIR_ASSIGN", payload, CanonicalJsonHasher.sha256(payload), expectedSnapshot));
            assertEquals(42L, result.resourceId());
        }

        verify(snapshots).requireRepairAssignmentSnapshot(locked, expectedSnapshot);
    }
}
