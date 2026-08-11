package com.example.dormitory.ai.notice;

import com.example.dormitory.ai.approval.ActionProposalService;
import com.example.dormitory.ai.approval.ActionType;
import com.example.dormitory.ai.approval.CanonicalJsonHasher;
import com.example.dormitory.ai.approval.ProposalOrigin;
import com.example.dormitory.ai.approval.ProposalPreview;
import com.example.dormitory.ai.domain.model.BusinessExecutionActor;
import com.example.dormitory.ai.security.DataClassification;
import com.example.dormitory.ai.security.PiiClassificationService;
import com.example.dormitory.ai.security.PiiRedactionService;
import com.example.dormitory.ai.security.PromptInjectionGuard;
import com.example.dormitory.ai.security.SensitiveDataBlockedException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 公告 AI 首期只生成纯文本并创建 NOTICE_CREATE_DRAFT 提案，绝不发布。 */
public final class NoticeDraftService {

    private static final Set<String> TONES = Set.of("正式", "温和", "紧急");

    private final ActionProposalService proposalService;
    private final PromptInjectionGuard injectionGuard;
    private final PiiClassificationService classificationService;
    private final ObjectMapper objectMapper;

    public NoticeDraftService(
            ActionProposalService proposalService,
            PromptInjectionGuard injectionGuard,
            PiiClassificationService classificationService,
            ObjectMapper objectMapper) {
        this.proposalService = java.util.Objects.requireNonNull(proposalService);
        this.injectionGuard = java.util.Objects.requireNonNull(injectionGuard);
        this.classificationService = java.util.Objects.requireNonNull(classificationService);
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper);
    }

    /** 仅供不连接业务姓名字典的纯领域测试。 */
    public NoticeDraftService(
            ActionProposalService proposalService,
            PromptInjectionGuard injectionGuard,
            PiiRedactionService redactionService,
            ObjectMapper objectMapper) {
        this(proposalService, injectionGuard,
                new PiiClassificationService(redactionService, java.util.List::of), objectMapper);
    }

    public DraftResult draft(
            DraftCommand command,
            BusinessExecutionActor actor,
            Set<String> currentPermissions,
            String idempotencyKey,
            String requestHash,
            String runPublicId) {
        if (actor == null || currentPermissions == null
                || !currentPermissions.containsAll(Set.of("ai:notice:draft", "notice:read"))) {
            throw new SecurityException("缺少公告 AI 起草或公告写权限");
        }
        validate(command);
        List<String> safetyMessages = new ArrayList<>();
        String combined = command.points() + "\n" + command.audience();
        if (combined.contains("<") || combined.contains(">")) {
            safetyMessages.add("检测到 HTML/标记内容，已阻止生成");
        }
        if (injectionGuard.inspect(combined).blocked()) {
            safetyMessages.add("检测到提示注入或动态工具指令，已阻止生成");
        }
        try {
            PiiRedactionService.RedactionResult redacted =
                    classificationService.redact(combined, "notice-draft-input");
            if (redacted.classification() != DataClassification.L1) {
                safetyMessages.add("检测到姓名以外的具体联系或定位信息，已阻止进入公告草稿");
            }
        } catch (SensitiveDataBlockedException exception) {
            safetyMessages.add("检测到密钥、密码或 Token，已阻止生成");
        }
        if (!safetyMessages.isEmpty()) {
            return new DraftResult("", command.type(), "由审批执行人重写", "草稿", "",
                    true, List.copyOf(safetyMessages), "notice-draft.v1", null);
        }

        String subject = command.points().replaceAll("[\\r\\n]+", " ").trim();
        String title = "关于" + subject.substring(0, Math.min(subject.length(), 18)) + "的通知";
        String content = command.audience().trim() + "：\n\n" + command.points().trim()
                + "\n\n请按要求落实，并关注后续通知。";
        if (content.length() > 10_000) throw new IllegalArgumentException("公告草稿超过 10000 字");
        String payload = json(Map.of(
                "title", title,
                "type", command.type().trim(),
                "status", "草稿",
                "content", content));
        Instant asOf = Instant.now();
        ProposalPreview preview = new ProposalPreview(
                "当前不存在此 AI 公告草稿",
                "创建可编辑纯文本公告草稿《" + title + "》",
                "仅创建草稿，不发布公告；发布仍需走原公告流程",
                asOf, ProposalPreview.EvidenceBasis.DETERMINISTIC, null,
                List.of(new ProposalPreview.Citation(
                        "USER_COMMAND", "RUN:" + runPublicId,
                        "当前用户提交的公告起草命令", CanonicalJsonHasher.sha256(payload))));
        ActionProposalService.ProposalView proposal = proposalService.create(
                new ActionProposalService.CreateProposalCommand(
                        ActionType.NOTICE_CREATE_DRAFT, "NOTICE", null, payload,
                        preview, "notice:write", 1,
                        "MEDIUM", actor.userId(), asOf.plusSeconds(600),
                        ProposalOrigin.forAction(runPublicId, ActionType.NOTICE_CREATE_DRAFT)),
                idempotencyKey, requestHash, actor);
        return new DraftResult(title, command.type().trim(), "由审批执行人重写", "草稿", content,
                false, List.of(), "notice-draft.v1", proposal);
    }

    private void validate(DraftCommand command) {
        if (command == null || blank(command.points()) || command.points().length() > 6_000
                || blank(command.type()) || command.type().length() > 32
                || !TONES.contains(command.tone())
                || blank(command.audience()) || command.audience().length() > 200) {
            throw new IllegalArgumentException("公告起草参数不合法");
        }
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("公告草稿序列化失败", exception);
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record DraftCommand(String points, String type, String tone, String audience) {
    }

    public record DraftResult(
            String title,
            String type,
            String publisher,
            String status,
            String content,
            boolean blocked,
            List<String> safetyMessages,
            String version,
            ActionProposalService.ProposalView proposal) {
        public DraftResult {
            safetyMessages = List.copyOf(safetyMessages);
        }
    }
}
