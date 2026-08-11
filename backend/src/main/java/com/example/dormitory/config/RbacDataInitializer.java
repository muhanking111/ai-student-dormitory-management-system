package com.example.dormitory.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.dormitory.domain.Permission;
import com.example.dormitory.domain.Role;
import com.example.dormitory.domain.RolePermission;
import com.example.dormitory.domain.UserAccount;
import com.example.dormitory.domain.UserRole;
import com.example.dormitory.mapper.PermissionMapper;
import com.example.dormitory.mapper.RoleMapper;
import com.example.dormitory.mapper.RolePermissionMapper;
import com.example.dormitory.mapper.UserAccountMapper;
import com.example.dormitory.mapper.UserRoleMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Order(20)
public class RbacDataInitializer implements ApplicationRunner {

    private static final List<PermissionSeed> PERMISSIONS = List.of(
            new PermissionSeed("dashboard:read", "查看数据驾驶舱", "dashboard"),
            new PermissionSeed("dormitory:read", "查看宿舍资源", "dormitory"),
            new PermissionSeed("dormitory:write", "管理宿舍资源", "dormitory"),
            new PermissionSeed("student:read", "查看学生信息", "student"),
            new PermissionSeed("student:write", "管理学生信息", "student"),
            new PermissionSeed("checkin:read", "查看入住业务", "checkin"),
            new PermissionSeed("checkin:review", "审核入住业务", "checkin"),
            new PermissionSeed("repair:read", "查看维修业务", "repair"),
            new PermissionSeed("repair:write", "处理维修业务", "repair"),
            new PermissionSeed("payment:read", "查看费用业务", "payment"),
            new PermissionSeed("payment:write", "管理费用业务", "payment"),
            new PermissionSeed("hygiene:read", "查看卫生检查", "hygiene"),
            new PermissionSeed("hygiene:write", "管理卫生检查", "hygiene"),
            new PermissionSeed("notice:read", "查看公告", "notice"),
            new PermissionSeed("notice:write", "管理公告", "notice"),
            new PermissionSeed("system:user:read", "查看用户", "system"),
            new PermissionSeed("system:user:write", "管理用户", "system"),
            new PermissionSeed("system:role:read", "查看角色权限", "system"),
            new PermissionSeed("system:role:write", "管理角色权限", "system"),
            new PermissionSeed("ai:assistant:use", "使用 AI 智能助手", "ai"),
            new PermissionSeed("ai:knowledge:read", "使用 AI 知识检索", "ai"),
            new PermissionSeed("ai:knowledge:manage", "管理 AI 知识源", "ai"),
            new PermissionSeed("ai:knowledge:publish-public", "审批公开知识版本", "ai"),
            new PermissionSeed("ai:dashboard:query", "使用自然语言指标查询", "ai"),
            new PermissionSeed("ai:repair:triage", "使用维修智能分诊", "ai"),
            new PermissionSeed("ai:notice:draft", "使用公告 AI 草稿", "ai"),
            new PermissionSeed("ai:risk:read", "查看 AI 风险案例", "ai"),
            new PermissionSeed("ai:risk:manage", "处置 AI 风险案例", "ai"),
            new PermissionSeed("ai:approval:review", "复核 AI 业务提案", "ai"),
            new PermissionSeed("ai:audit:read", "查看脱敏 AI 运行审计", "ai"),
            new PermissionSeed("ai:audit:content:read", "按理由读取必要审计正文", "ai"),
            new PermissionSeed("ai:config:manage", "管理 AI 非密钥配置", "ai"),
            new PermissionSeed("ai:eval:run", "运行 AI 离线评测", "ai")
    );

    private final PermissionMapper permissionMapper;
    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final UserAccountMapper userAccountMapper;
    private final UserRoleMapper userRoleMapper;

    public RbacDataInitializer(
            PermissionMapper permissionMapper,
            RoleMapper roleMapper,
            RolePermissionMapper rolePermissionMapper,
            UserAccountMapper userAccountMapper,
            UserRoleMapper userRoleMapper) {
        this.permissionMapper = permissionMapper;
        this.roleMapper = roleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.userAccountMapper = userAccountMapper;
        this.userRoleMapper = userRoleMapper;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Map<String, Permission> permissions = seedPermissions();
        Map<String, Role> roles = seedRoles();
        seedRolePermissions(roles, permissions);
        migrateLegacyUserRoles(roles);
    }

    private Map<String, Permission> seedPermissions() {
        Map<String, Permission> result = new LinkedHashMap<>();
        for (PermissionSeed seed : PERMISSIONS) {
            Permission permission = permissionMapper.selectOne(
                    Wrappers.<Permission>lambdaQuery().eq(Permission::getCode, seed.code()));
            if (permission == null) {
                permission = new Permission(null, seed.code(), seed.name(), seed.module());
                permissionMapper.insert(permission);
            }
            result.put(seed.code(), permission);
        }
        return result;
    }

    private Map<String, Role> seedRoles() {
        Map<String, Role> roles = new LinkedHashMap<>();
        roles.put("ADMIN", ensureRole("ADMIN", "系统管理员", "拥有全部系统权限", true));
        roles.put("DORM_MANAGER", ensureRole("DORM_MANAGER", "宿舍管理员", "负责住宿资源和日常宿管业务", true));
        roles.put("REPAIRER", ensureRole("REPAIRER", "维修人员", "负责维修单处理和维修记录", true));
        roles.put("VIEWER", ensureRole("VIEWER", "只读人员", "仅查看授权业务数据", true));
        return roles;
    }

    private Role ensureRole(String code, String name, String description, boolean builtIn) {
        Role role = roleMapper.selectOne(Wrappers.<Role>lambdaQuery().eq(Role::getCode, code));
        if (role == null) {
            role = new Role(null, code, name, description, true, builtIn);
            roleMapper.insert(role);
        }
        return role;
    }

    private void seedRolePermissions(Map<String, Role> roles, Map<String, Permission> permissions) {
        assignMissing(roles.get("ADMIN"), permissionSubset(permissions,
                "dashboard:read", "dormitory:read", "dormitory:write", "student:read", "student:write",
                "checkin:read", "checkin:review", "repair:read", "repair:write", "payment:read", "payment:write",
                "hygiene:read", "hygiene:write", "notice:read", "notice:write", "system:user:read",
                "system:user:write", "system:role:read", "system:role:write",
                "ai:assistant:use", "ai:knowledge:read", "ai:knowledge:manage", "ai:dashboard:query",
                "ai:repair:triage", "ai:notice:draft", "ai:risk:read", "ai:risk:manage",
                "ai:approval:review", "ai:audit:read", "ai:config:manage", "ai:eval:run"));
        assignMissing(roles.get("DORM_MANAGER"), permissionSubset(permissions,
                "dashboard:read", "dormitory:read", "dormitory:write", "student:read", "student:write",
                "checkin:read", "checkin:review", "hygiene:read", "hygiene:write", "notice:read", "notice:write"));
        assignMissing(roles.get("REPAIRER"), permissionSubset(permissions,
                "dashboard:read", "repair:read", "repair:write"));
        assignMissing(roles.get("VIEWER"), permissionSubset(permissions,
                "dashboard:read", "dormitory:read", "student:read", "checkin:read", "repair:read",
                "payment:read", "hygiene:read", "notice:read"));
    }

    private List<Permission> permissionSubset(Map<String, Permission> permissions, String... codes) {
        return java.util.Arrays.stream(codes).map(permissions::get).toList();
    }

    private void assignMissing(Role role, List<Permission> permissions) {
        for (Permission permission : permissions) {
            Long count = rolePermissionMapper.selectCount(
                    Wrappers.<RolePermission>lambdaQuery()
                            .eq(RolePermission::getRoleId, role.getId())
                            .eq(RolePermission::getPermissionId, permission.getId()));
            if (count == 0) {
                rolePermissionMapper.insert(new RolePermission(null, role.getId(), permission.getId()));
            }
        }
    }

    private void migrateLegacyUserRoles(Map<String, Role> roles) {
        for (UserAccount user : userAccountMapper.selectList(null)) {
            Long count = userRoleMapper.selectCount(
                    Wrappers.<UserRole>lambdaQuery().eq(UserRole::getUserId, user.getId()));
            if (count > 0) continue;
            Role role = roles.getOrDefault(user.getRoleCode(), roles.get("VIEWER"));
            userRoleMapper.insert(new UserRole(null, user.getId(), role.getId()));
        }
    }

    private record PermissionSeed(String code, String name, String module) {
    }
}
