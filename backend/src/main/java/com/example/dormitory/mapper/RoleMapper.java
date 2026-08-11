package com.example.dormitory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.dormitory.domain.Role;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface RoleMapper extends BaseMapper<Role> {

    @Select("""
            SELECT r.id, r.code, r.name, r.description, r.enabled, r.built_in
            FROM sys_role r
            INNER JOIN sys_user_role ur ON ur.role_id = r.id
            WHERE ur.user_id = #{userId} AND r.enabled = TRUE
            ORDER BY r.id
            """)
    List<Role> selectByUserId(Long userId);
}
