package com.example.dormitory.ai.contract;

import com.example.dormitory.ai.config.AiProperties;
import com.example.dormitory.ai.infrastructure.runtime.AiRuntimeAuditWriter;
import com.example.dormitory.ai.infrastructure.runtime.AiRuntimeCrypto;
import com.example.dormitory.ai.infrastructure.runtime.SpringAiModelRuntimeAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ActiveProfiles({"test", "real-model-contract"})
@SpringBootTest(properties = {
        "spring.ai.openai.api-key=real-contract-profile-provider-key-not-a-secret",
        "spring.datasource.url=jdbc:h2:mem:real-model-contract-profile;MODE=MySQL;DB_CLOSE_DELAY=-1"
})
class RealModelContractProfileContextTest {

    @Autowired
    private AiProperties properties;

    @Autowired
    private AiRuntimeAuditWriter audit;

    @Autowired
    private AiRuntimeCrypto crypto;

    @Autowired
    private SpringAiModelRuntimeAdapter adapter;

    @Test
    void startsWithoutARealProviderKeyUsingExplicitTestControlKeys() {
        assertTrue(audit.startupReady());
        assertTrue(crypto.tokenizationReady());
        assertTrue(properties.getStepUp().getHmacKey()
                .getBytes(StandardCharsets.UTF_8).length >= 32);
        assertEquals("deepseek-v4-flash", adapter.modelAlias());
    }
}
