package com.example.dormitory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.dormitory.domain.Permission;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface PermissionMapper extends BaseMapper<Permission> {

    @Select("""
            SELECT DISTINCT p.id, p.code, p.name, p.module
            FROM sys_permission p
            INNER JOIN sys_role_permission rp ON rp.permission_id = p.id
            INNER JOIN sys_user_role ur ON ur.role_id = rp.role_id
            INNER JOIN sys_role r ON r.id = ur.role_id
            WHERE ur.user_id = #{userId} AND r.enabled = TRUE
            ORDER BY p.id
            """)
    List<Permission> selectByUserId(Long userId);

    @Select("""
            SELECT p.id, p.code, p.name, p.module
            FROM sys_permission p
            INNER JOIN sys_role_permission rp ON rp.permission_id = p.id
            WHERE rp.role_id = #{roleId}
            ORDER BY p.id
            """)
    List<Permission> selectByRoleId(Long roleId);
}
