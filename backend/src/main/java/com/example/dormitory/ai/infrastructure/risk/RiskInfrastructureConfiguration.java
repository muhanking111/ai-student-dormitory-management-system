package com.example.dormitory.ai.infrastructure.risk;

import com.example.dormitory.ai.config.AiProperties;
import com.example.dormitory.ai.risk.RiskCaseRepository;
import com.example.dormitory.ai.risk.RiskCaseService;
import com.example.dormitory.ai.risk.RiskSignalProvider;
import com.example.dormitory.ai.risk.RiskSignalRegistry;
import com.example.dormitory.ai.risk.RiskExplanationRunPort;
import com.example.dormitory.ai.security.PiiClassificationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** 风险能力启用时只装配 JDBC 事实仓储；InMemoryRiskCaseRepository 仅供单元测试显式构造。 */
@Configuration
@ConditionalOnProperty(prefix = "dormitory.ai.capabilities", name = "risk", havingValue = "true")
public class RiskInfrastructureConfiguration {

    @Bean
    public RiskSignalRegistry riskSignalRegistry(List<RiskSignalProvider> providers) {
        return new RiskSignalRegistry(providers);
    }

    @Bean
    public RiskCaseService riskCaseService(
            RiskCaseRepository repository,
            AiProperties properties,
            PiiClassificationService classificationService) {
        byte[] key = properties.getTokenization().getHmacKey().getBytes(StandardCharsets.UTF_8);
        if (key.length < 32 || properties.getTokenization().getActiveKeyVersion() < 1) {
            throw new IllegalStateException("风险能力启用前必须配置至少 32 字节的 tokenization HMAC key");
        }
        return new RiskCaseService(repository, classificationService);
    }

    @Bean
    @ConditionalOnMissingBean(RiskExplanationRunPort.class)
    public RiskExplanationRunPort disabledRiskExplanationRunPort() {
        return new DisabledRiskExplanationRunAdapter();
    }
}
