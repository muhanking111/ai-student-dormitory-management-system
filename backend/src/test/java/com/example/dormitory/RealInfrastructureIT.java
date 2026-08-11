package com.example.dormitory;

import com.example.dormitory.config.BootstrapAdminInitializer;
import com.example.dormitory.config.CheckInDataInitializer;
import com.example.dormitory.config.DemoDataInitializer;
import com.example.dormitory.config.RbacDataInitializer;
import com.example.dormitory.config.ResourceDataInitializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("real-it")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE, printOnlyOnFailure = false)
@SpringBootTest
@ContextConfiguration(initializers = RealInfrastructureIT.RootEnvInitializer.class)
class RealInfrastructureIT {

    private static final Set<String> BUSINESS_TABLES = Set.of(
            "sys_user", "sys_role", "sys_permission", "sys_user_role", "sys_role_permission",
            "building", "dormitory", "dormitory_building", "student", "bed",
            "check_in_application", "check_in_application_detail", "check_in_record",
            "repair_order", "repair_record", "payment", "payment_record", "hygiene_check", "notice");
    private static final Set<String> SOFT_DELETABLE_TABLES = Set.of(
            "sys_user", "sys_role", "building", "dormitory", "student", "hygiene_check", "notice");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Value("${dormitory.dashboard.cache-key}")
    private String dashboardCacheKey;

    @MockitoBean
    private BootstrapAdminInitializer bootstrapAdminInitializer;

    @MockitoBean
    private RbacDataInitializer rbacDataInitializer;

    @MockitoBean
    private ResourceDataInitializer resourceDataInitializer;

    @MockitoBean
    private DemoDataInitializer demoDataInitializer;

    @MockitoBean
    private CheckInDataInitializer checkInDataInitializer;

    private final List<String> loginTokens = new ArrayList<>();
    private String runId;
    private String temporaryUsername;

    @BeforeEach
    void setUpRunId() {
        runId = "RIT" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
        temporaryUsername = "rit-" + runId.substring(3).toLowerCase(Locale.ROOT);
    }

    @AfterEach
    void clearExternalState() throws Exception {
        for (String token : List.copyOf(loginTokens)) {
            logout(token);
        }
        Boolean deleted = redisTemplate.delete(dashboardCacheKey);
        assertNotNull(deleted, "Dashboard 缓存清理结果不能为空");
    }

    @AfterTransaction
    void verifyDatabaseTransactionRolledBack() {
        if (runId == null) return;
        assertEquals(0, count("SELECT COUNT(*) FROM building WHERE code = ?", runId));
        assertEquals(0, count("SELECT COUNT(*) FROM student WHERE student_no = ?", runId + "S"));
        assertEquals(0, count("SELECT COUNT(*) FROM payment WHERE student_no = ?", runId + "S"));
        assertEquals(0, count("SELECT COUNT(*) FROM repair_order WHERE reporter = ?", runId + "报修"));
        assertEquals(0, count("SELECT COUNT(*) FROM notice WHERE title = ?", runId + "公告"));
        assertEquals(0, count("SELECT COUNT(*) FROM sys_user WHERE username = ?", temporaryUsername));
    }

    @Test
    @Transactional
    @Rollback
    void verifiesRealMysqlRedisAndRollbackSafeBusinessLifecycles() throws Exception {
        assertRealInfrastructureAndSchema();

        Map<String, String> env = RootEnvInitializer.rootEnvironment();
        LoginSession admin = login(env.get("BOOTSTRAP_ADMIN_USERNAME"), env.get("BOOTSTRAP_ADMIN_PASSWORD"));

        long buildingId = createBuilding(admin.token());
        long dormitoryId = createDormitory(admin.token(), buildingId);
        long studentId = createStudent(admin.token());
        long applicationId = createApplication(admin, studentId, dormitoryId);
        long bedId = jdbcTemplate.queryForObject(
                "SELECT id FROM bed WHERE dormitory_id = ? AND status = '空闲' ORDER BY bed_no LIMIT 1",
                Long.class, dormitoryId);

        approveAndCheckout(admin, applicationId, studentId, bedId);
        verifyPaymentLifecycle(admin);
        verifyRepairLifecycle(admin);
        verifyNoticeLifecycle(admin);

        logoutAll();
        Boolean deleted = redisTemplate.delete(dashboardCacheKey);
        assertNotNull(deleted, "Dashboard 缓存清理结果不能为空");
    }

    private void assertRealInfrastructureAndSchema() {
        String databaseProduct = jdbcTemplate.execute((ConnectionCallback<String>) connection ->
                connection.getMetaData().getDatabaseProductName());
        assertNotNull(databaseProduct);
        assertTrue(databaseProduct.toLowerCase(Locale.ROOT).contains("mysql"),
                () -> "RealInfrastructureIT 必须连接 MySQL，实际为 " + databaseProduct);

        Set<String> tables = jdbcTemplate.queryForList(
                        "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
                        String.class)
                .stream().map(value -> value.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        Set<String> missingTables = BUSINESS_TABLES.stream()
                .filter(table -> !tables.contains(table)).collect(Collectors.toSet());
        assertTrue(missingTables.isEmpty(), () -> "MySQL 缺少业务表: " + missingTables);
        assertEquals(19, BUSINESS_TABLES.size());

        BUSINESS_TABLES.forEach(table -> assertColumns(
                table, "id", "created_at", "updated_at", "created_operator_user_id", "updated_operator_user_id"));
        SOFT_DELETABLE_TABLES.forEach(table -> assertColumns(table, "deleted"));
        assertColumns("check_in_application", "created_by_user_id", "applied_at");
        assertColumns("check_in_application_detail", "reviewer_user_id", "reviewed_at");
        assertColumns("check_in_record", "check_in_operator_user_id", "check_out_operator_user_id",
                "check_in_date", "check_out_date", "active_student_id", "active_bed_id");
        assertColumns("repair_order", "description", "assignee_user_id", "status");
        assertColumns("repair_record", "operator_user_id", "handled_at", "status");
        assertColumns("payment_record", "operator_user_id", "paid_at", "amount");
        assertColumns("notice", "published_at");
        assertEquals(6, jdbcTemplate.queryForObject(
                "SELECT datetime_precision FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND LOWER(table_name) = 'notice' "
                        + "AND LOWER(column_name) = 'published_at'",
                Integer.class));

        try (RedisConnection connection = Objects.requireNonNull(redisTemplate.getConnectionFactory()).getConnection()) {
            assertEquals("PONG", connection.ping(), "Redis PING 必须返回 PONG");
        }
    }

    private long createBuilding(String token) throws Exception {
        MvcResult result = postJson("/api/buildings", token, Map.of(
                "code", runId,
                "name", runId + "楼",
                "genderType", "男生宿舍",
                "floors", 6,
                "manager", "真实集成测试",
                "status", "启用"), 201);
        return body(result).path("data").path("id").asLong();
    }

    private long createDormitory(String token, long buildingId) throws Exception {
        MvcResult result = postJson("/api/dormitories", token, Map.of(
                "name", runId + "宿舍",
                "type", "男生宿舍",
                "buildingId", buildingId,
                "beds", 2,
                "occupied", 0), 201);
        return body(result).path("data").path("id").asLong();
    }

    private long createStudent(String token) throws Exception {
        long phoneSuffix = Math.floorMod(runId.hashCode(), 100_000_000);
        MvcResult result = postJson("/api/students", token, Map.of(
                "studentNo", runId + "S",
                "name", "真实联调学生",
                "gender", "男",
                "college", "集成测试学院",
                "grade", "2026",
                "phone", "139" + String.format(Locale.ROOT, "%08d", phoneSuffix)), 201);
        return body(result).path("data").path("id").asLong();
    }

    private long createApplication(LoginSession admin, long studentId, long dormitoryId) throws Exception {
        MvcResult result = postJson("/api/check-in-applications", admin.token(), Map.of(
                "studentId", studentId,
                "dormitoryId", dormitoryId,
                "remark", runId), 201);
        JsonNode data = body(result).path("data");
        assertEquals(admin.userId(), data.path("createdByUserId").asLong());
        assertFalse(data.path("appliedAt").asText().isBlank());
        return data.path("id").asLong();
    }

    private void approveAndCheckout(
            LoginSession admin, long applicationId, long studentId, long bedId) throws Exception {
        MvcResult approved = postJson("/api/check-in-applications/" + applicationId + "/approve",
                admin.token(), Map.of("bedId", bedId, "remark", "真实审核"), 200);
        JsonNode approval = body(approved).path("data");
        assertEquals("已通过", approval.path("status").asText());
        assertEquals(admin.userId(), approval.path("reviewerUserId").asLong());
        assertFalse(approval.path("reviewedAt").asText().isBlank());

        long recordId = jdbcTemplate.queryForObject(
                "SELECT id FROM check_in_record WHERE application_id = ?", Long.class, applicationId);
        assertEquals(admin.userId(), jdbcTemplate.queryForObject(
                "SELECT check_in_operator_user_id FROM check_in_record WHERE id = ?", Long.class, recordId));
        assertEquals("已占用", jdbcTemplate.queryForObject(
                "SELECT status FROM bed WHERE id = ?", String.class, bedId));
        assertEquals("已入住", jdbcTemplate.queryForObject(
                "SELECT check_in_status FROM student WHERE id = ?", String.class, studentId));

        MvcResult checkedOut = postJson("/api/check-in-records/" + recordId + "/checkout",
                admin.token(), Map.of("remark", "真实退宿"), 200);
        JsonNode checkout = body(checkedOut).path("data");
        assertEquals("已退宿", checkout.path("status").asText());
        assertEquals(admin.userId(), checkout.path("checkOutOperatorUserId").asLong());
        assertFalse(checkout.path("checkOutDate").asText().isBlank());
        assertEquals("空闲", jdbcTemplate.queryForObject(
                "SELECT status FROM bed WHERE id = ?", String.class, bedId));
        assertEquals("未入住", jdbcTemplate.queryForObject(
                "SELECT check_in_status FROM student WHERE id = ?", String.class, studentId));
        assertNull(jdbcTemplate.queryForObject(
                "SELECT active_student_id FROM check_in_record WHERE id = ?", Long.class, recordId));
        assertNull(jdbcTemplate.queryForObject(
                "SELECT active_bed_id FROM check_in_record WHERE id = ?", Long.class, recordId));
    }

    private void verifyPaymentLifecycle(LoginSession admin) throws Exception {
        MvcResult bill = postJson("/api/payment-bills", admin.token(), Map.of(
                "studentNo", runId + "S",
                "name", "真实联调学生",
                "type", "住宿费",
                "amountDue", new BigDecimal("800.00"),
                "deadline", LocalDate.now().plusMonths(1).toString()), 201);
        long billId = body(bill).path("data").path("id").asLong();

        MvcResult partial = postJson("/api/payment-bills/" + billId + "/payments", admin.token(),
                Map.of("amount", new BigDecimal("300.00"), "method", "真实测试"), 201);
        assertEquals("部分缴", body(partial).path("data").path("status").asText());
        MvcResult completed = postJson("/api/payment-bills/" + billId + "/payments", admin.token(),
                Map.of("amount", new BigDecimal("500.00"), "method", "真实测试"), 201);
        assertEquals("已缴", body(completed).path("data").path("status").asText());

        MvcResult records = mockMvc.perform(get("/api/payment-records")
                        .header("Authorization", admin.token())
                        .param("paymentId", String.valueOf(billId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andReturn();
        for (JsonNode record : body(records).path("data").path("records")) {
            assertEquals(admin.userId(), record.path("operatorUserId").asLong());
            assertFalse(record.path("operatorName").asText().isBlank());
            assertFalse(record.path("paidAt").asText().isBlank());
        }
        assertBigDecimal("800.00", jdbcTemplate.queryForObject(
                "SELECT amount_paid FROM payment WHERE id = ?", BigDecimal.class, billId));
        assertBigDecimal("800.00", jdbcTemplate.queryForObject(
                "SELECT SUM(amount) FROM payment_record WHERE payment_id = ?", BigDecimal.class, billId));
    }

    private void verifyRepairLifecycle(LoginSession admin) throws Exception {
        Long repairerRoleId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_role WHERE code = 'REPAIRER' AND enabled = TRUE", Long.class);
        String repairerPassword = "Real-it-password-" + runId.substring(3, 9);
        MvcResult user = postJson("/api/users", admin.token(), Map.of(
                "username", temporaryUsername,
                "displayName", runId + "维修员",
                "password", repairerPassword,
                "enabled", true,
                "roleIds", List.of(repairerRoleId)), 201);
        long repairerId = body(user).path("data").path("id").asLong();
        LoginSession repairer = login(temporaryUsername, repairerPassword);

        MvcResult order = postJson("/api/repair-orders", admin.token(), Map.of(
                "reporter", runId + "报修",
                "location", runId + "宿舍",
                "type", "水电维修",
                "description", "真实基础设施报修",
                "assigneeUserId", repairerId), 201);
        long orderId = body(order).path("data").path("id").asLong();

        postJson("/api/repair-orders/" + orderId + "/records", repairer.token(), Map.of(
                "handler", "不可采信处理人",
                "content", "开始处理",
                "cost", BigDecimal.ZERO,
                "status", "处理中"), 201);
        postJson("/api/repair-orders/" + orderId + "/records", repairer.token(), Map.of(
                "handler", "不可采信处理人",
                "content", "完成维修",
                "cost", new BigDecimal("20.00"),
                "status", "已完成"), 201);
        postJson("/api/repair-orders/" + orderId + "/records", repairer.token(), Map.of(
                "handler", "不可采信处理人",
                "content", "补充验收记录",
                "cost", BigDecimal.ZERO,
                "status", "已完成"), 201);

        MvcResult records = mockMvc.perform(get("/api/repair-records")
                        .header("Authorization", repairer.token())
                        .param("repairOrderId", String.valueOf(orderId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andReturn();
        for (JsonNode record : body(records).path("data").path("records")) {
            assertEquals(repairerId, record.path("operatorUserId").asLong());
            assertEquals(runId + "维修员", record.path("handler").asText());
            assertFalse(record.path("handledAt").asText().isBlank());
        }
        assertEquals("已完成", jdbcTemplate.queryForObject(
                "SELECT status FROM repair_order WHERE id = ?", String.class, orderId));
        assertEquals(3, count("SELECT COUNT(*) FROM repair_record WHERE repair_order_id = ?", orderId));
    }

    private void verifyNoticeLifecycle(LoginSession admin) throws Exception {
        MvcResult created = postJson("/api/notices", admin.token(), Map.of(
                "title", runId + "公告",
                "type", "宿舍通知",
                "publisher", "真实集成测试",
                "status", "草稿"), 201);
        long noticeId = body(created).path("data").path("id").asLong();

        MvcResult published = patchJson("/api/notices/" + noticeId, admin.token(), Map.of(
                "title", runId + "公告",
                "type", "宿舍通知",
                "publisher", "真实集成测试",
                "status", "已发布"), 200);
        assertEquals("已发布", body(published).path("data").path("status").asText());
        mockMvc.perform(delete("/api/notices/{id}", noticeId).header("Authorization", admin.token()))
                .andExpect(status().isNoContent());
        assertEquals("已撤回", jdbcTemplate.queryForObject(
                "SELECT status FROM notice WHERE id = ?", String.class, noticeId));
    }

    private LoginSession login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password))))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus(),
                "真实基础设施管理员登录失败，凭据不会写入测试日志");
        var cookie = result.getResponse().getCookie("Authorization");
        assertNotNull(cookie, "登录成功后必须返回 Authorization Cookie");
        loginTokens.add(cookie.getValue());
        long userId = body(result).path("data").path("id").asLong();
        return new LoginSession(userId, cookie.getValue());
    }

    private void logoutAll() throws Exception {
        for (String token : List.copyOf(loginTokens)) {
            logout(token);
        }
    }

    private void logout(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/logout").header("Authorization", token)).andReturn();
        assertEquals(200, result.getResponse().getStatus(), "真实基础设施登录态清理失败");
        loginTokens.remove(token);
    }

    private MvcResult postJson(String path, String token, Object payload, int expectedStatus) throws Exception {
        return mockMvc.perform(post(path)
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }

    private MvcResult patchJson(String path, String token, Object payload, int expectedStatus) throws Exception {
        return mockMvc.perform(patch(path)
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private int count(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, Integer.class, arguments);
    }

    private void assertColumns(String table, String... requiredColumns) {
        Set<String> actual = jdbcTemplate.queryForList(
                        "SELECT column_name FROM information_schema.columns "
                                + "WHERE table_schema = DATABASE() AND LOWER(table_name) = ?",
                        String.class, table.toLowerCase(Locale.ROOT))
                .stream().map(value -> value.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        for (String column : requiredColumns) {
            assertTrue(actual.contains(column), () -> table + " 缺少列 " + column);
        }
    }

    private void assertBigDecimal(String expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    private record LoginSession(long userId, String token) {
    }

    static final class RootEnvInitializer
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        private static final List<String> REQUIRED_KEYS = List.of(
                "DB_URL", "DB_USERNAME", "DB_PASSWORD",
                "REDIS_HOST", "REDIS_PORT", "REDIS_DATABASE",
                "BOOTSTRAP_ADMIN_USERNAME", "BOOTSTRAP_ADMIN_PASSWORD");
        private static Map<String, String> rootEnvironment = Map.of();

        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            Map<String, String> values = readRootEnv();
            List<String> missing = REQUIRED_KEYS.stream().filter(key -> !values.containsKey(key)).toList();
            if (!missing.isEmpty()) {
                throw new IllegalStateException("根 .env 缺少 RealInfrastructureIT 必需键: " + missing);
            }
            for (String key : List.of("DB_URL", "DB_USERNAME", "REDIS_HOST", "REDIS_PORT",
                    "BOOTSTRAP_ADMIN_USERNAME", "BOOTSTRAP_ADMIN_PASSWORD")) {
                if (values.get(key).isBlank()) {
                    throw new IllegalStateException("根 .env 的 " + key + " 不能为空");
                }
            }
            rootEnvironment = Map.copyOf(values);
            Map<String, Object> properties = new LinkedHashMap<>(values);
            properties.put("dormitory.demo-data-enabled", false);
            properties.put(
                    "dormitory.dashboard.cache-key",
                    "real-it:" + UUID.randomUUID().toString().replace("-", "") + ":dashboard:statistics");
            applicationContext.getEnvironment().getPropertySources()
                    .addFirst(new MapPropertySource("realInfrastructureRootEnv", properties));
        }

        static Map<String, String> rootEnvironment() {
            if (rootEnvironment.isEmpty()) {
                throw new IllegalStateException("RealInfrastructureIT 根 .env 尚未初始化");
            }
            return rootEnvironment;
        }

        private static Map<String, String> readRootEnv() {
            Path envPath = locateRootEnv();
            Map<String, String> values = new LinkedHashMap<>();
            try {
                for (String rawLine : Files.readAllLines(envPath, StandardCharsets.UTF_8)) {
                    String line = rawLine.replace("\uFEFF", "").trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    if (line.startsWith("export ")) line = line.substring("export ".length()).trim();
                    int separator = line.indexOf('=');
                    if (separator <= 0) continue;
                    String key = line.substring(0, separator).trim();
                    String value = line.substring(separator + 1).trim();
                    if (value.length() >= 2
                            && ((value.startsWith("\"") && value.endsWith("\""))
                            || (value.startsWith("'") && value.endsWith("'")))) {
                        value = value.substring(1, value.length() - 1);
                    }
                    values.put(key, value);
                }
            } catch (Exception exception) {
                throw new IllegalStateException("无法读取仓库根 .env", exception);
            }
            return values;
        }

        private static Path locateRootEnv() {
            Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
            for (int depth = 0; depth < 4 && current != null; depth++, current = current.getParent()) {
                Path candidate = current.resolve(".env");
                if (Files.isRegularFile(candidate) && Files.isDirectory(current.resolve("backend"))) {
                    return candidate;
                }
            }
            throw new IllegalStateException("未找到仓库根 .env，RealInfrastructureIT 不会静默跳过");
        }
    }
}
