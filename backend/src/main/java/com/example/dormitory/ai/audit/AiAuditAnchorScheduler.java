package com.example.dormitory.ai.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** 每日锚定前一自然日已稳定的链头；单个 scope 失败不会伪造 receipt。 */
public final class AiAuditAnchorScheduler {

    private static final Logger log = LoggerFactory.getLogger(AiAuditAnchorScheduler.class);
    private final JdbcTemplate jdbcTemplate;
    private final JdbcAiAuditAnchorService service;
    private final Clock clock;

    public AiAuditAnchorScheduler(
            JdbcTemplate jdbcTemplate,
            JdbcAiAuditAnchorService service,
            Clock clock) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
        this.service = Objects.requireNonNull(service);
        this.clock = Objects.requireNonNull(clock);
    }

    @Scheduled(
            cron = "${dormitory.ai.audit.anchor.cron:0 5 0 * * *}",
            zone = "${dormitory.ai.audit.anchor.zone:Asia/Shanghai}")
    public void anchorPreviousDay() {
        LocalDate date = LocalDate.now(clock).minusDays(1);
        for (String scope : chainScopes()) {
            try {
                service.anchor(date, scope);
            } catch (RuntimeException exception) {
                log.error("AI audit anchor failed for date={} scope={}", date, scope,
                        exception);
            }
        }
    }

    private List<String> chainScopes() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT chain_scope FROM ai_audit_chain_head "
                        + "WHERE last_sequence_no > 0 ORDER BY chain_scope",
                String.class);
    }
}
