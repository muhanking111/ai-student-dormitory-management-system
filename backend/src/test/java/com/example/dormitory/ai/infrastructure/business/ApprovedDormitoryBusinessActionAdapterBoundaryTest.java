package com.example.dormitory.ai.infrastructure.business;

import cn.dev33.satoken.stp.StpUtil;
import com.example.dormitory.ai.approval.ActionProposalService;
import com.example.dormitory.ai.approval.CanonicalJsonHasher;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.BusinessExecutionActor;
import com.example.dormitory.ai.port.ApprovedBusinessActionPort;
import com.example.dormitory.service.OperationsService;
import com.example.dormitory.service.RbacService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class ApprovedDormitoryBusinessActionAdapterBoundaryTest {

    private static final long USER_ID = 7L;
    private static final String SNAPSHOT = "b".repeat(64);

    private final OperationsService operations = mock(OperationsService.class);
    private final RbacService rbac = mock(RbacService.class);
    private final ActionProposalService.BusinessSnapshotProvider snapshots = mock(
            ActionProposalService.BusinessSnapshotProvider.class);
    private final ApprovedDormitoryBusinessActionAdapter adapter = new ApprovedDormitoryBusinessActionAdapter(
            operations, rbac, snapshots, new ObjectMapper(), () -> true);
    private final BusinessExecutionActor actor = BusinessExecutionActor.from(ActorDescriptor.user(USER_ID));

    @Test
    void executeRequiresTheCurrentRealUserSessionBeforeReadingTheAction() {
        assertThrows(SecurityException.class, () -> adapter.execute(null, null));

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::isLogin).thenReturn(false);
            assertThrows(SecurityException.class, () -> adapter.execute(actor, null));

            stp.when(StpUtil::isLogin).thenReturn(true);
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(USER_ID + 1);
            assertThrows(SecurityException.class, () -> adapter.execute(actor, null));
        }
    }

    @Test
    void executeRejectsNullActionTypeHashSnapshotAndNonObjectPayloads() {
        when(snapshots.currentSnapshotHash(any(), any())).thenReturn(SNAPSHOT);
        try (MockedStatic<StpUtil> ignored = loggedIn(true)) {
            assertAll(
                    () -> assertThrows(IllegalArgumentException.class, () -> adapter.execute(actor, null)),
                    () -> assertThrows(IllegalArgumentException.class, () -> adapter.execute(actor,
                            new ApprovedBusinessActionPort.ApprovedBusinessAction(null, "{}", hash("{}"), SNAPSHOT))),
                    () -> assertThrows(SecurityException.class, () -> adapter.execute(actor,
                            new ApprovedBusinessActionPort.ApprovedBusinessAction(
                                    "NOTICE_CREATE_DRAFT", "{}", null, SNAPSHOT))),
                    () -> assertThrows(SecurityException.class, () -> adapter.execute(actor,
                            action("NOTICE_CREATE_DRAFT", "{}", "c".repeat(64)))),
                    () -> assertThrows(IllegalArgumentException.class, () -> adapter.execute(actor,
                            action("NOTICE_CREATE_DRAFT", "[]", SNAPSHOT))),
                    () -> assertThrows(IllegalArgumentException.class, () -> adapter.execute(actor,
                            action("NOTICE_CREATE_DRAFT", "null", SNAPSHOT))));
        }
    }

    @Test
    void noticeExecutionRejectsContractStatusContentAndPublisherViolations() {
        when(snapshots.currentSnapshotHash(any(), any())).thenReturn(SNAPSHOT);
        when(rbac.executionIdentityForUser(USER_ID)).thenReturn(enabledIdentity("管理员"));
        try (MockedStatic<StpUtil> ignored = loggedIn(true)) {
            assertAll(
                    () -> assertThrows(IllegalArgumentException.class, () -> adapter.execute(actor, action(
                            "NOTICE_CREATE_DRAFT", "{\"title\":\"通知\"}", SNAPSHOT))),
                    () -> assertThrows(IllegalArgumentException.class, () -> adapter.execute(actor, action(
                            "NOTICE_CREATE_DRAFT",
                            "{\"title\":\"通知\",\"type\":\"安全\",\"status\":\"草稿\",\"content\":\"正文\",\"extra\":1}",
                            SNAPSHOT))),
                    () -> assertThrows(IllegalArgumentException.class, () -> adapter.execute(actor, action(
                            "NOTICE_CREATE_DRAFT",
                            "{\"title\":\"通知\",\"type\":\"安全\",\"status\":\"已发布\",\"content\":\"正文\"}",
                            SNAPSHOT))),
                    () -> assertThrows(IllegalArgumentException.class, () -> adapter.execute(actor, action(
                            "NOTICE_CREATE_DRAFT", noticePayload("x".repeat(10_001)), SNAPSHOT))),
                    () -> assertThrows(IllegalArgumentException.class, () -> adapter.execute(actor, action(
                            "NOTICE_CREATE_DRAFT", noticePayload("<b>正文</b>"), SNAPSHOT))));
        }

        assertAll(
                () -> assertInvalidPublisher(null),
                () -> assertInvalidPublisher(Optional.of(
                        new RbacService.ExecutionIdentity(USER_ID, false, "管理员"))),
                () -> assertInvalidPublisher(enabledIdentity(null)),
                () -> assertInvalidPublisher(enabledIdentity(" ")),
                () -> assertInvalidPublisher(enabledIdentity("管".repeat(33))));
    }

    @Test
    void repairExecutionRejectsInvalidIdentifiersRoleAndDisabledActor() {
        when(snapshots.currentSnapshotHash(any(), any())).thenReturn(SNAPSHOT);
        when(rbac.executionIdentityForUser(USER_ID)).thenReturn(enabledIdentity("管理员"));

        try (MockedStatic<StpUtil> ignored = loggedIn(true)) {
            assertAll(
                    () -> assertThrows(IllegalArgumentException.class, () -> adapter.execute(actor, action(
                            "REPAIR_ASSIGN", "{\"repairOrderId\":0,\"assigneeUserId\":9}", SNAPSHOT))),
                    () -> assertThrows(IllegalArgumentException.class, () -> adapter.execute(actor, action(
                            "REPAIR_ASSIGN", "{\"repairOrderId\":42,\"assigneeUserId\":0}", SNAPSHOT))));
        }

        try (MockedStatic<StpUtil> ignored = loggedIn(false)) {
            assertThrows(SecurityException.class, () -> adapter.execute(actor, action(
                    "REPAIR_ASSIGN", "{\"repairOrderId\":42,\"assigneeUserId\":9}", SNAPSHOT)));
        }

        when(rbac.executionIdentityForUser(USER_ID)).thenReturn(Optional.empty());
        try (MockedStatic<StpUtil> ignored = loggedIn(true)) {
            assertThrows(SecurityException.class, () -> adapter.execute(actor, action(
                    "REPAIR_ASSIGN", "{\"repairOrderId\":42,\"assigneeUserId\":9}", SNAPSHOT)));
        }
    }

    private void assertInvalidPublisher(Optional<RbacService.ExecutionIdentity> user) {
        when(rbac.executionIdentityForUser(USER_ID)).thenReturn(user);
        try (MockedStatic<StpUtil> ignored = loggedIn(true)) {
            assertThrows(RuntimeException.class, () -> adapter.execute(actor, action(
                    "NOTICE_CREATE_DRAFT", noticePayload("正文"), SNAPSHOT)));
        }
    }

    private MockedStatic<StpUtil> loggedIn(boolean admin) {
        MockedStatic<StpUtil> stp = mockStatic(StpUtil.class);
        stp.when(StpUtil::isLogin).thenReturn(true);
        stp.when(StpUtil::getLoginIdAsLong).thenReturn(USER_ID);
        stp.when(() -> StpUtil.hasRole("ADMIN")).thenReturn(admin);
        return stp;
    }

    private ApprovedBusinessActionPort.ApprovedBusinessAction action(
            String type, String payload, String snapshot) {
        String canonical = CanonicalJsonHasher.canonicalize(payload);
        return new ApprovedBusinessActionPort.ApprovedBusinessAction(
                type, canonical, CanonicalJsonHasher.sha256(canonical), snapshot);
    }

    private String noticePayload(String content) {
        try {
            return new ObjectMapper().writeValueAsString(java.util.Map.of(
                    "title", "通知", "type", "安全", "status", "草稿", "content", content));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String hash(String payload) {
        return CanonicalJsonHasher.sha256(CanonicalJsonHasher.canonicalize(payload));
    }

    private Optional<RbacService.ExecutionIdentity> enabledIdentity(String displayName) {
        return Optional.of(new RbacService.ExecutionIdentity(USER_ID, true, displayName));
    }
}
