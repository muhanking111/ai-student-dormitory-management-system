package com.example.dormitory;

import com.example.dormitory.domain.UserAccount;
import com.example.dormitory.mapper.UserAccountMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class RbacControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserAccountMapper userAccountMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long viewerRoleId;

    @BeforeEach
    void setUpViewer() {
        viewerRoleId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_role WHERE code = 'VIEWER'", Long.class);
        UserAccount viewer = userAccountMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<UserAccount>lambdaQuery()
                        .eq(UserAccount::getUsername, "rbac-viewer"));
        if (viewer == null) {
            viewer = new UserAccount(null, "rbac-viewer", passwordEncoder.encode("viewer-password-123"),
                    "RBAC 只读用户", "VIEWER", true);
            userAccountMapper.insert(viewer);
        }
        Long linkCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_user_role WHERE user_id = ? AND role_id = ?",
                Long.class, viewer.getId(), viewerRoleId);
        if (linkCount == 0) {
            jdbcTemplate.update("INSERT INTO sys_user_role(user_id, role_id) VALUES (?, ?)",
                    viewer.getId(), viewerRoleId);
        }
    }

    @Test
    void adminCanListUsersWithoutPasswordHash() throws Exception {
        String token = login("admin", "test-password-123");

        mockMvc.perform(get("/api/users")
                        .header("Authorization", token)
                        .param("page", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].username").exists())
                .andExpect(jsonPath("$.data.records[0].passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data.total").isNumber());
    }

    @Test
    void viewerCannotReadUserManagement() throws Exception {
        String token = login("rbac-viewer", "viewer-password-123");

        mockMvc.perform(get("/api/users").header("Authorization", token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void adminCanCreateUserAndNewUserReceivesRolePermissions() throws Exception {
        String token = login("admin", "test-password-123");
        String username = "manager-" + UUID.randomUUID().toString().substring(0, 8);

        mockMvc.perform(post("/api/users")
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "displayName", "新宿舍管理员",
                                "password", "Manager-password-123",
                                "enabled", true,
                                "roleIds", java.util.List.of(viewerRoleId)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.username").value(username))
                .andExpect(jsonPath("$.data.roles[0].code").value("VIEWER"));

        String newUserToken = login(username, "Manager-password-123");
        mockMvc.perform(get("/api/auth/me").header("Authorization", newUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roleCodes[0]").value("VIEWER"))
                .andExpect(jsonPath("$.data.permissions").isArray())
                .andExpect(jsonPath("$.data.permissions[?(@ == 'dormitory:read')]").exists())
                .andExpect(jsonPath("$.data.permissions[?(@ == 'system:user:write')]").doesNotExist());
    }

    @Test
    void duplicateUsernameReturnsConflict() throws Exception {
        String token = login("admin", "test-password-123");

        mockMvc.perform(post("/api/users")
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "admin",
                                "displayName", "重复管理员",
                                "password", "Duplicate-password-123",
                                "enabled", true,
                                "roleIds", java.util.List.of(viewerRoleId)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("用户名已存在"));
    }

    @Test
    void adminCanCreateRoleAndUpdateItsPermissions() throws Exception {
        String token = login("admin", "test-password-123");
        Long dormitoryReadId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_permission WHERE code = 'dormitory:read'", Long.class);
        Long studentReadId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_permission WHERE code = 'student:read'", Long.class);
        String code = "TEST_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        MvcResult created = mockMvc.perform(post("/api/roles")
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code,
                                "name", "测试角色",
                                "description", "接口测试角色",
                                "enabled", true,
                                "permissionIds", java.util.List.of(dormitoryReadId)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.permissionCodes[0]").value("dormitory:read"))
                .andReturn();
        JsonNode body = objectMapper.readTree(created.getResponse().getContentAsString());
        long roleId = body.path("data").path("id").asLong();

        mockMvc.perform(patch("/api/roles/{id}", roleId)
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "测试角色已更新",
                                "description", "已更新",
                                "enabled", true,
                                "permissionIds", java.util.List.of(dormitoryReadId, studentReadId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("测试角色已更新"))
                .andExpect(jsonPath("$.data.permissionCodes.length()").value(2));
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        var cookie = result.getResponse().getCookie("Authorization");
        assertNotNull(cookie);
        return cookie.getValue();
    }
}
