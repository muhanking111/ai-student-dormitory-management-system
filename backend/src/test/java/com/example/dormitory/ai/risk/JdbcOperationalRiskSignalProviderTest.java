package com.example.dormitory.ai.risk;

import com.example.dormitory.ai.infrastructure.risk.JdbcOperationalRiskSignalProvider;
import com.example.dormitory.ai.infrastructure.risk.JdbcOperationalRiskReadAdapter;
import com.example.dormitory.ai.infrastructure.risk.OperationalRiskThresholds;
import com.example.dormitory.ai.infrastructure.risk.RiskSubjectTokenizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcOperationalRiskSignalProviderTest {

    private static final Instant NOW = Instant.parse("2026-07-11T08:00:00Z");

    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanFacts() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:risk-provider-" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql"),
                new ClassPathResource("ai-schema.sql")).execute(dataSource);
        jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Test
    void emitsSixDeterministicOperationalRulesAtConfiguredBoundariesWithoutPii() {
        long dormitoryId = insertDormitory("敏感楼栋", "101宿舍", 2, 2, 0);
        insertRepair("WX-1", "张同学", "敏感楼栋-101宿舍", "水电", "待处理", NOW.minusSeconds(72 * 3600));
        insertRepair("WX-2", "李同学", "敏感楼栋-101宿舍", "水电", "处理中", NOW.minusSeconds(48 * 3600));
        insertRepair("WX-3", "王同学", "敏感楼栋-101宿舍", "水电", "已完成", NOW.minusSeconds(24 * 3600));
        insertFailedHygieneCheck("敏感楼栋", "101宿舍", "敏感检查员", 58, "2026-07-10");
        insertOverduePayment("20260000999", "欠费学生", "2026-07-10");
        insertPendingApplication("20260000001", "隐私姓名", NOW.minusSeconds(48 * 3600));

        JdbcOperationalRiskSignalProvider provider = new JdbcOperationalRiskSignalProvider(
                new JdbcOperationalRiskReadAdapter(jdbcTemplate),
                new OperationalRiskThresholds(72, 3, 30, 48),
                new RiskSubjectTokenizer("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8), 7),
                Clock.fixed(NOW, ZoneOffset.UTC));

        List<RiskSignal> signals = provider.evaluate(RiskScanScope.full(1L, Set.of(
                "repair:read", "dormitory:read", "checkin:read", "hygiene:read", "payment:read"), NOW));

        assertEquals(Set.of("repair-backlog", "repeat-repair", "resource-checkin-inconsistency",
                        "long-pending-operation", "failed-hygiene-check", "overdue-payment"),
                signals.stream().map(RiskSignal::riskType).collect(java.util.stream.Collectors.toSet()));
        assertTrue(signals.stream().allMatch(signal -> signal.observedAt().equals(NOW)));
        assertTrue(signals.stream().allMatch(signal -> signal.subjectToken().startsWith("risk_k7_")));
        assertTrue(signals.stream().allMatch(signal -> signal.evidence().keySet().stream().noneMatch(
                key -> Set.of("name", "studentNo", "phone", "location", "reporter").contains(key))));
        String serialized = signals.toString();
        assertFalse(serialized.contains("张同学"));
        assertFalse(serialized.contains("隐私姓名"));
        assertFalse(serialized.contains("20260000001"));
        assertFalse(serialized.contains("20260000999"));
        assertFalse(serialized.contains("欠费学生"));
        assertFalse(serialized.contains("敏感检查员"));
        assertFalse(serialized.contains("敏感楼栋"));
        assertTrue(signals.stream().anyMatch(signal -> signal.subjectResourceId().equals(dormitoryId)));
        RiskSignal payment = signals.stream()
                .filter(signal -> "overdue-payment".equals(signal.riskType()))
                .findFirst().orElseThrow();
        assertEquals(1L, ((Number) payment.evidence().get("ageDays")).longValue());
    }

    @Test
    void returnsNoSignalBelowThresholdsAndPolicyVersionChangesWithConfiguration() {
        long dormitoryId = insertDormitory("边界楼栋", "102宿舍", 1, 0, 1);
        jdbcTemplate.update("INSERT INTO bed (dormitory_id, bed_no, status, created_at, updated_at) "
                + "VALUES (?, '1', '空闲', ?, ?)", dormitoryId, NOW, NOW);
        insertRepair("WX-NEW", "甲", "边界楼栋-102宿舍", "家具", "待处理",
                NOW.minusSeconds(71 * 3600 + 3599));
        insertRepair("WX-FUTURE-1", "未来甲", "边界楼栋-102宿舍", "家具", "待处理", NOW.plusSeconds(3600));
        insertRepair("WX-FUTURE-2", "未来乙", "边界楼栋-102宿舍", "家具", "待处理", NOW.plusSeconds(7200));
        insertRepair("WX-FUTURE-3", "未来丙", "边界楼栋-102宿舍", "家具", "待处理", NOW.plusSeconds(10800));
        insertPendingApplication("20260000002", "乙", NOW.minusSeconds(47 * 3600 + 3599));

        JdbcOperationalRiskSignalProvider defaults = provider(new OperationalRiskThresholds(72, 3, 30, 48));
        JdbcOperationalRiskSignalProvider stricter = provider(new OperationalRiskThresholds(96, 4, 14, 72));

        assertTrue(defaults.evaluate(RiskScanScope.full(1L, Set.of(
                "repair:read", "dormitory:read", "checkin:read"), NOW)).isEmpty());
        assertFalse(defaults.policyVersion().equals(stricter.policyVersion()));
    }

    @Test
    void evaluatesOnlyTheRequestAndCurrentAuthorizationIntersection() {
        long first = insertRepair("WX-SCOPE-1", "甲", "一号楼-101", "水电", "待处理",
                NOW.minusSeconds(96 * 3600));
        insertRepair("WX-SCOPE-2", "乙", "二号楼-201", "家具", "待处理",
                NOW.minusSeconds(96 * 3600));
        JdbcOperationalRiskSignalProvider provider = provider(new OperationalRiskThresholds(72, 3, 30, 48));
        RiskScanScope requestScope = RiskScanScope.restricted(9L, Set.of("repair:read"),
                Set.of(first), Set.of(), Set.of(), NOW);
        RiskScanScope currentNarrower = RiskScanScope.restricted(9L, Set.of("repair:read"),
                Set.of(first), Set.of(), Set.of(), NOW.plusSeconds(10));

        List<RiskSignal> signals = provider.evaluate(requestScope.intersect(currentNarrower));

        assertEquals(1, signals.size());
        assertEquals(first, signals.getFirst().subjectResourceId());
        assertEquals("repair-backlog", signals.getFirst().riskType());
        assertTrue(provider.evaluate(requestScope.intersect(RiskScanScope.restricted(
                9L, Set.of(), Set.of(), Set.of(), Set.of(), NOW.plusSeconds(20)))).isEmpty());
    }

    @Test
    void neverMapsUnauthorizedRowsBeforeApplyingRestrictedScope() {
        long allowed = insertRepair("WX-GUARD-ALLOWED", "甲", "一号楼-101", "水电", "待处理",
                NOW.minusSeconds(96 * 3600));
        long forbidden = insertRepair("WX-GUARD-FORBIDDEN", "敏感姓名", "敏感宿舍", "水电", "待处理",
                NOW.minusSeconds(96 * 3600));
        JdbcTemplate guarded = new GuardedJdbcTemplate(
                jdbcTemplate.getDataSource(), Map.of("repair_order", Set.of(forbidden)));
        JdbcOperationalRiskSignalProvider provider = provider(
                new OperationalRiskThresholds(72, 3, 30, 48), guarded);

        List<RiskSignal> signals = provider.evaluate(RiskScanScope.restricted(
                9L, Set.of("repair:read"), Set.of(allowed), Set.of(), Set.of(), NOW));

        assertEquals(1, signals.size());
        assertEquals(allowed, signals.getFirst().subjectResourceId());
    }

    @Test
    void neverMapsUnauthorizedDormitoryRowsBeforeConsistencyAggregation() {
        long allowed = insertDormitory("授权楼栋", "101宿舍", 2, 2, 0);
        long forbidden = insertDormitory("未授权楼栋", "201宿舍", 2, 2, 0);
        JdbcTemplate guarded = new GuardedJdbcTemplate(
                jdbcTemplate.getDataSource(), Map.of("dormitory", Set.of(forbidden)));
        JdbcOperationalRiskSignalProvider provider = provider(
                new OperationalRiskThresholds(72, 3, 30, 48), guarded);
        RiskScanScope scope = new RiskScanScope(9L, Set.of("dormitory:read", "checkin:read"),
                false, Set.of(), false, Set.of(allowed), true, Set.of(), NOW);

        List<RiskSignal> signals = provider.evaluate(scope);

        assertEquals(1, signals.size());
        assertEquals("resource-checkin-inconsistency", signals.getFirst().riskType());
        assertEquals(allowed, signals.getFirst().subjectResourceId());
    }

    @Test
    void neverMapsUnauthorizedCheckInRowsBeforePendingRuleEvaluation() {
        long allowed = insertPendingApplication("20260000011", "授权记录", NOW.minusSeconds(72 * 3600));
        long forbidden = insertPendingApplication("20260000012", "未授权记录", NOW.minusSeconds(72 * 3600));
        JdbcTemplate guarded = new GuardedJdbcTemplate(
                jdbcTemplate.getDataSource(), Map.of("check_in_application", Set.of(forbidden)));
        JdbcOperationalRiskSignalProvider provider = provider(
                new OperationalRiskThresholds(72, 3, 30, 48), guarded);

        List<RiskSignal> signals = provider.evaluate(RiskScanScope.restricted(
                9L, Set.of("checkin:read"), Set.of(), Set.of(), Set.of(allowed), NOW));

        assertEquals(1, signals.size());
        assertEquals("long-pending-operation", signals.getFirst().riskType());
        assertEquals(allowed, signals.getFirst().subjectResourceId());
    }

    @Test
    void neverMapsUnauthorizedHygieneOrPaymentRowsBeforeRuleEvaluation() {
        long allowedDormitory = insertDormitory("授权楼栋", "301宿舍", 1, 0, 1);
        long forbiddenDormitory = insertDormitory("未授权楼栋", "401宿舍", 1, 0, 1);
        insertFailedHygieneCheck("授权楼栋", "301宿舍", "授权检查员", 59, "2026-07-10");
        insertFailedHygieneCheck("未授权楼栋", "401宿舍", "未授权检查员", 58, "2026-07-10");
        long allowedPayment = insertOverduePayment("20260000101", "授权缴费记录", "2026-07-10");
        long forbiddenPayment = insertOverduePayment("20260000102", "未授权缴费记录", "2026-07-10");
        JdbcTemplate guarded = new GuardedJdbcTemplate(jdbcTemplate.getDataSource(), Map.of(
                "hygiene_check", Set.of(forbiddenDormitory),
                "payment", Set.of(forbiddenPayment)));
        JdbcOperationalRiskSignalProvider provider = provider(
                new OperationalRiskThresholds(72, 3, 30, 48), guarded);

        List<RiskSignal> signals = provider.evaluate(RiskScanScope.restricted(
                9L, Set.of("dormitory:read", "hygiene:read", "payment:read"),
                Set.of(), Set.of(allowedDormitory), Set.of(allowedPayment), Set.of(), NOW));

        assertEquals(Set.of("failed-hygiene-check", "overdue-payment"),
                signals.stream().map(RiskSignal::riskType).collect(java.util.stream.Collectors.toSet()));
        assertEquals(allowedDormitory, signals.stream()
                .filter(signal -> "failed-hygiene-check".equals(signal.riskType()))
                .findFirst().orElseThrow().subjectResourceId());
        assertEquals(allowedPayment, signals.stream()
                .filter(signal -> "overdue-payment".equals(signal.riskType()))
                .findFirst().orElseThrow().subjectResourceId());
    }

    private JdbcOperationalRiskSignalProvider provider(OperationalRiskThresholds thresholds) {
        return provider(thresholds, jdbcTemplate);
    }

    private JdbcOperationalRiskSignalProvider provider(
            OperationalRiskThresholds thresholds,
            JdbcTemplate facts) {
        return provider(thresholds, new JdbcOperationalRiskReadAdapter(facts));
    }

    private JdbcOperationalRiskSignalProvider provider(
            OperationalRiskThresholds thresholds,
            JdbcOperationalRiskReadAdapter facts) {
        return new JdbcOperationalRiskSignalProvider(facts, thresholds,
                new RiskSubjectTokenizer("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8), 7),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private long insertDormitory(String building, String name, int beds, int occupied, int vacant) {
        jdbcTemplate.update("INSERT INTO dormitory "
                        + "(name, type, building, beds, occupied, vacant, status, deleted, created_at, updated_at) "
                        + "VALUES (?, '男生宿舍', ?, ?, ?, ?, '入住中', FALSE, ?, ?)",
                name, building, beds, occupied, vacant, NOW, NOW);
        return jdbcTemplate.queryForObject("SELECT id FROM dormitory WHERE building = ? AND name = ?",
                Long.class, building, name);
    }

    private long insertRepair(String code, String reporter, String location, String type, String status, Instant createdAt) {
        jdbcTemplate.update("INSERT INTO repair_order "
                        + "(code, reporter, location, type, date, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, '2026-07-01', ?, ?, ?)",
                code, reporter, location, type, status, createdAt, createdAt);
        return jdbcTemplate.queryForObject("SELECT id FROM repair_order WHERE code=?", Long.class, code);
    }

    private long insertPendingApplication(String studentNo, String name, Instant createdAt) {
        jdbcTemplate.update("INSERT INTO check_in_application "
                        + "(student_no, name, dormitory, date, status, applied_at, created_at, updated_at) "
                        + "VALUES (?, ?, '敏感楼栋-101宿舍', '2026-07-01', '待审核', ?, ?, ?)",
                studentNo, name, createdAt, createdAt, createdAt);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM check_in_application WHERE student_no=?", Long.class, studentNo);
    }

    private void insertFailedHygieneCheck(
            String building,
            String dormitory,
            String inspector,
            int score,
            String date) {
        jdbcTemplate.update("INSERT INTO hygiene_check "
                        + "(dormitory, building, date, inspector, score, result, remark, deleted, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, '不合格', '敏感原始备注', FALSE, ?, ?)",
                dormitory, building, date, inspector, score, NOW, NOW);
    }

    private long insertOverduePayment(String studentNo, String name, String deadline) {
        jdbcTemplate.update("INSERT INTO payment "
                        + "(student_no, name, type, amount_due, amount_paid, status, deadline, created_at, updated_at) "
                        + "VALUES (?, ?, '住宿费', 1200.00, 200.00, '部分缴', ?, ?, ?)",
                studentNo, name, deadline, NOW, NOW);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM payment WHERE student_no=?", Long.class, studentNo);
    }

    private static final class GuardedJdbcTemplate extends JdbcTemplate {
        private final Map<String, Set<Long>> forbiddenIds;

        private GuardedJdbcTemplate(DataSource dataSource, Map<String, Set<Long>> forbiddenIds) {
            super(dataSource);
            this.forbiddenIds = Map.copyOf(forbiddenIds);
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            return super.query(sql, (resultSet, rowNum) -> {
                long id = resultSet.getLong("id");
                String table = table(sql);
                if (forbiddenIds.getOrDefault(table, Set.of()).contains(id)) {
                    throw new AssertionError("未授权业务行进入 AI RowMapper: " + id);
                }
                return rowMapper.mapRow(resultSet, rowNum);
            }, args);
        }

        private String table(String sql) {
            if (sql.contains("hygiene_check")) return "hygiene_check";
            if (sql.contains("payment")) return "payment";
            if (sql.contains("repair_order")) return "repair_order";
            if (sql.contains("check_in_application")) return "check_in_application";
            if (sql.contains("dormitory")) return "dormitory";
            throw new AssertionError("风险 adapter 出现未登记 SQL");
        }
    }
}
