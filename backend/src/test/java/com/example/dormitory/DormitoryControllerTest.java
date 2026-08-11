package com.example.dormitory;

import com.example.dormitory.domain.Dormitory;
import com.example.dormitory.domain.UserAccount;
import com.example.dormitory.mapper.DormitoryMapper;
import com.example.dormitory.mapper.UserAccountMapper;
import com.example.dormitory.mapper.BedMapper;
import com.example.dormitory.mapper.DormitoryBuildingMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class DormitoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DormitoryMapper dormitoryMapper;

    @Autowired
    private UserAccountMapper userAccountMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private BedMapper bedMapper;

    @Autowired
    private DormitoryBuildingMapper dormitoryBuildingMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUpDormitoryData() {
        jdbcTemplate.update("DELETE FROM check_in_record");
        jdbcTemplate.update("DELETE FROM check_in_application_detail");
        jdbcTemplate.update("DELETE FROM check_in_application");
        bedMapper.delete(null);
        dormitoryBuildingMapper.delete(null);
        jdbcTemplate.update("DELETE FROM dormitory");
        dormitoryMapper.insert(new Dormitory(null, "测试宿舍-901", "男生宿舍", "测试楼", 4, 2, 2, "入住中"));
        if (userAccountMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<UserAccount>lambdaQuery()
                .eq(UserAccount::getUsername, "viewer")) == null) {
            userAccountMapper.insert(new UserAccount(
                    null, "viewer", passwordEncoder.encode("viewer-password-123"), "只读用户", "VIEWER", true));
        }
    }

    @Test
    void healthDoesNotRequireLogin() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UP"));
    }

    @Test
    void protectedEndpointRequiresLogin() throws Exception {
        mockMvc.perform(get("/api/dormitories"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void malformedPaginationParameterReturnsReadableBadRequest() throws Exception {
        String token = loginAndGetToken();
        mockMvc.perform(get("/api/dormitories")
                        .header("Authorization", token)
                        .param("page", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请求参数格式错误"));
    }

    @Test
    void loginThenQueryDormitoriesReadsPersistedData() throws Exception {
        String token = loginAndGetToken();

        mockMvc.perform(get("/api/dormitories").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.records[0].name").value("测试宿舍-901"));
    }

    @Test
    void loginCanRestoreCurrentUserAndLogout() throws Exception {
        String token = loginAndGetToken();

        mockMvc.perform(get("/api/auth/me").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userName").value("测试管理员"))
                .andExpect(jsonPath("$.data.roleCode").value("ADMIN"));

        mockMvc.perform(post("/api/auth/logout").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/auth/me").header("Authorization", token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidCredentialsReturnUnauthorized() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("账号或密码错误"));
    }

    @Test
    void adminCanCreateUpdateAndDeleteEmptyDormitory() throws Exception {
        String token = loginAndGetToken();
        MvcResult created = mockMvc.perform(post("/api/dormitories")
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content("{\"name\":\"新宿舍-101\",\"type\":\"女生宿舍\",\"building\":\"2号楼\",\"beds\":6,\"occupied\":0}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.vacant").value(6))
                .andReturn();
        long id = objectMapper.readTree(created.getResponse().getContentAsString()).path("data").path("id").asLong();

        mockMvc.perform(put("/api/dormitories/{id}", id)
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content("{\"name\":\"新宿舍-101\",\"type\":\"女生宿舍\",\"building\":\"2号楼\",\"beds\":8,\"occupied\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.beds").value(8))
                .andExpect(jsonPath("$.data.vacant").value(8));

        mockMvc.perform(delete("/api/dormitories/{id}", id).header("Authorization", token))
                .andExpect(status().isNoContent());
    }

    @Test
    void dormitoryWritesCannotBypassCheckInLifecycleByChangingOccupiedCount() throws Exception {
        String token = loginAndGetToken();

        mockMvc.perform(post("/api/dormitories")
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content("{\"name\":\"绕过入住的新宿舍\",\"type\":\"男生宿舍\",\"building\":\"测试楼\",\"beds\":4,\"occupied\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("入住人数只能通过入住流程变更"));

        Long existingId = dormitoryMapper.selectList(null).getFirst().getId();
        mockMvc.perform(put("/api/dormitories/{id}", existingId)
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content("{\"name\":\"测试宿舍-901\",\"type\":\"男生宿舍\",\"building\":\"测试楼\",\"beds\":4,\"occupied\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("入住人数只能通过入住流程变更"));
    }

    @Test
    void occupiedDormitoryCannotBeDeleted() throws Exception {
        String token = loginAndGetToken();
        Long id = dormitoryMapper.selectList(null).getFirst().getId();

        mockMvc.perform(delete("/api/dormitories/{id}", id).header("Authorization", token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("存在已入住床位，不能删除宿舍"));
    }

    @Test
    void viewerCannotCreateDormitory() throws Exception {
        String token = loginAndGetToken("viewer", "viewer-password-123");
        mockMvc.perform(post("/api/dormitories")
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content("{\"name\":\"无权宿舍\",\"type\":\"男生宿舍\",\"building\":\"3号楼\",\"beds\":4,\"occupied\":0}"))
                .andExpect(status().isForbidden());
    }

    private String loginAndGetToken() throws Exception {
        return loginAndGetToken("admin", "test-password-123");
    }

    private String loginAndGetToken(String username, String password) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        var cookie = login.getResponse().getCookie("Authorization");
        assertNotNull(cookie);
        assertTrue(cookie.isHttpOnly());
        return cookie.getValue();
    }
}
