package com.example.dormitory.ai.infrastructure.security;

import com.example.dormitory.ai.port.KnownPiiDictionaryPort;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class JdbcKnownPiiDictionaryAdapterTest {

    @Test
    void loadsOnlyActiveKnownNamesStudentNumbersAndPhonesWithoutLoggingValues() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:known-pii;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE sys_user (id BIGINT PRIMARY KEY, username VARCHAR(64), "
                + "display_name VARCHAR(64), enabled BOOLEAN, deleted BOOLEAN)");
        jdbc.execute("CREATE TABLE student (id BIGINT PRIMARY KEY, student_no VARCHAR(32), "
                + "name VARCHAR(32), phone VARCHAR(32), deleted BOOLEAN)");
        jdbc.update("INSERT INTO sys_user VALUES (?,?,?,?,?)", 1, "active", "阿依古丽", true, false);
        jdbc.update("INSERT INTO sys_user VALUES (?,?,?,?,?)", 2, "disabled", "停用人员", false, false);
        jdbc.update("INSERT INTO sys_user VALUES (?,?,?,?,?)", 3, "deleted", "删除人员", true, true);
        jdbc.update("INSERT INTO student VALUES (?,?,?,?,?)", 10, "IMPORT-001", "阿依古丽·买买提",
                "+86 138-1234-5678", false);
        jdbc.update("INSERT INTO student VALUES (?,?,?,?,?)", 11, "2026000011", "已删学生",
                "13800000000", true);

        List<KnownPiiDictionaryPort.KnownPiiValue> values =
                new JdbcKnownPiiDictionaryAdapter(jdbc).loadKnownPii();

        assertEquals(java.util.Set.of("阿依古丽·买买提", "阿依古丽", "IMPORT-001", "+86 138-1234-5678"),
                values.stream().map(KnownPiiDictionaryPort.KnownPiiValue::value)
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(KnownPiiDictionaryPort.PiiKind.STUDENT_NO,
                values.stream().filter(value -> value.value().equals("IMPORT-001"))
                        .findFirst().orElseThrow().kind());
        assertEquals(KnownPiiDictionaryPort.PiiKind.PHONE,
                values.stream().filter(value -> value.value().contains("138-1234"))
                        .findFirst().orElseThrow().kind());
        String diagnostic = values.toString();
        assertFalse(diagnostic.contains("阿依古丽"), "字典值的诊断字符串不得泄露原姓名");
        assertFalse(diagnostic.contains("IMPORT-001"));
        assertFalse(diagnostic.contains("138-1234"));
        assertFalse(diagnostic.contains("active"));
    }
}
