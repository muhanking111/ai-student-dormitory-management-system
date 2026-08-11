package com.example.dormitory.ai.port;

import java.util.List;
import java.util.Objects;

/**
 * 项目自有的已知 PII 字典端口。实现只能从内部事实源读取，值仅允许在当前进程内短暂使用。
 */
@FunctionalInterface
public interface KnownPiiDictionaryPort {

    List<KnownPiiValue> loadKnownPii();

    enum PiiKind {
        PERSON_NAME,
        STUDENT_NO,
        PHONE
    }

    record KnownPiiValue(PiiKind kind, String value) {
        public KnownPiiValue {
            Objects.requireNonNull(kind, "PII 类型不能为空");
            Objects.requireNonNull(value, "PII 值不能为空");
        }

        /** 防止诊断日志或异常上下文通过 record 默认 toString 泄露原值。 */
        @Override
        public String toString() {
            return "KnownPiiValue[kind=" + kind + ",length=" + value.codePointCount(0, value.length()) + "]";
        }
    }
}
