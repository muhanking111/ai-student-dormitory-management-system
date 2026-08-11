package com.example.dormitory.ai.api;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ai-disabled-api;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "dormitory.ai.enabled=false"
})
class AiRuntimeDisabledApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void masterSwitchFailsClosedWithoutCreatingControlPlaneRows() throws Exception {
        String token = login("admin", "test-password-123");

        mockMvc.perform(post("/api/ai/conversations")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("surface", "GLOBAL"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(result -> assertVaryTokens(result, "Authorization", "Cookie", "Origin"))
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.data.errorCode").value("AI_DISABLED"))
                .andExpect(jsonPath("$.data.retryable").value(true));

        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_conversation", Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_run", Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_usage_ledger", Integer.class));
    }

    @Test
    void publicHealthRemainsCoarseAndDoesNotExposeAiReadiness() throws Exception {
        String response = mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(response.toLowerCase().contains("provider"));
        assertFalse(response.toLowerCase().contains("model"));
        assertFalse(response.toLowerCase().contains("dormitory.ai"));
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        var cookie = result.getResponse().getCookie("Authorization");
        assertNotNull(cookie);
        return cookie.getValue();
    }

    private void assertVaryTokens(MvcResult result, String... expected) {
        java.util.Set<String> tokens = result.getResponse().getHeaders("Vary").stream()
                .flatMap(value -> java.util.Arrays.stream(value.split(",")))
                .map(String::trim)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(tokens.containsAll(java.util.Set.of(expected)), () -> "Vary tokens: " + tokens);
    }
}
