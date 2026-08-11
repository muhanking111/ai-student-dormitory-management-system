package com.example.dormitory.ai.infrastructure.risk;

import com.example.dormitory.ai.port.OperationalRiskReadPort;
import com.example.dormitory.ai.risk.RiskSignal;
import com.example.dormitory.ai.risk.RiskSignalProvider;
import com.example.dormitory.ai.risk.RiskScanScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 只读、确定性的运营风险规则。该 provider 不依赖 ModelGateway，也不把业务原始 PII 写入信号。
 */
@Component
@ConditionalOnProperty(prefix = "dormitory.ai.capabilities", name = "risk", havingValue = "true")
public class JdbcOperationalRiskSignalProvider implements RiskSignalProvider {

    private final OperationalRiskReadPort facts;
    private final OperationalRiskThresholds thresholds;
    private final RiskSubjectTokenizer tokenizer;
    private final Clock clock;
    private final String policyVersion;

    @Autowired
    public JdbcOperationalRiskSignalProvider(
            OperationalRiskReadPort facts,
            OperationalRiskThresholds thresholds,
            RiskSubjectTokenizer tokenizer) {
        this(facts, thresholds, tokenizer, Clock.systemUTC());
    }

    public JdbcOperationalRiskSignalProvider(
            OperationalRiskReadPort facts,
            OperationalRiskThresholds thresholds,
            RiskSubjectTokenizer tokenizer,
            Clock clock) {
        this.facts = java.util.Objects.requireNonNull(facts);
        this.thresholds = java.util.Objects.requireNonNull(thresholds);
        this.tokenizer = java.util.Objects.requireNonNull(tokenizer);
        this.clock = java.util.Objects.requireNonNull(clock);
        thresholds.validate();
        this.policyVersion = "operational-v1-" + shortHash(thresholds.getRepairBacklogHours() + "|"
                + thresholds.getRepeatRepairCount() + "|" + thresholds.getRepeatRepairWindowDays() + "|"
                + thresholds.getLongPendingHours() + "|failed-hygiene-check|overdue-payment");
    }

    @Override
    public String id() {
        return "operational-risk";
    }

    @Override
    public String policyVersion() {
        return policyVersion;
    }

    @Override
    public List<RiskSignal> evaluate(RiskScanScope scope) {
        if (scope == null) throw new IllegalArgumentException("风险规则必须携带 actor scope");
        Instant now = clock.instant();
        List<RiskSignal> signals = new ArrayList<>();
        signals.addAll(repairBacklog(now, scope));
        signals.addAll(repeatRepairs(now, scope));
        signals.addAll(resourceConsistency(now, scope));
        signals.addAll(failedHygiene(now, scope));
        signals.addAll(longPending(now, scope));
        signals.addAll(overduePayments(now, scope));
        return signals.stream().sorted(Comparator.comparing(RiskSignal::riskType)
                .thenComparing(RiskSignal::subjectToken)).toList();
    }

    private List<RiskSignal> repairBacklog(Instant now, RiskScanScope scope) {
        return facts.incompleteRepairs(scope).stream()
                .map(fact -> Map.entry(fact, ageHours(fact.createdAt(), now)))
                .filter(entry -> entry.getValue() >= thresholds.getRepairBacklogHours())
                .map(entry -> new RiskSignal("repair-backlog", "REPAIR_ORDER", entry.getKey().id(),
                        tokenizer.tokenize("repair-order", Long.toString(entry.getKey().id())),
                        entry.getValue() >= thresholds.getRepairBacklogHours() * 2L ? "CRITICAL" : "HIGH",
                        policyVersion,
                        Map.of("ageHours", entry.getValue(), "status", entry.getKey().status(),
                                "ruleThreshold", thresholds.getRepairBacklogHours(), "asOf", now.toString()), now))
                .toList();
    }

    private List<RiskSignal> repeatRepairs(Instant now, RiskScanScope scope) {
        Instant since = now.minus(Duration.ofDays(thresholds.getRepeatRepairWindowDays()));
        List<OperationalRiskReadPort.RepeatRepairFact> scopedFacts = facts.repairsCreatedBetween(scope, since, now);
        Map<String, List<OperationalRiskReadPort.RepeatRepairFact>> groups = new HashMap<>();
        for (OperationalRiskReadPort.RepeatRepairFact fact : scopedFacts.stream()
                .sorted(Comparator.comparing(OperationalRiskReadPort.RepeatRepairFact::createdAt)
                        .thenComparingLong(OperationalRiskReadPort.RepeatRepairFact::id))
                .toList()) {
            groups.computeIfAbsent(fact.location() + "\u001f" + fact.type(),
                ignored -> new ArrayList<>()).add(fact);
        }
        List<RiskSignal> result = new ArrayList<>();
        for (Map.Entry<String, List<OperationalRiskReadPort.RepeatRepairFact>> entry : groups.entrySet()) {
            List<OperationalRiskReadPort.RepeatRepairFact> group = entry.getValue();
            if (group.size() < thresholds.getRepeatRepairCount()) continue;
            OperationalRiskReadPort.RepeatRepairFact latest = group.getLast();
            result.add(new RiskSignal("repeat-repair", "REPAIR_ORDER", latest.id(),
                    tokenizer.tokenize("repair-location-type", entry.getKey()),
                    group.size() >= thresholds.getRepeatRepairCount() * 2 ? "HIGH" : "MEDIUM", policyVersion,
                    Map.of("count", group.size(), "windowDays", thresholds.getRepeatRepairWindowDays(),
                            "ruleThreshold", thresholds.getRepeatRepairCount(),
                            "firstSeenAt", group.getFirst().createdAt().toString(),
                            "lastSeenAt", latest.createdAt().toString(), "asOf", now.toString()), now));
        }
        return result;
    }

    private List<RiskSignal> resourceConsistency(Instant now, RiskScanScope scope) {
        return facts.dormitoryConsistency(scope).stream()
                .filter(fact -> fact.beds() != fact.actualBeds() || fact.occupied() != fact.occupiedBeds()
                        || fact.vacant() != fact.actualBeds() - fact.occupiedBeds()
                        || fact.occupiedBeds() != fact.activeCheckIns())
                .map(fact -> new RiskSignal("resource-checkin-inconsistency", "DORMITORY", fact.id(),
                        tokenizer.tokenize("dormitory", Long.toString(fact.id())), "HIGH", policyVersion,
                        Map.of("configuredBeds", fact.beds(), "actualBeds", fact.actualBeds(),
                                "configuredOccupied", fact.occupied(), "occupiedBeds", fact.occupiedBeds(),
                                "activeCheckIns", fact.activeCheckIns(), "configuredVacant", fact.vacant(),
                                "asOf", now.toString()), now)).toList();
    }

    private List<RiskSignal> failedHygiene(Instant now, RiskScanScope scope) {
        Map<Long, List<OperationalRiskReadPort.FailedHygieneFact>> byDormitory = new HashMap<>();
        for (OperationalRiskReadPort.FailedHygieneFact fact : facts.failedHygieneChecks(scope)) {
            byDormitory.computeIfAbsent(fact.dormitoryId(), ignored -> new ArrayList<>()).add(fact);
        }
        List<RiskSignal> result = new ArrayList<>();
        for (Map.Entry<Long, List<OperationalRiskReadPort.FailedHygieneFact>> entry : byDormitory.entrySet()) {
            List<OperationalRiskReadPort.FailedHygieneFact> failures = entry.getValue().stream()
                    .sorted(Comparator.comparing(OperationalRiskReadPort.FailedHygieneFact::inspectedOn))
                    .toList();
            OperationalRiskReadPort.FailedHygieneFact latest = failures.getLast();
            result.add(new RiskSignal("failed-hygiene-check", "DORMITORY", entry.getKey(),
                    tokenizer.tokenize("dormitory", Long.toString(entry.getKey())),
                    latest.score() < 60 ? "CRITICAL" : "HIGH", policyVersion,
                    Map.of("count", failures.size(), "result", latest.result(), "score", latest.score(),
                            "inspectedOn", latest.inspectedOn(), "ruleThreshold", 1,
                            "asOf", now.toString()), now));
        }
        return result;
    }

    private List<RiskSignal> longPending(Instant now, RiskScanScope scope) {
        return facts.pendingCheckInApplications(scope).stream()
                .map(fact -> Map.entry(fact, ageHours(fact.appliedAt(), now)))
                .filter(entry -> entry.getValue() >= thresholds.getLongPendingHours())
                .map(entry -> new RiskSignal("long-pending-operation", "CHECK_IN_APPLICATION", entry.getKey().id(),
                        tokenizer.tokenize("check-in-application", Long.toString(entry.getKey().id())),
                        entry.getValue() >= thresholds.getLongPendingHours() * 2L ? "HIGH" : "MEDIUM",
                        policyVersion,
                        Map.of("ageHours", entry.getValue(), "status", entry.getKey().status(),
                                "ruleThreshold", thresholds.getLongPendingHours(), "asOf", now.toString()), now))
                .toList();
    }

    private List<RiskSignal> overduePayments(Instant now, RiskScanScope scope) {
        List<RiskSignal> result = new ArrayList<>();
        for (OperationalRiskReadPort.OverduePaymentFact fact : facts.overduePayments(scope)) {
            BigDecimal outstanding = fact.amountDue().subtract(fact.amountPaid());
            long ageDays = overdueAgeDays(fact.deadline(), now);
            if (outstanding.signum() <= 0 || ageDays < 1) continue;
            String severity = fact.amountPaid().signum() == 0 ? "CRITICAL" : "HIGH";
            result.add(new RiskSignal("overdue-payment", "PAYMENT", fact.id(),
                    tokenizer.tokenize("payment", Long.toString(fact.id())), severity, policyVersion,
                    Map.of("status", fact.status(), "deadline", fact.deadline(),
                            "ageDays", ageDays, "amountDue", fact.amountDue(), "amountPaid", fact.amountPaid(),
                            "asOf", now.toString()), now));
        }
        return result;
    }

    private long overdueAgeDays(String deadline, Instant now) {
        try {
            return ChronoUnit.DAYS.between(LocalDate.parse(deadline), LocalDate.ofInstant(now, ZoneOffset.UTC));
        } catch (DateTimeParseException invalid) {
            return 0;
        }
    }

    private long ageHours(Instant then, Instant now) {
        if (then.isAfter(now)) return 0;
        return Duration.between(then, now).toHours();
    }

    private static String shortHash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 10);
        } catch (Exception exception) {
            throw new IllegalStateException("风险规则版本哈希不可用", exception);
        }
    }

}
