package com.example.dormitory.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SchemaMigrationInitializerTest {

    @Test
    void migratesLegacyTablesWithoutDroppingExistingRows() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:legacy-schema;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE sys_user (id BIGINT PRIMARY KEY AUTO_INCREMENT, username VARCHAR(64) NOT NULL)");
        jdbc.execute("CREATE TABLE dormitory (id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(64) NOT NULL)");
        jdbc.execute("CREATE TABLE dormitory_building (dormitory_id BIGINT PRIMARY KEY, building_id BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE check_in_application_detail (application_id BIGINT PRIMARY KEY, student_id BIGINT NOT NULL, dormitory_id BIGINT NOT NULL)");
        jdbc.update("INSERT INTO sys_user(username) VALUES (?)", "legacy-admin");
        jdbc.update("INSERT INTO dormitory(name) VALUES (?)", "legacy-room");
        jdbc.update("INSERT INTO dormitory_building(dormitory_id, building_id) VALUES (1, 9)");
        jdbc.update("INSERT INTO check_in_application_detail(application_id, student_id, dormitory_id) VALUES (2, 3, 1)");

        new SchemaMigrationInitializer(jdbc).migrate();

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username='legacy-admin'", Integer.class));
        assertEquals(Boolean.FALSE, jdbc.queryForObject("SELECT deleted FROM sys_user WHERE username='legacy-admin'", Boolean.class));
        assertNotNull(jdbc.queryForObject("SELECT created_at FROM dormitory WHERE name='legacy-room'", java.sql.Timestamp.class));
        assertNotNull(jdbc.queryForObject("SELECT id FROM dormitory_building WHERE dormitory_id=1", Long.class));
        assertNotNull(jdbc.queryForObject("SELECT id FROM check_in_application_detail WHERE application_id=2", Long.class));

        new SchemaMigrationInitializer(jdbc).migrate();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dormitory_building WHERE dormitory_id=1", Integer.class));
    }

    @Test
    void addsLifecycleActorAssignmentAndDescriptionColumnsIdempotently() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:lifecycle-audit-schema;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE sys_user (id BIGINT PRIMARY KEY AUTO_INCREMENT)");
        jdbc.execute("CREATE TABLE check_in_application (id BIGINT PRIMARY KEY AUTO_INCREMENT)");
        jdbc.execute("CREATE TABLE check_in_application_detail (application_id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE check_in_record (id BIGINT PRIMARY KEY AUTO_INCREMENT)");
        jdbc.execute("CREATE TABLE repair_order (id BIGINT PRIMARY KEY AUTO_INCREMENT)");
        jdbc.execute("CREATE TABLE repair_record (id BIGINT PRIMARY KEY AUTO_INCREMENT)");
        jdbc.execute("CREATE TABLE payment_record (id BIGINT PRIMARY KEY AUTO_INCREMENT)");
        jdbc.update("INSERT INTO check_in_application DEFAULT VALUES");

        SchemaMigrationInitializer initializer = new SchemaMigrationInitializer(jdbc);
        initializer.migrate();
        initializer.migrate();

        assertColumn(jdbc, "check_in_application", "created_by_user_id");
        assertColumn(jdbc, "check_in_application", "applied_at");
        assertColumn(jdbc, "check_in_application_detail", "reviewer_user_id");
        assertColumn(jdbc, "check_in_record", "check_in_operator_user_id");
        assertColumn(jdbc, "check_in_record", "check_out_operator_user_id");
        assertColumn(jdbc, "repair_order", "assignee_user_id");
        assertColumn(jdbc, "repair_order", "description");
        assertColumn(jdbc, "repair_record", "operator_user_id");
        assertColumn(jdbc, "payment_record", "operator_user_id");
        assertNotNull(jdbc.queryForObject(
                "SELECT applied_at FROM check_in_application WHERE id = 1", java.sql.Timestamp.class));
        assertForeignKey(jdbc, "fk_application_creator");
        assertForeignKey(jdbc, "fk_application_reviewer");
        assertForeignKey(jdbc, "fk_check_in_operator");
        assertForeignKey(jdbc, "fk_check_out_operator");
        assertForeignKey(jdbc, "fk_repair_assignee");
        assertForeignKey(jdbc, "fk_repair_record_operator");
        assertForeignKey(jdbc, "fk_payment_record_operator");
    }

    @Test
    void backfillsPublishedTimeForLegacyPublishedNotices() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:legacy-notice-schema;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE notice ("
                + "id BIGINT PRIMARY KEY AUTO_INCREMENT, "
                + "status VARCHAR(16) NOT NULL, "
                + "type VARCHAR(32) NOT NULL DEFAULT '通知', "
                + "updated_at TIMESTAMP NOT NULL)");
        jdbc.update("INSERT INTO notice(status, updated_at) VALUES (?, TIMESTAMP '2026-07-10 12:34:56')", "已发布");

        SchemaMigrationInitializer initializer = new SchemaMigrationInitializer(jdbc);
        initializer.migrate();
        initializer.migrate();

        assertColumn(jdbc, "notice", "published_at");
        assertEquals(
                jdbc.queryForObject("SELECT updated_at FROM notice WHERE id = 1", java.sql.Timestamp.class),
                jdbc.queryForObject("SELECT published_at FROM notice WHERE id = 1", java.sql.Timestamp.class));
    }

    @Test
    void addsHygieneRemarkAndBedStatusIndexIdempotently() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:legacy-hygiene-bed-schema;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE hygiene_check (id BIGINT PRIMARY KEY AUTO_INCREMENT)");
        jdbc.execute("CREATE TABLE bed (id BIGINT PRIMARY KEY AUTO_INCREMENT, status VARCHAR(16) NOT NULL)");

        SchemaMigrationInitializer initializer = new SchemaMigrationInitializer(jdbc);
        initializer.migrate();
        initializer.migrate();

        assertColumn(jdbc, "hygiene_check", "remark");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.indexes "
                        + "WHERE LOWER(table_name) = ? AND LOWER(index_name) = ?",
                Integer.class, "bed", "idx_bed_status"));
    }

    private void assertColumn(JdbcTemplate jdbc, String table, String column) {
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE LOWER(table_name) = ? AND LOWER(column_name) = ?",
                Integer.class, table, column));
    }

    private void assertForeignKey(JdbcTemplate jdbc, String constraint) {
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE LOWER(constraint_name) = ? AND constraint_type = 'FOREIGN KEY'",
                Integer.class, constraint));
    }
}
