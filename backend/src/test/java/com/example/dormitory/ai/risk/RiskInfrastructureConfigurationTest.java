package com.example.dormitory.ai.risk;

import com.example.dormitory.ai.infrastructure.persistence.JdbcRiskCaseRepository;
import com.example.dormitory.ai.infrastructure.risk.DisabledRiskExplanationRunAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@ActiveProfiles("test")
@SpringBootTest(properties = {
        "dormitory.ai.capabilities.risk=true",
        "dormitory.ai.tokenization.hmac-key=0123456789abcdef0123456789abcdef",
        "dormitory.ai.tokenization.active-key-version=7"
})
class RiskInfrastructureConfigurationTest {

    @Autowired
    private RiskCaseRepository repository;

    @Autowired
    private RiskCaseService service;

    @Autowired
    private RiskSignalRegistry registry;

    @Autowired
    private RiskExplanationRunPort explanationRuns;

    @Autowired
    private RiskExplanationProperties explanationProperties;

    @Test
    void productionRiskCapabilityUsesJdbcRepositoryAndWiresDeterministicProvider() {
        assertInstanceOf(JdbcRiskCaseRepository.class, repository);
        assertNotNull(service);
        assertNotNull(registry);
        assertInstanceOf(DisabledRiskExplanationRunAdapter.class, explanationRuns);
        org.junit.jupiter.api.Assertions.assertFalse(explanationProperties.isEnabled());
    }
}
