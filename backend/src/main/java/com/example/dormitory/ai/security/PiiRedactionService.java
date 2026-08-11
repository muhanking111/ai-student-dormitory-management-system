package com.example.dormitory.ai.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PiiRedactionService {

    public static final String POLICY_VERSION = "pii-redaction-v4";

    private static final Pattern REDACTION_TOKEN = Pattern.compile(
            "\\[(?:PERSON_NAME|NATIONAL_ID|LOCATION|PHONE|STUDENT_NO):"
                    + "[A-Za-z0-9._-]{1,32}:[A-Za-z0-9_-]{8,64}]");

    private static final Pattern SECRET = Pattern.compile(
            "(?iu)(?:sk-[a-z0-9_-]{8,}|api[_-]?key\\s*[:=：]\\s*\\S+|"
                    + "gh[pousr]_[a-z0-9]{20,}|"
                    + "eyJ[a-z0-9_-]{8,}\\.eyJ[a-z0-9_-]{8,}\\.[a-z0-9_-]{8,}|"
                    + "-----BEGIN(?: [A-Z0-9]+)? PRIVATE KEY-----|"
                    + "authorization\\s*[:：]\\s*bearer\\s+\\S+|"
                    + "(?:password|passphrase|(?:access_)?token|api[_-]?secret|"
                    + "密码|口令|密钥|秘钥|访问令牌|身份令牌|令牌)"
                    + "\\s*+(?:[:=：]\\s*+|(?:为|是)\\s++)[^\\s，。；;]++)");
    private static final Pattern HEALTH_RECORD = Pattern.compile(
            "(?iu)(?:健康(?:状况|信息|记录)|病史|既往史|疾病|诊断|用药(?:记录|情况)?|"
                    + "过敏史|残疾(?:情况|等级|证号)?|"
                    + "心理咨询记录|体检异常)\\s*[:=：]\\s*[^\\s，。；;]{1,64}");
    private static final Pattern PERSONAL_HEALTH_STATEMENT = Pattern.compile(
            "(?iu)(?:我|本人|当事人|患者|姓名\\s*[:：]\\s*[\\p{IsHan}·]{2,8})"
                    + "[^。；;\\r\\n]{0,24}(?:患有|确诊(?:为)?|正在?服用|残疾等级为|"
                    + "有[^。；;\\r\\n]{0,8}病史)");
    /*
     * 仅在句首或显式分隔符之后识别具名主体，并排除制度文本常见的泛化主体。
     * 这里不依赖姓氏表，避免少数民族姓名、别名和非常见姓氏被默认降为 L1。
     */
    private static final String NAMED_PERSON_SUBJECT =
            "(?:^|[\\s，,。；;：:])"
                    + "(?:(?:学生|住户|当事人|患者)\\s*[\\p{IsHan}·]{2,8}"
                    + "|(?!(?:有关学生|任何学生|全体学生|每位学生|学生|住户|当事人|患者|"
                    + "个人|人员|学校|制度|办法|规定|指南|通知|公告|宿舍|部门))"
                    + "[\\p{IsHan}·]{2,8})(?:同学)?";
    private static final Pattern NAMED_HEALTH_STATEMENT = Pattern.compile(
            "(?iu)" + NAMED_PERSON_SUBJECT
                    + "[^。；;\\r\\n]{0,16}(?:患有|确诊(?:为)?|感染(?:了|有)?|罹患|"
                    + "被诊断(?:为)?|正在?服用|正在?接受治疗|接受治疗|住院治疗|"
                    + "检测(?:结果)?(?:为)?阳性|残疾等级为|有[^。；;\\r\\n]{0,8}病史)");
    private static final Pattern DISCIPLINARY_RECORD = Pattern.compile(
            "(?iu)(?:纪律处分|处分记录|处分决定|违纪记录)\\s*[:=：]\\s*"
                    + "[^\\s，。；;]{1,64}");
    private static final Pattern FINANCIAL_SENSITIVE_DETAIL = Pattern.compile(
            "(?iu)(?:(?:银行卡(?:号)?|银行(?:账户|账号)|账户号|收款账号|支付账号|卡号)"
                    + "\\s*+(?:[:=：]\\s*+|(?:为|是)\\s*+)[0-9][0-9\\s-]{7,30}+"
                    + "|(?:cvv|cvc|安全码)\\s*+(?:[:=：]\\s*+|(?:为|是)\\s*+)\\d{3,4}+"
                    + "|(?:财务敏感明细|个人缴费明细|个人欠费明细|账户余额|银行卡余额|"
                    + "补助金额|贷款金额)\\s*+(?:[:=：]\\s*+|(?:为|是)\\s*+)"
                    + "[^。；;\\r\\n]{1,128}+)");
    private static final Pattern PERSONAL_DISCIPLINARY_STATEMENT = Pattern.compile(
            "(?iu)(?:当事人|姓名\\s*[:：]\\s*[\\p{IsHan}·]{2,8})"
                    + "[^。；;\\r\\n]{0,24}(?:受到|给予|被处以)\\s*"
                    + "(?:警告|严重警告|记过|留校察看|开除学籍)");
    private static final Pattern NAMED_DISCIPLINARY_STATEMENT = Pattern.compile(
            "(?iu)" + NAMED_PERSON_SUBJECT + "[^。；;\\r\\n]{0,24}(?:"
                    + "(?:受到|给予|被(?:学校)?处以)\\s*(?:警告|严重警告|记过|留校察看|开除学籍)"
                    + "|因[^。；;\\r\\n]{0,12}被(?:学校)?(?:通报批评|处分|警告|记过|留校察看|开除学籍))");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern STUDENT_NO = Pattern.compile("(?<!\\d)20\\d{6,12}(?!\\d)");
    private static final Pattern LOCATION = Pattern.compile("(?<!\\d)(?:\\d{1,2}号楼[-— ]?)?\\d{3,4}(?:宿舍|寝室|室)");
    private static final Pattern NATIONAL_ID = Pattern.compile(
            "(?<!\\d)[1-9]\\d{5}(?:18|19|20)\\d{2}(?:0[1-9]|1[0-2])"
                    + "(?:0[1-9]|[12]\\d|3[01])\\d{3}[0-9Xx](?!\\d)");
    private static final Pattern LABELED_PERSON_NAME = Pattern.compile(
            "(?iu)((?:学生|住户|联系人|申请人|报修人|发布人)?姓名|联系人|申请人|报修人|发布人)"
                    + "(\\s*+[:=：]\\s*+)([\\p{IsHan}][\\p{IsHan}·]{1,7})");
    private static final Pattern LABELED_STUDENT_NO = Pattern.compile(
            "(?iu)(学号|student(?:[ _-]?no)?)(\\s*+(?:[:=：]\\s*+)?)([A-Za-z0-9-]{2,32})");
    private static final Pattern LABELED_PHONE = Pattern.compile(
            "(?iu)(联系电话|手机号|电话|phone)(\\s*+(?:[:=：]\\s*+)?)(\\+?[0-9][0-9 -]{5,31})");
    private static final Pattern CONTEXTUAL_PERSON_NAME = Pattern.compile(
            "(?iu)(学生|住户|联系人|申请人|报修人)(\\s*)"
                    + "((?!(?:受到|给予|被处以|因|患有|确诊|感染|应该|应当|可以|必须|需要))"
                    + "[\\p{IsHan}][\\p{IsHan}·]{1,7})"
                    + "(?=\\s*(?:(?:[(（,，]\\s*)?(?:学号|身份证(?:号)?|电话|手机号|住在|宿舍)"
                    + "|的|同学|老师|先生|女士|报修|入住|申请))");
    private static final Pattern QUERY_CONTEXT_PERSON_NAME = Pattern.compile(
            "(?iu)((?:请)?(?:查询|查找|查看|检索|搜索|联系|关于))(\\s*)"
                    + "((?!(?:当前|有关|所有|全部|每位|某个|这个|学生|住户|维修|报修|入住|申请|"
                    + "公告|宿舍|政策|制度|流程)(?=的|同学|老师|先生|女士|报修|入住|申请|电话|学号|住在))"
                    + "[\\p{IsHan}·]{2,8})(?=\\s*(?:的|同学|老师|先生|女士|报修|入住|申请|电话|学号|住在))");
    private static final Pattern OPERATIONAL_PERSON_NAME = Pattern.compile(
            "(?iu)(^|[\\s，,。；;：:])()"
                    + "((?!(?:学校|学院|部门|后勤|宿管|系统|平台|制度|流程|规定|公告|宿舍|维修组|"
                    + "委员会|中心|当前管理员|管理员|维修人员|宿管员)(?=\\s*(?:负责|住在|报修|申请|入住)))"
                    + "[\\p{IsHan}·]{2,8})"
                    + "(?=\\s*(?:负责|住在|报修|申请|入住|联系电话|手机号|学号))");

    private final byte[] hmacKey;
    private final String keyVersion;

    public PiiRedactionService(byte[] hmacKey, String keyVersion) {
        if (hmacKey == null || hmacKey.length < 8) throw new IllegalArgumentException("HMAC key 长度不足");
        if (keyVersion == null || keyVersion.isBlank()) throw new IllegalArgumentException("key version 不能为空");
        this.hmacKey = hmacKey.clone();
        this.keyVersion = keyVersion;
    }

    public RedactionResult redact(String input, String purpose) {
        return redact(input, purpose, DataClassification.L1);
    }

    public RedactionResult redact(String input, String purpose, DataClassification minimumClassification) {
        Objects.requireNonNull(input, "待脱敏文本不能为空");
        if (purpose == null || purpose.isBlank()) throw new IllegalArgumentException("用途域不能为空");
        Objects.requireNonNull(minimumClassification, "最低数据分类不能为空");
        if (minimumClassification == DataClassification.L3) {
            throw new SensitiveDataBlockedException("L3 数据不得进入 AI");
        }
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFKC);
        if (SECRET.matcher(normalized).find() || HEALTH_RECORD.matcher(normalized).find()
                || PERSONAL_HEALTH_STATEMENT.matcher(normalized).find()
                || NAMED_HEALTH_STATEMENT.matcher(normalized).find()
                || DISCIPLINARY_RECORD.matcher(normalized).find()
                || FINANCIAL_SENSITIVE_DETAIL.matcher(normalized).find()
                || PERSONAL_DISCIPLINARY_STATEMENT.matcher(normalized).find()
                || NAMED_DISCIPLINARY_STATEMENT.matcher(normalized).find()) {
            throw new SensitiveDataBlockedException("检测到禁止进入 AI 的 L3 数据");
        }

        List<String> rules = new ArrayList<>();
        String redacted = replaceLabeled(normalized, LABELED_PERSON_NAME, "PERSON_NAME", purpose, rules);
        redacted = replaceLabeled(redacted, LABELED_STUDENT_NO, "STUDENT_NO", purpose, rules);
        redacted = replaceLabeled(redacted, LABELED_PHONE, "PHONE", purpose, rules);
        redacted = replaceLabeled(redacted, CONTEXTUAL_PERSON_NAME, "PERSON_NAME", purpose, rules);
        redacted = replaceLabeled(redacted, QUERY_CONTEXT_PERSON_NAME, "PERSON_NAME", purpose, rules);
        redacted = replaceLabeled(redacted, OPERATIONAL_PERSON_NAME, "PERSON_NAME", purpose, rules);
        redacted = replace(redacted, NATIONAL_ID, "NATIONAL_ID", purpose, rules);
        redacted = replace(redacted, LOCATION, "LOCATION", purpose, rules);
        redacted = replace(redacted, PHONE, "PHONE", purpose, rules);
        redacted = replace(redacted, STUDENT_NO, "STUDENT_NO", purpose, rules);
        DataClassification detected = rules.isEmpty() && !REDACTION_TOKEN.matcher(normalized).find()
                ? DataClassification.L1 : DataClassification.L2;
        DataClassification classification = detected.ordinal() >= minimumClassification.ordinal()
                ? detected : minimumClassification;
        return new RedactionResult(redacted, classification, List.copyOf(rules), POLICY_VERSION, keyVersion,
                irreversibleHash(normalized, purpose));
    }

    private String replaceLabeled(
            String input, Pattern pattern, String kind, String purpose, List<String> rules) {
        return transformOutsideTokens(input, segment -> replaceLabeledSegment(segment, pattern, kind, purpose, rules));
    }

    private String replaceLabeledSegment(
            String input, Pattern pattern, String kind, String purpose, List<String> rules) {
        Matcher matcher = pattern.matcher(input);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String value = matcher.group(3);
            String replacement = matcher.group(1) + matcher.group(2) + "[" + kind + ":" + keyVersion + ":"
                    + token(purpose, kind, value) + "]";
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
            rules.add(kind);
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private String replace(String input, Pattern pattern, String kind, String purpose, List<String> rules) {
        return transformOutsideTokens(input, segment -> replaceSegment(segment, pattern, kind, purpose, rules));
    }

    private String replaceSegment(String input, Pattern pattern, String kind, String purpose, List<String> rules) {
        Matcher matcher = pattern.matcher(input);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String replacement = "[" + kind + ":" + keyVersion + ":" + token(purpose, kind, matcher.group()) + "]";
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
            rules.add(kind);
        }
        matcher.appendTail(output);
        return output.toString();
    }

    /**
     * 将内部已知姓名按最长匹配转换为用途域隔离 token。调用方负责提供经可信字典校验的姓名集合。
     */
    public KnownNameTokenizationResult tokenizeKnownPersonNames(
            String input,
            String purpose,
            List<String> knownNames) {
        return tokenizeKnownValues(input, purpose, "PERSON_NAME", knownNames);
    }

    public KnownNameTokenizationResult tokenizeKnownValues(
            String input,
            String purpose,
            String kind,
            List<String> knownValues) {
        Objects.requireNonNull(input, "待脱敏文本不能为空");
        if (purpose == null || purpose.isBlank()) throw new IllegalArgumentException("用途域不能为空");
        if (!Set.of("PERSON_NAME", "STUDENT_NO", "PHONE").contains(kind)) {
            throw new IllegalArgumentException("已知 PII 类型不合法");
        }
        Objects.requireNonNull(knownValues, "已知 PII 值不能为空");
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFKC);
        NameTrie trie = NameTrie.from(knownValues);
        Matcher tokens = REDACTION_TOKEN.matcher(normalized);
        StringBuilder output = new StringBuilder(normalized.length());
        int cursor = 0;
        int tokenized = 0;
        int preserved = 0;
        while (tokens.find()) {
            SegmentTokenization segment = tokenizeSegment(
                    normalized.substring(cursor, tokens.start()), purpose, kind, trie);
            output.append(segment.text()).append(tokens.group());
            tokenized += segment.count();
            preserved++;
            cursor = tokens.end();
        }
        SegmentTokenization tail = tokenizeSegment(normalized.substring(cursor), purpose, kind, trie);
        output.append(tail.text());
        tokenized += tail.count();
        return new KnownNameTokenizationResult(output.toString(), tokenized, preserved);
    }

    private SegmentTokenization tokenizeSegment(String input, String purpose, String kind, NameTrie trie) {
        if (input.isEmpty() || trie.empty()) return new SegmentTokenization(input, 0);
        StringBuilder output = new StringBuilder(input.length());
        int count = 0;
        int cursor = 0;
        while (cursor < input.length()) {
            int end = trie.longestMatchEnd(input, cursor);
            if (end > cursor) {
                String value = input.substring(cursor, end);
                output.append('[').append(kind).append(':').append(keyVersion).append(':')
                        .append(token(purpose, kind, value)).append(']');
                cursor = end;
                count++;
            } else {
                int codePoint = input.codePointAt(cursor);
                output.appendCodePoint(codePoint);
                cursor += Character.charCount(codePoint);
            }
        }
        return new SegmentTokenization(output.toString(), count);
    }

    private String transformOutsideTokens(String input, Function<String, String> transform) {
        Matcher tokens = REDACTION_TOKEN.matcher(input);
        StringBuilder output = new StringBuilder(input.length());
        int cursor = 0;
        while (tokens.find()) {
            output.append(transform.apply(input.substring(cursor, tokens.start())));
            output.append(tokens.group());
            cursor = tokens.end();
        }
        output.append(transform.apply(input.substring(cursor)));
        return output.toString();
    }

    private String token(String purpose, String kind, String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                hmac(purpose + "|" + kind + "|" + value)).substring(0, 12);
    }

    String irreversibleHash(String input, String purpose) {
        Objects.requireNonNull(input, "待哈希文本不能为空");
        if (purpose == null || purpose.isBlank()) throw new IllegalArgumentException("用途域不能为空");
        byte[] digest = hmac("redaction-input|" + purpose + "|"
                + Normalizer.normalize(input, Normalizer.Form.NFKC));
        return java.util.HexFormat.of().formatHex(digest);
    }

    private byte[] hmac(String framedValue) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            return mac.doFinal(framedValue.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC 初始化失败", exception);
        }
    }

    public record RedactionResult(
            String redactedText,
            DataClassification classification,
            List<String> matchedRules,
            String policyVersion,
            String keyVersion,
            String irreversibleHash) {
        public RedactionResult {
            Objects.requireNonNull(redactedText);
            Objects.requireNonNull(classification);
            matchedRules = List.copyOf(matchedRules);
            Objects.requireNonNull(policyVersion);
            Objects.requireNonNull(keyVersion);
            if (irreversibleHash == null || !irreversibleHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("脱敏输入 hash 不合法");
            }
        }
    }

    public record KnownNameTokenizationResult(
            String redactedText,
            int tokenizedCount,
            int preservedTokenCount) {
        public KnownNameTokenizationResult {
            Objects.requireNonNull(redactedText);
            if (tokenizedCount < 0 || preservedTokenCount < 0) {
                throw new IllegalArgumentException("PII token 计数不能为负数");
            }
        }
    }

    private record SegmentTokenization(String text, int count) {
    }

    private static final class NameTrie {
        private final NameTrieNode root = new NameTrieNode();
        private boolean empty = true;

        private static NameTrie from(List<String> values) {
            NameTrie trie = new NameTrie();
            for (String value : values) {
                if (value == null || value.isBlank()) throw new IllegalArgumentException("已知姓名条目不能为空");
                String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
                NameTrieNode node = trie.root;
                for (int index = 0; index < normalized.length(); index++) {
                    node = node.children.computeIfAbsent(normalized.charAt(index), ignored -> new NameTrieNode());
                }
                node.terminal = true;
                trie.empty = false;
            }
            return trie;
        }

        private boolean empty() {
            return empty;
        }

        private int longestMatchEnd(String value, int start) {
            NameTrieNode node = root;
            int longestEnd = -1;
            for (int index = start; index < value.length(); index++) {
                node = node.children.get(value.charAt(index));
                if (node == null) break;
                if (node.terminal) longestEnd = index + 1;
            }
            return longestEnd;
        }
    }

    private static final class NameTrieNode {
        private final Map<Character, NameTrieNode> children = new HashMap<>();
        private boolean terminal;
    }
}
