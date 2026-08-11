package com.example.dormitory.ai.security;

import com.example.dormitory.ai.port.KnownPiiDictionaryPort;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PiiClassificationServiceTest {

    private final PiiRedactionService core = new PiiRedactionService(
            "classification-test-domain-key-32-bytes".getBytes(StandardCharsets.UTF_8), "v1");

    @Test
    void tokenizesKnownUnlabelledMinorityNamesLongestFirstAndSeparatesPurposeDomains() {
        KnownPiiDictionaryPort dictionary = () -> List.of(
                person("阿依"), person("阿依古丽"), person("阿依古丽·买买提"));
        PiiClassificationService service = new PiiClassificationService(core, dictionary);

        String input = "今日由阿依古丽·买买提值班。";
        var first = service.redact(input, "assistant-input");
        var samePurpose = service.redact(input, "assistant-input");
        var otherPurpose = service.redact(input, "knowledge-ingestion");

        assertEquals(DataClassification.L2, first.classification());
        assertFalse(first.redactedText().contains("阿依"));
        assertEquals(1, occurrences(first.redactedText(), "[PERSON_NAME:"));
        assertEquals(first.redactedText(), samePurpose.redactedText());
        assertFalse(first.redactedText().equals(otherPurpose.redactedText()));
    }

    @Test
    void preservesExistingTokensWithoutTokenizingThemAgain() {
        PiiClassificationService service = new PiiClassificationService(core,
                () -> List.of(person("阿依古丽")));

        var first = service.redact("阿依古丽今日值班。", "assistant-input");
        var second = service.redact(first.redactedText(), "assistant-input");

        assertEquals(first.redactedText(), second.redactedText());
        assertEquals(DataClassification.L2, second.classification());
        assertEquals(1, occurrences(second.redactedText(), "[PERSON_NAME:"));
    }

    @Test
    void leavesOrdinaryPolicyTextUnchangedWhenNoKnownPiiMatches() {
        PiiClassificationService service = new PiiClassificationService(core,
                () -> List.of(person("阿依古丽")));
        String policy = "宿舍维修制度要求在两个工作日内完成一般维修。";

        var result = service.redact(policy, "knowledge-ingestion");

        assertEquals(policy, result.redactedText());
        assertEquals(DataClassification.L1, result.classification());
    }

    @Test
    void ignoresNonPersonDictionaryCandidatesWithoutDisablingKnownPiiProtection() {
        PiiClassificationService service = new PiiClassificationService(core,
                () -> List.of(person("ai-admin-4b9f8d31"), person("张三")));

        var ordinary = service.redact("请说明今日宿舍运行摘要", "assistant-input");
        var personal = service.redact("今日由张三值班。", "assistant-input");

        assertEquals(DataClassification.L1, ordinary.classification());
        assertEquals("请说明今日宿舍运行摘要", ordinary.redactedText());
        assertEquals(DataClassification.L2, personal.classification());
        assertFalse(personal.redactedText().contains("张三"));
        assertTrue(personal.redactedText().contains("[PERSON_NAME:"));
    }

    @Test
    void fixedL3RulesRunBeforeKnownNameTokenization() {
        PiiClassificationService service = new PiiClassificationService(core,
                () -> List.of(person("张三")));

        for (String text : List.of("张三患有抑郁症，需要持续服药。", "张三被处以记过处分。")) {
            assertThrows(SensitiveDataBlockedException.class,
                    () -> service.redact(text, "assistant-input"), text);
        }
    }

    @Test
    void tokenizesBusinessContractStudentNumbersAndFormattedPhones() {
        PiiClassificationService service = new PiiClassificationService(core, () -> List.of(
                new KnownPiiDictionaryPort.KnownPiiValue(
                        KnownPiiDictionaryPort.PiiKind.STUDENT_NO, "IMPORT-001"),
                new KnownPiiDictionaryPort.KnownPiiValue(
                        KnownPiiDictionaryPort.PiiKind.PHONE, "+86 138-1234-5678")));

        var result = service.redact("请联系 +86 138-1234-5678，学号 IMPORT-001。", "assistant-input");

        assertEquals(DataClassification.L2, result.classification());
        assertFalse(result.redactedText().contains("IMPORT-001"));
        assertFalse(result.redactedText().contains("138-1234"));
        assertTrue(result.redactedText().contains("[STUDENT_NO:"));
        assertTrue(result.redactedText().contains("[PHONE:"));
    }

    @Test
    void failsClosedWithoutLeakingDictionaryFailureDetails() {
        PiiClassificationService service = new PiiClassificationService(core,
                () -> { throw new IllegalStateException("数据库错误包含原姓名阿依古丽"); });

        SensitiveDataBlockedException blocked = assertThrows(SensitiveDataBlockedException.class,
                () -> service.redact("普通文本", "assistant-input"));

        assertTrue(blocked.getMessage().contains("分类控制不可用"));
        assertFalse(blocked.getMessage().contains("阿依古丽"));
        assertEquals(null, blocked.getCause());
    }

    @Test
    void refreshesOnceForConcurrentReadersAndNeverUsesExpiredSnapshotAfterRefreshFailure() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-12T00:00:00Z"));
        AtomicInteger loads = new AtomicInteger();
        AtomicBoolean fail = new AtomicBoolean();
        KnownPiiDictionaryPort dictionary = () -> {
            loads.incrementAndGet();
            if (fail.get()) throw new IllegalStateException("dictionary unavailable");
            return List.of(person("阿依古丽"));
        };
        PiiClassificationService service = new PiiClassificationService(
                core, dictionary, clock, Duration.ofSeconds(30));
        int readers = 8;
        CountDownLatch ready = new CountDownLatch(readers);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(readers)) {
            var futures = java.util.stream.IntStream.range(0, readers)
                    .mapToObj(index -> executor.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                        return service.redact("阿依古丽今日值班。", "assistant-input").redactedText();
                    })).toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (var future : futures) assertFalse(future.get(5, TimeUnit.SECONDS).contains("阿依古丽"));
        }
        assertEquals(1, loads.get(), "并发首刷只能加载一次字典");

        service.redact("阿依古丽今日值班。", "assistant-input");
        assertEquals(1, loads.get(), "TTL 内复用进程内快照");

        clock.advance(Duration.ofSeconds(31));
        fail.set(true);
        assertThrows(SensitiveDataBlockedException.class,
                () -> service.redact("阿依古丽今日值班。", "assistant-input"));
        assertEquals(2, loads.get(), "过期后必须刷新，刷新失败不得继续使用旧快照");
    }

    @Test
    void constructorRejectsNullDependenciesAndTtlOutsideOneNanosecondToSixtySeconds() {
        KnownPiiDictionaryPort dictionary = List::of;
        Clock clock = Clock.fixed(Instant.parse("2026-07-13T00:00:00Z"), ZoneOffset.UTC);

        assertThrows(NullPointerException.class,
                () -> new PiiClassificationService(null, dictionary, clock, Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class,
                () -> new PiiClassificationService(core, null, clock, Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class,
                () -> new PiiClassificationService(core, dictionary, null, Duration.ofSeconds(1)));
        for (Duration ttl : java.util.Arrays.asList(
                null, Duration.ZERO, Duration.ofNanos(-1), Duration.ofSeconds(60).plusNanos(1))) {
            assertThrows(IllegalArgumentException.class,
                    () -> new PiiClassificationService(core, dictionary, clock, ttl));
        }
        var minimum = new PiiClassificationService(core, dictionary, clock, Duration.ofNanos(1));
        var maximum = new PiiClassificationService(core, dictionary, clock, Duration.ofSeconds(60));
        assertEquals(DataClassification.L1, minimum.redact("普通文本", "assistant").classification());
        assertEquals(DataClassification.L1, maximum.redact("普通文本", "assistant").classification());
    }

    @Test
    void invalidDictionarySnapshotsFailClosedWithoutReturningStaleOrPartialData() {
        KnownPiiDictionaryPort.KnownPiiValue missingKind = mock(KnownPiiDictionaryPort.KnownPiiValue.class);
        when(missingKind.kind()).thenReturn(null);
        List<KnownPiiDictionaryPort> invalidDictionaries = List.of(
                () -> null,
                () -> java.util.Arrays.asList((KnownPiiDictionaryPort.KnownPiiValue) null),
                () -> List.of(missingKind),
                () -> List.of(new KnownPiiDictionaryPort.KnownPiiValue(
                        KnownPiiDictionaryPort.PiiKind.STUDENT_NO, "bad value!")),
                () -> List.of(new KnownPiiDictionaryPort.KnownPiiValue(
                        KnownPiiDictionaryPort.PiiKind.STUDENT_NO, "AB[12")),
                () -> List.of(new KnownPiiDictionaryPort.KnownPiiValue(
                        KnownPiiDictionaryPort.PiiKind.PHONE, "bad")),
                () -> List.of(new KnownPiiDictionaryPort.KnownPiiValue(
                        KnownPiiDictionaryPort.PiiKind.PHONE, "++++++")));

        for (KnownPiiDictionaryPort dictionary : invalidDictionaries) {
            SensitiveDataBlockedException error = assertThrows(SensitiveDataBlockedException.class,
                    () -> new PiiClassificationService(core, dictionary)
                            .redact("普通文本", "assistant-input"));
            assertEquals("AI_L3_DATA_BLOCKED", error.errorCode());
            assertEquals(null, error.getCause());
        }
    }

    @Test
    void malformedPersonCandidatesAreIgnoredWhileValidNamesRemainProtected() {
        String control = "张\n三";
        PiiClassificationService service = new PiiClassificationService(core, () -> List.of(
                person("A"),
                person("张".repeat(65)),
                person("维修制度"),
                person("张[三"),
                person(control),
                person("张三")));

        var result = service.redact("普通文本由张三确认。", "assistant-input");

        assertEquals(DataClassification.L2, result.classification());
        assertFalse(result.redactedText().contains("张三"));
        assertTrue(result.redactedText().contains("[PERSON_NAME:"));
    }

    private KnownPiiDictionaryPort.KnownPiiValue person(String value) {
        return new KnownPiiDictionaryPort.KnownPiiValue(
                KnownPiiDictionaryPort.PiiKind.PERSON_NAME, value);
    }

    private int occurrences(String value, String term) {
        return (value.length() - value.replace(term, "").length()) / term.length();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
