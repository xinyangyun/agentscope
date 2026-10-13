package com.example.springbootdemo.config;

import com.example.springbootdemo.config.PlanModeAgentConfiguration.PlanModeAgentClient;
import com.example.springbootdemo.tool.FinancialNewsTool;
import com.example.springbootdemo.tool.MarketIndexTool;
import com.example.springbootdemo.tool.PushPlusTool;
import com.example.springbootdemo.tool.StockQuoteTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.extensions.model.openai.compat.deepseek.DeepSeekModelProvider;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.io.EOFException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;

class PlanModeAgentConfigurationTest {

    @TempDir
    Path workspace;

    @Test
    void retriesOnlyModelRequestAfterEofAndUsesNonStreamingResponse() throws Exception {
        HttpTransport transport = mock(HttpTransport.class);
        when(transport.execute(any(HttpRequest.class)))
                .thenThrow(new HttpTransportException("Connection closed", new EOFException("EOF reached while reading")))
                .thenReturn(modelResponse());

        Model model = modelWithTransport(transport, 3);
        assertThat(model.stream(List.of(new UserMessage("test")), List.of(), null)
                .blockLast().getContent())
                .anyMatch(block -> block instanceof TextBlock text && "ok".equals(text.getText()));
        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport, times(2)).execute(requests.capture());
        for (HttpRequest request : requests.getAllValues()) {
            assertThat(new ObjectMapper().readTree(request.getBody()).get("stream").booleanValue()).isFalse();
        }
        verify(transport, never()).stream(any());
    }

    @Test
    void limitsEofRetries() {
        HttpTransport transport = mock(HttpTransport.class);
        when(transport.execute(any(HttpRequest.class)))
                .thenThrow(new HttpTransportException("Connection closed", new EOFException("EOF")));
        Model model = modelWithTransport(transport, 2);

        assertThatThrownBy(() -> model.stream(List.of(new UserMessage("test")), List.of(), null).blockLast())
                .hasRootCauseInstanceOf(EOFException.class);
        verify(transport, times(2)).execute(any());
    }

    @Test
    void doesNotRetryAuthenticationFailure() {
        HttpTransport transport = mock(HttpTransport.class);
        when(transport.execute(any(HttpRequest.class)))
                .thenReturn(HttpResponse.builder().statusCode(401).body("{\"error\":{\"message\":\"Unauthorized\"}}").build());
        Model model = modelWithTransport(transport, 3);

        assertThatThrownBy(() -> model.stream(List.of(new UserMessage("test")), List.of(), null).blockLast())
                .isInstanceOf(RuntimeException.class);
        verify(transport).execute(any());
    }

    @Test
    void disablesRetryForStreamingAndValidatesBounds() {
        PlanModeAgentConfiguration config = new PlanModeAgentConfiguration();
        assertThat(config.modelOptions(true, 120, 3).getExecutionConfig().getMaxAttempts()).isEqualTo(1);
        assertThatThrownBy(() -> config.modelOptions(false, 0, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.modelOptions(false, 120, 6)).isInstanceOf(IllegalArgumentException.class);
    }

    private Model modelWithTransport(HttpTransport transport, int maxAttempts) {
        return new DeepSeekModelProvider().create("deepseek:deepseek-chat", ModelCreationContext.builder()
                .apiKey("test-key").baseUrl("https://example.com").stream(false)
                .component(HttpTransport.class, transport)
                .component(GenerateOptions.class, new PlanModeAgentConfiguration().modelOptions(false, 120, maxAttempts))
                .build());
    }

    private HttpResponse modelResponse() {
        return HttpResponse.builder().statusCode(200).body("""
                {"id":"test-response","object":"chat.completion","model":"deepseek-chat",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}
                """).build();
    }

    @Test
    void createsIndependentAgentWithPlanModeTools() {
        PlanModeAgentConfiguration configuration = new PlanModeAgentConfiguration();
        ObjectMapper objectMapper = new ObjectMapper();

        try (PlanModeAgentClient client = configuration.planModeAgentClient(
                "plan-assistant",
                "Plan before execution.",
                "deepseek-chat",
                "test-api-key",
                "https://api.deepseek.com",
                false,
                false,
                120,
                3,
                Path.of("skills").toString(),
                workspace.toString(),
                "plans-v2",
                false,
                new FinancialNewsTool(objectMapper, "https://example.com/news", 8),
                new PushPlusTool(objectMapper, "test-token", "https://example.com/push"),
                new StockQuoteTool(objectMapper, "https://example.com/quote"),
                new MarketIndexTool("https://example.com/index"))) {
            HarnessAgent agent = client.agent();
            RuntimeContext context = RuntimeContext.builder()
                    .userId("user-001")
                    .sessionId("session-001")
                    .build();

            assertThat(agent.getToolkit().getToolNames())
                    .contains("plan_enter", "plan_write", "plan_exit", "todo_write");
            assertThat(agent.getStateStore()).isInstanceOf(InMemoryAgentStateStore.class);

            agent.enterPlanMode(context);
            assertThat(agent.isPlanModeActive(context)).isTrue();
            assertThat(agent.getDelegate().getAgentState(context)
                    .getPlanModeContext().getCurrentPlanFile())
                    .isEqualTo("plans-v2/PLAN.md");
        }
    }
}
