package com.example.dormitory.ai.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.TokenStream;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.SpringBootVersion;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameworkCompatibilityTest {

    @Test
    void runtimeBaselineIsBoot3516Java21AndJackson2215() {
        assertEquals("3.5.16", SpringBootVersion.getVersion());
        assertEquals(21, Runtime.version().feature());
        assertTrue(ObjectMapper.class.getPackage().getImplementationVersion().startsWith("2.21.5"));
    }

    @Test
    void servletAndLoggingRuntimeIncludeTheApprovedSecurityFixes() throws Exception {
        assertEquals("10.1.59.0", org.apache.catalina.util.ServerInfo.getServerNumber());
        assertEquals("2.25.5", mavenVersion("org.apache.logging.log4j", "log4j-api"));
    }

    @Test
    void springAi118ExposesCandidateChatToolSchemaUsageAndObservationSurfaces() throws Exception {
        assertEquals("1.1.8", mavenVersion("org.springframework.ai", "spring-ai-client-chat"));
        assertTrue(Arrays.stream(ChatClient.class.getMethods()).anyMatch(method -> method.getName().equals("prompt")));
        Class<?> requestSpec = Class.forName("org.springframework.ai.chat.client.ChatClient$ChatClientRequestSpec");
        assertTrue(Arrays.stream(requestSpec.getMethods()).anyMatch(method -> method.getName().equals("call")));
        assertTrue(Arrays.stream(requestSpec.getMethods()).anyMatch(method -> method.getName().equals("stream")));
        assertNotNull(Class.forName("org.springframework.ai.tool.annotation.Tool"));
        assertNotNull(Class.forName("org.springframework.ai.converter.BeanOutputConverter"));
        assertNotNull(Class.forName("org.springframework.ai.chat.metadata.Usage"));
        assertNotNull(Class.forName("io.micrometer.observation.ObservationRegistry"));
    }

    @Test
    void langChain4j1121ExposesTheSurfacesUsedByTheOfflineBehaviorComparison() throws Exception {
        assertEquals("1.12.1", mavenVersion("dev.langchain4j", "langchain4j"));
        assertTrue(ChatModel.class.isInterface());
        assertTrue(StreamingChatModel.class.isInterface());
        assertTrue(TokenStream.class.isInterface());
        assertTrue(Tool.class.isAnnotation());
        assertNotNull(Class.forName("dev.langchain4j.model.chat.request.json.JsonSchema"));
        assertNotNull(Class.forName("dev.langchain4j.model.output.TokenUsage"));
        assertNotNull(Class.forName("dev.langchain4j.model.chat.listener.ChatModelListener"));
    }

    private String mavenVersion(String groupId, String artifactId) throws Exception {
        String resource = "META-INF/maven/" + groupId + "/" + artifactId + "/pom.properties";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, () -> "缺少 Maven 元数据: " + resource);
            Properties properties = new Properties();
            properties.load(input);
            return properties.getProperty("version");
        }
    }
}
