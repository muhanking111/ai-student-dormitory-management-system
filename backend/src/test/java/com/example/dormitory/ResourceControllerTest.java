package com.example.dormitory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class ResourceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate.update("DELETE FROM bed");
        jdbcTemplate.update("DELETE FROM dormitory_building");
        jdbcTemplate.update("DELETE FROM dormitory");
        jdbcTemplate.update("DELETE FROM building");
        adminToken = login("admin", "test-password-123");
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM bed");
        jdbcTemplate.update("DELETE FROM dormitory_building");
        jdbcTemplate.update("DELETE FROM dormitory");
        jdbcTemplate.update("DELETE FROM building");
    }

    @Test
    void creatingDormitoryGeneratesBedsAndReturnsBuildingRelation() throws Exception {
        long buildingId = createBuilding("B01", "测试1号楼");

        MvcResult created = mockMvc.perform(post("/api/dormitories")
                        .header("Authorization", adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "101宿舍",
                                "type", "男生宿舍",
                                "buildingId", buildingId,
                                "beds", 4,
                                "occupied", 0))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.buildingId").value(buildingId))
                .andExpect(jsonPath("$.data.building").value("测试1号楼"))
                .andExpect(jsonPath("$.data.vacant").value(4))
                .andReturn();
        long dormitoryId = body(created).path("data").path("id").asLong();

        mockMvc.perform(get("/api/beds")
                        .header("Authorization", adminToken)
                        .param("dormitoryId", String.valueOf(dormitoryId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.records[0].bedNo").value("01"))
                .andExpect(jsonPath("$.data.records[0].status").value("空闲"))
                .andExpect(jsonPath("$.data.records[1].status").value("空闲"));
    }

    @Test
    void dormitoryListUsesServerPaginationAndFilters() throws Exception {
        long buildingId = createBuilding("B02", "测试2号楼");
        createDormitory(buildingId, "201宿舍", 4, 0);
        createDormitory(buildingId, "202宿舍", 6, 0);

        mockMvc.perform(get("/api/dormitories")
                        .header("Authorization", adminToken)
                        .param("page", "1")
                        .param("pageSize", "1")
                        .param("keyword", "20")
                        .param("buildingId", String.valueOf(buildingId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.pageSize").value(1));
    }

    @Test
    void resizingDormitoryRemovesOnlyFreeBeds() throws Exception {
        long buildingId = createBuilding("B03", "测试3号楼");
        long dormitoryId = createDormitory(buildingId, "301宿舍", 4, 0);

        mockMvc.perform(put("/api/dormitories/{id}", dormitoryId)
                        .header("Authorization", adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "301宿舍",
                                "type", "男生宿舍",
                                "buildingId", buildingId,
                                "beds", 2,
                                "occupied", 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.beds").value(2));

        mockMvc.perform(get("/api/beds")
                        .header("Authorization", adminToken)
                        .param("dormitoryId", String.valueOf(dormitoryId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));

        mockMvc.perform(put("/api/dormitories/{id}", dormitoryId)
                        .header("Authorization", adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "301宿舍",
                                "type", "男生宿舍",
                                "buildingId", buildingId,
                                "beds", 0,
                                "occupied", 0))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void buildingWithDormitoriesCannotBeDeleted() throws Exception {
        long buildingId = createBuilding("B04", "测试4号楼");
        createDormitory(buildingId, "401宿舍", 4, 0);

        mockMvc.perform(delete("/api/buildings/{id}", buildingId).header("Authorization", adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("楼栋下仍有宿舍，不能删除"));
    }

    @Test
    void invalidPaginationIsRejected() throws Exception {
        mockMvc.perform(get("/api/buildings")
                        .header("Authorization", adminToken)
                        .param("page", "0")
                        .param("pageSize", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("分页参数不合法"));
    }

    private long createBuilding(String code, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/buildings")
                        .header("Authorization", adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code,
                                "name", name,
                                "genderType", "男生宿舍",
                                "floors", 6,
                                "manager", "测试宿管",
                                "status", "启用"))))
                .andExpect(status().isCreated())
                .andReturn();
        return body(result).path("data").path("id").asLong();
    }

    private long createDormitory(long buildingId, String name, int beds, int occupied) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/dormitories")
                        .header("Authorization", adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", name,
                                "type", "男生宿舍",
                                "buildingId", buildingId,
                                "beds", beds,
                                "occupied", occupied))))
                .andExpect(status().isCreated())
                .andReturn();
        return body(result).path("data").path("id").asLong();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        var cookie = result.getResponse().getCookie("Authorization");
        assertNotNull(cookie);
        return cookie.getValue();
    }
}
