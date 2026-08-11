package com.example.dormitory.ai.dashboard;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 不调用供应商的确定性降级 parser。生产模型 adapter 可替换 DashboardIntentGenerator，目录验证仍由服务端执行。
 */
public final class DashboardIntentParser implements DashboardIntentGenerator {

    private static final Pattern DYNAMIC_EXECUTION = Pattern.compile(
            "(?is).*(?:\\b(?:select|insert|update|delete|drop|alter|union|join|schema|mapper)\\b"
                    + "|密码|password|表名|字段名|任意sql|系统提示词|忽略(?:以上|之前)指令|反射|shell|动态url).*" );
    private static final Pattern BUILDING_ID = Pattern.compile(
            "(?i)(?:楼栋\\s*(?:id)?|building\\s*id)\\s*[:：#]?\\s*(\\d{1,18})");
    private static final List<String> REPAIR_TYPES = List.of("水电维修", "家具维修", "门窗维修");

    private final MetricCatalog catalog;

    public DashboardIntentParser(MetricCatalog catalog) {
        this.catalog = java.util.Objects.requireNonNull(catalog);
    }

    @Override
    public GeneratedIntent generate(String question) {
        return new GeneratedIntent(parse(question), "deterministic-fallback.v1", false, true);
    }

    public DashboardQueryIntent parse(String question) {
        if (question == null || question.isBlank() || question.length() > 500) {
            throw new IllegalArgumentException("指标问题不合法");
        }
        String normalized = Normalizer.normalize(question, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        if (DYNAMIC_EXECUTION.matcher(normalized).matches()) throw unsupported();

        Set<String> ids = metricIds(normalized);
        if (ids.isEmpty()) throw unsupported();

        Set<String> dimensions = new LinkedHashSet<>();
        if (containsAny(normalized, "按维修类型", "按报修类型", "by repair type")) {
            dimensions.add("repairType");
        }
        if (containsAny(normalized, "按楼栋", "按宿舍楼", "by building")) dimensions.add("buildingId");

        Map<String, List<String>> filters = new LinkedHashMap<>();
        List<String> repairTypes = REPAIR_TYPES.stream().filter(normalized::contains).toList();
        if (!repairTypes.isEmpty()) filters.put("repairTypes", repairTypes);
        List<String> buildingIds = new ArrayList<>();
        Matcher buildingMatcher = BUILDING_ID.matcher(normalized);
        while (buildingMatcher.find()) buildingIds.add(buildingMatcher.group(1));
        if (!buildingIds.isEmpty()) filters.put("buildingIds", buildingIds.stream().distinct().toList());

        String preset = containsAny(normalized, "今天", "今日", "today") ? "TODAY"
                : containsAny(normalized, "本周", "近7", "最近7", "last 7 days", "this week") ? "LAST_7_DAYS"
                : containsAny(normalized, "近30", "最近30", "本月", "last 30 days", "this month")
                ? "LAST_30_DAYS" : "TODAY";
        String presentation = containsAny(normalized, "趋势", "折线", "trend", "line chart") ? "LINE"
                : containsAny(normalized, "柱状", "bar chart") ? "BAR"
                : ids.size() > 1 || !dimensions.isEmpty() || containsAny(normalized, "表格", "table")
                ? "TABLE" : "CARD";
        DashboardQueryIntent intent = new DashboardQueryIntent(
                ids, new DashboardQueryIntent.DateRange(preset), dimensions, filters, presentation);
        catalog.validate(intent);
        return intent;
    }

    private Set<String> metricIds(String normalized) {
        Set<String> ids = new LinkedHashSet<>();
        if (containsAny(normalized, "宿舍总数", "多少宿舍", "total dormitories")) ids.add("dormitory.total");
        if (containsAny(normalized, "入住人数", "入住学生", "checked-in students", "checked in students")) {
            ids.add("student.checked-in.count");
        }
        if (containsAny(normalized, "空余床位", "空床", "available beds", "vacant beds")) {
            ids.add("bed.available.count");
        }
        if (containsAny(normalized, "待维修", "待处理维修", "待处理报修", "待处理工单", "报修", "维修工单",
                "pending repair", "repair backlog")) ids.add("repair.pending.count");
        if (containsAny(normalized, "卫生", "待整改", "failed hygiene", "hygiene")) {
            ids.add("hygiene.failed.count");
        }
        if (containsAny(normalized, "未缴", "欠费", "账单", "unpaid payment", "unpaid bill")) {
            ids.add("payment.unpaid.count");
        }
        if (containsAny(normalized, "运营风险", "operational risk")) {
            ids.add("repair.pending.count");
            ids.add("hygiene.failed.count");
            ids.add("payment.unpaid.count");
        }
        return ids;
    }

    private UnsupportedMetricException unsupported() {
        return new UnsupportedMetricException("问题无法映射到已批准指标；支持的指标：" + catalog.supportedMetricSummary(),
                catalog.definitions().keySet().stream().toList());
    }

    private boolean containsAny(String value, String... candidates) {
        return java.util.Arrays.stream(candidates).anyMatch(value::contains);
    }
}
