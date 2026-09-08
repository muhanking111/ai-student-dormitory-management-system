package com.example.dormitory.ai.governance;

import com.example.dormitory.ai.approval.CanonicalJsonHasher;
import com.example.dormitory.ai.tool.ToolCatalog;
import com.example.dormitory.ai.tool.ToolDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Component
public class StandardToolCatalogManifest {

    private final String version;
    private final String manifest;
    private final String hash;
    private final List<String> toolIds;

    public StandardToolCatalogManifest(ToolCatalog catalog, ObjectMapper mapper) {
        this.version = catalog.version();
        this.toolIds = catalog.definitions().keySet().stream().sorted().toList();
        if (toolIds.size() != 7) {
            throw new IllegalStateException("首期 ToolCatalog 必须且只能包含 7 个标准工具");
        }
        Map<String, Object> root = new TreeMap<>();
        root.put("schemaVersion", "tool-catalog.v2");
        root.put("version", version);
        root.put("providerCallableToolIds", catalog.providerCallableIds().stream().sorted().toList());
        root.put("runtimeExecutableToolIds", catalog.runtimeExecutableIds().stream().sorted().toList());
        root.put("internalProposalToolIds", catalog.internalProposalIds().stream().sorted().toList());
        root.put("reservedToolIds", catalog.reservedIds().stream().sorted().toList());
        List<Map<String, Object>> tools = new ArrayList<>();
        for (String id : toolIds) {
            ToolDefinition definition = catalog.definitions().get(id);
            ToolCatalog.ExecutionMode executionMode = catalog.executionMode(id);
            Map<String, Object> item = new TreeMap<>();
            item.put("contractEnforcement", switch (executionMode) {
                case RUNTIME_CONTEXT -> "FIXED_EXECUTOR";
                case INTERNAL_PROPOSAL -> "SERVER_GENERATED";
                case RESERVED -> "NONE_RESERVED";
            });
            item.put("dataClassification", definition.dataClassification().name());
            item.put("deadlineEnforcement", executionMode == ToolCatalog.ExecutionMode.RUNTIME_CONTEXT
                    ? "RESULT_ADMISSION_NON_PREEMPTIVE" : "NOT_APPLICABLE");
            item.put("description", definition.description());
            item.put("id", definition.id());
            item.put("inputSchema", definition.inputSchemaJson().strip());
            item.put("kind", definition.kind().name());
            item.put("executionMode", executionMode.name());
            item.put("providerCallable", catalog.providerCallableIds().contains(id));
            item.put("maxCallsPerRun", definition.maxCallsPerRun());
            item.put("maxResponseBytes", definition.maxResponseBytes());
            item.put("outputSchema", definition.outputSchemaJson().strip());
            item.put("producesProposal", definition.producesProposal());
            item.put("requiredPermissions", definition.requiredPermissions().stream().sorted().toList());
            item.put("schemaVersion", definition.schemaVersion());
            item.put("timeoutMillis", definition.timeout().toMillis());
            tools.add(new LinkedHashMap<>(item));
        }
        root.put("tools", tools);
        try {
            this.manifest = CanonicalJsonHasher.canonicalize(mapper.writeValueAsString(root));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法生成标准 ToolCatalog manifest", exception);
        }
        this.hash = CanonicalJsonHasher.sha256(manifest);
    }

    public String version() {
        return version;
    }

    public String manifest() {
        return manifest;
    }

    public String hash() {
        return hash;
    }

    public List<String> toolIds() {
        return toolIds;
    }
}
