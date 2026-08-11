package com.example.dormitory.ai.risk;

import com.example.dormitory.ai.infrastructure.persistence.JdbcAiOutboxRepository;
import com.example.dormitory.ai.outbox.AiOutboxEventHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@ConditionalOnProperty(prefix = "dormitory.ai.capabilities", name = "risk", havingValue = "true")
public class RiskScanOutboxHandler implements AiOutboxEventHandler {

    private static final String EVENT_TYPE = "RiskScanRequested.v1";

    private final RiskScanService scans;

    public RiskScanOutboxHandler(RiskScanService scans) {
        this.scans = scans;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(EVENT_TYPE);
    }

    @Override
    public void handle(JdbcAiOutboxRepository.ClaimedOutboxEvent event) {
        if (!EVENT_TYPE.equals(event.eventType()) || !"RISK_SCAN".equals(event.aggregateType())
                || !"SERVICE".equals(event.actorKind()) || !"risk-scan".equals(event.servicePrincipalCode())) {
            throw new IllegalArgumentException("风险扫描 outbox actor/类型不合法");
        }
        scans.process(event.aggregatePublicId());
    }

    @Override
    public void onDead(JdbcAiOutboxRepository.ClaimedOutboxEvent event, String errorCode) {
        scans.markNeedsReview(event.aggregatePublicId(), "RISK_SCAN_OUTBOX_DEAD");
    }
}
