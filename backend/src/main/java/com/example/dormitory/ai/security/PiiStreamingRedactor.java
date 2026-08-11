package com.example.dormitory.ai.security;

public final class PiiStreamingRedactor {

    private final Redactor service;
    private final String purpose;
    private final int holdbackCharacters;
    private final StringBuilder buffer = new StringBuilder();

    public PiiStreamingRedactor(PiiRedactionService service, String purpose, int holdbackCharacters) {
        this(service == null ? null : service::redact, purpose, holdbackCharacters);
    }

    public PiiStreamingRedactor(PiiClassificationService service, String purpose, int holdbackCharacters) {
        this(service == null ? null : service::redact, purpose, holdbackCharacters);
    }

    private PiiStreamingRedactor(Redactor service, String purpose, int holdbackCharacters) {
        if (service == null) throw new IllegalArgumentException("脱敏服务不能为空");
        if (holdbackCharacters < 16) throw new IllegalArgumentException("流式 holdback 过小");
        this.service = service;
        this.purpose = purpose;
        this.holdbackCharacters = holdbackCharacters;
    }

    public synchronized String accept(String chunk) {
        if (chunk == null || chunk.isEmpty()) return "";
        buffer.append(chunk);
        int emitLength = lastCompletedSentenceEnd(buffer);
        if (emitLength < 1) {
            // 固定字符数不能证明边界安全：手机号、Bearer token 或 L3 语句都可能正好跨过切点。
            // 没有完整句子时宁可继续 holdback，最终由 finish() 对完整内容一次性判定。
            if (buffer.length() <= holdbackCharacters) return "";
            return "";
        }
        String completedSentences = buffer.substring(0, emitLength);
        String redacted = service.redact(completedSentences, purpose).redactedText();
        buffer.delete(0, emitLength);
        return redacted;
    }

    public synchronized String finish() {
        String remaining = buffer.toString();
        buffer.setLength(0);
        return remaining.isEmpty() ? "" : service.redact(remaining, purpose).redactedText();
    }

    private int lastCompletedSentenceEnd(CharSequence value) {
        for (int index = value.length() - 1; index >= 0; index--) {
            char current = value.charAt(index);
            if (current == '。' || current == '！' || current == '？'
                    || current == '；' || current == ';' || current == '!'
                    || current == '?' || current == '\n' || current == '\r') {
                return index + 1;
            }
        }
        return -1;
    }

    @FunctionalInterface
    private interface Redactor {
        PiiRedactionService.RedactionResult redact(String value, String purpose);
    }
}
