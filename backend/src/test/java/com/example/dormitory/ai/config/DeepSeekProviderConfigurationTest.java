package com.example.dormitory.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class DeepSeekProviderConfigurationTest {

    private static final String CURRENT_MODEL = "deepseek-v4-flash";
    private static final String DEPRECATED_MODEL = "deepseek-chat";

    @Test
    void applicationAndRealContractDefaultsNeverUseTheDeprecatedModelAlias() throws IOException {
        assertCurrentDefaults("application.yml");
        assertCurrentDefaults("application-real-model-contract.yml");
    }

    private void assertCurrentDefaults(String resourceName) throws IOException {
        PropertySourcesPropertyResolver resolver = resolver(resourceName);
        String model = resolver.getRequiredProperty("spring.ai.openai.chat.options.model");
        String alias = resolver.getRequiredProperty("dormitory.ai.provider.model-alias");

        assertEquals(CURRENT_MODEL, model, resourceName);
        assertEquals(CURRENT_MODEL, alias, resourceName);
        assertNotEquals(DEPRECATED_MODEL, model, resourceName);
        assertNotEquals(DEPRECATED_MODEL, alias, resourceName);
    }

    private PropertySourcesPropertyResolver resolver(String resourceName) throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        new YamlPropertySourceLoader().load(resourceName, new ClassPathResource(resourceName))
                .forEach(sources::addLast);
        return new PropertySourcesPropertyResolver(sources);
    }
}
