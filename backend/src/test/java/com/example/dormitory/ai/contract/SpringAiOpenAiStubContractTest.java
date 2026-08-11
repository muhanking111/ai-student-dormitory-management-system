package com.example.dormitory.ai.contract;

import com.example.dormitory.ai.application.control.BillingSubject;
import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.AiCapability;
import com.example.dormitory.ai.domain.model.AiStreamEvent;
import com.example.dormitory.ai.domain.model.ModelRequest;
import com.example.dormitory.ai.infrastructure.persistence.JdbcAiBudgetService;
import com.example.dormitory.ai.infrastructure.runtime.SpringAiModelRuntimeAdapter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles({"test", "ai-contract"})
@AutoConfigureObservability
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:spring-ai-stub-contract;MODE=MySQL;DB_CLOSE_DELAY=-1"
})
class SpringAiOpenAiStubContractTest {

    private static final HttpServer SERVER = startServer();
    private static final AtomicInteger RETRY_REQUESTS = new AtomicInteger();
    private static final AtomicBoolean TOOL_REQUEST_DISABLED_THINKING = new AtomicBoolean();

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private SpringAiModelRuntimeAdapter adapter;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JdbcAiBudgetService budgets;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void prepareRuntimeControlFacts() {
        RETRY_REQUESTS.set(0);
        TOOL_REQUEST_DISABLED_THINKING.set(false);
        for (String table : new String[]{"ai_run_event", "ai_usage_ledger", "ai_provider_attempt",
                "ai_budget_reservation",
                "ai_run", "ai_message", "ai_conversation", "ai_budget_bucket", "ai_quota_policy",
                "ai_audit_event", "ai_audit_chain_head", "ai_tool_catalog_version", "ai_pricing_version"}) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("INSERT INTO ai_pricing_version "
                        + "(provider_code,model_name,currency,input_cost_per_million,output_cost_per_million,"
                        + "effective_from,effective_to,source_reference,created_at,updated_at) "
                        + "VALUES ('spring-ai','stub-model','CNY',1,2,?,NULL,'stub://pricing-v1',"
                        + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                Timestamp.from(Instant.EPOCH));
        jdbc.update("UPDATE ai_prompt_version SET status='DRAFT', active_slot_key=NULL, activated_at=NULL");
        jdbc.update("UPDATE ai_prompt_version SET status='ACTIVE', active_slot_key='assistant.system', "
                + "activated_at=CURRENT_TIMESTAMP WHERE prompt_key='assistant.system' AND version='v1'");
        jdbc.update("INSERT INTO ai_tool_catalog_version "
                        + "(version, manifest_text, manifest_hash, status, active_slot_key, activated_at, created_at, updated_at) "
                        + "VALUES ('stub-v1', '{\"tools\":[]}', ?, 'ACTIVE', 'runtime', CURRENT_TIMESTAMP, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                "c".repeat(64));
        jdbc.update("INSERT INTO ai_quota_policy "
                        + "(scope_type, scope_key, capability, daily_token_limit, monthly_cost_limit, "
                        + "concurrent_run_limit, status, effective_from, created_at, updated_at) "
                        + "VALUES ('GLOBAL', '*', 'ASSISTANT', 1000000, 1000, 2, 'ACTIVE', ?, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                Timestamp.from(Instant.now().minusSeconds(60)));
        Long policyId = jdbc.queryForObject("SELECT MAX(id) FROM ai_quota_policy", Long.class);
        jdbc.update("INSERT INTO ai_budget_bucket "
                        + "(quota_policy_id, scope_type, scope_key, capability, provider_code, period_type, "
                        + "period_start, period_end, token_limit, cost_limit, reserved_tokens, committed_tokens, "
                        + "reserved_cost, committed_cost, currency, version, created_at, updated_at) "
                        + "VALUES (?, 'GLOBAL', '*', 'ASSISTANT', 'spring-ai', 'DAY', ?, ?, 1000000, 1000, "
                        + "0, 0, 0, 0, 'CNY', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                policyId, Timestamp.from(Instant.now().minusSeconds(60)),
                Timestamp.from(Instant.now().plusSeconds(3600)));
    }

    @DynamicPropertySource
    static void provider(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url",
                () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @Test
    void selectedAdapterUsesRealSpringAiHttpStreamingUsageAndObservation() {
        var events = new ArrayList<AiStreamEvent>();
        String billingId = UUID.randomUUID().toString();
        reserveProviderBudget(billingId);

        adapter.stream(ModelRequest.roleSeparated(AiCapability.ASSISTANT,
                        "只输出合同测试所需纯文本。", "stream-contract",
                        java.util.List.of(new ModelRequest.UntrustedContent("PAGE_CONTEXT_JSON", "{}")),
                        Set.of(), null, Duration.ofSeconds(5), 32,
                        new ModelRequest.BillingTrace(billingId, 1, ActorDescriptor.user(1L))))
                .consume(events::add);

        assertEquals("合同", events.stream().map(AiStreamEvent::textDelta)
                .filter(java.util.Objects::nonNull).reduce("", String::concat));
        assertTrue(events.stream().anyMatch(event -> event.type() == AiStreamEvent.Type.COMPLETED));
        assertTrue(events.stream().filter(event -> event.type() == AiStreamEvent.Type.USAGE)
                .anyMatch(event -> event.usage().inputTokens() == 2 && event.usage().outputTokens() == 1));
        assertTrue(meters.getMeters().stream().map(meter -> meter.getId().getName())
                .anyMatch(name -> name.contains("gen_ai") || name.contains("spring.ai")));
    }

    @Test
    void transientFiveHundredIsRetriedOnceAndEveryPhysicalRequestHasItsOwnLedgerRow() {
        String billingId = UUID.randomUUID().toString();
        var events = new ArrayList<AiStreamEvent>();
        reserveProviderBudget(billingId);

        adapter.stream(ModelRequest.roleSeparated(AiCapability.ASSISTANT,
                        "只输出合同测试所需纯文本。", "retry-contract",
                        java.util.List.of(new ModelRequest.UntrustedContent("PAGE_CONTEXT_JSON", "{}")),
                        Set.of(), null, Duration.ofSeconds(5), 32,
                        new ModelRequest.BillingTrace(billingId, 1, ActorDescriptor.user(1L))))
                .consume(events::add);

        assertTrue(events.stream().anyMatch(event -> event.type() == AiStreamEvent.Type.COMPLETED));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_usage_ledger WHERE billing_subject_public_id=?",
                Integer.class, billingId));
        assertEquals(List.of("FAILED_RETRYABLE", "SUCCEEDED"), jdbc.queryForList(
                "SELECT attempt_outcome FROM ai_usage_ledger WHERE billing_subject_public_id=? ORDER BY attempt_no",
                String.class, billingId));
        assertEquals(2, RETRY_REQUESTS.get());
    }

    private void reserveProviderBudget(String billingId) {
        Long bucketId = jdbc.queryForObject(
                "SELECT id FROM ai_budget_bucket WHERE provider_code='spring-ai' ORDER BY id LIMIT 1",
                Long.class);
        budgets.reserve(new BillingSubject(BillingSubject.Kind.RUN, billingId), List.of(bucketId),
                4_096, new BigDecimal("10.000000"), Instant.now().plusSeconds(600));
    }

    @Test
    void springAiStructuredOutputConverterParsesTheDeclaredSchema() {
        BeanOutputConverter<ContractAnswer> converter = new BeanOutputConverter<>(ContractAnswer.class);
        String raw = chatModel.call(new Prompt("structured-contract\n" + converter.getFormat()))
                .getResult().getOutput().getText();

        ContractAnswer answer = converter.convert(raw);

        assertEquals("合同", answer.answer());
        assertFalse(converter.getJsonSchema().isBlank());
    }

    @Test
    void springAiToolCallbackExecutesOnlyTheExplicitlyRegisteredObject() {
        String result = ChatClient.builder(chatModel).build().prompt()
                .user("multiply-contract: multiply 2 and 3")
                .tools(new MathTools())
                .call()
                .content();

        assertEquals("6", result);
    }

    @Test
    void deepSeekRequiredToolRequestSerializesNonThinkingMode() {
        ToolCallback callback = FunctionToolCallback
                .<MultiplyInput, Double>builder("multiply", input -> input.a() * input.b())
                .description("Multiply two numbers")
                .inputType(MultiplyInput.class)
                .build();
        ChatResponse response = chatModel.call(new Prompt(
                "multiply-contract: multiply 2 and 3 by calling the tool",
                OpenAiChatOptions.builder()
                        .model("stub-model")
                        .toolCallbacks(callback)
                        .toolChoice("required")
                        .internalToolExecutionEnabled(false)
                        .extraBody(Map.of("thinking", Map.of("type", "disabled")))
                        .build()));

        assertTrue(response.hasToolCalls());
        assertTrue(TOOL_REQUEST_DISABLED_THINKING.get());
    }

    @Test
    void controllerRunGateSpringAiHttpAndMysqlUsageFormOneRealApplicationContract() throws Exception {
        String token = login();
        MvcResult conversation = mockMvc.perform(post("/api/ai/conversations")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"surface\":\"DASHBOARD\"}"))
                .andExpect(status().isCreated()).andReturn();
        String conversationId = objectMapper.readTree(conversation.getResponse().getContentAsString())
                .path("data").path("id").asText();
        String clientRequestId = UUID.randomUUID().toString();
        MvcResult accepted = mockMvc.perform(post("/api/ai/conversations/{id}/messages", conversationId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "text", "stream-contract", "clientRequestId", clientRequestId))))
                .andExpect(status().isAccepted()).andReturn();
        String runId = objectMapper.readTree(accepted.getResponse().getContentAsString())
                .path("data").path("runId").asText();
        awaitSucceeded(runId);

        String events = mockMvc.perform(get("/api/ai/runs/{id}/events", runId)
                        .header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertTrue(events.contains("event:message.delta"));
        assertTrue(events.contains("合同"));
        assertTrue(events.contains("event:run.completed"));
        assertEquals("spring-ai", jdbc.queryForObject(
                "SELECT provider_code FROM ai_usage_ledger WHERE billing_subject_public_id=?",
                String.class, runId));
        assertEquals("stub-model", jdbc.queryForObject(
                "SELECT model_name FROM ai_usage_ledger WHERE billing_subject_public_id=?",
                String.class, runId));
        assertEquals("PROVIDER", jdbc.queryForObject(
                "SELECT usage_source FROM ai_usage_ledger WHERE billing_subject_public_id=?",
                String.class, runId));
        assertEquals("SUCCEEDED", jdbc.queryForObject(
                "SELECT attempt_outcome FROM ai_usage_ledger WHERE billing_subject_public_id=?",
                String.class, runId));
        assertNotNull(jdbc.queryForObject(
                "SELECT pricing_version_id FROM ai_usage_ledger WHERE billing_subject_public_id=?",
                Long.class, runId));
        assertEquals(3L, jdbc.queryForObject(
                "SELECT committed_tokens FROM ai_budget_reservation WHERE billing_subject_public_id=?",
                Long.class, runId));
    }

    private String login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"test-password-123\"}"))
                .andExpect(status().isOk()).andReturn();
        var cookie = result.getResponse().getCookie("Authorization");
        assertNotNull(cookie);
        return cookie.getValue();
    }

    private void awaitSucceeded(String runId) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            String state = jdbc.queryForObject("SELECT state FROM ai_run WHERE public_id=?", String.class, runId);
            if ("SUCCEEDED".equals(state)) return;
            if ("FAILED".equals(state)) {
                String code = jdbc.queryForObject("SELECT failure_code FROM ai_run WHERE public_id=?", String.class, runId);
                throw new AssertionError("Spring AI application run 失败: " + code);
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Spring AI application run 未终止");
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", SpringAiOpenAiStubContractTest::respond);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            return server;
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        try (exchange) {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode requestJson = new ObjectMapper().readTree(request);
            if (requestJson.path("tools").isArray()) {
                TOOL_REQUEST_DISABLED_THINKING.set("disabled".equals(
                        requestJson.path("thinking").path("type").asText()));
            }
            if (request.contains("retry-contract") && RETRY_REQUESTS.incrementAndGet() == 1) {
                byte[] body = "{\"error\":{\"message\":\"temporary\",\"type\":\"server_error\"}}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(500, body.length);
                exchange.getResponseBody().write(body);
                return;
            }
            if (request.contains("\"stream\":true")) {
                byte[] body = ("data: " + streamChunk("合同", null, false) + "\n\n"
                        + "data: " + streamChunk("", "stop", true) + "\n\n"
                        + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                return;
            }
            String response;
            if (request.contains("structured-contract")) {
                response = completion("{\"answer\":\"合同\"}", "stop", null);
            } else if (request.contains("\"role\":\"tool\"")) {
                response = completion("6", "stop", null);
            } else if (request.contains("multiply-contract")) {
                response = completion("", "tool_calls",
                        "\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\","
                                + "\"function\":{\"name\":\"multiply\","
                                + "\"arguments\":\"{\\\"a\\\":2,\\\"b\\\":3}\"}}]");
            } else {
                response = completion("合同", "stop", null);
            }
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    private static String completion(String content, String finishReason, String extraMessageField) {
        String extra = extraMessageField == null ? "" : "," + extraMessageField;
        return "{\"id\":\"chatcmpl-stub\",\"object\":\"chat.completion\",\"created\":1,"
                + "\"model\":\"stub-model\",\"choices\":[{\"index\":0,\"message\":{"
                + "\"role\":\"assistant\",\"content\":" + json(content) + extra + "},"
                + "\"finish_reason\":\"" + finishReason + "\"}],"
                + "\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1,\"total_tokens\":3}}";
    }

    private static String streamChunk(String content, String finishReason, boolean usage) {
        String finish = finishReason == null ? "null" : "\"" + finishReason + "\"";
        String usageJson = usage
                ? "{\"prompt_tokens\":2,\"completion_tokens\":1,\"total_tokens\":3}"
                : "null";
        return "{\"id\":\"chatcmpl-stub\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                + "\"model\":\"stub-model\",\"choices\":[{\"index\":0,\"delta\":{"
                + "\"content\":" + json(content) + "},\"finish_reason\":" + finish + "}],"
                + "\"usage\":" + usageJson + "}";
    }

    private static String json(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    record ContractAnswer(@JsonProperty(required = true) String answer) {
    }

    record MultiplyInput(double a, double b) {
    }

    static final class MathTools {
        @Tool(name = "multiply", description = "Multiply two numbers")
        double multiply(double a, double b) {
            return a * b;
        }
    }
}
