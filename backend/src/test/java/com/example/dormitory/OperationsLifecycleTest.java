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

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest(properties = "dormitory.ai.business-clock.fixed-instant=2026-08-03T04:00:00Z")
class OperationsLifecycleTest {

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
    void repairOrderRequiresAdjacentStateTransitionsAndKeepsRecords() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/repair-orders")
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reporter", "张同学",
                                "location", "1号楼-101宿舍",
                                "type", "水电维修",
                                "description", "灯管损坏"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("待处理"))
                .andExpect(jsonPath("$.data.code").isString())
                .andExpect(jsonPath("$.data.description").value("灯管损坏"))
                .andReturn();
        long orderId = body(created).path("data").path("id").asLong();

        mockMvc.perform(post("/api/repair-orders/{id}/records", orderId)
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "handler", "维修人员",
                                "content", "更换灯管",
                                "cost", new BigDecimal("25.00"),
                                "status", "已完成"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("维修状态只能从待处理变更为处理中"));

        mockMvc.perform(post("/api/repair-orders/{id}/records", orderId)
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "handler", "维修人员",
                                "content", "开始现场处理",
                                "cost", BigDecimal.ZERO,
                                "status", "处理中"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("处理中"));

        mockMvc.perform(post("/api/repair-orders/{id}/records", orderId)
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "handler", "维修人员",
                                "content", "更换灯管",
                                "cost", new BigDecimal("25.00"),
                                "status", "已完成"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("已完成"));

        mockMvc.perform(post("/api/repair-orders/{id}/records", orderId)
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "handler", "伪造处理人",
                                "content", "补充验收说明",
                                "cost", BigDecimal.ZERO,
                                "status", "已完成"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("已完成"));

        mockMvc.perform(post("/api/repair-orders/{id}/records", orderId)
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "handler", "伪造处理人",
                                "content", "试图回退状态",
                                "cost", BigDecimal.ZERO,
                                "status", "处理中"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("已完成报修单只能补充已完成记录"));

        mockMvc.perform(get("/api/repair-orders")
                        .header("Authorization", token)
                        .param("status", "已完成"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));

        mockMvc.perform(get("/api/repair-records")
                        .header("Authorization", token)
                        .param("page", "1")
                        .param("pageSize", "1")
                        .param("keyword", "更换灯管")
                        .param("repairOrderId", String.valueOf(orderId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(1))
                .andExpect(jsonPath("$.data.records[0].id").isNumber())
                .andExpect(jsonPath("$.data.records[0].repairOrderId").value(orderId))
                .andExpect(jsonPath("$.data.records[0].location").value("1号楼-101宿舍"))
                .andExpect(jsonPath("$.data.records[0].handler").value("测试管理员"))
                .andExpect(jsonPath("$.data.records[0].operatorUserId").value(adminUserId))
                .andExpect(jsonPath("$.data.records[0].content").value("更换灯管"))
                .andExpect(jsonPath("$.data.records[0].cost").value(25.00))
                .andExpect(jsonPath("$.data.records[0].status").value("已完成"))
                .andExpect(jsonPath("$.data.records[0].handledAt").isString());

        mockMvc.perform(get("/api/repair-records")
                        .header("Authorization", token)
                        .param("keyword", "1号楼-101宿舍"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3));

        mockMvc.perform(get("/api/repair-records")
                        .header("Authorization", token)
                        .param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("分页参数不合法"));
        org.junit.jupiter.api.Assertions.assertEquals("已完成",
                jdbcTemplate.queryForObject("SELECT status FROM repair_order WHERE id = ?", String.class, orderId));
        org.junit.jupiter.api.Assertions.assertEquals("灯管损坏",
                jdbcTemplate.queryForObject("SELECT description FROM repair_order WHERE id = ?", String.class, orderId));
        org.junit.jupiter.api.Assertions.assertEquals(3,
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM repair_record WHERE repair_order_id = ?", Integer.class, orderId));
    }

    @Test
    void paymentBillRejectsOverPaymentAndUpdatesStatus() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/payment-bills")
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "studentNo", "20262001",
                                "name", "缴费学生",
                                "type", "住宿费",
                                "amountDue", new BigDecimal("800.00"),
                                "deadline", "2026-08-31"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("未缴"))
                .andReturn();
        long billId = body(created).path("data").path("id").asLong();

        mockMvc.perform(post("/api/payment-bills/{id}/payments", billId)
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("amount", new BigDecimal("300.00"), "method", "现金"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.amountPaid").value(300.00))
                .andExpect(jsonPath("$.data.status").value("部分缴"));

        mockMvc.perform(post("/api/payment-bills/{id}/payments", billId)
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("amount", new BigDecimal("600.00"), "method", "现金"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("缴费金额超过待缴金额"));

        mockMvc.perform(post("/api/payment-bills/{id}/payments", billId)
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("amount", new BigDecimal("500.00"), "method", "现金"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.amountPaid").value(800.00))
                .andExpect(jsonPath("$.data.status").value("已缴"));

        mockMvc.perform(get("/api/payment-records")
                        .header("Authorization", token)
                        .param("page", "1")
                        .param("pageSize", "1")
                        .param("keyword", "缴费学生")
                        .param("paymentId", String.valueOf(billId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(1))
                .andExpect(jsonPath("$.data.records[0].id").isNumber())
                .andExpect(jsonPath("$.data.records[0].paymentId").value(billId))
                .andExpect(jsonPath("$.data.records[0].studentNo").value("20262001"))
                .andExpect(jsonPath("$.data.records[0].name").value("缴费学生"))
                .andExpect(jsonPath("$.data.records[0].type").value("住宿费"))
                .andExpect(jsonPath("$.data.records[0].amount").value(500.00))
                .andExpect(jsonPath("$.data.records[0].method").value("现金"))
                .andExpect(jsonPath("$.data.records[0].operatorUserId").value(adminUserId))
                .andExpect(jsonPath("$.data.records[0].operatorName").value("测试管理员"))
                .andExpect(jsonPath("$.data.records[0].paidAt").isString());

        mockMvc.perform(get("/api/payment-records")
                        .header("Authorization", token)
                        .param("keyword", "现金"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));

        mockMvc.perform(get("/api/payment-records")
                        .header("Authorization", token)
                        .param("pageSize", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("分页参数不合法"));
        org.junit.jupiter.api.Assertions.assertEquals(adminUserId,
                jdbcTemplate.queryForObject(
                        "SELECT operator_user_id FROM payment_record ORDER BY id DESC LIMIT 1", Long.class));
        org.junit.jupiter.api.Assertions.assertEquals(0,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM payment WHERE id = ? AND amount_paid > amount_due",
                        Integer.class, billId));
    }

    @Test
    void repairersOnlySeeAndProcessOrdersAssignedToThem() throws Exception {
        TestUser firstRepairer = createUser("维修员甲", "REPAIRER", true);
        TestUser secondRepairer = createUser("维修员乙", "REPAIRER", true);
        TestUser dormitoryManager = createUser("非维修宿管", "DORM_MANAGER", true);
        TestUser disabledRepairer = createUser("停用维修员", "REPAIRER", false);

        long firstOrderId = createAssignedRepairOrder("A栋-101", firstRepairer.id());
        long secondOrderId = createAssignedRepairOrder("B栋-202", secondRepairer.id());

        MvcResult selfCreated = mockMvc.perform(post("/api/repair-orders")
                        .header("Authorization", firstRepairer.token())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reporter", "维修员自建",
                                "location", "A栋-102",
                                "type", "水电维修"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.assigneeUserId").value(firstRepairer.id()))
                .andReturn();
        long selfCreatedOrderId = body(selfCreated).path("data").path("id").asLong();

        mockMvc.perform(post("/api/repair-orders")
                        .header("Authorization", firstRepairer.token())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reporter", "试图跨人指派",
                                "location", "B栋-203",
                                "type", "水电维修",
                                "assigneeUserId", secondRepairer.id()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("维修人员只能将新报修单分配给自己"));

        mockMvc.perform(post("/api/repair-orders")
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reporter", "错误指派",
                                "location", "C栋-303",
                                "type", "水电维修",
                                "assigneeUserId", dormitoryManager.id()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("指派用户必须是已启用的维修人员"));

        mockMvc.perform(post("/api/repair-orders")
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reporter", "停用指派",
                                "location", "D栋-404",
                                "type", "家具维修",
                                "assigneeUserId", disabledRepairer.id()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("指派用户必须是已启用的维修人员"));

        MvcResult unassigned = mockMvc.perform(post("/api/repair-orders")
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reporter", "待指派",
                                "location", "E栋-505",
                                "type", "门窗维修"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.assigneeUserId").doesNotExist())
                .andReturn();
        long reassignedOrderId = body(unassigned).path("data").path("id").asLong();

        mockMvc.perform(patch("/api/repair-orders/{id}/assignee", reassignedOrderId)
                        .header("Authorization", secondRepairer.token())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("assigneeUserId", secondRepairer.id()))))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/repair-orders/{id}/assignee", reassignedOrderId)
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("assigneeUserId", firstRepairer.id()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assigneeUserId").value(firstRepairer.id()));

        mockMvc.perform(get("/api/repair-orders").header("Authorization", firstRepairer.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.records[?(@.id == " + firstOrderId + ")]").exists())
                .andExpect(jsonPath("$.data.records[?(@.id == " + selfCreatedOrderId + ")]").exists())
                .andExpect(jsonPath("$.data.records[?(@.id == " + reassignedOrderId + ")]").exists())
                .andExpect(jsonPath("$.data.records[?(@.id == " + secondOrderId + ")]").doesNotExist());
        mockMvc.perform(get("/api/repair-orders").header("Authorization", secondRepairer.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(secondOrderId));
        mockMvc.perform(get("/api/repair-orders").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(4));

        mockMvc.perform(get("/api/dashboard/statistics").header("Authorization", firstRepairer.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[3].title").value("待维修数量"))
                .andExpect(jsonPath("$.data[3].value").value(3));
        mockMvc.perform(get("/api/dashboard/statistics").header("Authorization", secondRepairer.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[3].value").value(1));
        mockMvc.perform(get("/api/dashboard/statistics").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[3].value").value(4));

        mockMvc.perform(post("/api/repair-orders/{id}/records", secondOrderId)
                        .header("Authorization", firstRepairer.token())
                        .contentType("application/json")
                        .content(repairRecordJson("伪造处理人", "越权处理", "处理中")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("只能处理分配给自己的维修单"));

        addRepairRecord(firstOrderId, firstRepairer, "伪造为其他人", "甲开始处理");
        addRepairRecord(secondOrderId, secondRepairer, "伪造为管理员", "乙开始处理");

        mockMvc.perform(get("/api/repair-records").header("Authorization", firstRepairer.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].repairOrderId").value(firstOrderId))
                .andExpect(jsonPath("$.data.records[0].handler").value(firstRepairer.displayName()))
                .andExpect(jsonPath("$.data.records[0].operatorUserId").value(firstRepairer.id()));
        mockMvc.perform(get("/api/repair-records").header("Authorization", secondRepairer.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].repairOrderId").value(secondOrderId))
                .andExpect(jsonPath("$.data.records[0].handler").value(secondRepairer.displayName()))
                .andExpect(jsonPath("$.data.records[0].operatorUserId").value(secondRepairer.id()));
        mockMvc.perform(get("/api/repair-records").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));

        mockMvc.perform(post("/api/repair-orders/{id}/records", firstOrderId)
                        .header("Authorization", firstRepairer.token())
                        .contentType("application/json")
                        .content(repairRecordJson("不可伪造", "甲完成维修", "已完成")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("已完成"));
        mockMvc.perform(patch("/api/repair-orders/{id}/assignee", firstOrderId)
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("assigneeUserId", secondRepairer.id()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("已完成报修单不能重新分配"));
    }

    @Test
    void recordHistoryRequiresDomainReadPermissions() throws Exception {
        Long dormitoryManagerRoleId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_role WHERE code = 'DORM_MANAGER'", Long.class);
        String username = "record-restricted-" + UUID.randomUUID().toString().substring(0, 8);
        String password = "Restricted-password-123";

        mockMvc.perform(post("/api/users")
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "displayName", "记录受限用户",
                                "password", password,
                                "enabled", true,
                                "roleIds", java.util.List.of(dormitoryManagerRoleId)))))
                .andExpect(status().isCreated());

        String restrictedToken = login(username, password);
        mockMvc.perform(get("/api/repair-records").header("Authorization", restrictedToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("无权执行该操作"));
        mockMvc.perform(get("/api/payment-records").header("Authorization", restrictedToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("无权执行该操作"));
    }

    @Test
    void hygieneCheckCalculatesResultAndSupportsDeletion() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/hygiene-checks")
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "dormitory", "1号宿舍",
                                "building", "1号楼",
                                "inspector", "管理员",
                                "score", 58,
                                "remark", "首次检查需整改"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.result").value("不合格"))
                .andExpect(jsonPath("$.data.remark").value("首次检查需整改"))
                .andExpect(jsonPath("$.data.createdOperatorUserId").value(adminUserId))
                .andReturn();
        long checkId = body(created).path("data").path("id").asLong();

        mockMvc.perform(patch("/api/hygiene-checks/{id}", checkId)
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "dormitory", "1号宿舍",
                                "building", "1号楼",
                                "inspector", "管理员",
                                "score", 65,
                                "remark", "已完成基础整改"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("一般"))
                .andExpect(jsonPath("$.data.remark").value("已完成基础整改"))
                .andExpect(jsonPath("$.data.updatedOperatorUserId").value(adminUserId));

        mockMvc.perform(patch("/api/hygiene-checks/{id}", checkId)
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "dormitory", "1号宿舍",
                                "building", "1号楼",
                                "inspector", "管理员",
                                "score", 92,
                                "remark", "复查通过"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("优秀"))
                .andExpect(jsonPath("$.data.remark").value("复查通过"));

        mockMvc.perform(get("/api/hygiene-checks").header("Authorization", token).param("result", "优秀"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));

        mockMvc.perform(delete("/api/hygiene-checks/{id}", checkId).header("Authorization", token))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/hygiene-checks")
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "dormitory", "2号宿舍",
                                "building", "1号楼",
                                "inspector", "管理员",
                                "score", 80,
                                "remark", "x".repeat(256)))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void newlyPublishedDraftAppearsFirstByPublishedTime() throws Exception {
        MvcResult draft = createNotice("稍后发布的草稿", "草稿");
        long draftId = body(draft).path("data").path("id").asLong();
        org.junit.jupiter.api.Assertions.assertEquals(
                "2026-08-03", body(draft).path("data").path("date").asText());
        for (int index = 1; index <= 5; index++) {
            createNotice("已发布公告 " + index, "已发布");
        }

        MvcResult published = mockMvc.perform(patch("/api/notices/{id}", draftId)
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "稍后发布的草稿",
                                "type", "宿舍通知",
                                "publisher", "管理员",
                                "status", "已发布"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.date").value("2026-08-03"))
                .andExpect(jsonPath("$.data.publishedAt").isNotEmpty())
                .andReturn();
        String publishedAt = body(published).path("data").path("publishedAt").asText();
        org.junit.jupiter.api.Assertions.assertFalse(publishedAt.isBlank());
        org.junit.jupiter.api.Assertions.assertTrue(publishedAt.startsWith("2026-08-03T12:00:00"));

        mockMvc.perform(get("/api/notices")
                        .header("Authorization", token)
                        .param("status", "已发布")
                        .param("pageSize", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(6))
                .andExpect(jsonPath("$.data.records[0].id").value(draftId))
                .andExpect(jsonPath("$.data.records[0].publishedAt").isNotEmpty());
    }

    @Test
    void draftNoticeIsHiddenFromPublishedListUntilPublished() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/notices")
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "阶段四公告",
                                "type", "宿舍通知",
                                "publisher", "管理员",
                                "status", "草稿"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("草稿"))
                .andReturn();
        long noticeId = body(created).path("data").path("id").asLong();

        mockMvc.perform(get("/api/notices")
                        .header("Authorization", token)
                        .param("status", "已发布"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        mockMvc.perform(patch("/api/notices/{id}", noticeId)
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "阶段四公告",
                                "type", "宿舍通知",
                                "publisher", "管理员",
                                "status", "已发布"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("已发布"));

        mockMvc.perform(patch("/api/notices/{id}", noticeId)
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "阶段四公告已更新",
                                "type", "宿舍通知",
                                "publisher", "管理员",
                                "status", "已发布"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("阶段四公告已更新"))
                .andExpect(jsonPath("$.data.status").value("已发布"));

        mockMvc.perform(patch("/api/notices/{id}", noticeId)
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "试图回草稿",
                                "type", "宿舍通知",
                                "publisher", "管理员",
                                "status", "草稿"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("已发布公告不能改回草稿，请先撤回"));

        mockMvc.perform(get("/api/notices")
                        .header("Authorization", token)
                        .param("status", "已发布"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));

        mockMvc.perform(delete("/api/notices/{id}", noticeId).header("Authorization", token))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/notices/{id}", noticeId).header("Authorization", token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("已撤回公告必须保留记录"));

        for (String targetStatus : java.util.List.of("草稿", "已发布")) {
            mockMvc.perform(patch("/api/notices/{id}", noticeId)
                            .header("Authorization", token)
                            .contentType("application/json")
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "title", "试图恢复公告",
                                    "type", "宿舍通知",
                                    "publisher", "管理员",
                                    "status", targetStatus))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("已撤回公告不能再次编辑"));
        }

        mockMvc.perform(get("/api/notices")
                        .header("Authorization", token)
                        .param("status", "已发布"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/api/notices")
                        .header("Authorization", token)
                        .param("status", "已撤回"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
        org.junit.jupiter.api.Assertions.assertEquals("已撤回",
                jdbcTemplate.queryForObject("SELECT status FROM notice WHERE id = ?", String.class, noticeId));
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult createNotice(String title, String statusValue) throws Exception {
        return mockMvc.perform(post("/api/notices")
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", title,
                                "type", "宿舍通知",
                                "publisher", "管理员",
                                "status", statusValue))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.createdOperatorUserId").value(adminUserId))
                .andReturn();
    }

    private String login() throws Exception {
        return login("admin", "test-password-123");
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk()).andReturn();
        var cookie = result.getResponse().getCookie("Authorization");
        assertNotNull(cookie);
        return cookie.getValue();
    }

    private TestUser createUser(String displayName, String roleCode, boolean enabled) throws Exception {
        Long roleId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_role WHERE code = ?", Long.class, roleCode);
        String username = "ops-" + UUID.randomUUID().toString().substring(0, 8);
        String password = "Operations-password-123";
        MvcResult result = mockMvc.perform(post("/api/users")
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "displayName", displayName,
                                "password", password,
                                "enabled", enabled,
                                "roleIds", java.util.List.of(roleId)))))
                .andExpect(status().isCreated())
                .andReturn();
        long id = body(result).path("data").path("id").asLong();
        return new TestUser(id, displayName, enabled ? login(username, password) : null);
    }

    private long createAssignedRepairOrder(String location, long assigneeUserId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/repair-orders")
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reporter", "测试报修人",
                                "location", location,
                                "type", "水电维修",
                                "assigneeUserId", assigneeUserId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.assigneeUserId").value(assigneeUserId))
                .andReturn();
        return body(result).path("data").path("id").asLong();
    }

    private void addRepairRecord(long orderId, TestUser repairer, String forgedHandler, String content) throws Exception {
        mockMvc.perform(post("/api/repair-orders/{id}/records", orderId)
                        .header("Authorization", repairer.token())
                        .contentType("application/json")
                        .content(repairRecordJson(forgedHandler, content, "处理中")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("处理中"));
    }

    private String repairRecordJson(String handler, String content, String status) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "handler", handler,
                "content", content,
                "cost", BigDecimal.ZERO,
                "status", status));
    }

    private record TestUser(long id, String displayName, String token) {
    }

    private void cleanBusinessData() {
        for (String table : new String[]{"repair_record", "payment_record", "repair_order",
                "payment", "hygiene_check", "notice"}) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
    }
}
