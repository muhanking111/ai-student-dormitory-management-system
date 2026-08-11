package com.example.dormitory.ai.audit;

import com.example.dormitory.ai.infrastructure.runtime.AiRuntimeAuditWriter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@Configuration
public class AiAuditAnchorConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "dormitory.ai.audit.anchor", name = "sink", havingValue = "fake")
    DeterministicFakeAuditAnchorSink deterministicFakeAuditAnchorSink() {
        return new DeterministicFakeAuditAnchorSink();
    }

    @Bean
    @ConditionalOnProperty(prefix = "dormitory.ai.audit.anchor", name = "sink", havingValue = "https-hmac")
    HttpsHmacAuditAnchorSink httpsHmacAuditAnchorSink(Environment environment) {
        return new HttpsHmacAuditAnchorSink(
                URI.create(environment.getRequiredProperty("dormitory.ai.audit.anchor.endpoint")),
                environment.getRequiredProperty("dormitory.ai.audit.anchor.hmac-key")
                        .getBytes(StandardCharsets.UTF_8),
                Duration.parse(environment.getProperty(
                        "dormitory.ai.audit.anchor.timeout", "PT5S")),
                new JdkAuditAnchorHttpTransport());
    }

    @Bean
    @ConditionalOnBean(AuditAnchorSink.class)
    @ConditionalOnProperty(prefix = "dormitory.ai.audit.anchor", name = "enabled", havingValue = "true")
    JdbcAiAuditAnchorService jdbcAiAuditAnchorService(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            AuditAnchorSink sink,
            Environment environment,
            AiRuntimeAuditWriter auditWriter) {
        String zone = environment.getProperty("dormitory.ai.audit.anchor.zone", "Asia/Shanghai");
        return new JdbcAiAuditAnchorService(
                jdbcTemplate,
                transactionManager,
                sink,
                ZoneId.of(zone),
                auditWriter::requireValidChain);
    }

    @Bean
    @ConditionalOnBean(JdbcAiAuditAnchorService.class)
    @ConditionalOnProperty(prefix = "dormitory.ai.audit.anchor", name = "enabled", havingValue = "true")
    AiAuditAnchorScheduler aiAuditAnchorScheduler(
            JdbcTemplate jdbcTemplate,
            JdbcAiAuditAnchorService service,
            Environment environment) {
        String zone = environment.getProperty("dormitory.ai.audit.anchor.zone", "Asia/Shanghai");
        return new AiAuditAnchorScheduler(jdbcTemplate, service, Clock.system(ZoneId.of(zone)));
    }

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock aiSystemClock(Environment environment) {
        ZoneId zone = ZoneId.of(environment.getProperty(
                "dormitory.ai.business-clock.zone", "Asia/Shanghai"));
        String fixedInstant = environment.getProperty("dormitory.ai.business-clock.fixed-instant", "").trim();
        if (fixedInstant.isEmpty()) return Clock.system(zone);

        String[] activeProfiles = environment.getActiveProfiles();
        boolean developmentOrTestOnly = activeProfiles.length > 0
                && Arrays.stream(activeProfiles).allMatch(profile -> "dev".equals(profile) || "test".equals(profile));
        if (!developmentOrTestOnly) {
            throw new IllegalStateException("固定业务时钟仅允许在 dev/test profile 使用");
        }
        return Clock.fixed(Instant.parse(fixedInstant), zone);
    }
}
