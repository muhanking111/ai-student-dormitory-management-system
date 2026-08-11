package com.example.dormitory.ai.security;

import com.example.dormitory.ai.port.KnownPiiDictionaryPort;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * 模型前 PII 分类边界。固定规则由 {@link PiiRedactionService} 执行，内部已知姓名由短 TTL 字典补充；
 * 字典过期或刷新失败时不使用陈旧快照继续放行。
 */
public final class PiiClassificationService {

    public static final Duration DEFAULT_DICTIONARY_TTL = Duration.ofSeconds(30);
    private static final Duration MAXIMUM_DICTIONARY_TTL = Duration.ofSeconds(60);
    private static final java.util.Set<String> NON_PERSON_TERMS = java.util.Set.of(
            "维修制度", "宿舍管理", "系统提示", "忽略指令", "安全规定", "全体学生");

    private final PiiRedactionService core;
    private final KnownPiiDictionaryPort dictionary;
    private final Clock clock;
    private final Duration ttl;
    private final Object refreshMonitor = new Object();
    private volatile CachedDictionary cachedDictionary;

    public PiiClassificationService(
            PiiRedactionService core,
            KnownPiiDictionaryPort dictionary) {
        this(core, dictionary, Clock.systemUTC(), DEFAULT_DICTIONARY_TTL);
    }

    public PiiClassificationService(
            PiiRedactionService core,
            KnownPiiDictionaryPort dictionary,
            Clock clock,
            Duration ttl) {
        this.core = Objects.requireNonNull(core);
        this.dictionary = Objects.requireNonNull(dictionary);
        this.clock = Objects.requireNonNull(clock);
        if (ttl == null || ttl.isZero() || ttl.isNegative() || ttl.compareTo(MAXIMUM_DICTIONARY_TTL) > 0) {
            throw new IllegalArgumentException("PII 字典 TTL 必须在 1 纳秒到 60 秒之间");
        }
        this.ttl = ttl;
    }

    public PiiRedactionService.RedactionResult redact(String input, String purpose) {
        return redact(input, purpose, DataClassification.L1);
    }

    public PiiRedactionService.RedactionResult redact(
            String input,
            String purpose,
            DataClassification minimumClassification) {
        // 固定 L3 规则必须先在原始规范化文本上执行，避免姓名 token 化破坏健康/纪律句式。
        PiiRedactionService.RedactionResult fixed = core.redact(input, purpose, minimumClassification);
        List<KnownPiiDictionaryPort.KnownPiiValue> dictionary = dictionarySnapshot();
        List<String> rules = new ArrayList<>(fixed.matchedRules());
        String redacted = fixed.redactedText();
        boolean containsKnownOrExistingToken = false;
        for (KnownPiiDictionaryPort.PiiKind kind : KnownPiiDictionaryPort.PiiKind.values()) {
            List<String> values = dictionary.stream().filter(value -> value.kind() == kind)
                    .map(KnownPiiDictionaryPort.KnownPiiValue::value).toList();
            PiiRedactionService.KnownNameTokenizationResult known =
                    core.tokenizeKnownValues(redacted, purpose, kind.name(), values);
            redacted = known.redactedText();
            for (int index = 0; index < known.tokenizedCount(); index++) rules.add(kind.name());
            containsKnownOrExistingToken |= known.tokenizedCount() > 0 || known.preservedTokenCount() > 0;
        }
        DataClassification classification = containsKnownOrExistingToken
                && fixed.classification().ordinal() < DataClassification.L2.ordinal()
                ? DataClassification.L2 : fixed.classification();
        return new PiiRedactionService.RedactionResult(
                redacted, classification, List.copyOf(rules),
                fixed.policyVersion(), fixed.keyVersion(), core.irreversibleHash(input, purpose));
    }

    private List<KnownPiiDictionaryPort.KnownPiiValue> dictionarySnapshot() {
        Instant now = clock.instant();
        CachedDictionary current = cachedDictionary;
        if (current != null && now.isBefore(current.expiresAt())) return current.values();
        synchronized (refreshMonitor) {
            now = clock.instant();
            current = cachedDictionary;
            if (current != null && now.isBefore(current.expiresAt())) return current.values();
            try {
                List<KnownPiiDictionaryPort.KnownPiiValue> loaded = dictionary.loadKnownPii();
                List<KnownPiiDictionaryPort.KnownPiiValue> validated = validate(loaded);
                CachedDictionary refreshed = new CachedDictionary(validated, now.plus(ttl));
                cachedDictionary = refreshed;
                return refreshed.values();
            } catch (RuntimeException failure) {
                // 不保留 cause：下游异常日志不能带出 JDBC/字典实现可能包含的原始姓名。
                throw new SensitiveDataBlockedException("PII 分类控制不可用，已阻断 AI 数据处理");
            }
        }
    }

    private List<KnownPiiDictionaryPort.KnownPiiValue> validate(
            List<KnownPiiDictionaryPort.KnownPiiValue> loaded) {
        if (loaded == null) throw new IllegalStateException("PII 字典返回空快照");
        java.util.EnumMap<KnownPiiDictionaryPort.PiiKind, LinkedHashSet<String>> values =
                new java.util.EnumMap<>(KnownPiiDictionaryPort.PiiKind.class);
        for (KnownPiiDictionaryPort.PiiKind kind : KnownPiiDictionaryPort.PiiKind.values()) {
            values.put(kind, new LinkedHashSet<>());
        }
        for (KnownPiiDictionaryPort.KnownPiiValue value : loaded) {
            if (value == null || value.kind() == null) throw new IllegalStateException("PII 字典包含空条目");
            String normalized = Normalizer.normalize(value.value().trim(), Normalizer.Form.NFKC);
            if (!validDictionaryValue(value.kind(), normalized)) {
                // sys_user.display_name 允许机器账号式展示名；这类值不具备姓名词法，不能让任意
                // 新建账号把整个 AI 分类边界变成拒绝服务。学生学号和电话来自强业务合同，异常仍阻断。
                if (value.kind() == KnownPiiDictionaryPort.PiiKind.PERSON_NAME) continue;
                throw new IllegalStateException("PII 字典条目不满足本地分类约束");
            }
            values.get(value.kind()).add(normalized);
        }
        java.util.List<KnownPiiDictionaryPort.KnownPiiValue> validated = new java.util.ArrayList<>();
        values.forEach((kind, entries) -> entries.stream()
                .sorted(java.util.Comparator.comparingInt((String entry) -> entry.codePointCount(0, entry.length()))
                        .reversed().thenComparing(java.util.Comparator.naturalOrder()))
                .map(entry -> new KnownPiiDictionaryPort.KnownPiiValue(kind, entry))
                .forEach(validated::add));
        return List.copyOf(validated);
    }

    private boolean validDictionaryValue(KnownPiiDictionaryPort.PiiKind kind, String value) {
        int length = value.codePointCount(0, value.length());
        if (value.chars().anyMatch(Character::isISOControl)
                || value.indexOf('[') >= 0 || value.indexOf(']') >= 0) return false;
        return switch (kind) {
            case PERSON_NAME -> length >= 2 && length <= 64
                    && value.matches("[\\p{L}·.'’ -]{2,64}") && !NON_PERSON_TERMS.contains(value);
            case STUDENT_NO -> value.matches("[A-Za-z0-9-]{2,32}");
            case PHONE -> value.matches("[0-9+ -]{6,32}")
                    && value.chars().filter(Character::isDigit).count() >= 6;
        };
    }

    private record CachedDictionary(
            List<KnownPiiDictionaryPort.KnownPiiValue> values,
            Instant expiresAt) {
        private CachedDictionary {
            values = List.copyOf(values);
            Objects.requireNonNull(expiresAt);
        }
    }
}
