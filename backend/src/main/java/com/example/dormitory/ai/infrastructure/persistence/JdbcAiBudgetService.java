package com.example.dormitory.ai.infrastructure.persistence;

import com.example.dormitory.ai.application.control.BillingSubject;
import com.example.dormitory.ai.application.control.BudgetExceededException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class JdbcAiBudgetService {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public JdbcAiBudgetService(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public List<Long> reserve(
            BillingSubject subject,
            List<Long> bucketIds,
            long requestedTokens,
            BigDecimal requestedCost,
            Instant expiresAt) {
        List<Long> stableBucketIds = validateReservation(
                subject, bucketIds, requestedTokens, requestedCost, expiresAt);
        return transactionTemplate.execute(status -> {
            List<Long> reservationIds = new ArrayList<>();
            for (Long bucketId : stableBucketIds) {
                int updated = jdbcTemplate.update(
                        "UPDATE ai_budget_bucket SET "
                                + "reserved_tokens = reserved_tokens + ?, "
                                + "reserved_cost = reserved_cost + ?, version = version + 1, "
                                + "updated_at = CURRENT_TIMESTAMP "
                                + "WHERE id = ? "
                                + "AND reserved_tokens + committed_tokens + ? <= token_limit "
                                + "AND reserved_cost + committed_cost + ? <= cost_limit",
                        requestedTokens,
                        requestedCost,
                        bucketId,
                        requestedTokens,
                        requestedCost);
                if (updated != 1) throw new BudgetExceededException();

                String publicId = UUID.randomUUID().toString();
                jdbcTemplate.update(
                        "INSERT INTO ai_budget_reservation "
                                + "(public_id, billing_subject_kind, billing_subject_public_id, budget_bucket_id, "
                                + "reserved_tokens, reserved_cost, committed_tokens, committed_cost, state, "
                                + "expires_at, version, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, 0, 0, 'RESERVED', ?, 0, "
                                + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                        publicId,
                        subject.kind().name(),
                        subject.publicId(),
                        bucketId,
                        requestedTokens,
                        requestedCost,
                        // TIMESTAMP(6) may round nanoseconds up; truncation must never extend provider budget validity.
                        Timestamp.from(expiresAt.truncatedTo(ChronoUnit.MICROS)));
                Long reservationId = jdbcTemplate.queryForObject(
                        "SELECT id FROM ai_budget_reservation WHERE public_id = ?",
                        Long.class,
                        publicId);
                reservationIds.add(reservationId);
            }
            return List.copyOf(reservationIds);
        });
    }

    public void commit(BillingSubject subject, long committedTokens, BigDecimal committedCost) {
        if (!commitIfPresent(subject, committedTokens, committedCost)) {
            throw new IllegalStateException("没有可提交的预算预留");
        }
    }

    /**
     * 对恢复重放提供幂等提交：新提交或已经以相同事实 COMMITTED 都返回 true；不存在可核对的
     * reservation 时返回 false。reservation 行锁必须先于未决 attempt 检查，从而与 provider
     * 调用登记串行化，避免在重试间隙先结算、随后又发起未预留的物理请求。
     */
    public boolean commitIfPresent(BillingSubject subject, long committedTokens, BigDecimal committedCost) {
        if (subject == null || committedTokens < 0 || committedCost == null || committedCost.signum() < 0) {
            throw new IllegalArgumentException("预算提交参数不合法");
        }
        Boolean committed = transactionTemplate.execute(status -> {
            List<ReservationRow> allRows = lockSubjectReservations(subject);
            if (allRows.isEmpty()) return false;
            List<ReservationRow> rows = allRows.stream()
                    .filter(row -> "RESERVED".equals(row.state())).toList();
            if (rows.isEmpty()) return verifyExistingCommit(allRows, committedTokens, committedCost);
            if (rows.size() != allRows.size()) {
                throw new IllegalStateException("同一计费主体的预算 reservation 状态不一致");
            }
            if (needsAttemptReconciliation(subject)) throw new AttemptReconciliationRequiredException();
            for (ReservationRow row : rows) {
                if (committedTokens > row.reservedTokens()
                        || committedCost.compareTo(row.reservedCost()) > 0) {
                    throw new BudgetExceededException();
                }
                int bucketUpdated = jdbcTemplate.update(
                        "UPDATE ai_budget_bucket SET "
                                + "reserved_tokens = reserved_tokens - ?, "
                                + "reserved_cost = reserved_cost - ?, "
                                + "committed_tokens = committed_tokens + ?, "
                                + "committed_cost = committed_cost + ?, "
                                + "version = version + 1, updated_at = CURRENT_TIMESTAMP "
                                + "WHERE id = ? AND reserved_tokens >= ? AND reserved_cost >= ?",
                        row.reservedTokens(),
                        row.reservedCost(),
                        committedTokens,
                        committedCost,
                        row.bucketId(),
                        row.reservedTokens(),
                        row.reservedCost());
                if (bucketUpdated != 1) throw new IllegalStateException("预算 bucket 对账失败");
                int reservationUpdated = jdbcTemplate.update(
                        "UPDATE ai_budget_reservation SET state = 'COMMITTED', "
                                + "committed_tokens = ?, committed_cost = ?, version = version + 1, "
                                + "updated_at = CURRENT_TIMESTAMP WHERE id = ? AND state = 'RESERVED'",
                        committedTokens,
                        committedCost,
                        row.id());
                if (reservationUpdated != 1) throw new IllegalStateException("预算 reservation 对账失败");
            }
            return true;
        });
        return Boolean.TRUE.equals(committed);
    }

    public void release(BillingSubject subject) {
        if (subject == null) throw new IllegalArgumentException("计费主体不能为空");
        transactionTemplate.executeWithoutResult(status -> {
            List<ReservationRow> allRows = lockSubjectReservations(subject);
            List<ReservationRow> rows = allRows.stream()
                    .filter(row -> "RESERVED".equals(row.state())).toList();
            if (rows.isEmpty()) throw new IllegalStateException("没有可释放的预算预留");
            if (rows.size() != allRows.size()) {
                throw new IllegalStateException("同一计费主体的预算 reservation 状态不一致");
            }
            if (needsAttemptReconciliation(subject)) throw new AttemptReconciliationRequiredException();
            releaseRows(rows);
        });
    }

    /**
     * 为确定性、零成本 run 提供幂等终态清理：这类 run 没有 provider 预算预留，失败或取消时不应
     * 因“无 reservation”覆盖原始错误，也不能阻止持久化终态 SSE 事件。
     */
    public boolean releaseIfPresent(BillingSubject subject) {
        if (subject == null) throw new IllegalArgumentException("计费主体不能为空");
        Boolean released = transactionTemplate.execute(status -> {
            List<ReservationRow> allRows = lockSubjectReservations(subject);
            List<ReservationRow> rows = allRows.stream()
                    .filter(row -> "RESERVED".equals(row.state())).toList();
            if (rows.isEmpty()) return false;
            if (rows.size() != allRows.size()) {
                throw new IllegalStateException("同一计费主体的预算 reservation 状态不一致");
            }
            if (needsAttemptReconciliation(subject)) return false;
            releaseRows(rows);
            return true;
        });
        return Boolean.TRUE.equals(released);
    }

    /**
     * provider supplier 执行前的最后一道硬预算门。与 commit/release 使用相同 reservation 行锁；
     * 已结算、已释放、已过期或根本不存在的 reservation 均不得产生真实 provider 调用。
     */
    public void requireProviderCallReservation(BillingSubject subject, Instant dispatchAt) {
        if (subject == null || dispatchAt == null) {
            throw new IllegalArgumentException("provider 预算门参数不合法");
        }
        transactionTemplate.executeWithoutResult(status -> {
            List<ReservationRow> rows = lockSubjectReservations(subject);
            if (rows.isEmpty()
                    || rows.stream().anyMatch(row -> !"RESERVED".equals(row.state()))
                    || rows.stream().anyMatch(row -> !row.expiresAt().isAfter(dispatchAt))) {
                throw new ProviderReservationUnavailableException();
            }
        });
    }

    /**
     * 回收已过期且从未产生 provider usage 的孤立预留。候选扫描不承担正确性；每个 subject
     * 都在独立事务中重新锁定全部 reservation，再与 provider attempt/usage 事实核对。
     * 返回实际迁移到 EXPIRED 的 reservation 行数。
     */
    public int expireDueReservations(Instant observedAt, int batchSize) {
        if (observedAt == null || batchSize < 1 || batchSize > 1_000) {
            throw new IllegalArgumentException("预算过期扫描参数不合法");
        }
        List<BillingSubject> candidates = jdbcTemplate.query(
                "SELECT billing_subject_kind,billing_subject_public_id "
                        + "FROM ai_budget_reservation WHERE state='RESERVED' AND expires_at<=? "
                        + "GROUP BY billing_subject_kind,billing_subject_public_id "
                        + "ORDER BY MIN(expires_at),MIN(id) LIMIT ?",
                (rs, row) -> new BillingSubject(
                        BillingSubject.Kind.valueOf(rs.getString("billing_subject_kind")),
                        rs.getString("billing_subject_public_id")),
                Timestamp.from(observedAt), batchSize);
        int expired = 0;
        for (BillingSubject subject : candidates) {
            Integer changed = transactionTemplate.execute(status -> expireSubject(subject, observedAt));
            expired = Math.addExact(expired, changed == null ? 0 : changed);
        }
        return expired;
    }

    public boolean needsAttemptReconciliation(BillingSubject subject) {
        if (subject == null) throw new IllegalArgumentException("计费主体不能为空");
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_provider_attempt WHERE billing_subject_kind=? "
                        + "AND billing_subject_public_id=? "
                        + "AND state IN ('STARTED','NEEDS_RECONCILIATION')",
                Integer.class, subject.kind().name(), subject.publicId());
        return count != null && count > 0;
    }

    private int expireSubject(BillingSubject subject, Instant observedAt) {
        List<ReservationRow> allRows = lockSubjectReservations(subject);
        List<ReservationRow> reserved = allRows.stream()
                .filter(row -> "RESERVED".equals(row.state())).toList();
        if (reserved.isEmpty()) return 0;
        if (reserved.size() != allRows.size()) {
            throw new IllegalStateException("同一计费主体的预算 reservation 状态不一致");
        }
        if (reserved.stream().anyMatch(row -> row.expiresAt().isAfter(observedAt))) return 0;
        if (needsAttemptReconciliation(subject) || hasProviderUsageFact(subject)) return 0;

        for (ReservationRow row : reserved) {
            int bucketUpdated = jdbcTemplate.update(
                    "UPDATE ai_budget_bucket SET reserved_tokens=reserved_tokens-?,"
                            + "reserved_cost=reserved_cost-?,version=version+1,updated_at=CURRENT_TIMESTAMP "
                            + "WHERE id=? AND reserved_tokens>=? AND reserved_cost>=?",
                    row.reservedTokens(), row.reservedCost(), row.bucketId(),
                    row.reservedTokens(), row.reservedCost());
            if (bucketUpdated != 1) throw new IllegalStateException("过期预算 bucket 归还失败");
            int reservationUpdated = jdbcTemplate.update(
                    "UPDATE ai_budget_reservation SET state='EXPIRED',version=version+1,"
                            + "updated_at=CURRENT_TIMESTAMP WHERE id=? AND state='RESERVED' AND version=?",
                    row.id(), row.version());
            if (reservationUpdated != 1) throw new IllegalStateException("预算 reservation 过期 CAS 失败");
        }
        return reserved.size();
    }

    private boolean hasProviderUsageFact(BillingSubject subject) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_usage_ledger WHERE billing_subject_kind=? "
                        + "AND billing_subject_public_id=?",
                Integer.class, subject.kind().name(), subject.publicId());
        return count != null && count > 0;
    }

    private void releaseRows(List<ReservationRow> rows) {
        for (ReservationRow row : rows) {
            int bucketUpdated = jdbcTemplate.update(
                    "UPDATE ai_budget_bucket SET "
                            + "reserved_tokens = reserved_tokens - ?, "
                            + "reserved_cost = reserved_cost - ?, "
                            + "version = version + 1, updated_at = CURRENT_TIMESTAMP "
                            + "WHERE id = ? AND reserved_tokens >= ? AND reserved_cost >= ?",
                    row.reservedTokens(),
                    row.reservedCost(),
                    row.bucketId(),
                    row.reservedTokens(),
                    row.reservedCost());
            if (bucketUpdated != 1) throw new IllegalStateException("预算 bucket 释放失败");
            int reservationUpdated = jdbcTemplate.update(
                    "UPDATE ai_budget_reservation SET state = 'RELEASED', version = version + 1, "
                            + "updated_at = CURRENT_TIMESTAMP WHERE id = ? AND state = 'RESERVED'",
                    row.id());
            if (reservationUpdated != 1) throw new IllegalStateException("预算 reservation 释放失败");
        }
    }

    private List<ReservationRow> lockSubjectReservations(BillingSubject subject) {
        return jdbcTemplate.query(
                "SELECT id,budget_bucket_id,reserved_tokens,reserved_cost,committed_tokens,committed_cost,"
                        + "state,expires_at,version "
                        + "FROM ai_budget_reservation WHERE billing_subject_kind = ? "
                        + "AND billing_subject_public_id = ? "
                        + "ORDER BY budget_bucket_id FOR UPDATE",
                (resultSet, rowNum) -> new ReservationRow(
                        resultSet.getLong("id"),
                        resultSet.getLong("budget_bucket_id"),
                        resultSet.getLong("reserved_tokens"),
                        resultSet.getBigDecimal("reserved_cost"),
                        resultSet.getLong("committed_tokens"),
                        resultSet.getBigDecimal("committed_cost"),
                        resultSet.getString("state"),
                        resultSet.getTimestamp("expires_at").toInstant(),
                        resultSet.getLong("version")),
                subject.kind().name(),
                subject.publicId());
    }

    private boolean verifyExistingCommit(
            List<ReservationRow> rows,
            long committedTokens,
            BigDecimal committedCost) {
        if (rows.stream().anyMatch(row -> !"COMMITTED".equals(row.state()))) return false;
        if (rows.stream().anyMatch(row -> row.committedTokens() != committedTokens
                || row.committedCost().compareTo(committedCost) != 0)) {
            throw new IllegalStateException("预算已用不同 usage 结果完成对账");
        }
        return true;
    }

    private List<Long> validateReservation(
            BillingSubject subject,
            List<Long> bucketIds,
            long tokens,
            BigDecimal cost,
            Instant expiresAt) {
        if (subject == null || bucketIds == null || bucketIds.isEmpty()
                || bucketIds.stream().anyMatch(id -> id == null || id < 1)
                || tokens < 1 || cost == null || cost.signum() < 0 || expiresAt == null) {
            throw new IllegalArgumentException("预算预留参数不合法");
        }
        List<Long> stable = bucketIds.stream().distinct().sorted().toList();
        if (stable.size() != bucketIds.size()) {
            throw new IllegalArgumentException("同一预算 bucket 不能重复预留");
        }
        return stable;
    }

    private record ReservationRow(
            long id,
            long bucketId,
            long reservedTokens,
            BigDecimal reservedCost,
            long committedTokens,
            BigDecimal committedCost,
            String state,
            Instant expiresAt,
            long version) {
    }

    public static final class AttemptReconciliationRequiredException extends IllegalStateException {
        public AttemptReconciliationRequiredException() {
            super("存在 NEEDS_RECONCILIATION provider attempt，预算保持预留");
        }
    }

    public static final class ProviderReservationUnavailableException extends IllegalStateException {
        public ProviderReservationUnavailableException() {
            super("provider 调用缺少有效 RESERVED 预算预留");
        }
    }
}
