package com.example.dormitory.ai.config;

import com.example.dormitory.ai.infrastructure.runtime.AiRuntimeAuditWriter;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** AI 开启时审计 keyring 与持久链结构必须就绪；总开关关闭时不影响原业务启动。 */
@Component
@Order(20)
public final class AiAuditStartupGate implements ApplicationRunner {

    private final AiProperties properties;
    private final AiRuntimeAuditWriter audit;

    public AiAuditStartupGate(AiProperties properties, AiRuntimeAuditWriter audit) {
        this.properties = properties;
        this.audit = audit;
    }

    @Override
    public void run(ApplicationArguments args) {
        verify();
    }

    public void verify() {
        if (properties.isEnabled() && !audit.startupReady()) {
            throw new IllegalStateException("AI_AUDIT_UNAVAILABLE_AT_STARTUP");
        }
    }
}
