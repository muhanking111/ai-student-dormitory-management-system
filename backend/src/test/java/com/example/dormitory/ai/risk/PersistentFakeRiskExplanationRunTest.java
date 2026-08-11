package com.example.dormitory.ai.risk;

import com.example.dormitory.ai.infrastructure.fake.JdbcDeterministicFakeRiskExplanationRunAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ActiveProfiles("test")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:risk-explanation-persistent;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "dormitory.ai.enabled=true",
        "dormitory.ai.capabilities.risk=true",
        "dormitory.ai.risk.explanation.enabled=true",
        "dormitory.ai.risk.explanation.adapter=fake",
        "dormitory.ai.audit.hmac-key=risk-explanation-audit-key-at-least-32-bytes",
        "dormitory.ai.tokenization.hmac-key=risk-explanation-token-key-at-least-32-bytes"
})
class PersistentFakeRiskExplanationRunTest {

    @Autowired
    private RiskExplanationRunPort port;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void activateRiskRuntimeVersions() {
        jdbc.update("UPDATE ai_prompt_version SET status='DRAFT',active_slot_key=NULL,activated_at=NULL "
                + "WHERE prompt_key='risk.system'");
        jdbc.update("UPDATE ai_prompt_version SET status='ACTIVE',active_slot_key='risk.system',"
                + "activated_at=CURRENT_TIMESTAMP WHERE prompt_key='risk.system' AND version='v1'");
        jdbc.update("DELETE FROM ai_tool_catalog_version");
        jdbc.update("INSERT INTO ai_tool_catalog_version "
                        + "(version,manifest_text,manifest_hash,status,active_slot_key,activated_at,created_at,updated_at) "
                        + "VALUES ('risk-fake-v1','{\"tools\":[]}',?,'ACTIVE','runtime',CURRENT_TIMESTAMP,"
                        + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                "b".repeat(64));
    }

    @Test
    void deterministicFakePersistsARealSucceededRiskRunWithoutClaimingProviderUsage() {
        assertInstanceOf(JdbcDeterministicFakeRiskExplanationRunAdapter.class, port);
        Long adminId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username='admin'", Long.class);
        RiskSignal signal = new RiskSignal("repair-backlog", "REPAIR_ORDER", 42L,
                "risk_subject_token", "HIGH", "repair-backlog.v1",
                Map.of("ageHours", 96, "ruleThreshold", 72), Instant.parse("2026-07-12T00:00:00Z"));

        RiskExplanationRunPort.RunOutcome outcome = port.run(new RiskExplanationRunPort.RunRequest(
                UUID.randomUUID().toString(), adminId,
                RiskSignalEvidence.from(signal), RiskBusinessSnapshot.from(signal)));

        assertEquals(RiskExplanationRunPort.OutcomeStatus.SUCCEEDED, outcome.status());
        assertEquals("RISK", outcome.receipt().capability());
        assertEquals("SUCCEEDED", outcome.receipt().state());
        assertTrue(outcome.receipt().explanation().contains("仅用于辅助人工核验"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_run WHERE public_id=? AND capability='RISK' AND state='SUCCEEDED'",
                Integer.class, outcome.receipt().runPublicId()));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_usage_ledger WHERE billing_subject_public_id=?",
                Integer.class, outcome.receipt().runPublicId()));
        assertTrue(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_audit_event WHERE aggregate_public_id=?",
                Integer.class, outcome.receipt().runPublicId()) >= 2);
    }
}
