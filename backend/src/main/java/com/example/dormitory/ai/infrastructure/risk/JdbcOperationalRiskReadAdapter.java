package com.example.dormitory.ai.infrastructure.risk;

import com.example.dormitory.ai.port.OperationalRiskReadPort;
import com.example.dormitory.ai.risk.RiskScanScope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 只执行固定 SQL。受限 scope 使用固定的主键等值查询，保证范围外行不会进入 RowMapper；
 * 全量查询只在对应业务权限和 all-scope 同时成立时启用。
 */
@Component
@ConditionalOnProperty(prefix = "dormitory.ai.capabilities", name = "risk", havingValue = "true")
public final class JdbcOperationalRiskReadAdapter implements OperationalRiskReadPort {

    private static final String INCOMPLETE_REPAIRS =
            "SELECT id,status,created_at FROM repair_order WHERE status<>? ORDER BY id";
    private static final String INCOMPLETE_REPAIR_BY_ID =
            "SELECT id,status,created_at FROM repair_order WHERE id=? AND status<>?";
    private static final String REPAIRS_IN_WINDOW =
            "SELECT id,location,type,created_at FROM repair_order "
                    + "WHERE created_at>=? AND created_at<=? ORDER BY created_at,id";
    private static final String REPAIR_IN_WINDOW_BY_ID =
            "SELECT id,location,type,created_at FROM repair_order "
                    + "WHERE id=? AND created_at>=? AND created_at<=?";
    private static final String DORMITORY_CONSISTENCY_SELECT =
            "SELECT d.id,d.beds,d.occupied,d.vacant,COUNT(b.id) AS actual_beds,"
                    + "SUM(CASE WHEN b.status='已占用' THEN 1 ELSE 0 END) AS occupied_beds,"
                    + "SUM(CASE WHEN cir.status='在住' THEN 1 ELSE 0 END) AS active_checkins "
                    + "FROM dormitory d LEFT JOIN bed b ON b.dormitory_id=d.id "
                    + "LEFT JOIN check_in_record cir ON cir.active_bed_id=b.id ";
    private static final String ALL_DORMITORY_CONSISTENCY = DORMITORY_CONSISTENCY_SELECT
            + "WHERE d.deleted=FALSE GROUP BY d.id,d.beds,d.occupied,d.vacant ORDER BY d.id";
    private static final String DORMITORY_CONSISTENCY_BY_ID = DORMITORY_CONSISTENCY_SELECT
            + "WHERE d.id=? AND d.deleted=FALSE GROUP BY d.id,d.beds,d.occupied,d.vacant";
    private static final String PENDING_CHECK_INS =
            "SELECT id,status,applied_at FROM check_in_application WHERE status=? ORDER BY id";
    private static final String PENDING_CHECK_IN_BY_ID =
            "SELECT id,status,applied_at FROM check_in_application WHERE id=? AND status=?";
    private static final String FAILED_HYGIENE =
            "SELECT d.id,h.result,h.score,h.date inspected_on FROM hygiene_check h "
                    + "JOIN dormitory d ON d.building=h.building AND d.name=h.dormitory "
                    + "WHERE h.deleted=FALSE AND d.deleted=FALSE AND h.result=? ORDER BY d.id,h.date,h.id";
    private static final String FAILED_HYGIENE_BY_DORMITORY =
            "SELECT d.id,h.result,h.score,h.date inspected_on FROM hygiene_check h "
                    + "JOIN dormitory d ON d.building=h.building AND d.name=h.dormitory "
                    + "WHERE d.id=? AND h.deleted=FALSE AND d.deleted=FALSE AND h.result=? ORDER BY h.date,h.id";
    private static final String OPEN_PAYMENTS =
            "SELECT id,status,deadline,amount_due,amount_paid FROM payment "
                    + "WHERE status IN (?,?) ORDER BY id";
    private static final String OPEN_PAYMENT_BY_ID =
            "SELECT id,status,deadline,amount_due,amount_paid FROM payment "
                    + "WHERE id=? AND status IN (?,?)";

    private static final RowMapper<RepairBacklogFact> BACKLOG_MAPPER = (resultSet, rowNum) ->
            new RepairBacklogFact(resultSet.getLong("id"), resultSet.getString("status"),
                    resultSet.getTimestamp("created_at").toInstant());
    private static final RowMapper<RepeatRepairFact> REPEAT_MAPPER = (resultSet, rowNum) ->
            new RepeatRepairFact(resultSet.getLong("id"), resultSet.getString("location"),
                    resultSet.getString("type"), resultSet.getTimestamp("created_at").toInstant());
    private static final RowMapper<DormitoryConsistencyFact> CONSISTENCY_MAPPER = (resultSet, rowNum) ->
            new DormitoryConsistencyFact(resultSet.getLong("id"), resultSet.getInt("beds"),
                    resultSet.getInt("occupied"), resultSet.getInt("vacant"), resultSet.getInt("actual_beds"),
                    resultSet.getInt("occupied_beds"), resultSet.getInt("active_checkins"));
    private static final RowMapper<PendingCheckInFact> CHECK_IN_MAPPER = (resultSet, rowNum) ->
            new PendingCheckInFact(resultSet.getLong("id"), resultSet.getString("status"),
                    resultSet.getTimestamp("applied_at").toInstant());
    private static final RowMapper<FailedHygieneFact> HYGIENE_MAPPER = (resultSet, rowNum) ->
            new FailedHygieneFact(resultSet.getLong("id"), resultSet.getString("result"),
                    resultSet.getInt("score"), resultSet.getString("inspected_on"));
    private static final RowMapper<OverduePaymentFact> PAYMENT_MAPPER = (resultSet, rowNum) ->
            new OverduePaymentFact(resultSet.getLong("id"), resultSet.getString("status"),
                    resultSet.getString("deadline"), resultSet.getBigDecimal("amount_due"),
                    resultSet.getBigDecimal("amount_paid"));

    private final JdbcTemplate jdbc;

    public JdbcOperationalRiskReadAdapter(JdbcTemplate jdbc) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc);
    }

    @Override
    public List<RepairBacklogFact> incompleteRepairs(RiskScanScope scope) {
        requireScope(scope);
        if (!scope.permissionCodes().contains("repair:read")) return List.of();
        if (scope.allRepairOrders()) return jdbc.query(INCOMPLETE_REPAIRS, BACKLOG_MAPPER, "已完成");
        return byIds(scope.repairOrderIds(), id -> jdbc.query(
                INCOMPLETE_REPAIR_BY_ID, BACKLOG_MAPPER, id, "已完成"));
    }

    @Override
    public List<RepeatRepairFact> repairsCreatedBetween(RiskScanScope scope, Instant from, Instant to) {
        requireScope(scope);
        if (from == null || to == null || from.isAfter(to)) {
            throw new IllegalArgumentException("重复报修时间窗口不合法");
        }
        if (!scope.permissionCodes().contains("repair:read")) return List.of();
        Timestamp start = Timestamp.from(from);
        Timestamp end = Timestamp.from(to);
        if (scope.allRepairOrders()) return jdbc.query(REPAIRS_IN_WINDOW, REPEAT_MAPPER, start, end);
        return byIds(scope.repairOrderIds(), id -> jdbc.query(
                REPAIR_IN_WINDOW_BY_ID, REPEAT_MAPPER, id, start, end));
    }

    @Override
    public List<DormitoryConsistencyFact> dormitoryConsistency(RiskScanScope scope) {
        requireScope(scope);
        if (!scope.permissionCodes().containsAll(Set.of("dormitory:read", "checkin:read"))
                || !scope.allCheckInApplications()) {
            return List.of();
        }
        if (scope.allDormitories()) return jdbc.query(ALL_DORMITORY_CONSISTENCY, CONSISTENCY_MAPPER);
        return byIds(scope.dormitoryIds(), id -> jdbc.query(
                DORMITORY_CONSISTENCY_BY_ID, CONSISTENCY_MAPPER, id));
    }

    @Override
    public List<FailedHygieneFact> failedHygieneChecks(RiskScanScope scope) {
        requireScope(scope);
        if (!scope.permissionCodes().containsAll(Set.of("dormitory:read", "hygiene:read"))) {
            return List.of();
        }
        if (scope.allDormitories()) return jdbc.query(FAILED_HYGIENE, HYGIENE_MAPPER, "不合格");
        return byIds(scope.dormitoryIds(), id -> jdbc.query(
                FAILED_HYGIENE_BY_DORMITORY, HYGIENE_MAPPER, id, "不合格"));
    }

    @Override
    public List<PendingCheckInFact> pendingCheckInApplications(RiskScanScope scope) {
        requireScope(scope);
        if (!scope.permissionCodes().contains("checkin:read")) return List.of();
        if (scope.allCheckInApplications()) return jdbc.query(PENDING_CHECK_INS, CHECK_IN_MAPPER, "待审核");
        return byIds(scope.checkInApplicationIds(), id -> jdbc.query(
                PENDING_CHECK_IN_BY_ID, CHECK_IN_MAPPER, id, "待审核"));
    }

    @Override
    public List<OverduePaymentFact> overduePayments(RiskScanScope scope) {
        requireScope(scope);
        if (!scope.permissionCodes().contains("payment:read")) return List.of();
        List<OverduePaymentFact> rows = scope.allPayments()
                ? jdbc.query(OPEN_PAYMENTS, PAYMENT_MAPPER, "未缴", "部分缴")
                : byIds(scope.paymentIds(), id -> jdbc.query(
                OPEN_PAYMENT_BY_ID, PAYMENT_MAPPER, id, "未缴", "部分缴"));
        LocalDate today = LocalDate.ofInstant(scope.capturedAt(), ZoneOffset.UTC);
        return rows.stream().filter(fact -> isOverdue(fact.deadline(), today)).toList();
    }

    private <T> List<T> byIds(Set<Long> ids, java.util.function.LongFunction<List<T>> query) {
        if (ids.isEmpty()) return List.of();
        List<T> facts = new ArrayList<>();
        ids.stream().sorted().forEach(id -> facts.addAll(query.apply(id)));
        return List.copyOf(facts);
    }

    private void requireScope(RiskScanScope scope) {
        if (scope == null) throw new IllegalArgumentException("风险业务读取必须携带 actor scope");
    }

    private boolean isOverdue(String deadline, LocalDate today) {
        if (deadline == null || deadline.isBlank()) return false;
        try {
            return LocalDate.parse(deadline).isBefore(today);
        } catch (DateTimeParseException invalid) {
            return false;
        }
    }
}
