package com.example.dormitory.ai.risk;

import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.ActorKind;
import com.example.dormitory.ai.domain.model.BusinessExecutionActor;
import com.example.dormitory.ai.security.PiiRedactionService;
import com.example.dormitory.ai.security.PiiClassificationService;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

public class RiskCaseService {

    private static final Set<String> ALLOWED_RISK_TYPES = Set.of(
            "repair-backlog", "repeat-repair", "resource-checkin-inconsistency", "failed-hygiene-check",
            "long-pending-operation", "overdue-payment");

    private final RiskCaseRepository repository;
    private final PiiClassificationService classificationService;
    private final RiskExplanationPolicy explanationPolicy;

    public RiskCaseService(RiskCaseRepository repository, PiiClassificationService classificationService) {
        this(repository, classificationService, new RiskExplanationPolicy());
    }

    RiskCaseService(
            RiskCaseRepository repository,
            PiiClassificationService classificationService,
            RiskExplanationPolicy explanationPolicy) {
        this.repository = java.util.Objects.requireNonNull(repository);
        this.classificationService = java.util.Objects.requireNonNull(classificationService);
        this.explanationPolicy = java.util.Objects.requireNonNull(explanationPolicy);
    }

    /** 仅供不连接业务姓名字典的纯领域测试。 */
    public RiskCaseService(RiskCaseRepository repository, PiiRedactionService redactionService) {
        this(repository, new PiiClassificationService(redactionService, List::of), new RiskExplanationPolicy());
    }

    public CaseView ingest(RiskSignal signal) {
        java.util.Objects.requireNonNull(signal, "风险信号不能为空");
        synchronized (repository) {
            RiskCaseRepository.StoredRiskCase existing = repository.findLatestByDedupKey(signal.dedupKey())
                    .orElse(null);
            if (existing != null) {
                RiskCase riskCase = existing.riskCase();
                if (riskCase.state() == RiskCaseState.OPEN || riskCase.state() == RiskCaseState.ACKNOWLEDGED) {
                    existing.refreshSignal(signal);
                    repository.save(existing, riskCase.version(), null);
                    return view(existing);
                }
                if (!riskCase.signalPolicyVersion().equals(signal.policyVersion())) {
                    int expectedVersion = riskCase.version();
                    riskCase.reopenFromRule(expectedVersion, signal.policyVersion(), "新版本确定性规则再次命中");
                    existing.refreshSignal(signal);
                    repository.save(existing, expectedVersion, null);
                }
                return view(existing);
            }
            RiskCase riskCase = RiskCase.open(UUID.randomUUID().toString(), signal.riskType(),
                    signal.subjectToken(), signal.policyVersion());
            RiskCaseRepository.StoredRiskCase stored = new RiskCaseRepository.StoredRiskCase(riskCase, signal);
            stored.explanation(new RiskExplanationEvidence(
                    explanationPolicy.safeFallback(signal.riskType(), signal.evidence()),
                    RiskExplanationBasis.DETERMINISTIC_DEGRADED,
                    "risk-explanation-deterministic.v1", null, null, null, true));
            try {
                repository.create(stored);
                return view(stored);
            } catch (ActiveRiskCaseConflictException concurrentCreate) {
                RiskCaseRepository.StoredRiskCase winner = repository.findLatestByDedupKey(signal.dedupKey())
                        .filter(candidate -> candidate.riskCase().state() == RiskCaseState.OPEN
                                || candidate.riskCase().state() == RiskCaseState.ACKNOWLEDGED)
                        .orElseThrow(() -> concurrentCreate);
                return view(winner);
            }
        }
    }

    public CaseView recordExplanation(String publicId, String explanation, ActorDescriptor actor) {
        if (actor == null || actor.kind() != ActorKind.MODEL) {
            throw new IllegalArgumentException("只有模型 actor 可写解释层，且不能写人工状态");
        }
        throw new IllegalArgumentException("模型风险解释必须绑定受控 RISK run");
    }

    /**
     * 仅供已完成预算、审计和状态控制的 RISK run 回写解释；本服务不会自行调用模型。
     */
    public CaseView recordModelExplanation(
            String publicId,
            String explanation,
            ActorDescriptor actor,
            long runDatabaseId,
            String runPublicId) {
        if (actor == null || actor.kind() != ActorKind.MODEL) {
            throw new IllegalArgumentException("只有模型 actor 可写解释层，且不能写人工状态");
        }
        if (runDatabaseId < 1 || runPublicId == null
                || !runPublicId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new IllegalArgumentException("模型风险解释必须绑定受控 RISK run");
        }
        if (explanation == null || explanation.isBlank() || explanation.length() > 4_000) {
            throw new IllegalArgumentException("模型风险解释不合法");
        }
        RiskCaseRepository.StoredRiskCase stored = requireCase(publicId);
        String redacted = classificationService.redact(explanation, "risk-explanation").redactedText();
        stored.explanation(new RiskExplanationEvidence(redacted, RiskExplanationBasis.MODEL,
                "risk-explanation-model.v1", runDatabaseId, runPublicId, null, false));
        repository.save(stored, stored.riskCase().version(), null);
        return view(stored);
    }

    public CaseView transition(
            String publicId,
            RiskCaseState target,
            int expectedVersion,
            BusinessExecutionActor actor,
            Set<String> currentPermissions,
            String detail,
            String idempotencyKey,
            String requestHash) {
        return transition(publicId, target, expectedVersion, actor, currentPermissions, detail,
                idempotencyKey, requestHash, null);
    }

    public CaseView transition(
            String publicId,
            RiskCaseState target,
            int expectedVersion,
            BusinessExecutionActor actor,
            Set<String> currentPermissions,
            String detail,
            String idempotencyKey,
            String requestHash,
            Instant dueAt) {
        if (actor == null || currentPermissions == null
                || !currentPermissions.containsAll(Set.of("ai:risk:read", "ai:risk:manage"))) {
            throw new SecurityException("缺少风险读取或处置权限");
        }
        if (target != RiskCaseState.ACKNOWLEDGED
                && target != RiskCaseState.RESOLVED
                && target != RiskCaseState.DISMISSED) {
            throw new IllegalArgumentException("人工风险目标状态不合法");
        }
        String key = required(idempotencyKey, "Idempotency-Key");
        String hash = required(requestHash, "requestHash");
        RiskCaseRepository.TransitionReservation reservation = repository.reserveTransition(
                actor.userId(), publicId, target, key, hash);
        if (reservation.replay()) return view(requireCase(publicId));
        try {
            RiskCaseRepository.StoredRiskCase stored = requireCase(publicId);
            String redactedDetail = classificationService.redact(
                    required(detail, "处置说明"), "risk-human-event").redactedText();
            stored.riskCase().transitionByActor(target, expectedVersion, RiskActorKind.USER,
                    String.valueOf(actor.userId()), redactedDetail);
            stored.assign(actor.userId(), dueAt);
            repository.save(stored, expectedVersion, reservation.recordId());
            return view(stored);
        } catch (RuntimeException failure) {
            repository.releaseTransition(reservation.recordId());
            throw failure;
        }
    }

    @Transactional
    public CaseView transition(
            String publicId,
            RiskCaseState target,
            int expectedVersion,
            BusinessExecutionActor actor,
            Set<String> currentPermissions,
            String detail,
            String idempotencyKey,
            String requestHash,
            Instant dueAt,
            Supplier<TransitionAuthorization> freshAuthorization) {
        if (actor == null || currentPermissions == null
                || !currentPermissions.containsAll(Set.of("ai:risk:read", "ai:risk:manage"))) {
            throw new SecurityException("缺少风险读取或处置权限");
        }
        if (target != RiskCaseState.ACKNOWLEDGED
                && target != RiskCaseState.RESOLVED
                && target != RiskCaseState.DISMISSED) {
            throw new IllegalArgumentException("人工风险目标状态不合法");
        }
        String key = required(idempotencyKey, "Idempotency-Key");
        String hash = required(requestHash, "requestHash");
        String redactedDetail = classificationService.redact(
                required(detail, "处置说明"), "risk-human-event").redactedText();

        TransitionAuthorization authorization = freshAuthorization == null ? null : freshAuthorization.get();
        RiskCaseRepository.StoredRiskCase stored = requireCase(publicId);
        if (!canTransition(actor, authorization, stored)) throw new RiskAccessDeniedException();

        RiskCaseRepository.TransitionReservation reservation = repository.reserveTransition(
                actor.userId(), publicId, target, key, hash);
        if (reservation.replay()) return view(stored);
        try {
            stored.riskCase().transitionByActor(target, expectedVersion, RiskActorKind.USER,
                    String.valueOf(actor.userId()), redactedDetail);
            stored.assign(actor.userId(), dueAt);
            repository.save(stored, expectedVersion, reservation.recordId());
            return view(stored);
        } catch (RuntimeException failure) {
            repository.releaseTransition(reservation.recordId());
            throw failure;
        }
    }

    public CaseView get(String publicId) {
        return view(requireCase(publicId));
    }

    public CaseView get(String publicId, Set<String> currentPermissions) {
        requireReadPermission(currentPermissions);
        return get(publicId);
    }

    public CaseView get(String publicId, RiskScanScope scope) {
        CaseView value = get(publicId);
        if (!canAccess(value, scope)) throw new IllegalArgumentException("风险案例不存在");
        return value;
    }

    public PageResult list(RiskCaseState state, int page, int pageSize) {
        return list(state, Set.of(), page, pageSize);
    }

    public PageResult list(RiskCaseState state, Set<String> riskTypes, int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("分页参数不合法");
        Set<String> filters = riskTypes == null ? Set.of() : Set.copyOf(riskTypes);
        if (!ALLOWED_RISK_TYPES.containsAll(filters)) throw new IllegalArgumentException("风险类型过滤不合法");
        List<CaseView> records = repository.findByCriteria(state, filters, (page - 1) * pageSize, pageSize)
                .stream().map(this::view).toList();
        return new PageResult(records, repository.countByCriteria(state, filters), page, pageSize);
    }

    public PageResult list(
            RiskCaseState state,
            Set<String> riskTypes,
            int page,
            int pageSize,
            Set<String> currentPermissions) {
        requireReadPermission(currentPermissions);
        return list(state, riskTypes, page, pageSize);
    }

    /**
     * 先按确定性条件读取，再按当前底层资源范围过滤；不把不可见案例计入 total。
     * 首期每批最多 100，避免在 SQL 中拼接动态 scope 表达式。
     */
    public PageResult listAuthorized(
            RiskCaseState state,
            Set<String> riskTypes,
            int page,
            int pageSize,
            RiskScanScope scope) {
        return listAuthorized(state, riskTypes, page, pageSize, null, scope);
    }

    public PageResult listAuthorized(
            RiskCaseState state,
            Set<String> riskTypes,
            int page,
            int pageSize,
            String keyword,
            RiskScanScope scope) {
        if (scope == null || page < 1 || pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("风险授权分页参数不合法");
        }
        Set<String> filters = riskTypes == null ? Set.of() : Set.copyOf(riskTypes);
        if (!ALLOWED_RISK_TYPES.containsAll(filters)) throw new IllegalArgumentException("风险类型过滤不合法");
        String normalizedKeyword = normalizeKeyword(keyword);
        long rawTotal = repository.countByCriteria(state, filters);
        List<CaseView> visible = new java.util.ArrayList<>();
        for (int offset = 0; offset < rawTotal; offset += 100) {
            repository.findByCriteria(state, filters, offset, (int) Math.min(100, rawTotal - offset)).stream()
                    .map(this::view).filter(value -> canAccess(value, scope))
                    .filter(value -> matchesKeyword(value, normalizedKeyword)).forEach(visible::add);
        }
        int from = Math.min((page - 1) * pageSize, visible.size());
        int to = Math.min(from + pageSize, visible.size());
        return new PageResult(visible.subList(from, to), visible.size(), page, pageSize);
    }

    private void requireReadPermission(Set<String> currentPermissions) {
        if (currentPermissions == null || !currentPermissions.contains("ai:risk:read")) {
            throw new SecurityException("缺少风险读取权限");
        }
    }

    private RiskCaseRepository.StoredRiskCase requireCase(String publicId) {
        return repository.findByPublicId(required(publicId, "riskCasePublicId"))
                .orElseThrow(() -> new IllegalArgumentException("风险案例不存在"));
    }

    private boolean canTransition(
            BusinessExecutionActor actor,
            TransitionAuthorization authorization,
            RiskCaseRepository.StoredRiskCase stored) {
        if (authorization == null || !authorization.enabled()
                || authorization.actorUserId() != actor.userId()
                || !authorization.permissionCodes().containsAll(Set.of("ai:risk:read", "ai:risk:manage"))
                || authorization.scope() == null
                || authorization.scope().effectiveSubjectUserId() != actor.userId()) {
            return false;
        }
        return canAccess(view(stored), authorization.scope());
    }

    private CaseView view(RiskCaseRepository.StoredRiskCase stored) {
        RiskCase value = stored.riskCase();
        return new CaseView(value.publicId(), value.riskType(), value.subjectToken(), stored.severity(),
                value.state(), value.signalPolicyVersion(), value.version(), stored.signalEvidence(),
                stored.businessSnapshot(), stored.explanationEvidence(), value.events(),
                stored.subjectType(), stored.subjectResourceId(), stored.assigneeUserId(), stored.dueAt());
    }

    private boolean canAccess(CaseView value, RiskScanScope scope) {
        if (scope == null || value.subjectResourceId() == null) return false;
        return switch (value.riskType()) {
            case "repair-backlog", "repeat-repair" -> scope.canReadRepair(value.subjectResourceId());
            case "resource-checkin-inconsistency" -> scope.canReadDormitory(value.subjectResourceId())
                    && scope.permissionCodes().contains("checkin:read")
                    && scope.allCheckInApplications();
            case "failed-hygiene-check" -> scope.canReadDormitory(value.subjectResourceId())
                    && scope.permissionCodes().contains("hygiene:read");
            case "long-pending-operation" -> scope.canReadCheckInApplication(value.subjectResourceId());
            case "overdue-payment" -> scope.canReadPayment(value.subjectResourceId());
            default -> false;
        };
    }

    private String normalizeKeyword(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.length() > 100 || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("风险关键词不合法");
        }
        return normalized;
    }

    private boolean matchesKeyword(CaseView value, String keyword) {
        if (keyword.isEmpty()) return true;
        String searchable = String.join(" ", value.subjectToken(), displayType(value.riskType()),
                evidenceSummary(value)).toLowerCase(java.util.Locale.ROOT);
        return searchable.contains(keyword);
    }

    private String displayType(String riskType) {
        return switch (riskType) {
            case "repair-backlog", "repeat-repair" -> "维修风险";
            case "resource-checkin-inconsistency", "long-pending-operation" -> "入住风险";
            case "failed-hygiene-check" -> "卫生风险";
            case "overdue-payment" -> "欠费风险";
            default -> riskType;
        };
    }

    private String evidenceSummary(CaseView value) {
        Map<String, Object> evidence = value.evidence();
        return switch (value.riskType()) {
            case "repair-backlog" -> "维修待办已持续 " + number(evidence, "ageHours")
                    + " 小时，规则阈值为 " + number(evidence, "ruleThreshold") + " 小时";
            case "repeat-repair" -> "统计窗口内重复报修 " + number(evidence, "count")
                    + " 次，规则阈值为 " + number(evidence, "ruleThreshold") + " 次";
            case "resource-checkin-inconsistency" -> "资源与入住汇总存在确定性口径差异，请人工复核";
            case "long-pending-operation" -> "入住运营待办已持续 " + number(evidence, "ageHours")
                    + " 小时，规则阈值为 " + number(evidence, "ruleThreshold") + " 小时";
            case "failed-hygiene-check" -> "卫生检查不合格 " + number(evidence, "count")
                    + " 次，最近一次评分 " + number(evidence, "score") + " 分";
            case "overdue-payment" -> "账单已超过截止日，仍处于" + String.valueOf(evidence.get("status")) + "状态";
            default -> "确定性运营规则命中，请人工复核";
        };
    }

    private long number(Map<String, Object> evidence, String key) {
        Object value = evidence.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 512) {
            throw new IllegalArgumentException(field + " 不合法");
        }
        return value;
    }

    public record CaseView(
            String publicId,
            String riskType,
            String subjectToken,
            String severity,
            RiskCaseState state,
            String signalPolicyVersion,
            int version,
            RiskSignalEvidence signalEvidence,
            RiskBusinessSnapshot businessSnapshot,
            RiskExplanationEvidence explanationEvidence,
            List<RiskCase.RiskCaseEvent> events,
            String subjectType,
            Long subjectResourceId,
            Long assigneeUserId,
            Instant dueAt) {
        public CaseView {
            events = List.copyOf(events);
        }

        public Map<String, Object> evidence() { return signalEvidence.facts(); }
        public Instant asOf() { return signalEvidence.observedAt(); }
        public String explanation() { return explanationEvidence.text(); }
        public boolean explanationDegraded() { return explanationEvidence.degraded(); }
    }

    public record PageResult(List<CaseView> records, long total, int page, int pageSize) {
        public PageResult {
            records = List.copyOf(records);
        }
    }

    public record TransitionAuthorization(
            long actorUserId,
            boolean enabled,
            Set<String> permissionCodes,
            RiskScanScope scope) {
        public TransitionAuthorization {
            permissionCodes = permissionCodes == null ? Set.of() : Set.copyOf(permissionCodes);
        }
    }

    public static final class RiskAccessDeniedException extends RuntimeException {
        public RiskAccessDeniedException() {
            super("风险案例不可见");
        }
    }

}
