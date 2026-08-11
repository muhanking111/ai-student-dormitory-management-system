package com.example.dormitory.ai.security;

import com.example.dormitory.ai.api.AiApiException;
import com.example.dormitory.ai.approval.ActionProposalService;
import com.example.dormitory.ai.approval.ActionType;
import com.example.dormitory.ai.approval.InMemoryActionProposalRepository;
import com.example.dormitory.ai.approval.ProposalOrigin;
import com.example.dormitory.ai.approval.ProposalPreview;
import com.example.dormitory.ai.approval.ProposalState;
import com.example.dormitory.ai.approval.SpringActionProposalTransactionRunner;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.BusinessExecutionActor;
import com.example.dormitory.ai.port.AiAuditPort;
import com.example.dormitory.ai.port.ApprovedBusinessActionPort;
import com.example.dormitory.service.OperationsService;
import com.example.dormitory.service.RbacService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:fresh-rbac-denial-pool1;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.minimum-idle=1",
        "spring.datasource.hikari.connection-timeout=250",
        "dormitory.bootstrap-admin.username=",
        "dormitory.bootstrap-admin.password=",
        "dormitory.ai.audit.hmac-key=0123456789abcdef0123456789abcdef",
        "dormitory.ai.tokenization.hmac-key=fedcba9876543210fedcba9876543210"
})
class FreshRbacDenialSingleConnectionPoolTest {

    @Autowired
    private SpringActionProposalTransactionRunner transactions;

    @Autowired
    private AiAuditPort audit;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Test
    void freshDenialCommitsAuditAndReturnsHiddenFailureWithOneConnection() {
        assertEquals(1, ((HikariDataSource) dataSource).getMaximumPoolSize());
        ActorAuthorizationFacade authorization = mock(ActorAuthorizationFacade.class);
        when(authorization.snapshotForUpdate(7L)).thenReturn(new RbacService.AuthorizationSnapshot(
                7L, true, List.of("VIEWER"), List.of("ai:approval:review")));
        ActionAuthorizationPolicy policy = new ActionAuthorizationPolicy(
                authorization, mock(OperationsService.class), audit);
        AtomicInteger writes = new AtomicInteger();
        ActionProposalService service = new ActionProposalService(
                new InMemoryActionProposalRepository(),
                (actor, action) -> {
                    writes.incrementAndGet();
                    return new ApprovedBusinessActionPort.BusinessActionResult("NOTICE", 1L, hash('e'));
                },
                (type, payload) -> hash('d'), audit, () -> true, transactions,
                (actor, proposal, ignored) -> policy.requireFreshExecutionAccess(actor, proposal));
        BusinessExecutionActor actor = BusinessExecutionActor.from(ActorDescriptor.user(7L));
        ActionProposalService.ProposalView proposal = service.create(command(), "pool1-create", hash('c'), actor);

        AiApiException denied = assertThrows(AiApiException.class, () -> service.approve(
                proposal.publicId(), proposal.version(), proposal.payloadHash(), proposal.businessSnapshotHash(),
                actor, Set.of("ai:approval:review", "notice:write"), "pool1-approve", hash('a')));

        assertEquals("AI_RESOURCE_NOT_FOUND", denied.errorCode());
        assertEquals(0, writes.get());
        assertEquals(ProposalState.PENDING_APPROVAL, service.get(proposal.publicId()).state());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_audit_event WHERE aggregate_public_id=? "
                        + "AND event_type='PROPOSAL_OBJECT_ACCESS_DENIED'",
                Integer.class, proposal.publicId()));
    }

    private ActionProposalService.CreateProposalCommand command() {
        ProposalPreview preview = new ProposalPreview(
                "当前值", "公告草稿", "有限影响", Instant.now(),
                ProposalPreview.EvidenceBasis.DETERMINISTIC, null,
                List.of(new ProposalPreview.Citation(
                        "USER_COMMAND", "RUN:test", "测试命令", hash('b'))));
        return new ActionProposalService.CreateProposalCommand(
                ActionType.NOTICE_CREATE_DRAFT, "NOTICE", null,
                "{\"title\":\"test\",\"status\":\"草稿\"}", preview,
                "notice:write", 1, "HIGH", 7L, Instant.now().plusSeconds(300),
                ProposalOrigin.forAction(UUID.randomUUID().toString(), ActionType.NOTICE_CREATE_DRAFT));
    }

    private static String hash(char value) {
        return String.valueOf(value).repeat(64);
    }
}
