package com.example.dormitory.config;

import cn.dev33.satoken.stp.StpInterface;
import com.example.dormitory.domain.UserAccount;
import com.example.dormitory.mapper.UserAccountMapper;
import com.example.dormitory.service.RbacService;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SaTokenPermissionProvider implements StpInterface {

    private final UserAccountMapper userAccountMapper;
    private final RbacService rbacService;

    public SaTokenPermissionProvider(UserAccountMapper userAccountMapper, RbacService rbacService) {
        this.userAccountMapper = userAccountMapper;
        this.rbacService = rbacService;
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        UserAccount user = userAccountMapper.selectById(Long.valueOf(String.valueOf(loginId)));
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())) {
            return List.of();
        }
        return rbacService.permissionCodesForUser(user.getId());
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        UserAccount user = userAccountMapper.selectById(Long.valueOf(String.valueOf(loginId)));
        return user == null || !Boolean.TRUE.equals(user.getEnabled())
                ? List.of()
                : rbacService.roleCodesForUser(user.getId());
    }
}
