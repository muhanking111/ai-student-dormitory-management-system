package com.example.dormitory.ai.risk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RiskSignalRegistry {

    private final List<RiskSignalProvider> providers;

    public RiskSignalRegistry(List<RiskSignalProvider> providers) {
        if (providers == null) throw new IllegalArgumentException("风险信号 provider 不能为空");
        Map<String, RiskSignalProvider> indexed = new LinkedHashMap<>();
        for (RiskSignalProvider provider : providers) {
            if (provider == null || provider.id() == null || provider.id().isBlank()
                    || provider.policyVersion() == null || provider.policyVersion().isBlank()) {
                throw new IllegalArgumentException("风险信号 provider 合同不完整");
            }
            if (indexed.putIfAbsent(provider.id(), provider) != null) {
                throw new IllegalArgumentException("风险信号 provider ID 重复: " + provider.id());
            }
        }
        this.providers = List.copyOf(indexed.values());
    }

    public ScanResult scan(RiskScanScope scope) {
        if (scope == null) throw new IllegalArgumentException("风险扫描必须携带 actor scope");
        Map<String, RiskSignal> unique = new LinkedHashMap<>();
        int duplicates = 0;
        List<String> versions = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        for (RiskSignalProvider provider : providers) {
            String providerVersion = provider.id() + "@" + provider.policyVersion();
            versions.add(providerVersion);
            try {
                List<RiskSignal> signals = provider.evaluate(scope);
                if (signals == null) throw new IllegalStateException("风险信号 provider 返回 null");
                for (RiskSignal signal : signals) {
                    if (signal == null || !provider.policyVersion().equals(signal.policyVersion())) {
                        throw new IllegalStateException("风险信号版本与 provider 不一致");
                    }
                    if (unique.putIfAbsent(signal.dedupKey(), signal) != null) duplicates++;
                }
            } catch (RuntimeException providerFailure) {
                // 不把数据库/供应商内部错误或可能含敏感信息的异常消息带入扫描结果。
                unavailable.add(providerVersion);
            }
        }
        return new ScanResult(List.copyOf(unique.values()), duplicates, List.copyOf(versions),
                List.copyOf(unavailable));
    }

    public record ScanResult(
            List<RiskSignal> signals,
            int duplicateCount,
            List<String> providerVersions,
            List<String> unavailableProviders) {
        public ScanResult {
            signals = List.copyOf(signals);
            providerVersions = List.copyOf(providerVersions);
            unavailableProviders = List.copyOf(unavailableProviders);
        }
    }
}
