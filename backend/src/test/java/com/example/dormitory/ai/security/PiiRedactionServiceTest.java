package com.example.dormitory.ai.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiiRedactionServiceTest {

    private final PiiRedactionService service = new PiiRedactionService(
            "test-only-domain-separated-key".getBytes(StandardCharsets.UTF_8), "v1");

    @Test
    void removesPersonalIdentifiersAndUsesPurposeSeparatedTokens() {
        PiiRedactionService.RedactionResult result = service.redact(
                "维修联系人 13812345678，学号 2026123456，位置 2号楼-305宿舍。",
                "repair-triage");

        assertEquals(DataClassification.L2, result.classification());
        assertFalse(result.redactedText().contains("13812345678"));
        assertFalse(result.redactedText().contains("2026123456"));
        assertFalse(result.redactedText().contains("305"));
        assertTrue(result.redactedText().contains("[PHONE:"));
        assertTrue(result.redactedText().contains("[STUDENT_NO:"));
        assertTrue(result.redactedText().contains("[LOCATION:"));

        String samePurpose = service.redact("13812345678", "repair-triage").redactedText();
        String otherPurpose = service.redact("13812345678", "risk-explanation").redactedText();
        assertFalse(samePurpose.equals(otherPurpose));

        assertTrue(result.irreversibleHash().matches("[0-9a-f]{64}"));
        assertEquals(result.irreversibleHash(), service.redact(
                "维修联系人 13812345678，学号 2026123456，位置 2号楼-305宿舍。",
                "repair-triage").irreversibleHash());
        assertFalse(result.irreversibleHash().equals(service.redact(
                "维修联系人 13812345678，学号 2026123456，位置 2号楼-305宿舍。",
                "risk-explanation").irreversibleHash()));
    }

    @Test
    void blocksL3SecretsInsteadOfBestEffortRedaction() {
        SensitiveDataBlockedException exception = assertThrows(SensitiveDataBlockedException.class,
                () -> service.redact("Authorization: Bearer sk-test-secret-value", "assistant"));
        assertEquals("AI_L3_DATA_BLOCKED", exception.errorCode());
    }

    @Test
    void blocksChineseSecretsHealthAndDisciplinaryRecordsFailClosed() {
        for (String text : java.util.List.of(
                "模型密钥：sk-prod-1234567890",
                "数据库密码=SuPerSecret!",
                "访问令牌：token-value-123456",
                "健康状况：心脏病术后复查",
                "病史：癫痫，长期用药：丙戊酸钠",
                "残疾情况：视力一级残疾",
                "纪律处分：记过，处分决定编号 2026-17",
                "银行卡号：6222020202020202020",
                "银行卡号是 6222020202020202020",
                "个人缴费明细：张三欠费 3200 元",
                "账户余额：12500.50 元",
                "本人确诊为抑郁症，需要服药",
                "-----BEGIN PRIVATE KEY----- secret",
                "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.signaturevalue",
                "ghp_abcdefghijklmnopqrstuvwxyz123456")) {
            assertThrows(SensitiveDataBlockedException.class,
                    () -> service.redact(text, "knowledge-ingestion"), text);
        }
    }

    @Test
    void deidentifiesChineseIdentityAndNameOnlyWithPersonalFieldContext() {
        PiiRedactionService.RedactionResult result = service.redact(
                "学生姓名：张三，身份证号：11010519491231002X，负责制度宣讲。",
                "knowledge-ingestion");

        assertEquals(DataClassification.L2, result.classification());
        assertFalse(result.redactedText().contains("张三"));
        assertFalse(result.redactedText().contains("11010519491231002X"));
        assertTrue(result.redactedText().contains("学生姓名:[PERSON_NAME:"));
        assertTrue(result.redactedText().contains("身份证号:[NATIONAL_ID:"));
        assertTrue(result.matchedRules().containsAll(java.util.List.of("PERSON_NAME", "NATIONAL_ID")));
        assertEquals(PiiRedactionService.POLICY_VERSION, result.policyVersion());

        PiiRedactionService.RedactionResult contextual = service.redact(
                "学生张三（学号 2026123456）申请调宿。", "knowledge-ingestion");
        assertFalse(contextual.redactedText().contains("张三"));
        assertTrue(contextual.redactedText().contains("学生[PERSON_NAME:"));
    }

    @Test
    void deidentifiesLabeledBusinessContractIdentifiers() {
        var result = service.redact(
                "学号 IMPORT-001，联系电话 +86 138-1234-5678。", "assistant-input");

        assertFalse(result.redactedText().contains("IMPORT-001"));
        assertFalse(result.redactedText().contains("138-1234"));
        assertTrue(result.redactedText().contains("[STUDENT_NO:"));
        assertTrue(result.redactedText().contains("[PHONE:"));
    }

    @Test
    void deidentifiesUnlabelledNamesInFreeTextRequestAndOperationalNarrative() {
        PiiRedactionService.RedactionResult query = service.redact(
                "请查询张伟的报修进度。", "assistant-query");
        PiiRedactionService.RedactionResult narrative = service.redact(
                "张伟负责跟进该维修单。", "knowledge-ingestion");
        PiiRedactionService.RedactionResult minorityName = service.redact(
                "阿依古丽负责跟进该维修单。", "knowledge-ingestion");

        assertFalse(query.redactedText().contains("张伟"));
        assertFalse(narrative.redactedText().contains("张伟"));
        assertFalse(minorityName.redactedText().contains("阿依古丽"));
        assertTrue(query.redactedText().contains("查询[PERSON_NAME:"));
        assertTrue(narrative.redactedText().contains("[PERSON_NAME:"));
        assertEquals(DataClassification.L2, query.classification());
        assertEquals(DataClassification.L2, narrative.classification());
        assertEquals(DataClassification.L2, minorityName.classification());
    }

    @Test
    void nameContextDetectorDoesNotTreatOrdinaryDomainNounsAsPeople() {
        String text = "请查询维修的处理进度，并查看宿舍的空余床位。";

        PiiRedactionService.RedactionResult result = service.redact(text, "assistant-query");

        assertEquals(Normalizer.normalize(text, Normalizer.Form.NFKC), result.redactedText());
        assertEquals(DataClassification.L1, result.classification());
    }

    @Test
    void doesNotOverblockOrdinaryPolicyTextWithoutSensitiveValues() {
        String policy = "健康教育制度要求保护学生隐私，纪律处分管理办法禁止公开病史、用药记录、密码和姓名字段。";

        PiiRedactionService.RedactionResult result = service.redact(policy, "knowledge-ingestion");

        assertEquals(DataClassification.L1, result.classification());
        assertEquals(Normalizer.normalize(policy, Normalizer.Form.NFKC), result.redactedText());
        assertTrue(result.matchedRules().isEmpty());
    }

    @Test
    void blocksNamedNaturalLanguageHealthAndDisciplinaryStatements() {
        for (String text : java.util.List.of(
                "张三患有抑郁症，需要持续服药。",
                "李四被处以记过处分。",
                "张三感染了 HIV，正在接受治疗。",
                "李四因打架被学校通报批评。",
                "经核实，阿依古丽感染了乙肝，正在接受治疗。",
                "学生张三感染了 HIV，正在接受治疗。",
                "住户李四因打架被学校通报批评。")) {
            assertThrows(SensitiveDataBlockedException.class,
                    () -> service.redact(text, "risk-explanation"), text);
        }

        String policy = "健康教育制度和纪律处分管理办法属于公开制度文本。";
        assertEquals(policy, service.redact(policy, "knowledge-ingestion").redactedText());

        String disciplinaryPolicy = "有关学生受到警告处分的规定，应依照纪律处分管理办法执行。";
        assertEquals(Normalizer.normalize(disciplinaryPolicy, Normalizer.Form.NFKC),
                service.redact(disciplinaryPolicy, "knowledge-ingestion").redactedText());
    }

    @Test
    void streamingHoldbackDetectsSensitiveValueSplitAcrossChunks() {
        PiiStreamingRedactor streaming = new PiiStreamingRedactor(service, "assistant", 32);
        String first = streaming.accept("请联系 13812");
        String second = streaming.accept("345678 处理维修");
        String last = streaming.finish();

        String combined = first + second + last;
        assertFalse(combined.contains("13812345678"));
        assertTrue(combined.contains("[PHONE:"));
    }

    @Test
    void streamingNeverEmitsPartialPiiWhenThresholdFallsInsideTheValue() {
        PiiStreamingRedactor streaming = new PiiStreamingRedactor(service, "assistant", 32);

        String first = streaming.accept("这是一段用于触发批次阈值的普通前置文字，仍然需要安全输出。联系电话 13812");
        String second = streaming.accept("345678，请尽快处理。下一句可以继续输出。");
        String last = streaming.finish();

        String combined = first + second + last;
        assertFalse(first.contains("13812"), "手机号尚未完整时不得发送任何局部明文");
        assertFalse(combined.contains("13812345678"));
        assertFalse(combined.contains("13812"));
        assertTrue(combined.contains("[PHONE:"));
    }

    @Test
    void streamingBlocksL3StatementSplitAcrossProviderDeltasBeforeItIsEmitted() {
        PiiStreamingRedactor streaming = new PiiStreamingRedactor(service, "assistant", 32);

        String first = streaming.accept("这是普通说明。张三确");
        assertEquals("这是普通说明。", first);
        assertThrows(SensitiveDataBlockedException.class,
                () -> streaming.accept("诊为抑郁症，正在服药。"));
    }

    @Test
    void rejectsInvalidConfigurationInputsAndForbiddenMinimumClassification() {
        assertThrows(IllegalArgumentException.class, () -> new PiiRedactionService(null, "v1"));
        assertThrows(IllegalArgumentException.class, () -> new PiiRedactionService(new byte[7], "v1"));
        assertThrows(IllegalArgumentException.class,
                () -> new PiiRedactionService("long-enough".getBytes(StandardCharsets.UTF_8), null));
        assertThrows(IllegalArgumentException.class,
                () -> new PiiRedactionService("long-enough".getBytes(StandardCharsets.UTF_8), " "));
        assertThrows(NullPointerException.class, () -> service.redact(null, "assistant"));
        assertThrows(IllegalArgumentException.class, () -> service.redact("text", null));
        assertThrows(IllegalArgumentException.class, () -> service.redact("text", " "));
        assertThrows(NullPointerException.class, () -> service.redact("text", "assistant", null));
        assertThrows(SensitiveDataBlockedException.class,
                () -> service.redact("text", "assistant", DataClassification.L3));

        assertEquals(DataClassification.L2,
                service.redact("ordinary", "assistant", DataClassification.L2).classification());
        assertEquals(DataClassification.L1,
                service.redact("ordinary", "assistant", DataClassification.L0).classification());
    }

    @Test
    void knownValueTokenizationValidatesInputsPreservesTokensAndHandlesEmptyDictionaries() {
        assertThrows(NullPointerException.class,
                () -> service.tokenizeKnownValues(null, "assistant", "PERSON_NAME", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> service.tokenizeKnownValues("text", null, "PERSON_NAME", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> service.tokenizeKnownValues("text", " ", "PERSON_NAME", List.of()));
        assertThrows(NullPointerException.class,
                () -> service.tokenizeKnownValues("text", "assistant", null, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> service.tokenizeKnownValues("text", "assistant", "NATIONAL_ID", List.of()));
        assertThrows(NullPointerException.class,
                () -> service.tokenizeKnownValues("text", "assistant", "PERSON_NAME", null));
        assertThrows(IllegalArgumentException.class,
                () -> service.tokenizeKnownPersonNames("text", "assistant", java.util.Arrays.asList((String) null)));
        assertThrows(IllegalArgumentException.class,
                () -> service.tokenizeKnownPersonNames("text", "assistant", List.of(" ")));

        var emptyInput = service.tokenizeKnownPersonNames("", "assistant", List.of("张三"));
        var emptyDictionary = service.tokenizeKnownPersonNames("普通文本", "assistant", List.of());
        assertEquals("", emptyInput.redactedText());
        assertEquals(0, emptyInput.tokenizedCount());
        assertEquals("普通文本", emptyDictionary.redactedText());
        assertEquals(0, emptyDictionary.tokenizedCount());

        String existing = service.tokenizeKnownPersonNames("张三", "assistant", List.of("张三"))
                .redactedText();
        var preserved = service.tokenizeKnownPersonNames(existing + "和李四", "assistant", List.of("李四"));
        assertEquals(1, preserved.preservedTokenCount());
        assertEquals(1, preserved.tokenizedCount());
        assertFalse(preserved.redactedText().contains("李四"));
    }

    @Test
    void redactionResultValueContractsRejectMalformedHashesAndNegativeCounts() {
        assertThrows(NullPointerException.class, () -> new PiiRedactionService.RedactionResult(
                null, DataClassification.L1, List.of(), "v1", "k1", "a".repeat(64)));
        assertThrows(NullPointerException.class, () -> new PiiRedactionService.RedactionResult(
                "text", null, List.of(), "v1", "k1", "a".repeat(64)));
        assertThrows(NullPointerException.class, () -> new PiiRedactionService.RedactionResult(
                "text", DataClassification.L1, null, "v1", "k1", "a".repeat(64)));
        assertThrows(NullPointerException.class, () -> new PiiRedactionService.RedactionResult(
                "text", DataClassification.L1, List.of(), null, "k1", "a".repeat(64)));
        assertThrows(NullPointerException.class, () -> new PiiRedactionService.RedactionResult(
                "text", DataClassification.L1, List.of(), "v1", null, "a".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new PiiRedactionService.RedactionResult(
                "text", DataClassification.L1, List.of(), "v1", "k1", null));
        assertThrows(IllegalArgumentException.class, () -> new PiiRedactionService.RedactionResult(
                "text", DataClassification.L1, List.of(), "v1", "k1", "a".repeat(63)));
        assertThrows(NullPointerException.class,
                () -> new PiiRedactionService.KnownNameTokenizationResult(null, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new PiiRedactionService.KnownNameTokenizationResult("text", -1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new PiiRedactionService.KnownNameTokenizationResult("text", 0, -1));
        assertThrows(NullPointerException.class, () -> service.irreversibleHash(null, "assistant"));
        assertThrows(IllegalArgumentException.class, () -> service.irreversibleHash("text", null));
        assertThrows(IllegalArgumentException.class, () -> service.irreversibleHash("text", " "));
    }

    @Test
    void streamingConstructorsAndEmptyChunksEnforceSafeHoldbackContract() {
        assertThrows(IllegalArgumentException.class,
                () -> new PiiStreamingRedactor((PiiRedactionService) null, "assistant", 16));
        assertThrows(IllegalArgumentException.class,
                () -> new PiiStreamingRedactor(service, "assistant", 15));
        PiiClassificationService classified = new PiiClassificationService(service, List::of);
        assertThrows(IllegalArgumentException.class,
                () -> new PiiStreamingRedactor((PiiClassificationService) null, "assistant", 16));
        PiiStreamingRedactor streaming = new PiiStreamingRedactor(classified, "assistant", 16);

        assertEquals("", streaming.accept(null));
        assertEquals("", streaming.accept(""));
        assertEquals("", streaming.accept("x".repeat(17)));
        assertEquals("x".repeat(17), streaming.finish());
        assertEquals("", streaming.finish());
    }

    @Test
    void streamingRecognizesEverySupportedSentenceBoundaryWithoutLeakingFollowingChunk() {
        for (char boundary : new char[] {'。', '！', '？', '；', ';', '!', '?', '\n', '\r'}) {
            PiiStreamingRedactor streaming = new PiiStreamingRedactor(service, "assistant", 16);
            String emitted = streaming.accept("普通句子" + boundary + "联系电话 13812");
            String finished = streaming.accept("345678") + streaming.finish();

            assertEquals(Normalizer.normalize("普通句子" + boundary, Normalizer.Form.NFKC), emitted);
            assertFalse(finished.contains("13812345678"));
            assertTrue(finished.contains("[PHONE:"));
        }
    }
}
