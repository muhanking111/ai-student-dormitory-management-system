package com.example.dormitory.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.example.dormitory.ai.security.StepUpGrantRevocationPort;
import com.example.dormitory.domain.Permission;
import com.example.dormitory.domain.Role;
import com.example.dormitory.domain.UserAccount;
import com.example.dormitory.domain.UserRole;
import com.example.dormitory.dto.UserUpdateRequest;
import com.example.dormitory.dto.RoleUpdateRequest;
import com.example.dormitory.mapper.PermissionMapper;
import com.example.dormitory.mapper.RoleMapper;
import com.example.dormitory.mapper.RolePermissionMapper;
import com.example.dormitory.mapper.UserAccountMapper;
import com.example.dormitory.mapper.UserRoleMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RbacAssignmentLockContractTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void repairAssigneeValidationLocksUserMembershipAndRoleFactsInTheCallingTransaction() {
        UserAccountMapper users = mock(UserAccountMapper.class);
        RoleMapper roles = mock(RoleMapper.class);
        UserRoleMapper memberships = mock(UserRoleMapper.class);
        when(users.selectOne(any())).thenReturn(
                new UserAccount(7L, "repairer", "hash", "维修员", "REPAIRER", true));
        when(memberships.selectList(any())).thenReturn(List.of(new UserRole(11L, 7L, 13L)));
        when(roles.selectList(any())).thenReturn(List.of(
                new Role(13L, "REPAIRER", "维修员", "", true, true)));
        RbacService service = new RbacService(users, roles, mock(PermissionMapper.class), memberships,
                mock(RolePermissionMapper.class), mock(PasswordEncoder.class),
                mock(StepUpGrantRevocationPort.class));

        assertTrue(service.hasEnabledRoleForUserForUpdate(7L, "REPAIRER"));

        ArgumentCaptor<Wrapper<UserAccount>> userQuery = ArgumentCaptor.forClass(Wrapper.class);
        ArgumentCaptor<Wrapper<UserRole>> membershipQuery = ArgumentCaptor.forClass(Wrapper.class);
        ArgumentCaptor<Wrapper<Role>> roleQuery = ArgumentCaptor.forClass(Wrapper.class);
        verify(users).selectOne(userQuery.capture());
        verify(memberships).selectList(membershipQuery.capture());
        verify(roles).selectList(roleQuery.capture());
        InOrder lockOrder = inOrder(users, memberships, roles);
        lockOrder.verify(users).selectOne(any());
        lockOrder.verify(memberships).selectList(any());
        lockOrder.verify(roles).selectList(any());
        assertTrue(userQuery.getValue().getSqlSegment().toUpperCase().contains("FOR UPDATE"));
        assertTrue(membershipQuery.getValue().getSqlSegment().toUpperCase().contains("FOR UPDATE"));
        assertTrue(roleQuery.getValue().getSqlSegment().toUpperCase().contains("FOR UPDATE"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void userMutationUsesAccountMembershipRoleLockOrder() {
        UserAccountMapper users = mock(UserAccountMapper.class);
        RoleMapper roles = mock(RoleMapper.class);
        UserRoleMapper memberships = mock(UserRoleMapper.class);
        UserAccount user = new UserAccount(7L, "repairer", "hash", "维修员", "REPAIRER", true);
        Role admin = new Role(1L, "ADMIN", "系统管理员", "", true, true);
        Role repairer = new Role(13L, "REPAIRER", "维修员", "", true, true);
        when(users.selectOne(any())).thenReturn(user);
        when(memberships.selectList(any())).thenReturn(List.of(new UserRole(11L, 7L, 13L)));
        when(roles.selectList(any())).thenReturn(List.of(admin, repairer));
        when(roles.selectByUserId(7L)).thenReturn(List.of(repairer));
        RbacService service = new RbacService(users, roles, mock(PermissionMapper.class), memberships,
                mock(RolePermissionMapper.class), mock(PasswordEncoder.class),
                mock(StepUpGrantRevocationPort.class));

        try (MockedStatic<cn.dev33.satoken.stp.StpUtil> ignored = mockStatic(
                cn.dev33.satoken.stp.StpUtil.class)) {
            service.updateUser(7L, new UserUpdateRequest("维修员", null, true, List.of(13L)), 1L);
        }

        ArgumentCaptor<Wrapper<UserAccount>> userLock = ArgumentCaptor.forClass(Wrapper.class);
        ArgumentCaptor<Wrapper<UserRole>> membershipLock = ArgumentCaptor.forClass(Wrapper.class);
        ArgumentCaptor<Wrapper<Role>> roleLock = ArgumentCaptor.forClass(Wrapper.class);
        InOrder lockOrder = inOrder(users, memberships, roles);
        lockOrder.verify(users).selectOne(userLock.capture());
        lockOrder.verify(memberships).selectList(membershipLock.capture());
        lockOrder.verify(roles).selectList(roleLock.capture());
        lockOrder.verify(memberships).delete(any(Wrapper.class));
        assertTrue(userLock.getValue().getSqlSegment().toUpperCase().contains("FOR UPDATE"));
        assertTrue(membershipLock.getValue().getSqlSegment().toUpperCase().contains("FOR UPDATE"));
        assertTrue(roleLock.getValue().getSqlSegment().toUpperCase().contains("FOR UPDATE"));
        assertTrue(roleLock.getValue().getSqlSegment().toUpperCase().contains("ORDER BY ID"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void freshAuthorizationSnapshotLocksAccountMembershipAndRoleFacts() {
        UserAccountMapper users = mock(UserAccountMapper.class);
        RoleMapper roles = mock(RoleMapper.class);
        PermissionMapper permissions = mock(PermissionMapper.class);
        UserRoleMapper memberships = mock(UserRoleMapper.class);
        UserAccount user = new UserAccount(7L, "admin", "hash", "管理员", "ADMIN", true);
        Role admin = new Role(1L, "ADMIN", "系统管理员", "", true, true);
        when(users.selectOne(any())).thenReturn(user);
        when(memberships.selectList(any())).thenReturn(List.of(new UserRole(11L, 7L, 1L)));
        when(roles.selectList(any())).thenReturn(List.of(admin));
        when(permissions.selectByUserId(7L)).thenReturn(List.of());
        RbacService service = new RbacService(users, roles, permissions, memberships,
                mock(RolePermissionMapper.class), mock(PasswordEncoder.class),
                mock(StepUpGrantRevocationPort.class));

        RbacService.AuthorizationSnapshot snapshot = service.authorizationSnapshotForUserForUpdate(7L);

        assertTrue(snapshot.enabled());
        assertTrue(snapshot.roleCodes().contains("ADMIN"));
        ArgumentCaptor<Wrapper<UserAccount>> userLock = ArgumentCaptor.forClass(Wrapper.class);
        ArgumentCaptor<Wrapper<UserRole>> membershipLock = ArgumentCaptor.forClass(Wrapper.class);
        ArgumentCaptor<Wrapper<Role>> roleLock = ArgumentCaptor.forClass(Wrapper.class);
        InOrder lockOrder = inOrder(users, memberships, roles, permissions);
        lockOrder.verify(users).selectOne(userLock.capture());
        lockOrder.verify(memberships).selectList(membershipLock.capture());
        lockOrder.verify(roles).selectList(roleLock.capture());
        lockOrder.verify(permissions).selectByUserId(7L);
        assertTrue(userLock.getValue().getSqlSegment().toUpperCase().contains("FOR UPDATE"));
        assertTrue(membershipLock.getValue().getSqlSegment().toUpperCase().contains("FOR UPDATE"));
        assertTrue(roleLock.getValue().getSqlSegment().toUpperCase().contains("FOR UPDATE"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void rolePermissionMutationLocksRoleBeforeReplacingPermissionFacts() {
        UserAccountMapper users = mock(UserAccountMapper.class);
        RoleMapper roles = mock(RoleMapper.class);
        PermissionMapper permissions = mock(PermissionMapper.class);
        RolePermissionMapper rolePermissions = mock(RolePermissionMapper.class);
        Role custom = new Role(9L, "CUSTOM", "自定义角色", "", true, false);
        Permission noticeWrite = new Permission(5L, "notice:write", "管理公告", "notice");
        when(roles.selectOne(any())).thenReturn(custom);
        when(permissions.selectByIds(List.of(5L))).thenReturn(List.of(noticeWrite));
        when(permissions.selectByRoleId(9L)).thenReturn(List.of(noticeWrite));
        RbacService service = new RbacService(users, roles, permissions, mock(UserRoleMapper.class),
                rolePermissions, mock(PasswordEncoder.class), mock(StepUpGrantRevocationPort.class));

        try (MockedStatic<cn.dev33.satoken.stp.StpUtil> ignored = mockStatic(
                cn.dev33.satoken.stp.StpUtil.class)) {
            service.updateRole(9L, new RoleUpdateRequest("自定义角色", "", true, List.of(5L)));
        }

        ArgumentCaptor<Wrapper<Role>> roleLock = ArgumentCaptor.forClass(Wrapper.class);
        InOrder lockOrder = inOrder(roles, rolePermissions);
        lockOrder.verify(roles).selectOne(roleLock.capture());
        lockOrder.verify(roles).updateById(custom);
        lockOrder.verify(rolePermissions).delete(any(Wrapper.class));
        assertTrue(roleLock.getValue().getSqlSegment().toUpperCase().contains("FOR UPDATE"));
    }
}
