package com.example.dormitory.ai.config;

import com.example.dormitory.ai.domain.model.AiCapability;
import com.example.dormitory.ai.port.KnownPiiDictionaryPort;
import com.example.dormitory.ai.security.PiiClassificationService;
import com.example.dormitory.ai.security.PiiRedactionService;
import com.example.dormitory.ai.security.SensitiveDataBlockedException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiRuntimeControlServiceTest {

    @Test
    void runtimeKillSwitchCanOnlyNarrowStaticConfigurationAndCanBeCleared() {
        Fixture fixture = fixture();
        AiProperties properties = fixture.properties();
        AiRuntimeControlService controls = fixture.controls();

        assertTrue(controls.masterEnabled());
        assertTrue(controls.capabilityEnabled(AiCapability.REPAIR));
        assertTrue(controls.providerEnabled("fake"));
        assertTrue(controls.writeExecutionEnabled());

        AiRuntimeControlService.SwitchRecord repairSwitch = controls.disable(
                AiRuntimeControlService.Scope.CAPABILITY, "REPAIR", "供应商故障演练");
        controls.disable(AiRuntimeControlService.Scope.PROVIDER, "fake", "上游错误率过高");
        controls.disable(AiRuntimeControlService.Scope.WRITE, "*", "审批执行链维护");
        assertFalse(controls.capabilityEnabled(AiCapability.REPAIR));
        assertFalse(controls.providerEnabled("fake"));
        assertFalse(controls.writeExecutionEnabled());
        assertFalse(new AiRuntimeControlService(properties, fixture.jdbc(), classification())
                .capabilityEnabled(AiCapability.REPAIR), "新实例必须恢复数据库中的关闭状态");

        controls.clear(AiRuntimeControlService.Scope.CAPABILITY, "REPAIR",
                "故障已恢复并完成人工复核", repairSwitch.version(), null);
        assertTrue(controls.capabilityEnabled(AiCapability.REPAIR));

        properties.setEnabled(false);
        AiRuntimeControlService.SwitchRecord masterSwitch = controls.disable(
                AiRuntimeControlService.Scope.MASTER, "*", "验证静态总开关优先级");
        controls.clear(AiRuntimeControlService.Scope.MASTER, "*",
                "尝试打开静态关闭能力", masterSwitch.version(), null);
        assertFalse(controls.masterEnabled());
    }

    @Test
    void validatesScopeKeysAndPlainTextReason() {
        AiRuntimeControlService controls = fixture().controls();
        assertThrows(IllegalArgumentException.class,
                () -> controls.disable(AiRuntimeControlService.Scope.CAPABILITY, "UNKNOWN", "有效理由说明"));
        assertThrows(IllegalArgumentException.class,
                () -> controls.disable(AiRuntimeControlService.Scope.MASTER, "x", "有效理由说明"));
        assertThrows(IllegalArgumentException.class,
                () -> controls.disable(AiRuntimeControlService.Scope.WRITE, "*", "bad\nreason"));
    }

    @Test
    void killSwitchReasonIsClassifiedBeforeItCanEnterTheAiTable() {
        Fixture fixture = fixture();

        AiRuntimeControlService.SwitchRecord stored = fixture.controls().disable(
                AiRuntimeControlService.Scope.PROVIDER, "fake", "张三联系电话 13812345678，供应商异常");

        assertFalse(stored.reason().contains("张三"));
        assertFalse(stored.reason().contains("13812345678"));
        assertEquals(stored.reason(), fixture.jdbc().queryForObject(
                "SELECT reason_redacted FROM ai_runtime_switch WHERE scope_type='PROVIDER' AND scope_key='fake'",
                String.class));
        assertThrows(SensitiveDataBlockedException.class, () -> fixture.controls().disable(
                AiRuntimeControlService.Scope.WRITE, "*", "数据库密码=SuperSecret!，立即关闭写执行"));
        assertEquals(0, fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM ai_runtime_switch WHERE scope_type='WRITE'", Integer.class));
    }

    @Test
    void masterProviderWriteAndSourceShortCircuitsNeverWidenStaticConfiguration() {
        Fixture fixture = fixture();
        AiProperties properties = fixture.properties();
        AiRuntimeControlService controls = fixture.controls();

        properties.getProvider().setActive("none");
        assertFalse(controls.providerEnabled("fake"));
        properties.getProvider().setActive("fake");
        assertFalse(controls.providerEnabled("spring-ai"));
        properties.getWriteExecution().setEnabled(false);
        assertFalse(controls.writeExecutionEnabled());

        assertTrue(controls.sourceEnabled("source-1"));
        controls.disable(AiRuntimeControlService.Scope.SOURCE, "source-1", "来源内容异常需隔离");
        assertFalse(controls.sourceEnabled("source-1"));
        controls.disable(AiRuntimeControlService.Scope.MASTER, "*", "全局应急关闭演练");
        assertFalse(controls.masterEnabled());
        assertFalse(controls.sourceEnabled("source-2"));
        assertFalse(controls.writeExecutionEnabled());
    }

    @Test
    void everyCapabilityUsesItsOwnStaticFlagBeforeRuntimeOverride() {
        Fixture fixture = fixture();
        AiProperties.Capabilities capabilities = fixture.properties().getCapabilities();
        capabilities.setAssistant(true);
        capabilities.setKnowledge(true);
        capabilities.setDashboard(true);
        capabilities.setRepair(true);
        capabilities.setNotice(true);
        capabilities.setRisk(true);
        capabilities.setEvaluation(true);

        for (AiCapability capability : AiCapability.values()) {
            assertTrue(fixture.controls().capabilityEnabled(capability), capability.name());
        }
        capabilities.setAssistant(false);
        assertFalse(fixture.controls().capabilityEnabled(AiCapability.ASSISTANT));
    }

    @Test
    void clearRequiresPositiveMatchingVersionAndDisabledListReturnsCurrentFacts() {
        Fixture fixture = fixture();
        AiRuntimeControlService.SwitchRecord first = fixture.controls().disable(
                AiRuntimeControlService.Scope.PROVIDER, "fake", "供应商维护窗口", 7L);
        AiRuntimeControlService.SwitchRecord second = fixture.controls().disable(
                AiRuntimeControlService.Scope.WRITE, "*", "业务写执行维护", 7L);
        assertEquals(2, fixture.controls().disabledSwitches().size());
        assertThrows(IllegalArgumentException.class, () -> fixture.controls().clear(
                AiRuntimeControlService.Scope.PROVIDER, "fake", "人工复核通过", 0, 7L));
        assertThrows(RuntimeSwitchConflictException.class, () -> fixture.controls().clear(
                AiRuntimeControlService.Scope.PROVIDER, "fake", "人工复核通过", first.version() + 1, 7L));

        AiRuntimeControlService.SwitchRecord cleared = fixture.controls().clear(
                AiRuntimeControlService.Scope.PROVIDER, "fake", "人工复核通过", first.version(), 7L);
        assertFalse(cleared.disabled());
        assertEquals(first.version() + 1, cleared.version());
        assertEquals(1, fixture.controls().disabledSwitches().size());
        assertEquals(second.resourcePublicId(), fixture.controls().disabledSwitches().getFirst().resourcePublicId());
    }

    @Test
    void canonicalKeysAndReasonsRejectNullShortLongAndDynamicShapes() {
        assertEquals("REPAIR", AiRuntimeControlService.canonicalKey(
                AiRuntimeControlService.Scope.CAPABILITY, " repair "));
        assertEquals("spring-ai", AiRuntimeControlService.canonicalKey(
                AiRuntimeControlService.Scope.PROVIDER, " SPRING-AI "));
        assertEquals("Source_01", AiRuntimeControlService.canonicalKey(
                AiRuntimeControlService.Scope.SOURCE, " Source_01 "));
        for (AiRuntimeControlService.Scope scope : AiRuntimeControlService.Scope.values()) {
            assertThrows(IllegalArgumentException.class,
                    () -> AiRuntimeControlService.canonicalKey(scope, null));
        }
        for (String provider : java.util.List.of("x", "bad_provider", "x".repeat(33))) {
            assertThrows(IllegalArgumentException.class,
                    () -> AiRuntimeControlService.canonicalKey(AiRuntimeControlService.Scope.PROVIDER, provider));
        }
        for (String source : java.util.List.of("x", "bad source", "x".repeat(129))) {
            assertThrows(IllegalArgumentException.class,
                    () -> AiRuntimeControlService.canonicalKey(AiRuntimeControlService.Scope.SOURCE, source));
        }

        AiRuntimeControlService controls = fixture().controls();
        for (String reason : java.util.Arrays.asList(null, "短", "x".repeat(501), "valid\u0000reason")) {
            assertThrows(IllegalArgumentException.class,
                    () -> controls.disable(AiRuntimeControlService.Scope.MASTER, "*", reason));
        }
    }

    private AiProperties enabledProperties() {
        AiProperties properties = new AiProperties();
        properties.setEnabled(true);
        properties.getCapabilities().setRepair(true);
        properties.getProvider().setActive("fake");
        properties.getWriteExecution().setEnabled(true);
        return properties;
    }

    private Fixture fixture() {
        AiProperties properties = enabledProperties();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:runtime-switch-" + java.util.UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE ai_runtime_switch (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "scope_type VARCHAR(32) NOT NULL, scope_key VARCHAR(128) NOT NULL, "
                + "resource_public_id CHAR(36) NOT NULL, disabled BOOLEAN NOT NULL, "
                + "reason_redacted VARCHAR(500) NOT NULL, version BIGINT NOT NULL, "
                + "updated_by_user_id BIGINT, created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL, "
                + "UNIQUE(scope_type,scope_key), UNIQUE(resource_public_id))");
        PiiClassificationService classification = classification();
        return new Fixture(properties, jdbc, new AiRuntimeControlService(properties, jdbc, classification));
    }

    private PiiClassificationService classification() {
        PiiRedactionService redaction = new PiiRedactionService(
                "runtime-switch-test-hmac-key-32-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8), "v1");
        return new PiiClassificationService(redaction, () -> java.util.List.of(
                new KnownPiiDictionaryPort.KnownPiiValue(
                        KnownPiiDictionaryPort.PiiKind.PERSON_NAME, "张三")));
    }

    private record Fixture(AiProperties properties, JdbcTemplate jdbc, AiRuntimeControlService controls) { }
}
