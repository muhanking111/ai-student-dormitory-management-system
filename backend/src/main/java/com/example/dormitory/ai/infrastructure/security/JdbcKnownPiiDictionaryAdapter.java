package com.example.dormitory.ai.infrastructure.security;

import com.example.dormitory.ai.port.KnownPiiDictionaryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 从业务事实表只读加载当前有效的已知 L2 标识；值只进入短 TTL 进程内分类快照。 */
public class JdbcKnownPiiDictionaryAdapter implements KnownPiiDictionaryPort {

    private static final String ACTIVE_NAMES_SQL = """
            SELECT pii_kind, pii_value
            FROM (
                SELECT 'PERSON_NAME' AS pii_kind, TRIM(display_name) AS pii_value
                FROM sys_user
                WHERE enabled = ? AND deleted = ?
                UNION
                SELECT 'PERSON_NAME' AS pii_kind, TRIM(name) AS pii_value
                FROM student
                WHERE deleted = ?
                UNION
                SELECT 'STUDENT_NO' AS pii_kind, TRIM(student_no) AS pii_value
                FROM student
                WHERE deleted = ?
                UNION
                SELECT 'PHONE' AS pii_kind, TRIM(phone) AS pii_value
                FROM student
                WHERE deleted = ?
            ) known_pii
            WHERE pii_value IS NOT NULL AND pii_value <> ''
            ORDER BY CHAR_LENGTH(pii_value) DESC, pii_value ASC
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcKnownPiiDictionaryAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = java.util.Objects.requireNonNull(jdbcTemplate);
    }

    @Override
    @Transactional(readOnly = true)
    public List<KnownPiiValue> loadKnownPii() {
        return List.copyOf(jdbcTemplate.query(ACTIVE_NAMES_SQL,
                (resultSet, rowNumber) -> new KnownPiiValue(
                        PiiKind.valueOf(resultSet.getString("pii_kind")),
                        resultSet.getString("pii_value")),
                true, false, false, false, false));
    }
}
