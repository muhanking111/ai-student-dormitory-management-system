package com.example.dormitory.ai.governance;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.application.run.AiActorContext;
import com.example.dormitory.ai.approval.CanonicalJsonHasher;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ActiveProfiles("test")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ai-config-cas;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "dormitory.ai.audit.hmac-key=0123456789abcdef0123456789abcdef",
        "dormitory.ai.tokenization.hmac-key=fedcba9876543210fedcba9876543210"
})
class AiConfigurationGovernanceConcurrencyTest {

    @Autowired
    private AiConfigurationGovernanceService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private AiActorContext actor;

    @BeforeEach
    void reset() {
        for (String table : new String[]{"ai_outbox_event", "ai_prompt_version",
                "ai_audit_event", "ai_audit_chain_head"}) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        Long adminId = jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username='admin'", Long.class);
        actor = new AiActorContext(adminId, "test-session", "a".repeat(64), 1, "b".repeat(64),
                List.of("ADMIN"), List.of("ai:config:manage"), ActorDescriptor.user(adminId));
    }

    @Test
    void twoActivationsFromTheSameObservedSlotHaveExactlyOneWinner() throws Exception {
        var baseline = create("v301", "基线版本");
        service.activatePrompt(actor, baseline.id(), null, hash("baseline-activate"));
        var candidateA = create("v302", "候选版本 A");
        var candidateB = create("v303", "候选版本 B");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var futureA = executor.submit(() -> activate(candidateA.id(), baseline.id(), ready, start));
            var futureB = executor.submit(() -> activate(candidateB.id(), baseline.id(), ready, start));
            ready.await();
            start.countDown();
            List<String> outcomes = List.of(futureA.get(), futureB.get());
            assertEquals(1, outcomes.stream().filter("ACTIVATED"::equals).count());
            assertEquals(1, outcomes.stream().filter("CONFLICT"::equals).count());
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ai_prompt_version WHERE active_slot_key='assistant.system'",
                    Integer.class));
            String activeVersion = jdbcTemplate.queryForObject(
                    "SELECT version FROM ai_prompt_version WHERE active_slot_key='assistant.system'", String.class);
            assertTrue(List.of("v302", "v303").contains(activeVersion));
        } finally {
            executor.shutdownNow();
        }
    }

    private AiConfigurationGovernanceService.PromptView create(String version, String content) {
        return service.createPromptDraft(actor, "assistant.system", version, content, "text.v1", hash(version));
    }

    private String activate(
            String candidateId,
            String baselineId,
            CountDownLatch ready,
            CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        try {
            service.activatePrompt(actor, candidateId, baselineId,
                    hash("activate-" + candidateId + "-" + UUID.randomUUID()));
            return "ACTIVATED";
        } catch (AiApiException conflict) {
            if ("AI_CONFIG_VERSION_CONFLICT".equals(conflict.errorCode())) return "CONFLICT";
            throw conflict;
        }
    }

    private String hash(String value) {
        return CanonicalJsonHasher.sha256(value);
    }
}
