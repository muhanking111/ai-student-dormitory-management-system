package com.example.dormitory.ai.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * LangChain4j 回退候选的离线行为 Spike。它使用 1.12.1 的真实请求/响应类型，
 * 但不连接供应商，也不把框架类型带入项目业务端口。
 */
class LangChain4jOfflineCompatibilityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void structuredOutputUsageAndFixedToolRequestCanBeMappedWithoutExecutingTheTool() throws Exception {
        ContractFixture fixture = fixture();
        AtomicReference<ChatRequest> observed = new AtomicReference<>();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                observed.set(request);
                if (request.toString().contains("tool-contract")) {
                    return ChatResponse.builder()
                            .aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                                    .id("call-1")
                                    .name("knowledge_search_v1")
                                    .arguments("{\"query\":\"维修时限\"}")
                                    .build()))
                            .modelName("offline-spike")
                            .tokenUsage(new TokenUsage(2, 1, 3))
                            .finishReason(FinishReason.TOOL_EXECUTION)
                            .build();
                }
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from("{\"answer\":\"合同\"}"))
                        .modelName("offline-spike")
                        .tokenUsage(new TokenUsage(2, 1, 3))
                        .finishReason(FinishReason.STOP)
                        .build();
            }
        };

        ChatResponse structured = model.chat(fixture.request("structured-contract"));

        assertEquals("合同", objectMapper.readTree(structured.aiMessage().text()).path("answer").asText());
        assertEquals(2, structured.tokenUsage().inputTokenCount());
        assertEquals(1, structured.tokenUsage().outputTokenCount());
        assertEquals(3, structured.tokenUsage().totalTokenCount());
        assertEquals(ResponseFormatType.JSON, observed.get().responseFormat().type());
        JsonObjectSchema observedSchema = assertInstanceOf(JsonObjectSchema.class,
                observed.get().responseFormat().jsonSchema().rootElement());
        assertEquals(List.of("answer"), observedSchema.required());
        assertFalse(observedSchema.additionalProperties());

        ChatResponse toolCall = model.chat(fixture.request("tool-contract"));

        assertEquals(FinishReason.TOOL_EXECUTION, toolCall.finishReason());
        assertEquals(1, toolCall.aiMessage().toolExecutionRequests().size());
        ToolExecutionRequest untrusted = toolCall.aiMessage().toolExecutionRequests().getFirst();
        assertEquals("knowledge_search_v1", untrusted.name());
        assertEquals("维修时限", objectMapper.readTree(untrusted.arguments()).path("query").asText());
        assertEquals("knowledge_search_v1", observed.get().toolSpecifications().getFirst().name());
        // SDK 只返回请求对象；本测试没有注册或执行动态工具。
        assertEquals(1, toolCall.aiMessage().toolExecutionRequests().size());
    }

    @Test
    void streamingEmitsOrderedDeltasAndTerminalUsageThroughTheRealHandlerContract() {
        ContractFixture fixture = fixture();
        StreamingChatModel model = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                handler.onPartialResponse("合");
                handler.onPartialResponse("同");
                handler.onCompleteResponse(ChatResponse.builder()
                        .aiMessage(AiMessage.from("合同"))
                        .modelName("offline-spike")
                        .tokenUsage(new TokenUsage(2, 1, 3))
                        .finishReason(FinishReason.STOP)
                        .build());
            }
        };
        List<String> deltas = new ArrayList<>();
        AtomicReference<ChatResponse> completed = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();

        model.chat(fixture.request("stream-contract"), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                deltas.add(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                completed.set(completeResponse);
            }

            @Override
            public void onError(Throwable failure) {
                error.set(failure);
            }
        });

        assertEquals(List.of("合", "同"), deltas);
        assertNull(error.get());
        assertEquals("合同", completed.get().aiMessage().text());
        assertEquals(3, completed.get().tokenUsage().totalTokenCount());
    }

    @Test
    void listenerReceivesRequestAndResponseMetadataWithoutPromptTags() {
        ContractFixture fixture = fixture();
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger responses = new AtomicInteger();
        ChatModelListener listener = new ChatModelListener() {
            @Override
            public void onRequest(ChatModelRequestContext context) {
                requests.incrementAndGet();
                assertEquals(ResponseFormatType.JSON, context.chatRequest().responseFormat().type());
            }

            @Override
            public void onResponse(ChatModelResponseContext context) {
                responses.incrementAndGet();
                assertEquals(3, context.chatResponse().tokenUsage().totalTokenCount());
            }
        };
        ChatModel model = new ChatModel() {
            @Override
            public List<ChatModelListener> listeners() {
                return List.of(listener);
            }

            @Override
            public ChatResponse doChat(ChatRequest request) {
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from("{\"answer\":\"合同\"}"))
                        .modelName("offline-spike")
                        .tokenUsage(new TokenUsage(2, 1, 3))
                        .finishReason(FinishReason.STOP)
                        .build();
            }
        };

        model.chat(fixture.request("observation-contract"));

        assertEquals(1, requests.get());
        assertEquals(1, responses.get());
    }

    private ContractFixture fixture() {
        JsonObjectSchema output = JsonObjectSchema.builder()
                .addStringProperty("answer")
                .required("answer")
                .additionalProperties(false)
                .build();
        ResponseFormat responseFormat = ResponseFormat.builder()
                .type(ResponseFormatType.JSON)
                .jsonSchema(JsonSchema.builder().name("ContractAnswer").rootElement(output).build())
                .build();
        ToolSpecification tool = ToolSpecification.builder()
                .name("knowledge_search_v1")
                .description("Search only the approved knowledge index")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("query")
                        .required("query")
                        .additionalProperties(false)
                        .build())
                .build();
        return new ContractFixture(responseFormat, tool);
    }

    private record ContractFixture(ResponseFormat responseFormat, ToolSpecification tool) {
        ChatRequest request(String prompt) {
            return ChatRequest.builder()
                    .messages(UserMessage.from(prompt))
                    .responseFormat(responseFormat)
                    .toolSpecifications(tool)
                    .build();
        }
    }
}
