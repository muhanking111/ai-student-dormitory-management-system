package com.example.dormitory.ai.security;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

public class PromptInjectionGuard {

    private static final List<Rule> RULES = List.of(
            new Rule("OVERRIDE_POLICY", Pattern.compile("(?is)(ignore|disregard).{0,40}(previous|system|instruction)|忽略.{0,30}(指令|提示|规则)")),
            new Rule("PROMPT_EXFILTRATION", Pattern.compile("(?is)(system|hidden).{0,20}prompt|系统提示词|隐藏提示")),
            new Rule("DYNAMIC_TOOL", Pattern.compile("(?is)(call|invoke|execute).{0,30}(hidden|unknown|tool|shell|method|class)|调用.{0,30}(隐藏|未知|工具|方法|类)")),
            new Rule("DYNAMIC_URL", Pattern.compile("(?is)https?://|169\\.254\\.169\\.254|localhost|127\\.0\\.0\\.1")),
            new Rule("EXECUTABLE_SQL", Pattern.compile("(?is)\\b(select|insert|update|delete|drop|alter)\\b.{0,80}\\b(from|into|table|sys_|notice|repair_order)\\b")),
            new Rule("SCRIPT", Pattern.compile("(?is)<script|powershell|cmd\\.exe|/bin/sh|runtime\\.exec|processbuilder"))
    );

    public Inspection inspect(String value) {
        if (value == null) return new Inspection(false, List.of(), "");
        String normalized = normalize(value);
        List<String> hits = inspectNormalized(normalized);
        if (hits.isEmpty()) {
            decodeBase64Candidate(Normalizer.normalize(value, Normalizer.Form.NFKC)
                    .replaceAll("[\\u200B-\\u200D\\uFEFF]", ""))
                    .ifPresent(decoded -> hits.addAll(inspectNormalized(normalize(decoded))));
        }
        return new Inspection(!hits.isEmpty(), List.copyOf(hits), normalized);
    }

    public String wrapData(String sourceId, String versionId, String chunkId, String content) {
        if (sourceId == null || sourceId.isBlank() || versionId == null || versionId.isBlank()
                || chunkId == null || chunkId.isBlank()) {
            throw new IllegalArgumentException("知识数据定位不能为空");
        }
        return "<UNTRUSTED_KNOWLEDGE_DATA source=" + sourceId + " version=" + versionId
                + " chunk=" + chunkId + ">\n" + (content == null ? "" : content)
                + "\n</UNTRUSTED_KNOWLEDGE_DATA>";
    }

    private List<String> inspectNormalized(String normalized) {
        List<String> hits = new ArrayList<>();
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(normalized).find()) hits.add(rule.code());
        }
        return hits;
    }

    private java.util.Optional<String> decodeBase64Candidate(String value) {
        String compact = value.replaceAll("\\s", "");
        if (compact.length() < 16 || compact.length() > 8192 || compact.length() % 4 != 0
                || !compact.matches("[A-Za-z0-9+/=]+")) return java.util.Optional.empty();
        try {
            String decoded = new String(Base64.getDecoder().decode(compact), StandardCharsets.UTF_8);
            return decoded.chars().filter(ch -> ch >= 32 && ch < 127).count() >= decoded.length() * 0.8
                    ? java.util.Optional.of(decoded) : java.util.Optional.empty();
        } catch (IllegalArgumentException ignored) {
            return java.util.Optional.empty();
        }
    }

    private String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("[\\u200B-\\u200D\\uFEFF]", "")
                .toLowerCase(java.util.Locale.ROOT);
    }

    private record Rule(String code, Pattern pattern) {
    }

    public record Inspection(boolean blocked, List<String> ruleCodes, String normalizedText) {
    }
}
