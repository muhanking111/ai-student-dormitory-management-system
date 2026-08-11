package com.example.dormitory.ai.infrastructure.business;

import com.example.dormitory.ai.approval.ActionType;
import com.example.dormitory.service.OperationsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DormitoryBusinessSnapshotProviderTest {

    @Test
    void hashesOnlyFixedBusinessFieldsAndChangesWhenRepairSnapshotChanges() {
        OperationsService operations = mock(OperationsService.class);
        when(operations.repairAssignmentSnapshot(42L)).thenReturn(
                Optional.of(new OperationsService.RepairAssignmentSnapshot(
                        42L, "待处理", null, LocalDateTime.parse("2026-07-11T12:00:00"))),
                Optional.of(new OperationsService.RepairAssignmentSnapshot(
                        42L, "待处理", 8L, LocalDateTime.parse("2026-07-11T12:00:00"))));
        DormitoryBusinessSnapshotProvider provider = new DormitoryBusinessSnapshotProvider(
                operations, new ObjectMapper());
        String payload = "{\"assigneeUserId\":9,\"repairOrderId\":42}";
        String before = provider.currentSnapshotHash(ActionType.REPAIR_ASSIGN, payload);
        String after = provider.currentSnapshotHash(ActionType.REPAIR_ASSIGN, payload);

        assertTrue(before.matches("[0-9a-f]{64}"));
        assertNotEquals(before, after);
        assertThrows(IllegalArgumentException.class, () -> provider.currentSnapshotHash(
                ActionType.REPAIR_ASSIGN, "{\"repairOrderId\":42,\"assigneeUserId\":9,\"sql\":\"x\"}"));
    }
}
