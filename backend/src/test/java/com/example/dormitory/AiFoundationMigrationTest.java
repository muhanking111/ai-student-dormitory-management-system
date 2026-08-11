package com.example.dormitory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class AiFoundationMigrationTest {

    private static final Set<String> AI_PERMISSIONS = Set.of(
            "ai:assistant:use",
            "ai:knowledge:read",
            "ai:knowledge:manage",
            "ai:knowledge:publish-public",
            "ai:dashboard:query",
            "ai:repair:triage",
            "ai:notice:draft",
            "ai:risk:read",
            "ai:risk:manage",
            "ai:approval:review",
            "ai:audit:read",
            "ai:audit:content:read",
            "ai:config:manage",
            "ai:eval:run");

    private static final Set<String> SENSITIVE_AI_PERMISSIONS = Set.of(
            "ai:knowledge:publish-public",
            "ai:audit:content:read");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void cleanNotices() {
        jdbcTemplate.update("DELETE FROM notice");
    }

    @Test
    void seedsExactlyFourteenAiPermissionsAndExplicitlyMigratesAdmin() {
        Set<String> actual = Set.copyOf(jdbcTemplate.queryForList(
                "SELECT code FROM sys_permission WHERE code LIKE 'ai:%'", String.class));
        assertEquals(AI_PERMISSIONS, actual);

        Set<String> adminPermissions = Set.copyOf(jdbcTemplate.queryForList(
                "SELECT p.code FROM sys_permission p "
                        + "JOIN sys_role_permission rp ON rp.permission_id = p.id "
                        + "JOIN sys_role r ON r.id = rp.role_id WHERE r.code = 'ADMIN'",
                String.class));
        Set<String> generalAiPermissions = AI_PERMISSIONS.stream()
                .filter(code -> !SENSITIVE_AI_PERMISSIONS.contains(code))
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(adminPermissions.containsAll(generalAiPermissions));
        assertFalse(adminPermissions.containsAll(AI_PERMISSIONS));
        assertTrue(SENSITIVE_AI_PERMISSIONS.stream().noneMatch(adminPermissions::contains));
        assertTrue(adminPermissions.contains("notice:write"));
    }

    @Test
    void noticeContentIsNullablePlainTextAndPersisted() throws Exception {
        String token = login();
        String content = "今晚 22:00 起开展公共区域安全巡查。\n请保持消防通道畅通。";

        mockMvc.perform(post("/api/notices")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "安全巡查通知",
                                "type", "宿舍通知",
                                "publisher", "测试管理员",
                                "status", "草稿",
                                "content", content))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.content").value(content));

        assertEquals(content, jdbcTemplate.queryForObject(
                "SELECT content FROM notice WHERE title = ?", String.class, "安全巡查通知"));

        mockMvc.perform(post("/api/notices")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "旧合同兼容公告",
                                "type", "宿舍通知",
                                "publisher", "测试管理员",
                                "status", "草稿"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.content").doesNotExist());

        mockMvc.perform(post("/api/notices")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "非法富文本",
                                "type", "宿舍通知",
                                "publisher", "测试管理员",
                                "status", "草稿",
                                "content", "<script>alert(1)</script>"))))
                .andExpect(status().isBadRequest());
    }

    private String login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "admin",
                                "password", "test-password-123"))))
                .andExpect(status().isOk())
                .andReturn();
        var cookie = result.getResponse().getCookie("Authorization");
        assertNotNull(cookie);
        return cookie.getValue();
    }
}
