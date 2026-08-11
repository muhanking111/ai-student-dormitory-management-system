package com.example.dormitory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class CheckInLifecycleTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String token;
    private long adminUserId;

    @BeforeEach
    void setUp() throws Exception {
        cleanBusinessData();
        token = login();
        adminUserId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = 'admin'", Long.class);
    }

    @AfterEach
    void cleanUp() {
        cleanBusinessData();
    }

    @Test
    void approvingApplicationAtomicallyOccupiesBedAndCreatesRecord() throws Exception {
        long studentId = createStudent("20261001", "入住学生");
        long dormitoryId = createDormitory("C01", "测试入住楼", "101宿舍", 2, 0);
        long applicationId = createApplication(studentId, dormitoryId);
        long bedId = firstBedId(dormitoryId, "空闲");

        mockMvc.perform(get("/api/dashboard/statistics").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[4].title").value("今日申请"))
                .andExpect(jsonPath("$.data[4].value").value(1));

        mockMvc.perform(post("/api/check-in-applications/{id}/approve", applicationId)
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("bedId", bedId, "remark", "审核通过"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("已通过"))
                .andExpect(jsonPath("$.data.bedId").value(bedId))
                .andExpect(jsonPath("$.data.reviewerUserId").value(adminUserId))
                .andExpect(jsonPath("$.data.reviewedAt").isString());

        mockMvc.perform(get("/api/dashboard/statistics").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[4].value").value(1));

        mockMvc.perform(get("/api/students").header("Authorization", token).param("keyword", "20261001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].checkInStatus").value("已入住"));
        mockMvc.perform(get("/api/check-in-records").header("Authorization", token).param("status", "在住"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].studentId").value(studentId))
                .andExpect(jsonPath("$.data.records[0].bedId").value(bedId))
                .andExpect(jsonPath("$.data.records[0].checkInOperatorUserId").value(adminUserId));
        mockMvc.perform(get("/api/dashboard/check-in-trend").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(30))
                .andExpect(jsonPath("$.data[29].value").value(1));

        Integer occupied = jdbcTemplate.queryForObject("SELECT occupied FROM dormitory WHERE id = ?", Integer.class, dormitoryId);
        String bedStatus = jdbcTemplate.queryForObject("SELECT status FROM bed WHERE id = ?", String.class, bedId);
        org.junit.jupiter.api.Assertions.assertEquals(1, occupied);
        org.junit.jupiter.api.Assertions.assertEquals("已占用", bedStatus);
        org.junit.jupiter.api.Assertions.assertEquals(adminUserId,
                jdbcTemplate.queryForObject(
                        "SELECT created_by_user_id FROM check_in_application WHERE id = ?", Long.class, applicationId));
        org.junit.jupiter.api.Assertions.assertEquals(adminUserId,
                jdbcTemplate.queryForObject(
                        "SELECT reviewer_user_id FROM check_in_application_detail WHERE application_id = ?",
                        Long.class, applicationId));
        org.junit.jupiter.api.Assertions.assertEquals(adminUserId,
                jdbcTemplate.queryForObject(
                        "SELECT check_in_operator_user_id FROM check_in_record WHERE application_id = ?",
                        Long.class, applicationId));
    }

    @Test
    void sameStudentCannotReceiveSecondActiveBed() throws Exception {
        long studentId = createStudent("20261002", "重复入住学生");
        long dormitoryId = createDormitory("C02", "重复入住楼", "201宿舍", 2, 0);
        long firstBedId = firstBedId(dormitoryId, "空闲");
        long secondBedId = secondBedId(dormitoryId);

        mockMvc.perform(post("/api/check-in-records")
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("studentId", studentId, "bedId", firstBedId))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/check-in-records")
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("studentId", studentId, "bedId", secondBedId))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("学生已存在有效入住记录"));
    }

    @Test
    void fullDormitoryApplicationCannotBeApproved() throws Exception {
        long studentId = createStudent("20261003", "满床申请学生");
        long occupantId = createStudent("20261103", "已入住学生");
        long dormitoryId = createDormitory("C03", "满床测试楼", "301宿舍", 1, 0);
        long occupiedBedId = firstBedId(dormitoryId, "空闲");
        mockMvc.perform(post("/api/check-in-records")
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("studentId", occupantId, "bedId", occupiedBedId))))
                .andExpect(status().isCreated());
        long applicationId = createApplication(studentId, dormitoryId);

        mockMvc.perform(post("/api/check-in-applications/{id}/approve", applicationId)
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("bedId", occupiedBedId))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("床位当前不可用"));
    }

    @Test
    void checkoutReleasesBedAndStudentStatus() throws Exception {
        long studentId = createStudent("20261004", "退宿学生");
        long dormitoryId = createDormitory("C04", "退宿测试楼", "401宿舍", 1, 0);
        long bedId = firstBedId(dormitoryId, "空闲");
        MvcResult assigned = mockMvc.perform(post("/api/check-in-records")
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("studentId", studentId, "bedId", bedId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.checkInOperatorUserId").value(adminUserId))
                .andReturn();
        long recordId = body(assigned).path("data").path("id").asLong();

        mockMvc.perform(post("/api/check-in-records/{id}/checkout", recordId)
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content("{\"remark\":\"正常退宿\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("已退宿"))
                .andExpect(jsonPath("$.data.checkOutOperatorUserId").value(adminUserId))
                .andExpect(jsonPath("$.data.checkOutDate").isString());

        org.junit.jupiter.api.Assertions.assertEquals("空闲",
                jdbcTemplate.queryForObject("SELECT status FROM bed WHERE id = ?", String.class, bedId));
        org.junit.jupiter.api.Assertions.assertEquals("未入住",
                jdbcTemplate.queryForObject("SELECT check_in_status FROM student WHERE id = ?", String.class, studentId));
        org.junit.jupiter.api.Assertions.assertEquals(1,
                jdbcTemplate.queryForObject("SELECT vacant FROM dormitory WHERE id = ?", Integer.class, dormitoryId));
        org.junit.jupiter.api.Assertions.assertNull(
                jdbcTemplate.queryForObject("SELECT student_id FROM bed WHERE id = ?", Long.class, bedId));
        org.junit.jupiter.api.Assertions.assertNull(
                jdbcTemplate.queryForObject("SELECT active_student_id FROM check_in_record WHERE id = ?", Long.class, recordId));
        org.junit.jupiter.api.Assertions.assertNull(
                jdbcTemplate.queryForObject("SELECT active_bed_id FROM check_in_record WHERE id = ?", Long.class, recordId));
        org.junit.jupiter.api.Assertions.assertEquals(adminUserId,
                jdbcTemplate.queryForObject(
                        "SELECT check_out_operator_user_id FROM check_in_record WHERE id = ?", Long.class, recordId));

        mockMvc.perform(post("/api/check-in-records")
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("studentId", studentId, "bedId", bedId))))
                .andExpect(status().isCreated());
    }

    @Test
    void studentCrudSupportsPaginationAndDependencyProtection() throws Exception {
        long firstId = createStudent("20261005", "分页学生甲");
        createStudent("20261006", "分页学生乙");

        mockMvc.perform(get("/api/students")
                        .header("Authorization", token)
                        .param("page", "1").param("pageSize", "1").param("keyword", "分页学生"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.total").value(2));

        mockMvc.perform(patch("/api/students/{id}", firstId)
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "studentNo", "20261005",
                                "name", "分页学生甲更新",
                                "gender", "男",
                                "college", "计算机学院",
                                "grade", "2026",
                                "phone", "13800001005"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("分页学生甲更新"));

        mockMvc.perform(delete("/api/students/{id}", firstId).header("Authorization", token))
                .andExpect(status().isNoContent());
    }

    @Test
    void rejectedApplicationCannotBeProcessedAgain() throws Exception {
        long studentId = createStudent("20261007", "拒绝申请学生");
        long dormitoryId = createDormitory("C07", "拒绝申请楼", "701宿舍", 2, 0);
        long applicationId = createApplication(studentId, dormitoryId);

        mockMvc.perform(post("/api/check-in-applications/{id}/reject", applicationId)
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content("{\"remark\":\"资料不完整\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("已拒绝"))
                .andExpect(jsonPath("$.data.reviewRemark").value("资料不完整"))
                .andExpect(jsonPath("$.data.reviewerUserId").value(adminUserId))
                .andExpect(jsonPath("$.data.reviewedAt").isString());

        mockMvc.perform(post("/api/check-in-applications/{id}/reject", applicationId)
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("入住申请已处理"));
        org.junit.jupiter.api.Assertions.assertEquals(0,
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM check_in_record", Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(adminUserId,
                jdbcTemplate.queryForObject(
                        "SELECT reviewer_user_id FROM check_in_application_detail WHERE application_id = ?",
                        Long.class, applicationId));
    }

    @Test
    void bedOutsideRequestedDormitoryRollsBackApproval() throws Exception {
        long studentId = createStudent("20261008", "错床位申请学生");
        long requestedDormitoryId = createDormitory("C08", "申请目标楼", "801宿舍", 1, 0);
        long otherDormitoryId = createDormitory("C09", "其他宿舍楼", "901宿舍", 1, 0);
        long applicationId = createApplication(studentId, requestedDormitoryId);
        long otherBedId = firstBedId(otherDormitoryId, "空闲");

        mockMvc.perform(post("/api/check-in-applications/{id}/approve", applicationId)
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("bedId", otherBedId))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("所选床位不属于申请宿舍"));

        org.junit.jupiter.api.Assertions.assertEquals("待审核",
                jdbcTemplate.queryForObject("SELECT status FROM check_in_application WHERE id = ?",
                        String.class, applicationId));
        org.junit.jupiter.api.Assertions.assertEquals("空闲",
                jdbcTemplate.queryForObject("SELECT status FROM bed WHERE id = ?", String.class, otherBedId));
        org.junit.jupiter.api.Assertions.assertEquals("未入住",
                jdbcTemplate.queryForObject("SELECT check_in_status FROM student WHERE id = ?", String.class, studentId));
        org.junit.jupiter.api.Assertions.assertEquals(0,
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM check_in_record", Integer.class));
    }

    @Test
    void studentWithApplicationDependencyCannotBeDeleted() throws Exception {
        long studentId = createStudent("20261010", "有申请学生");
        long dormitoryId = createDormitory("C10", "依赖保护楼", "1001宿舍", 1, 0);
        createApplication(studentId, dormitoryId);

        mockMvc.perform(delete("/api/students/{id}", studentId).header("Authorization", token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("学生存在入住业务记录，不能删除"));
        org.junit.jupiter.api.Assertions.assertEquals(1,
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM student WHERE id = ?", Integer.class, studentId));
    }

    private long createStudent(String studentNo, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/students")
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "studentNo", studentNo,
                                "name", name,
                                "gender", "男",
                                "college", "计算机学院",
                                "grade", "2026",
                                "phone", "138" + studentNo.substring(studentNo.length() - 8)))))
                .andExpect(status().isCreated())
                .andReturn();
        return body(result).path("data").path("id").asLong();
    }

    private long createDormitory(String code, String buildingName, String dormitoryName, int beds, int occupied) throws Exception {
        MvcResult building = mockMvc.perform(post("/api/buildings")
                        .header("Authorization", token).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("code", code, "name", buildingName,
                                "genderType", "男生宿舍", "floors", 6, "manager", "测试宿管", "status", "启用"))))
                .andExpect(status().isCreated()).andReturn();
        long buildingId = body(building).path("data").path("id").asLong();
        MvcResult dormitory = mockMvc.perform(post("/api/dormitories")
                        .header("Authorization", token).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("name", dormitoryName, "type", "男生宿舍",
                                "buildingId", buildingId, "beds", beds, "occupied", occupied))))
                .andExpect(status().isCreated()).andReturn();
        return body(dormitory).path("data").path("id").asLong();
    }

    private long createApplication(long studentId, long dormitoryId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/check-in-applications")
                        .header("Authorization", token).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("studentId", studentId, "dormitoryId", dormitoryId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.createdByUserId").value(adminUserId))
                .andExpect(jsonPath("$.data.appliedAt").isString())
                .andReturn();
        return body(result).path("data").path("id").asLong();
    }

    private long firstBedId(long dormitoryId, String statusValue) {
        return jdbcTemplate.queryForObject("SELECT id FROM bed WHERE dormitory_id = ? AND status = ? ORDER BY bed_no LIMIT 1",
                Long.class, dormitoryId, statusValue);
    }

    private long secondBedId(long dormitoryId) {
        return jdbcTemplate.queryForObject("SELECT id FROM bed WHERE dormitory_id = ? ORDER BY bed_no DESC LIMIT 1",
                Long.class, dormitoryId);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"test-password-123\"}"))
                .andExpect(status().isOk()).andReturn();
        var cookie = result.getResponse().getCookie("Authorization");
        assertNotNull(cookie);
        return cookie.getValue();
    }

    private void cleanBusinessData() {
        for (String table : new String[]{"check_in_record", "check_in_application_detail", "check_in_application",
                "bed", "dormitory_building", "dormitory", "building", "student"}) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
    }
}
