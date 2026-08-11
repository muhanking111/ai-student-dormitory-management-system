package com.example.dormitory.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

@Component
@Order(0)
public class SchemaMigrationInitializer implements ApplicationRunner {

    private static final List<String> AUDITED_TABLES = List.of(
            "sys_user", "sys_role", "sys_permission", "sys_user_role", "sys_role_permission",
            "building", "dormitory", "dormitory_building", "student", "bed",
            "check_in_application", "check_in_application_detail", "check_in_record",
            "repair_order", "repair_record", "payment", "payment_record", "hygiene_check", "notice");

    private static final List<String> SOFT_DELETABLE_TABLES = List.of(
            "sys_user", "sys_role", "building", "dormitory", "student", "hygiene_check", "notice");

    private final JdbcTemplate jdbcTemplate;

    public SchemaMigrationInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        migrate();
    }

    void migrate() {
        boolean mysql = isMySql();
        for (String table : AUDITED_TABLES) {
            if (!tableExists(table, mysql)) continue;
            addColumnIfMissing(table, "created_at", "TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP", mysql);
            addColumnIfMissing(table, "updated_at", "TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP", mysql);
            addColumnIfMissing(table, "created_operator_user_id", "BIGINT NULL", mysql);
            addColumnIfMissing(table, "updated_operator_user_id", "BIGINT NULL", mysql);
            addIndexIfMissing(table, "idx_" + table + "_created_at", "created_at", mysql);
        }
        for (String table : SOFT_DELETABLE_TABLES) {
            if (tableExists(table, mysql)) {
                addColumnIfMissing(table, "deleted", "BOOLEAN NOT NULL DEFAULT FALSE", mysql);
            }
        }
        addSurrogateIdentityIfMissing("dormitory_building", "dormitory_id", mysql);
        addSurrogateIdentityIfMissing("check_in_application_detail", "application_id", mysql);
        addColumnIfMissing("check_in_application", "created_by_user_id", "BIGINT NULL", mysql);
        addColumnIfMissing("check_in_application", "applied_at", "TIMESTAMP NULL", mysql);
        addColumnIfMissing("check_in_application_detail", "reviewer_user_id", "BIGINT NULL", mysql);
        addColumnIfMissing("check_in_record", "check_in_operator_user_id", "BIGINT NULL", mysql);
        addColumnIfMissing("check_in_record", "check_out_operator_user_id", "BIGINT NULL", mysql);
        addColumnIfMissing("repair_order", "description", "VARCHAR(255) NULL", mysql);
        addColumnIfMissing("repair_order", "assignee_user_id", "BIGINT NULL", mysql);
        addColumnIfMissing("repair_record", "operator_user_id", "BIGINT NULL", mysql);
        addColumnIfMissing("payment_record", "operator_user_id", "BIGINT NULL", mysql);
        addColumnIfMissing("hygiene_check", "remark", "VARCHAR(255) NULL", mysql);
        addColumnIfMissing("notice", "content", mysql ? "MEDIUMTEXT NULL" : "CLOB NULL", mysql);
        addColumnIfMissing("notice", "published_at", "TIMESTAMP(6) NULL", mysql);
        ensureNoticePublishedAtPrecision(mysql);
        addIndexIfMissing("building", "idx_building_status", "status", mysql);
        addIndexIfMissing("dormitory", "idx_dormitory_type", "type", mysql);
        addIndexIfMissing("bed", "idx_bed_status", "status", mysql);
        addIndexIfMissing("student", "idx_student_college_grade", "college, grade", mysql);
        addIndexIfMissing("check_in_application", "idx_application_date", "date", mysql);
        addIndexIfMissing("repair_order", "idx_repair_type", "type", mysql);
        addIndexIfMissing("payment", "idx_payment_student_type", "student_no, type", mysql);
        addIndexIfMissing("hygiene_check", "idx_hygiene_result", "result", mysql);
        addIndexIfMissing("hygiene_check", "idx_hygiene_location", "building, dormitory", mysql);
        addIndexIfMissing("notice", "idx_notice_status_published", "status, published_at", mysql);
        addIndexIfMissing("notice", "idx_notice_type", "type", mysql);
        backfillApplicationTime(mysql);
        backfillNoticePublishedTime(mysql);
        addForeignKeyIfMissing("check_in_application", "created_by_user_id", "sys_user",
                "fk_application_creator", mysql);
        addForeignKeyIfMissing("check_in_application_detail", "reviewer_user_id", "sys_user",
                "fk_application_reviewer", mysql);
        addForeignKeyIfMissing("check_in_record", "check_in_operator_user_id", "sys_user",
                "fk_check_in_operator", mysql);
        addForeignKeyIfMissing("check_in_record", "check_out_operator_user_id", "sys_user",
                "fk_check_out_operator", mysql);
        addForeignKeyIfMissing("repair_order", "assignee_user_id", "sys_user",
                "fk_repair_assignee", mysql);
        addForeignKeyIfMissing("repair_record", "operator_user_id", "sys_user",
                "fk_repair_record_operator", mysql);
        addForeignKeyIfMissing("payment_record", "operator_user_id", "sys_user",
                "fk_payment_record_operator", mysql);
    }

    private void addColumnIfMissing(String table, String column, String definition, boolean mysql) {
        if (!tableExists(table, mysql)) return;
        if (!columnExists(table, column, mysql)) {
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    private void addIndexIfMissing(
            String table,
            String index,
            String columns,
            boolean mysql) {
        if (!tableExists(table, mysql) || indexExists(table, index, mysql)) return;
        for (String column : columns.split(",")) {
            if (!columnExists(table, column.trim(), mysql)) return;
        }
        jdbcTemplate.execute("CREATE INDEX " + index + " ON " + table + " (" + columns + ")");
    }

    private boolean indexExists(String table, String index, boolean mysql) {
        String sql = mysql
                ? "SELECT COUNT(*) FROM information_schema.statistics "
                    + "WHERE table_schema = DATABASE() AND LOWER(table_name) = ? AND LOWER(index_name) = ?"
                : "SELECT COUNT(*) FROM information_schema.indexes "
                    + "WHERE LOWER(table_name) = ? AND LOWER(index_name) = ?";
        Integer count = jdbcTemplate.queryForObject(
                sql, Integer.class, table.toLowerCase(), index.toLowerCase());
        return count != null && count > 0;
    }

    private void addSurrogateIdentityIfMissing(String table, String naturalKey, boolean mysql) {
        if (!tableExists(table, mysql) || columnExists(table, "id", mysql)) return;
        String constraint = "uq_" + table + "_" + naturalKey;
        if (mysql) {
            jdbcTemplate.execute("ALTER TABLE " + table
                    + " DROP PRIMARY KEY, ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY FIRST, "
                    + "ADD CONSTRAINT " + constraint + " UNIQUE (" + naturalKey + ")");
            return;
        }
        jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN id BIGINT GENERATED BY DEFAULT AS IDENTITY");
        jdbcTemplate.execute("ALTER TABLE " + table + " DROP PRIMARY KEY");
        jdbcTemplate.execute("ALTER TABLE " + table + " ADD PRIMARY KEY (id)");
        jdbcTemplate.execute("ALTER TABLE " + table + " ADD CONSTRAINT " + constraint
                + " UNIQUE (" + naturalKey + ")");
    }

    private void backfillApplicationTime(boolean mysql) {
        if (!tableExists("check_in_application", mysql)
                || !columnExists("check_in_application", "applied_at", mysql)
                || !columnExists("check_in_application", "created_at", mysql)) return;
        jdbcTemplate.update("UPDATE check_in_application SET applied_at = created_at WHERE applied_at IS NULL");
    }

    private void backfillNoticePublishedTime(boolean mysql) {
        if (!tableExists("notice", mysql)
                || !columnExists("notice", "published_at", mysql)
                || !columnExists("notice", "updated_at", mysql)) return;
        jdbcTemplate.update("UPDATE notice SET published_at = updated_at "
                + "WHERE status = '已发布' AND published_at IS NULL");
    }

    private void ensureNoticePublishedAtPrecision(boolean mysql) {
        if (!mysql || !tableExists("notice", true) || !columnExists("notice", "published_at", true)) return;
        Integer precision = jdbcTemplate.queryForObject(
                "SELECT datetime_precision FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND LOWER(table_name) = ? AND LOWER(column_name) = ?",
                Integer.class, "notice", "published_at");
        if (precision == null || precision < 6) {
            jdbcTemplate.execute("ALTER TABLE notice MODIFY COLUMN published_at TIMESTAMP(6) NULL");
        }
    }

    private void addForeignKeyIfMissing(
            String table,
            String column,
            String referencedTable,
            String constraint,
            boolean mysql) {
        if (!tableExists(table, mysql) || !tableExists(referencedTable, mysql)
                || !columnExists(table, column, mysql) || constraintExists(table, constraint, mysql)) return;
        jdbcTemplate.execute("ALTER TABLE " + table + " ADD CONSTRAINT " + constraint
                + " FOREIGN KEY (" + column + ") REFERENCES " + referencedTable + "(id)");
    }

    private boolean constraintExists(String table, String constraint, boolean mysql) {
        String sql = mysql
                ? "SELECT COUNT(*) FROM information_schema.table_constraints "
                    + "WHERE constraint_schema = DATABASE() AND LOWER(table_name) = ? AND LOWER(constraint_name) = ?"
                : "SELECT COUNT(*) FROM information_schema.table_constraints "
                    + "WHERE LOWER(table_name) = ? AND LOWER(constraint_name) = ?";
        Integer count = jdbcTemplate.queryForObject(
                sql, Integer.class, table.toLowerCase(), constraint.toLowerCase());
        return count != null && count > 0;
    }

    private boolean tableExists(String table, boolean mysql) {
        String sql = mysql
                ? "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND LOWER(table_name) = ?"
                : "SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_name) = ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, table.toLowerCase());
        return count != null && count > 0;
    }

    private boolean columnExists(String table, String column, boolean mysql) {
        String sql = mysql
                ? "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND LOWER(table_name) = ? AND LOWER(column_name) = ?"
                : "SELECT COUNT(*) FROM information_schema.columns WHERE LOWER(table_name) = ? AND LOWER(column_name) = ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, table.toLowerCase(), column.toLowerCase());
        return count != null && count > 0;
    }

    private boolean isMySql() {
        return Boolean.TRUE.equals(jdbcTemplate.execute((Connection connection) -> {
            try {
                return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("mysql");
            } catch (SQLException exception) {
                throw new IllegalStateException("无法识别数据库类型", exception);
            }
        }));
    }
}
