package com.example.dormitory.ai.knowledge;

import com.example.dormitory.ai.security.PromptInjectionGuard;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 没有生产恶意文件扫描产品时的 fail-closed 适配器：只接收大小受限、严格 UTF-8、无二进制签名的纯文本。
 * 它不会宣称具备病毒/宏扫描能力，生产知识能力仍由 ProductionAiSecurityGate 禁止启用。
 */
public final class ControlledPlainTextFileScanner implements KnowledgeFileScanner {

    private static final byte[][] DENIED_SIGNATURES = {
            {'%', 'P', 'D', 'F'},
            {'P', 'K', 0x03, 0x04},
            {'M', 'Z'},
            {0x7f, 'E', 'L', 'F'}
    };

    private final PromptInjectionGuard injectionGuard;
    private final long maximumBytes;

    public ControlledPlainTextFileScanner(PromptInjectionGuard injectionGuard, long maximumBytes) {
        if (injectionGuard == null || maximumBytes < 1) throw new IllegalArgumentException("纯文本扫描配置不合法");
        this.injectionGuard = injectionGuard;
        this.maximumBytes = maximumBytes;
    }

    @Override
    public ScanResult scan(InputStream content, long expectedSizeBytes) {
        if (content == null || expectedSizeBytes < 1 || expectedSizeBytes > maximumBytes) {
            throw new KnowledgeQuarantinedException("纯文本对象大小不合法");
        }
        byte[] bytes = readBounded(content, expectedSizeBytes);
        if (hasDeniedSignature(bytes) || hasBinaryControls(bytes)) {
            throw new KnowledgeQuarantinedException("对象签名不是受控纯文本");
        }
        String text = strictUtf8(bytes);
        if (text.isBlank() || injectionGuard.inspect(text).blocked()) {
            throw new KnowledgeQuarantinedException("纯文本未通过内容安全检查");
        }
        return new ScanResult("text/plain", bytes.length);
    }

    @Override
    public ScannerStatus status() {
        return new ScannerStatus("controlled-plain-text-v1", "CONTROLLED_PLAIN_TEXT_ONLY", false, false);
    }

    private byte[] readBounded(InputStream content, long expectedSizeBytes) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(
                    (int) Math.min(expectedSizeBytes, 64L * 1024));
            byte[] buffer = new byte[8 * 1024];
            long total = 0;
            int read;
            while ((read = content.read(buffer)) != -1) {
                total += read;
                if (total > expectedSizeBytes || total > maximumBytes) {
                    throw new KnowledgeQuarantinedException("对象实际大小超过声明或扫描上限");
                }
                output.write(buffer, 0, read);
            }
            if (total != expectedSizeBytes) throw new KnowledgeQuarantinedException("对象实际大小与声明不一致");
            return output.toByteArray();
        } catch (IOException failure) {
            throw new KnowledgeQuarantinedException("纯文本对象读取失败");
        }
    }

    private boolean hasDeniedSignature(byte[] content) {
        for (byte[] signature : DENIED_SIGNATURES) {
            if (content.length >= signature.length
                    && Arrays.equals(signature, Arrays.copyOf(content, signature.length))) return true;
        }
        String prefix = new String(content, 0, Math.min(content.length, 64), StandardCharsets.US_ASCII)
                .stripLeading().toLowerCase(java.util.Locale.ROOT);
        return prefix.startsWith("<!doctype") || prefix.startsWith("<html") || prefix.startsWith("<script");
    }

    private boolean hasBinaryControls(byte[] content) {
        for (byte value : content) {
            int unsigned = value & 0xff;
            if (unsigned == 0 || unsigned == 0x7f
                    || unsigned < 0x20 && unsigned != '\n' && unsigned != '\r' && unsigned != '\t') return true;
        }
        return false;
    }

    private String strictUtf8(byte[] content) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException invalid) {
            throw new KnowledgeQuarantinedException("对象不是有效 UTF-8 纯文本");
        }
    }
}
