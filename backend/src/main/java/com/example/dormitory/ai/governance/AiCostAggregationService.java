package com.example.dormitory.ai.governance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class AiCostAggregationService {

    private final JdbcTemplate jdbcTemplate;

    public AiCostAggregationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public CostAggregation aggregate(Instant from, Instant to, GroupBy groupBy) {
        Instant effectiveTo = to == null ? Instant.now() : to;
        Instant effectiveFrom = from == null ? effectiveTo.minus(Duration.ofDays(30)) : from;
        GroupBy effectiveGroup = groupBy == null ? GroupBy.CAPABILITY : groupBy;
        if (!effectiveFrom.isBefore(effectiveTo)
                || Duration.between(effectiveFrom, effectiveTo).compareTo(Duration.ofDays(366)) > 0) {
            throw new IllegalArgumentException("成本聚合时间范围不合法或超过 366 天");
        }
        String expression = switch (effectiveGroup) {
            case CAPABILITY -> "capability";
            case PROVIDER -> "provider_code";
            case MODEL -> "model_name";
            case SUBJECT_KIND -> "billing_subject_kind";
            case DAY -> "CAST(occurred_at AS DATE)";
        };
        String sql = "SELECT " + expression + " AS dimension_value,currency,COUNT(*) AS attempt_count,"
                + "COALESCE(SUM(input_tokens),0) AS input_tokens,"
                + "COALESCE(SUM(output_tokens),0) AS output_tokens,"
                + "COALESCE(SUM(cost_amount),0) AS cost_amount "
                + "FROM ai_usage_ledger WHERE occurred_at>=? AND occurred_at<? "
                + "GROUP BY " + expression + ",currency ORDER BY dimension_value,currency";
        List<CostRow> rows = jdbcTemplate.query(sql,
                (rs, row) -> new CostRow(rs.getString("dimension_value"), rs.getString("currency"),
                        rs.getLong("attempt_count"), rs.getLong("input_tokens"),
                        rs.getLong("output_tokens"), rs.getBigDecimal("cost_amount")),
                Timestamp.from(effectiveFrom), Timestamp.from(effectiveTo));
        return new CostAggregation(effectiveGroup, effectiveFrom, effectiveTo, rows);
    }

    public enum GroupBy {
        CAPABILITY,
        PROVIDER,
        MODEL,
        SUBJECT_KIND,
        DAY
    }

    public record CostAggregation(GroupBy groupBy, Instant from, Instant to, List<CostRow> rows) {
        public CostAggregation {
            rows = List.copyOf(rows);
        }
    }

    public record CostRow(
            String dimension,
            String currency,
            long attemptCount,
            long inputTokens,
            long outputTokens,
            BigDecimal costAmount) {
    }
}
