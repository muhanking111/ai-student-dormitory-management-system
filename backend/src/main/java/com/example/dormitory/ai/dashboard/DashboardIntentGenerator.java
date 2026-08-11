package com.example.dormitory.ai.dashboard;

/**
 * 结构化意图生成端口。模型 adapter 与确定性降级 parser 都必须只通过该边界返回 DashboardQueryIntent.v1。
 */
@FunctionalInterface
public interface DashboardIntentGenerator {

    GeneratedIntent generate(String question);

    record GeneratedIntent(
            DashboardQueryIntent intent,
            String source,
            boolean modelUsed,
            boolean degraded) {
        public GeneratedIntent {
            if (intent == null) throw new IllegalArgumentException("Dashboard intent 不能为空");
            if (source == null || source.isBlank() || source.length() > 64) {
                throw new IllegalArgumentException("Dashboard intent source 不合法");
            }
            if (modelUsed && degraded) {
                throw new IllegalArgumentException("模型意图不能同时标记为确定性降级");
            }
        }
    }
}
