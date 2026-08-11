package com.example.dormitory;

import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.dormitory.common.BusinessException;
import com.example.dormitory.domain.Student;
import com.example.dormitory.domain.UserAccount;
import com.example.dormitory.dto.StudentImportRequest;
import com.example.dormitory.dto.StudentRequest;
import com.example.dormitory.mapper.StudentMapper;
import com.example.dormitory.mapper.UserAccountMapper;
import com.example.dormitory.service.RbacService;
import com.example.dormitory.service.StudentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@SpringBootTest
class StudentImportControllerTest {

    private static final String ADMIN_USERNAME = "admin";
    private static final String VIEWER_USERNAME = "student-import-viewer";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StudentMapper studentMapper;

    @Autowired
    private StudentService studentService;

    @Autowired
    private RbacService rbacService;

    @Autowired
    private UserAccountMapper userAccountMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String adminToken;
    private Long adminId;
    private Long viewerId;

    @BeforeEach
    void setUp() throws Exception {
        deleteImportedStudents();
        ensureAdminStudentWritePermission();
        UserAccount viewer = userAccountMapper.selectOne(Wrappers.<UserAccount>lambdaQuery()
                .eq(UserAccount::getUsername, VIEWER_USERNAME));
        if (viewer == null) {
            viewer = new UserAccount(null, VIEWER_USERNAME, passwordEncoder.encode("viewer-password-123"),
                    "学生导入只读用户", "VIEWER", true);
            userAccountMapper.insert(viewer);
        }
        viewerId = viewer.getId();
        UserAccount admin = userAccountMapper.selectOne(Wrappers.<UserAccount>lambdaQuery()
                .eq(UserAccount::getUsername, ADMIN_USERNAME));
        adminId = admin.getId();
        assertTrue(rbacService.permissionCodesForUser(adminId).contains("student:write"),
                "学生导入测试的管理员必须拥有 student:write 权限");
        adminToken = login(ADMIN_USERNAME, "test-password-123");
    }

    @AfterEach
    void tearDown() {
        StpUtil.logout();
        deleteImportedStudents();
    }

    @Test
    void adminImportsStudentsAndReturnsStudentNumberSummary() throws Exception {
        mockMvc.perform(post("/api/students/import")
                        .header("Authorization", adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("students", List.of(
                                studentPayload("IMPORT-001", "导入学生甲"),
                                studentPayload("IMPORT-002", "导入学生乙"))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.importedCount").value(2))
                .andExpect(jsonPath("$.data.studentNos.length()").value(2))
                .andExpect(jsonPath("$.data.studentNos[0]").value("IMPORT-001"))
                .andExpect(jsonPath("$.data.studentNos[1]").value("IMPORT-002"));

        List<Student> created = importedStudents();
        assertEquals(2, created.size());
        assertEquals(List.of("未入住", "未入住"), created.stream().map(Student::getCheckInStatus).toList());
    }

    @Test
    void emptyBatchIsRejectedByBeanValidation() throws Exception {
        mockMvc.perform(post("/api/students/import")
                        .header("Authorization", adminToken)
                        .contentType("application/json")
                        .content("{\"students\":[]}"))
                .andExpect(status().isBadRequest());

        assertEquals(0, importedStudents().size());
    }

    @Test
    void moreThanOneHundredStudentsIsRejectedByBeanValidation() throws Exception {
        List<Map<String, String>> students = new ArrayList<>();
        for (int index = 0; index < 101; index++) {
            students.add(studentPayload("IMPORT-LIMIT-" + index, "上限学生" + index));
        }

        mockMvc.perform(post("/api/students/import")
                        .header("Authorization", adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("students", students))))
                .andExpect(status().isBadRequest());

        assertEquals(0, importedStudents().size());
    }

    @Test
    void exactlyOneHundredStudentsCanBeImported() throws Exception {
        List<Map<String, String>> students = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            students.add(studentPayload("IMPORT-MAX-" + index, "上限学生" + index));
        }

        mockMvc.perform(post("/api/students/import")
                        .header("Authorization", adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("students", students))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importedCount").value(100))
                .andExpect(jsonPath("$.data.studentNos.length()").value(100));

        assertEquals(100, importedStudents().size());
    }

    @Test
    void invalidNestedStudentRejectsWholeBatch() throws Exception {
        Map<String, String> invalid = studentPayload("IMPORT-NESTED-002", "无效学生");
        invalid.put("phone", "bad");

        mockMvc.perform(post("/api/students/import")
                        .header("Authorization", adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("students", List.of(
                                studentPayload("IMPORT-NESTED-001", "有效学生"), invalid)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("手机号格式不合法"));

        assertEquals(0, importedStudents().size());
    }

    @Test
    void duplicateStudentNumbersReturnConflictWithoutPartialInsert() throws Exception {
        mockMvc.perform(post("/api/students/import")
                        .header("Authorization", adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("students", List.of(
                                studentPayload("IMPORT-DUPLICATE", "重复学生甲"),
                                studentPayload("IMPORT-DUPLICATE", "重复学生乙"))))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("导入批次内学号重复: IMPORT-DUPLICATE"));

        assertEquals(0, importedStudents().size());
    }

    @Test
    void existingStudentNumberReturnsConflictWithoutPartialInsert() throws Exception {
        studentMapper.insert(student("IMPORT-EXISTING", "数据库已有学生", "计算机学院"));

        mockMvc.perform(post("/api/students/import")
                        .header("Authorization", adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("students", List.of(
                                studentPayload("IMPORT-NEW", "不应落库学生"),
                                studentPayload("IMPORT-EXISTING", "冲突学生"))))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("学号已存在: IMPORT-EXISTING"));

        assertEquals(List.of("IMPORT-EXISTING"),
                importedStudents().stream().map(Student::getStudentNo).toList());
    }

    @Test
    void viewerCannotUseImportEndpoint() throws Exception {
        String viewerToken = login(VIEWER_USERNAME, "viewer-password-123");

        mockMvc.perform(post("/api/students/import")
                        .header("Authorization", viewerToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("students", List.of(
                                studentPayload("IMPORT-CONTROLLER-FORBIDDEN", "越权学生"))))))
                .andExpect(status().isForbidden());

        assertEquals(0, importedStudents().size());
    }

    @Test
    void viewerCannotBypassControllerAndCallImportService() {
        StpUtil.login(viewerId);

        assertThrows(NotPermissionException.class, () -> studentService.importStudents(
                new StudentImportRequest(List.of(studentRequest("IMPORT-SERVICE-FORBIDDEN", "越权学生")))));
        assertEquals(0, importedStudents().size());
    }

    @Test
    void serviceRollsBackWholeBatchWhenLaterInsertFails() {
        StpUtil.login(adminId);
        StudentImportRequest request = new StudentImportRequest(List.of(
                studentRequest("IMPORT-ROLLBACK-001", "应回滚学生"),
                new StudentRequest("IMPORT-ROLLBACK-002", "失败学生", "女", "院".repeat(65),
                        "2026", "13800000002")));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> studentService.importStudents(request));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals(0, importedStudents().size());
    }

    private Map<String, String> studentPayload(String studentNo, String name) {
        return new java.util.LinkedHashMap<>(Map.of(
                "studentNo", studentNo,
                "name", name,
                "gender", "男",
                "college", "计算机学院",
                "grade", "2026",
                "phone", "13800000001"));
    }

    private StudentRequest studentRequest(String studentNo, String name) {
        return new StudentRequest(studentNo, name, "男", "计算机学院", "2026", "13800000001");
    }

    private Student student(String studentNo, String name, String college) {
        return new Student(null, studentNo, name, "男", college, "2026", "13800000001", "未入住");
    }

    private List<Student> importedStudents() {
        return studentMapper.selectList(Wrappers.<Student>lambdaQuery()
                .likeRight(Student::getStudentNo, "IMPORT-")
                .orderByAsc(Student::getStudentNo));
    }

    private void deleteImportedStudents() {
        jdbcTemplate.update("DELETE FROM student WHERE student_no LIKE 'IMPORT-%'");
    }

    private void ensureAdminStudentWritePermission() {
        jdbcTemplate.update("INSERT INTO sys_user_role(user_id, role_id) "
                + "SELECT u.id, r.id FROM sys_user u CROSS JOIN sys_role r "
                + "WHERE u.username = ? AND r.code = 'ADMIN' AND NOT EXISTS ("
                + "SELECT 1 FROM sys_user_role ur WHERE ur.user_id = u.id AND ur.role_id = r.id)",
                ADMIN_USERNAME);
        jdbcTemplate.update("INSERT INTO sys_role_permission(role_id, permission_id) "
                + "SELECT r.id, p.id FROM sys_role r CROSS JOIN sys_permission p "
                + "WHERE r.code = 'ADMIN' AND p.code = 'student:write' AND NOT EXISTS ("
                + "SELECT 1 FROM sys_role_permission rp WHERE rp.role_id = r.id AND rp.permission_id = p.id)");
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
