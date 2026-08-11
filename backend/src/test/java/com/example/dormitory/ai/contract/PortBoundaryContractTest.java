package com.example.dormitory.ai.contract;

import com.example.dormitory.ai.port.AiAuditPort;
import com.example.dormitory.ai.port.AiToolCallAuditPort;
import com.example.dormitory.ai.port.ApprovedBusinessActionPort;
import com.example.dormitory.ai.port.BusinessReadFacade;
import com.example.dormitory.ai.port.EmbeddingGateway;
import com.example.dormitory.ai.port.ModelGateway;
import com.example.dormitory.ai.port.ObjectStoragePort;
import com.example.dormitory.ai.port.SessionValidityPort;
import com.example.dormitory.ai.port.VectorIndexPort;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortBoundaryContractTest {

    private static final List<Class<?>> REQUIRED_PORTS = List.of(
            ModelGateway.class,
            EmbeddingGateway.class,
            VectorIndexPort.class,
            ObjectStoragePort.class,
            BusinessReadFacade.class,
            ApprovedBusinessActionPort.class,
            AiAuditPort.class,
            AiToolCallAuditPort.class,
            SessionValidityPort.class);

    private static final List<String> FORBIDDEN_TYPE_PREFIXES = List.of(
            "org.springframework.ai.",
            "dev.langchain4j.",
            "com.example.dormitory.mapper.",
            "org.springframework.jdbc.");

    @Test
    void requiredPortsAreProjectOwnedInterfacesWithoutSupplierTypes() {
        for (Class<?> port : REQUIRED_PORTS) {
            assertTrue(port.isInterface(), () -> port.getName() + " 必须是接口");
            assertTrue(port.getName().startsWith("com.example.dormitory.ai.port."));
            for (Method method : port.getMethods()) {
                assertProjectOwnedOrJdk(method.getReturnType(), port, method);
                for (Class<?> parameterType : method.getParameterTypes()) {
                    assertProjectOwnedOrJdk(parameterType, port, method);
                }
            }
        }
    }

    @Test
    void applicationAndDomainSourcesCannotImportMapperJdbcOrAiFrameworkSdk() throws Exception {
        Path aiRoot = Path.of("src/main/java/com/example/dormitory/ai");
        assertTrue(Files.isDirectory(aiRoot), "AI 包必须存在");
        List<Path> guardedSources;
        try (var paths = Files.walk(aiRoot)) {
            guardedSources = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.toString().contains("\\application\\")
                            || path.toString().contains("/application/")
                            || path.toString().contains("\\domain\\")
                            || path.toString().contains("/domain/"))
                    .toList();
        }
        assertFalse(guardedSources.isEmpty(), "至少应存在 domain/application 源码");
        for (Path source : guardedSources) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            for (String forbidden : List.of(
                    "com.example.dormitory.mapper",
                    "org.springframework.jdbc",
                    "org.springframework.ai",
                    "dev.langchain4j",
                    "java.net.http",
                    "ProcessBuilder",
                    "Runtime.getRuntime")) {
                assertFalse(text.contains(forbidden), () -> source + " 禁止依赖 " + forbidden);
            }
        }
    }

    @Test
    void completeAiModuleCannotImportBusinessMappers() throws Exception {
        Path aiRoot = Path.of("src/main/java/com/example/dormitory/ai");
        assertTrue(Files.isDirectory(aiRoot), "AI 包必须存在");
        try (var paths = Files.walk(aiRoot)) {
            for (Path source : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source, StandardCharsets.UTF_8);
                assertFalse(text.contains("import com.example.dormitory.mapper."),
                        () -> source + " 必须通过现有 Service 或受控 Facade 访问业务数据");
            }
        }
    }

    @Test
    void portSourcesCannotImportSupplierSdk() throws Exception {
        Path portRoot = Path.of("src/main/java/com/example/dormitory/ai/port");
        try (var paths = Files.walk(portRoot)) {
            for (Path source : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source, StandardCharsets.UTF_8);
                for (String forbidden : List.of("org.springframework.ai", "dev.langchain4j")) {
                    assertFalse(text.contains(forbidden), () -> source + " 泄漏供应商类型 " + forbidden);
                }
            }
        }
    }

    private void assertProjectOwnedOrJdk(Class<?> type, Class<?> port, Method method) {
        String name = type.isArray() ? type.componentType().getName() : type.getName();
        assertTrue(FORBIDDEN_TYPE_PREFIXES.stream().noneMatch(name::startsWith),
                () -> port.getSimpleName() + "." + method.getName() + " 泄漏类型 " + name);
    }
}
