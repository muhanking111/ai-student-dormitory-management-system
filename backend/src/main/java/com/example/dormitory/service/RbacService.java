package com.example.dormitory.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.dormitory.common.BusinessException;
import com.example.dormitory.common.PageResponse;
import com.example.dormitory.domain.Permission;
import com.example.dormitory.domain.Role;
import com.example.dormitory.domain.RolePermission;
import com.example.dormitory.domain.UserAccount;
import com.example.dormitory.domain.UserRole;
import com.example.dormitory.dto.PermissionResponse;
import com.example.dormitory.dto.RoleCreateRequest;
import com.example.dormitory.dto.RoleOptionResponse;
import com.example.dormitory.dto.RoleResponse;
import com.example.dormitory.dto.RoleUpdateRequest;
import com.example.dormitory.dto.UserCreateRequest;
import com.example.dormitory.dto.UserResponse;
import com.example.dormitory.dto.UserUpdateRequest;
import com.example.dormitory.mapper.PermissionMapper;
import com.example.dormitory.mapper.RoleMapper;
import com.example.dormitory.mapper.RolePermissionMapper;
import com.example.dormitory.mapper.UserAccountMapper;
import com.example.dormitory.mapper.UserRoleMapper;
import com.example.dormitory.ai.security.StepUpGrantRevocationPort;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

@Service
public class RbacService {

    private final UserAccountMapper userAccountMapper;
    private final RoleMapper roleMapper;
    private final PermissionMapper permissionMapper;
    private final UserRoleMapper userRoleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final PasswordEncoder passwordEncoder;
    private final StepUpGrantRevocationPort stepUpGrantRevocation;

    public RbacService(
            UserAccountMapper userAccountMapper,
            RoleMapper roleMapper,
            PermissionMapper permissionMapper,
            UserRoleMapper userRoleMapper,
            RolePermissionMapper rolePermissionMapper,
            PasswordEncoder passwordEncoder,
            StepUpGrantRevocationPort stepUpGrantRevocation) {
        this.userAccountMapper = userAccountMapper;
        this.roleMapper = roleMapper;
        this.permissionMapper = permissionMapper;
        this.userRoleMapper = userRoleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.passwordEncoder = passwordEncoder;
        this.stepUpGrantRevocation = stepUpGrantRevocation;
    }

    public PageResponse<UserResponse> users(long page, long pageSize, String keyword, Boolean enabled) {
        validatePage(page, pageSize);
        LambdaQueryWrapper<UserAccount> query = Wrappers.lambdaQuery();
        if (StringUtils.hasText(keyword)) {
            query.and(wrapper -> wrapper
                    .like(UserAccount::getUsername, keyword.trim())
                    .or()
                    .like(UserAccount::getDisplayName, keyword.trim()));
        }
        if (enabled != null) query.eq(UserAccount::getEnabled, enabled);
        query.orderByAsc(UserAccount::getId);
        IPage<UserAccount> result = userAccountMapper.selectPage(Page.of(page, pageSize), query);
        return new PageResponse<>(result.getRecords().stream().map(this::toUserResponse).toList(),
                result.getTotal(), page, pageSize);
    }

    @Transactional
    public UserResponse createUser(UserCreateRequest request) {
        StpUtil.checkPermission("system:user:write");
        Long existing = userAccountMapper.selectCount(
                Wrappers.<UserAccount>lambdaQuery().eq(UserAccount::getUsername, request.username()));
        if (existing > 0) throw new BusinessException(HttpStatus.CONFLICT, "用户名已存在");
        List<Role> roles = requireEnabledRoles(request.roleIds());
        UserAccount user = new UserAccount(null, request.username(), passwordEncoder.encode(request.password()),
                request.displayName(), roles.getFirst().getCode(), request.enabled());
        userAccountMapper.insert(user);
        replaceUserRoles(user, roles);
        return toUserResponse(user);
    }

    @Transactional
    public UserResponse updateUser(Long id, UserUpdateRequest request, long operatorId) {
        StpUtil.checkPermission("system:user:write");
        UserAccount user = requireUserForUpdate(id);
        if (id == operatorId && !request.enabled()) {
            throw new BusinessException(HttpStatus.CONFLICT, "不能停用当前登录账号");
        }
        List<UserRole> currentMemberships = lockUserRoles(id);
        List<Role> lockedRoles = lockRolesForUserMutation(currentMemberships, request.roleIds());
        List<Role> roles = requireEnabledRoles(request.roleIds(), lockedRoles);
        boolean removesLastAdmin = Boolean.TRUE.equals(user.getEnabled())
                && hasLockedRole(user, currentMemberships, lockedRoles, "ADMIN")
                && (!request.enabled() || roles.stream().noneMatch(role -> "ADMIN".equals(role.getCode())))
                && userRoleMapper.countEnabledUsersByRoleCode("ADMIN") <= 1;
        if (removesLastAdmin) {
            throw new BusinessException(HttpStatus.CONFLICT, "系统至少需要保留一个启用的管理员");
        }
        user.setDisplayName(request.displayName());
        user.setEnabled(request.enabled());
        if (StringUtils.hasText(request.password())) {
            user.setPasswordHash(passwordEncoder.encode(request.password()));
        }
        replaceUserRoles(user, roles);
        userAccountMapper.updateById(user);
        stepUpGrantRevocation.revokeActor(id, java.time.Instant.now());
        if (id != operatorId) StpUtil.kickout(id);
        return toUserResponse(user);
    }

    @Transactional
    public void deleteUser(Long id, long operatorId) {
        StpUtil.checkPermission("system:user:write");
        UserAccount user = requireUserForUpdate(id);
        if (id == operatorId) throw new BusinessException(HttpStatus.CONFLICT, "不能删除当前登录账号");
        List<UserRole> currentMemberships = lockUserRoles(id);
        List<Role> lockedRoles = lockRolesForUserMutation(currentMemberships, List.of());
        if (Boolean.TRUE.equals(user.getEnabled())
                && hasLockedRole(user, currentMemberships, lockedRoles, "ADMIN")
                && userRoleMapper.countEnabledUsersByRoleCode("ADMIN") <= 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "系统至少需要保留一个启用的管理员");
        }
        StpUtil.kickout(id);
        stepUpGrantRevocation.revokeActor(id, java.time.Instant.now());
        userRoleMapper.delete(Wrappers.<UserRole>lambdaQuery().eq(UserRole::getUserId, id));
        userAccountMapper.deleteById(id);
    }

    public PageResponse<RoleResponse> roles(long page, long pageSize, String keyword, Boolean enabled) {
        validatePage(page, pageSize);
        LambdaQueryWrapper<Role> query = Wrappers.lambdaQuery();
        if (StringUtils.hasText(keyword)) {
            query.and(wrapper -> wrapper.like(Role::getCode, keyword.trim())
                    .or().like(Role::getName, keyword.trim()));
        }
        if (enabled != null) query.eq(Role::getEnabled, enabled);
        query.orderByAsc(Role::getId);
        IPage<Role> result = roleMapper.selectPage(Page.of(page, pageSize), query);
        return new PageResponse<>(result.getRecords().stream().map(this::toRoleResponse).toList(),
                result.getTotal(), page, pageSize);
    }

    public List<RoleOptionResponse> roleOptions() {
        return roleMapper.selectList(Wrappers.<Role>lambdaQuery().orderByAsc(Role::getId))
                .stream().map(this::toRoleOption).toList();
    }

    public List<PermissionResponse> permissions() {
        return permissionMapper.selectList(Wrappers.<Permission>lambdaQuery()
                        .orderByAsc(Permission::getModule).orderByAsc(Permission::getId))
                .stream().map(this::toPermissionResponse).toList();
    }

    @Transactional
    public RoleResponse createRole(RoleCreateRequest request) {
        StpUtil.checkPermission("system:role:write");
        Long existing = roleMapper.selectCount(Wrappers.<Role>lambdaQuery().eq(Role::getCode, request.code()));
        if (existing > 0) throw new BusinessException(HttpStatus.CONFLICT, "角色编码已存在");
        List<Permission> permissions = requirePermissions(request.permissionIds());
        Role role = new Role(null, request.code(), request.name(), request.description(), request.enabled(), false);
        roleMapper.insert(role);
        replaceRolePermissions(role.getId(), permissions);
        return toRoleResponse(role);
    }

    @Transactional
    public RoleResponse updateRole(Long id, RoleUpdateRequest request) {
        StpUtil.checkPermission("system:role:write");
        Role role = requireRole(id);
        if (Boolean.TRUE.equals(role.getBuiltIn())) {
            throw new BusinessException(HttpStatus.CONFLICT, "内置角色不能修改");
        }
        List<Permission> permissions = requirePermissions(request.permissionIds());
        role.setName(request.name());
        role.setDescription(request.description());
        role.setEnabled(request.enabled());
        roleMapper.updateById(role);
        replaceRolePermissions(id, permissions);
        return toRoleResponse(role);
    }

    @Transactional
    public void deleteRole(Long id) {
        StpUtil.checkPermission("system:role:write");
        Role role = requireRole(id);
        if (Boolean.TRUE.equals(role.getBuiltIn())) {
            throw new BusinessException(HttpStatus.CONFLICT, "内置角色不能删除");
        }
        if (userRoleMapper.countUsersByRoleId(id) > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "角色仍被用户使用，不能删除");
        }
        rolePermissionMapper.delete(Wrappers.<RolePermission>lambdaQuery()
                .eq(RolePermission::getRoleId, id));
        roleMapper.deleteById(id);
    }

    public List<String> roleCodesForUser(Long userId) {
        List<String> codes = roleMapper.selectByUserId(userId).stream().map(Role::getCode).toList();
        if (!codes.isEmpty()) return codes;
        UserAccount user = userAccountMapper.selectById(userId);
        return user == null || !StringUtils.hasText(user.getRoleCode()) ? List.of() : List.of(user.getRoleCode());
    }

    public List<String> permissionCodesForUser(Long userId) {
        List<String> codes = permissionMapper.selectByUserId(userId).stream().map(Permission::getCode).toList();
        if (!codes.isEmpty()) return codes;
        UserAccount user = userAccountMapper.selectById(userId);
        if (user == null) return List.of();
        return switch (user.getRoleCode()) {
            case "ADMIN" -> List.of(
                    "dashboard:read", "dormitory:read", "dormitory:write", "student:read", "student:write",
                    "checkin:read", "checkin:review", "repair:read", "repair:write", "payment:read",
                    "payment:write", "hygiene:read", "hygiene:write", "notice:read", "notice:write",
                    "system:user:read", "system:user:write", "system:role:read", "system:role:write");
            case "DORM_MANAGER" -> List.of("dashboard:read", "dormitory:read", "dormitory:write");
            case "VIEWER" -> List.of("dashboard:read", "dormitory:read");
            default -> List.of();
        };
    }

    public AuthorizationSnapshot authorizationSnapshotForUser(Long userId) {
        UserAccount user = userAccountMapper.selectById(userId);
        if (user == null) {
            return new AuthorizationSnapshot(userId, false, List.of(), List.of());
        }
        return new AuthorizationSnapshot(
                userId,
                Boolean.TRUE.equals(user.getEnabled()),
                roleCodesForUser(userId),
                permissionCodesForUser(userId));
    }

    /**
     * 锁定执行者的授权事实直到调用方事务结束，使撤权与后续业务写形成明确的先后关系。
     */
    @Transactional
    public AuthorizationSnapshot authorizationSnapshotForUserForUpdate(Long userId) {
        if (userId == null || userId < 1) {
            return new AuthorizationSnapshot(userId, false, List.of(), List.of());
        }
        UserAccount user = userAccountMapper.selectOne(Wrappers.<UserAccount>query()
                .eq("id", userId).last("FOR UPDATE"));
        if (user == null) {
            return new AuthorizationSnapshot(userId, false, List.of(), List.of());
        }
        List<UserRole> memberships = lockUserRoles(userId);
        List<Long> roleIds = memberships.stream().map(UserRole::getRoleId).distinct().toList();
        List<String> fallbackRoleCodes = memberships.isEmpty() && StringUtils.hasText(user.getRoleCode())
                ? List.of(user.getRoleCode()) : List.of();
        List<Role> roles = lockRoles(roleIds, fallbackRoleCodes);
        List<String> roleCodes;
        if (memberships.isEmpty()) {
            roleCodes = fallbackRoleCodes;
        } else {
            var membershipRoleIds = memberships.stream().map(UserRole::getRoleId)
                    .collect(java.util.stream.Collectors.toSet());
            roleCodes = roles.stream()
                    .filter(role -> membershipRoleIds.contains(role.getId()))
                    .map(Role::getCode)
                    .toList();
        }
        List<String> permissionCodes = permissionMapper.selectByUserId(userId).stream()
                .map(Permission::getCode)
                .toList();
        if (permissionCodes.isEmpty()) permissionCodes = legacyPermissionCodes(user.getRoleCode());
        return new AuthorizationSnapshot(
                userId, Boolean.TRUE.equals(user.getEnabled()), roleCodes, permissionCodes);
    }

    public Optional<ExecutionIdentity> executionIdentityForUser(Long userId) {
        if (userId == null || userId < 1) return Optional.empty();
        UserAccount user = userAccountMapper.selectById(userId);
        if (user == null) return Optional.empty();
        return Optional.of(new ExecutionIdentity(
                user.getId(), Boolean.TRUE.equals(user.getEnabled()), user.getDisplayName()));
    }

    public boolean verifyEnabledUserPassword(Long userId, String rawPassword) {
        if (userId == null || userId < 1 || rawPassword == null) return false;
        UserAccount user = userAccountMapper.selectById(userId);
        return user != null
                && Boolean.TRUE.equals(user.getEnabled())
                && passwordEncoder.matches(rawPassword, user.getPasswordHash());
    }

    /**
     * 在调用方事务中锁定维修指派依赖的账号、成员关系和角色事实，防止校验后被并发撤权。
     */
    @Transactional
    public boolean hasEnabledRoleForUserForUpdate(Long userId, String roleCode) {
        if (userId == null || userId < 1 || !StringUtils.hasText(roleCode)) return false;
        String expected = roleCode.trim().toUpperCase(java.util.Locale.ROOT);
        UserAccount user = userAccountMapper.selectOne(Wrappers.<UserAccount>query()
                .eq("id", userId).last("FOR UPDATE"));
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())) return false;
        List<UserRole> memberships = lockUserRoles(userId);
        List<Long> roleIds = memberships.stream().map(UserRole::getRoleId).distinct().toList();
        List<Role> roles = lockRoles(roleIds, List.of(expected));
        return hasLockedRole(user, memberships, roles, expected)
                && roles.stream().anyMatch(role -> Boolean.TRUE.equals(role.getEnabled())
                        && expected.equals(role.getCode()));
    }

    public record ExecutionIdentity(long userId, boolean enabled, String displayName) {
        public ExecutionIdentity {
            if (userId < 1) throw new IllegalArgumentException("执行身份用户 ID 不合法");
        }
    }

    public record AuthorizationSnapshot(
            Long userId,
            boolean enabled,
            List<String> roleCodes,
            List<String> permissionCodes) {
        public AuthorizationSnapshot {
            roleCodes = List.copyOf(roleCodes);
            permissionCodes = List.copyOf(permissionCodes);
        }
    }

    private void validatePage(long page, long pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "分页参数不合法");
        }
    }

    private UserAccount requireUserForUpdate(Long id) {
        UserAccount user = userAccountMapper.selectOne(Wrappers.<UserAccount>query()
                .eq("id", id).last("FOR UPDATE"));
        if (user == null) throw new BusinessException(HttpStatus.NOT_FOUND, "用户不存在");
        return user;
    }

    private List<UserRole> lockUserRoles(Long userId) {
        return new ArrayList<>(userRoleMapper.selectList(Wrappers.<UserRole>query()
                .eq("user_id", userId)
                .orderByAsc("id")
                .last("FOR UPDATE")));
    }

    private List<Role> lockRolesForUserMutation(List<UserRole> memberships, List<Long> requestedRoleIds) {
        LinkedHashSet<Long> roleIds = new LinkedHashSet<>(requestedRoleIds);
        memberships.stream().map(UserRole::getRoleId).forEach(roleIds::add);
        List<Role> roles = lockRoles(new ArrayList<>(roleIds), List.of("ADMIN"));
        if (roles.stream().noneMatch(role -> "ADMIN".equals(role.getCode()))) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "管理员角色不可用");
        }
        return roles;
    }

    private List<Role> lockRoles(List<Long> roleIds, List<String> roleCodes) {
        var query = Wrappers.<Role>query();
        query.and(scope -> {
            boolean hasIds = !roleIds.isEmpty();
            if (hasIds) scope.in("id", roleIds);
            if (!roleCodes.isEmpty()) {
                if (hasIds) scope.or();
                scope.in("code", roleCodes);
            }
        });
        return new ArrayList<>(roleMapper.selectList(query.orderByAsc("id").last("FOR UPDATE")));
    }

    private boolean hasLockedRole(
            UserAccount user,
            List<UserRole> memberships,
            List<Role> lockedRoles,
            String roleCode) {
        if (memberships.isEmpty()) return roleCode.equals(user.getRoleCode());
        var membershipRoleIds = memberships.stream()
                .map(UserRole::getRoleId)
                .collect(java.util.stream.Collectors.toSet());
        return lockedRoles.stream().anyMatch(role -> membershipRoleIds.contains(role.getId())
                && roleCode.equals(role.getCode()));
    }

    private List<String> legacyPermissionCodes(String roleCode) {
        if (roleCode == null) return List.of();
        return switch (roleCode) {
            case "ADMIN" -> List.of(
                    "dashboard:read", "dormitory:read", "dormitory:write", "student:read", "student:write",
                    "checkin:read", "checkin:review", "repair:read", "repair:write", "payment:read",
                    "payment:write", "hygiene:read", "hygiene:write", "notice:read", "notice:write",
                    "system:user:read", "system:user:write", "system:role:read", "system:role:write");
            case "DORM_MANAGER" -> List.of("dashboard:read", "dormitory:read", "dormitory:write");
            case "VIEWER" -> List.of("dashboard:read", "dormitory:read");
            default -> List.of();
        };
    }

    private Role requireRole(Long id) {
        Role role = roleMapper.selectOne(Wrappers.<Role>query()
                .eq("id", id).last("FOR UPDATE"));
        if (role == null) throw new BusinessException(HttpStatus.NOT_FOUND, "角色不存在");
        return role;
    }

    private List<Role> requireEnabledRoles(List<Long> roleIds) {
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(roleIds));
        List<Role> roles = new ArrayList<>(roleMapper.selectByIds(ids));
        if (roles.size() != ids.size() || roles.stream().anyMatch(role -> !Boolean.TRUE.equals(role.getEnabled()))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "选择的角色不存在或已停用");
        }
        roles.sort(java.util.Comparator.comparingInt(role -> ids.indexOf(role.getId())));
        return roles;
    }

    private List<Role> requireEnabledRoles(List<Long> roleIds, List<Role> lockedRoles) {
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(roleIds));
        var rolesById = lockedRoles.stream().collect(java.util.stream.Collectors.toMap(Role::getId, role -> role));
        List<Role> roles = ids.stream().map(rolesById::get).toList();
        if (roles.stream().anyMatch(java.util.Objects::isNull)
                || roles.stream().anyMatch(role -> !Boolean.TRUE.equals(role.getEnabled()))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "选择的角色不存在或已停用");
        }
        return roles;
    }

    private List<Permission> requirePermissions(List<Long> permissionIds) {
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(permissionIds));
        List<Permission> permissions = new ArrayList<>(permissionMapper.selectByIds(ids));
        if (permissions.size() != ids.size()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "选择的权限不存在");
        }
        permissions.sort(java.util.Comparator.comparingInt(permission -> ids.indexOf(permission.getId())));
        return permissions;
    }

    private void replaceUserRoles(UserAccount user, List<Role> roles) {
        userRoleMapper.delete(Wrappers.<UserRole>lambdaQuery().eq(UserRole::getUserId, user.getId()));
        roles.forEach(role -> userRoleMapper.insert(new UserRole(null, user.getId(), role.getId())));
        user.setRoleCode(roles.getFirst().getCode());
    }

    private void replaceRolePermissions(Long roleId, List<Permission> permissions) {
        rolePermissionMapper.delete(Wrappers.<RolePermission>lambdaQuery()
                .eq(RolePermission::getRoleId, roleId));
        permissions.forEach(permission -> rolePermissionMapper.insert(
                new RolePermission(null, roleId, permission.getId())));
    }

    private UserResponse toUserResponse(UserAccount user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getDisplayName(), user.getEnabled(),
                roleMapper.selectByUserId(user.getId()).stream().map(this::toRoleOption).toList());
    }

    private RoleOptionResponse toRoleOption(Role role) {
        return new RoleOptionResponse(role.getId(), role.getCode(), role.getName(), role.getEnabled(), role.getBuiltIn());
    }

    private PermissionResponse toPermissionResponse(Permission permission) {
        return new PermissionResponse(permission.getId(), permission.getCode(), permission.getName(), permission.getModule());
    }

    private RoleResponse toRoleResponse(Role role) {
        List<Permission> permissions = permissionMapper.selectByRoleId(role.getId());
        return new RoleResponse(role.getId(), role.getCode(), role.getName(), role.getDescription(), role.getEnabled(),
                role.getBuiltIn(), permissions.stream().map(Permission::getId).toList(),
                permissions.stream().map(Permission::getCode).toList());
    }
}
