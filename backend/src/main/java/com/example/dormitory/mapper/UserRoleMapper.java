package com.example.dormitory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.dormitory.domain.UserRole;
import org.apache.ibatis.annotations.Select;

public interface UserRoleMapper extends BaseMapper<UserRole> {

    @Select("SELECT COUNT(*) FROM sys_user_role WHERE role_id = #{roleId}")
    long countUsersByRoleId(Long roleId);

    @Select("""
            SELECT COUNT(DISTINCT u.id)
            FROM sys_user u
            INNER JOIN sys_user_role ur ON ur.user_id = u.id
            INNER JOIN sys_role r ON r.id = ur.role_id
            WHERE r.code = #{roleCode} AND u.enabled = TRUE
            """)
    long countEnabledUsersByRoleCode(String roleCode);
}
