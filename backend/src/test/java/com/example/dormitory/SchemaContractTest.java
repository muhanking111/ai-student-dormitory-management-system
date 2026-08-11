package com.example.dormitory;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.dormitory.domain.HygieneCheck;
import com.example.dormitory.mapper.HygieneCheckMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

@ActiveProfiles("test")
@SpringBootTest
class SchemaContractTest {

    private static final List<String> TABLES = List.of(
            "sys_user", "sys_role", "sys_permission", "sys_user_role", "sys_role_permission",
            "building", "dormitory", "dormitory_building", "student", "bed",
            "check_in_application", "check_in_application_detail", "check_in_record",
            "repair_order", "repair_record", "payment", "payment_record", "hygiene_check", "notice");

    private static final List<String> SOFT_DELETABLE_TABLES = List.of(
            "sys_user", "sys_role", "building", "dormitory", "student", "hygiene_check", "notice");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private HygieneCheckMapper hygieneCheckMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Environment environment;

    @Test
    void everyTableHasIdentityAndAuditColumns() {
        for (String table : TABLES) {
            Set<String> columns = columns(table);
            for (String required : List.of(
                    "id", "created_at", "updated_at", "created_operator_user_id", "updated_operator_user_id")) {
                assertTrue(columns.contains(required), () -> table + " 缺少 " + required);
            }
        }
    }

    @Test
    void commonFilterAndCreationTimeColumnsHaveIndexes() {
        for (String table : TABLES) {
            assertTrue(indexExists("idx_" + table + "_created_at"), () -> table + " 缺少 created_at 索引");
        }
        for (String index : List.of(
                "idx_building_status", "idx_dormitory_type", "idx_bed_status", "idx_student_college_grade",
                "idx_application_date", "idx_repair_type", "idx_payment_student_type",
                "idx_hygiene_result", "idx_hygiene_location", "idx_notice_status_published", "idx_notice_type")) {
            assertTrue(indexExists(index), () -> "缺少筛选索引 " + index);
        }
    }

    @Test
    void deletableMasterDataTablesReserveLogicalDeleteColumn() {
        for (String table : SOFT_DELETABLE_TABLES) {
            assertTrue(columns(table).contains("deleted"), () -> table + " 缺少 deleted");
        }
    }

    @Test
    void lifecycleTablesPersistTrustedActorsBusinessTimesAndAssignments() {
        Map<String, List<String>> requiredColumns = Map.of(
                "check_in_application", List.of("created_by_user_id", "applied_at"),
                "check_in_application_detail", List.of("reviewer_user_id", "reviewed_at"),
                "check_in_record", List.of("check_in_operator_user_id", "check_out_operator_user_id",
                        "check_in_date", "check_out_date"),
                "repair_order", List.of("assignee_user_id", "description"),
                "repair_record", List.of("operator_user_id", "handled_at"),
                "payment_record", List.of("operator_user_id", "paid_at"),
                "hygiene_check", List.of("remark"),
                "notice", List.of("published_at"));
        requiredColumns.forEach((table, required) -> {
            Set<String> actual = columns(table);
            required.forEach(column -> assertTrue(actual.contains(column), () -> table + " 缺少 " + column));
        });

        for (String constraint : List.of(
                "fk_application_creator", "fk_application_reviewer",
                "fk_check_in_operator", "fk_check_out_operator",
                "fk_repair_assignee", "fk_repair_record_operator", "fk_payment_record_operator")) {
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.table_constraints "
                            + "WHERE LOWER(constraint_name) = ? AND constraint_type = 'FOREIGN KEY'",
                    Integer.class, constraint));
        }
    }

    @Test
    void mapperDeleteUsesLogicalDeleteForDeletableBusinessData() {
        jdbcTemplate.update("DELETE FROM hygiene_check WHERE dormitory = ?", "逻辑删除测试宿舍");
        HygieneCheck check = new HygieneCheck(null, "逻辑删除测试宿舍", "测试楼", "2026-07-11",
                "测试员", 88, "优秀", "逻辑删除测试");
        hygieneCheckMapper.insert(check);

        hygieneCheckMapper.deleteById(check.getId());

        assertNull(hygieneCheckMapper.selectById(check.getId()));
        assertEquals(Boolean.TRUE, jdbcTemplate.queryForObject(
                "SELECT deleted FROM hygiene_check WHERE id = ?", Boolean.class, check.getId()));
        jdbcTemplate.update("DELETE FROM hygiene_check WHERE id = ?", check.getId());
    }

    @Test
    void caseInsensitiveJacksonPropertiesRemainExplicitlyDisabled() {
        assertEquals("false", environment.getProperty(
                "spring.jackson.mapper.accept-case-insensitive-properties"));
        assertFalse(objectMapper.isEnabled(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES));
    }

    private Set<String> columns(String table) {
        return jdbcTemplate.queryForList(
                        "SELECT column_name FROM information_schema.columns WHERE LOWER(table_name) = ?",
                        String.class,
                        table.toLowerCase(Locale.ROOT))
                .stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    private boolean indexExists(String index) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.indexes WHERE LOWER(index_name) = ?",
                Integer.class,
                index.toLowerCase(Locale.ROOT));
        return count != null && count > 0;
    }
}
