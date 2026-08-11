package com.example.dormitory.ai.contract;

import com.example.dormitory.ai.application.control.BillingSubject;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.AiCapability;
import com.example.dormitory.ai.domain.model.AiStreamEvent;
import com.example.dormitory.ai.domain.model.ModelRequest;
import com.example.dormitory.ai.domain.model.ModelUsage;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiBudgetService;
import com.example.dormitory.ai.infrastructure.runtime.SpringAiModelRuntimeAdapter;
import com.example.dormitory.ai.port.AiEventStream;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实供应商合同。默认不运行；只有显式提供独立测试凭证和 AI_REAL_MODEL_TEST=true 才会执行。
 */
@ActiveProfiles({"test", "real-model-contract"})
@SpringBootTest(properties = {
        "dormitory.ai.provider.active=spring-ai",
        "spring.ai.model.chat=openai",
        "spring.datasource.url=jdbc:h2:mem:deepseek-real-model-contract;MODE=MySQL;DB_CLOSE_DELAY=-1"
})
@EnabledIfEnvironmentVariable(named = "AI_REAL_MODEL_TEST", matches = "(?i)true")
class RealModelContractIT {

    @Autowired
    private SpringAiModelRuntimeAdapter adapter;

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JdbcAiBudgetService budgets;

    @Value("${spring.ai.openai.chat.options.model}")
    private String modelName;

    @Value("${AI_REAL_MODEL_INPUT_COST_PER_MILLION:1}")
    private BigDecimal inputCost;

    @Value("${AI_REAL_MODEL_OUTPUT_COST_PER_MILLION:1}")
    private BigDecimal outputCost;

    private long budgetBucketId;

    @BeforeEach
    void prepareApprovedPricing() {
        jdbc.update("DELETE FROM ai_provider_attempt");
        jdbc.update("DELETE FROM ai_usage_ledger");
        jdbc.update("DELETE FROM ai_budget_reservation");
        jdbc.update("DELETE FROM ai_budget_bucket WHERE provider_code='spring-ai' "
                + "AND scope_key='real-model-contract'");
        jdbc.update("DELETE FROM ai_pricing_version WHERE provider_code='spring-ai' AND model_name=?", modelName);
        jdbc.update("INSERT INTO ai_pricing_version "
                        + "(provider_code,model_name,currency,input_cost_per_million,output_cost_per_million,"
                        + "effective_from,effective_to,source_reference,created_at,updated_at) "
                        + "VALUES ('spring-ai',?,'CNY',?,?,?,NULL,'real-contract://approved-price',"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                modelName, inputCost, outputCost, Timestamp.from(Instant.EPOCH));
        jdbc.update("INSERT INTO ai_budget_bucket "
                        + "(quota_policy_id,scope_type,scope_key,capability,provider_code,period_type,"
                        + "period_start,period_end,token_limit,cost_limit,reserved_tokens,committed_tokens,"
                        + "reserved_cost,committed_cost,currency,version,created_at,updated_at) "
                        + "VALUES (?,'USER','real-model-contract','ASSISTANT','spring-ai','DAILY',?,?,"
                        + "100000,100,0,0,0,0,'CNY',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                System.nanoTime(), Timestamp.from(Instant.now().minusSeconds(60)),
                Timestamp.from(Instant.now().plusSeconds(3600)));
        budgetBucketId = jdbc.queryForObject(
                "SELECT id FROM ai_budget_bucket WHERE provider_code='spring-ai' "
                        + "AND scope_key='real-model-contract' ORDER BY id DESC LIMIT 1",
                Long.class);
    }

    @Test
    void realProviderStreamsPlainTextAndReportsUsageWithoutToolCalls() {
        var events = new ArrayList<AiStreamEvent>();
        String billingId = UUID.randomUUID().toString();
        reserve(billingId);
        adapter.stream(ModelRequest.roleSeparated(
                        AiCapability.ASSISTANT,
                        "你是合同测试助手，只能按用户要求回复纯文本。",
                        "只回复两个汉字：合同",
                        java.util.List.of(new ModelRequest.UntrustedContent("PAGE_CONTEXT_JSON", "{}")),
                        Set.of(),
                        null,
                        Duration.ofSeconds(30),
                        32,
                        new ModelRequest.BillingTrace(billingId, 1, ActorDescriptor.user(1L))))
                .consume(events::add);

        String text = events.stream().map(AiStreamEvent::textDelta)
                .filter(java.util.Objects::nonNull).reduce("", String::concat);
        assertFalse(text.isBlank());
        assertTrue(events.stream().anyMatch(event -> event.type() == AiStreamEvent.Type.COMPLETED));
        assertTrue(events.stream().filter(event -> event.type() == AiStreamEvent.Type.USAGE)
                .anyMatch(event -> event.usage().source() == ModelUsage.Source.PROVIDER
                        && event.usage().inputTokens() > 0 && event.usage().outputTokens() > 0));
        assertTrue(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_usage_ledger WHERE billing_subject_public_id=? "
                        + "AND pricing_version_id IS NOT NULL AND attempt_outcome='SUCCEEDED'",
                Integer.class, billingId) == 1);
    }

    @Test
    void realProviderCompletesNonStreamingAndReportsUsage() {
        ChatResponse response = chatModel.call(new Prompt(List.of(
                new SystemMessage("你是 DeepSeek 合同测试助手，只能按要求简短回答。"),
                new UserMessage("只回复两个汉字：合同")),
                OpenAiChatOptions.builder()
                        .model(modelName)
                        .maxTokens(32)
                        .internalToolExecutionEnabled(false)
                        .build()));

        assertNotNull(response.getResult());
        assertFalse(response.getResult().getOutput().getText().isBlank());
        assertNotNull(response.getMetadata().getUsage());
        assertTrue(response.getMetadata().getUsage().getPromptTokens() > 0);
        assertTrue(response.getMetadata().getUsage().getCompletionTokens() > 0);
    }

    @Test
    void realProviderHonoursJsonObjectContract() throws Exception {
        ChatResponse response = chatModel.call(new Prompt(List.of(
                new SystemMessage("只输出合法 JSON 对象，不要 Markdown。"),
                new UserMessage("输出 JSON：answer 字段必须严格等于合同。")),
                OpenAiChatOptions.builder()
                        .model(modelName)
                        .temperature(0.0)
                        .maxTokens(64)
                        .responseFormat(ResponseFormat.builder()
                                .type(ResponseFormat.Type.JSON_OBJECT)
                                .build())
                        .internalToolExecutionEnabled(false)
                        .build()));

        JsonNode body = objectMapper.readTree(response.getResult().getOutput().getText());

        assertTrue(body.isObject());
        assertEquals("合同", body.path("answer").asText());
    }

    @Test
    void realProviderReturnsRequiredToolCallWithoutExecutingIt() throws Exception {
        AtomicBoolean executed = new AtomicBoolean();
        ToolCallback callback = FunctionToolCallback
                .<ContractToolInput, String>builder("echo_contract", input -> {
                    executed.set(true);
                    return input.value();
                })
                .description("Return the supplied contract value")
                .inputType(ContractToolInput.class)
                .build();
        ChatResponse response = chatModel.call(new Prompt(List.of(
                new SystemMessage("必须调用提供的函数，不得直接回答。"),
                new UserMessage("调用 echo_contract，value 必须是 contract-ok。")),
                OpenAiChatOptions.builder()
                        .model(modelName)
                        .maxTokens(64)
                        .toolCallbacks(callback)
                        .toolChoice("required")
                        .internalToolExecutionEnabled(false)
                        .extraBody(java.util.Map.of(
                                "thinking", java.util.Map.of("type", "disabled")))
                        .build()));

        assertTrue(response.hasToolCalls());
        var toolCall = response.getResult().getOutput().getToolCalls().getFirst();
        assertEquals("echo_contract", toolCall.name());
        assertEquals("contract-ok", objectMapper.readTree(toolCall.arguments()).path("value").asText());
        assertFalse(executed.get());
    }

    @Test
    void realProviderStreamingRequestCanBeCancelledAfterFirstDelta() {
        String billingId = UUID.randomUUID().toString();
        reserve(billingId);
        AtomicReference<AiEventStream> streamRef = new AtomicReference<>();
        AtomicBoolean cancellationRequested = new AtomicBoolean();
        List<AiStreamEvent> events = new CopyOnWriteArrayList<>();
        AiEventStream stream = adapter.stream(ModelRequest.roleSeparated(
                AiCapability.ASSISTANT,
                "你是合同测试助手，按顺序输出 1 到 200 的中文数字，每个数字单独一行。",
                "开始输出。",
                List.of(new ModelRequest.UntrustedContent("PAGE_CONTEXT_JSON", "{}")),
                Set.of(), null, Duration.ofSeconds(30), 512,
                new ModelRequest.BillingTrace(billingId, 1, ActorDescriptor.user(1L))));
        streamRef.set(stream);

        stream.consume(event -> {
            events.add(event);
            if (event.type() == AiStreamEvent.Type.TEXT_DELTA
                    && cancellationRequested.compareAndSet(false, true)) {
                assertTrue(streamRef.get().cancel());
            }
        });

        assertTrue(cancellationRequested.get());
        assertTrue(events.stream().anyMatch(event -> event.type() == AiStreamEvent.Type.CANCELLED));
        assertFalse(events.stream().anyMatch(event -> event.type() == AiStreamEvent.Type.COMPLETED));
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT attempt_outcome FROM ai_usage_ledger WHERE billing_subject_public_id=?",
                String.class, billingId));
    }

    private void reserve(String billingId) {
        budgets.reserve(new BillingSubject(BillingSubject.Kind.RUN, billingId), List.of(budgetBucketId),
                4_096, new BigDecimal("10.000000"), Instant.now().plusSeconds(600));
    }

    private record ContractToolInput(String value) {
    }
}
