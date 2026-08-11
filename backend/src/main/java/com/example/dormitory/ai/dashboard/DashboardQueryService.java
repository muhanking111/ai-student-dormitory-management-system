package com.example.dormitory.ai.dashboard;

import com.example.dormitory.ai.domain.model.BusinessActorScope;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * 自然语言 Dashboard 的安全编排层。意图与查询均受固定目录验证，不接收 SQL、字段名或 handler 名。
 */
public final class DashboardQueryService {

    private final DashboardIntentGenerator intentGenerator;
    private final MetricQueryExecutor executor;

    public DashboardQueryService(DashboardIntentGenerator intentGenerator, MetricQueryExecutor executor) {
        this.intentGenerator = java.util.Objects.requireNonNull(intentGenerator);
        this.executor = java.util.Objects.requireNonNull(executor);
    }

    public QueryResponse query(String question, Set<String> actorPermissions) {
        DashboardIntentGenerator.GeneratedIntent generated = intentGenerator.generate(question);
        MetricQueryResult result = executor.execute(generated.intent(), actorPermissions);
        return response(generated, result);
    }

    public QueryResponse query(String question, BusinessActorScope actorScope) {
        DashboardIntentGenerator.GeneratedIntent generated = intentGenerator.generate(question);
        MetricQueryResult result = executor.execute(generated.intent(), actorScope);
        return response(generated, result);
    }

    private QueryResponse response(DashboardIntentGenerator.GeneratedIntent generated, MetricQueryResult result) {
        String explanation = result.metrics().entrySet().stream()
                .map(entry -> entry.getValue().definition() + "：" + entry.getValue().value()
                        + " " + entry.getValue().unit() + "（口径 " + entry.getValue().metricVersion() + "）")
                .collect(Collectors.joining("；"))
                + "。数据时间：" + result.asOf() + "。";
        return new QueryResponse(generated.intent(), result, explanation, generated.modelUsed(),
                generated.degraded(), generated.source(), DashboardQueryIntent.SCHEMA_VERSION);
    }

    public record QueryResponse(
            DashboardQueryIntent intent,
            MetricQueryResult result,
            String explanation,
            boolean modelUsed,
            boolean degraded,
            String intentSource,
            String intentSchemaVersion) {
    }
}
