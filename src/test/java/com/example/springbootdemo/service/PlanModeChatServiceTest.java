package com.example.springbootdemo.service;

import com.example.springbootdemo.config.PlanModeAgentConfiguration.PlanModeAgentClient;
import com.example.springbootdemo.model.PlanDetails;
import com.example.springbootdemo.model.PlanModeChatResult;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanModeChatServiceTest {

    @TempDir
    Path workspace;

    @Test
    void keepsSessionInPlanModeUntilExplicitApproval() {
        HarnessAgent agent = buildAgent(new PlanningModel());
        try (PlanModeAgentClient client = new PlanModeAgentClient(agent)) {
            PlanModeChatService service = new PlanModeChatService(client);
            RuntimeContext context = RuntimeContext.builder()
                    .userId("user-001")
                    .sessionId("session-001")
                    .build();

            PlanModeChatResult planResult = service.chat(
                    "制定计划", "user-001", "session-001", false);
            assertThat(planResult.mode()).isEqualTo("PLAN");
            assertThat(planResult.planFile()).isEqualTo("plans/PLAN.md");

            assertThat(agent.getDelegate().getAgentState(context).getContext().stream()
                    .flatMap(msg -> msg.getContentBlocks(ToolUseBlock.class).stream())
                    .filter(tool -> "plan_exit".equals(tool.getName())))
                    .anyMatch(tool -> tool.getState() == ToolCallState.ASKING);

            PlanDetails details = service.getPlan("user-001", "session-001");
            assertThat(details.mode()).isEqualTo("PLAN");
            assertThat(details.planFile()).isEqualTo("plans/PLAN.md");
            assertThat(details.planContent()).isEqualTo("Test plan");
            assertThat(details.awaitingApproval()).isTrue();
            assertThat(details.tasks()).isEmpty();

            PlanModeChatResult buildResult = service.chat(
                    "批准并执行", "user-001", "session-001", true);
            List<ToolResultBlock> toolResults = agent.getDelegate().getAgentState(context).getContext().stream()
                    .flatMap(msg -> msg.getContentBlocks(ToolResultBlock.class).stream())
                    .toList();
            assertThat(toolResults).anyMatch(result -> "plan_exit".equals(result.getName())
                    && result.getState() == ToolResultState.SUCCESS);
            assertThat(buildResult.mode()).isEqualTo("BUILD");
            assertThat(buildResult.planFile()).isEqualTo("plans/PLAN.md");
        }
    }

    @Test
    void approvesPlanWhenModelHasNotCalledPlanExit() {
        try (PlanModeAgentClient client = new PlanModeAgentClient(buildAgent())) {
            PlanModeChatService service = new PlanModeChatService(client);
            service.chat("Plan", "user-001", "session-001", false);
            assertThat(service.chat("Approve", "user-001", "session-001", true).mode())
                    .isEqualTo("BUILD");
        }
    }

    @Test
    void rejectsApprovalWhenNoPlanIsActive() {
        try (PlanModeAgentClient client = new PlanModeAgentClient(buildAgent())) {
            PlanModeChatService service = new PlanModeChatService(client);

            assertThatThrownBy(() -> service.chat(
                    "批准", "user-001", "session-002", true))
                    .isInstanceOf(PlanModeChatService.PlanApprovalException.class)
                    .hasMessageContaining("No plan is awaiting approval");
        }
    }

    @Test
    void rejectsPlanLookupForUnknownSession() {
        try (PlanModeAgentClient client = new PlanModeAgentClient(buildAgent())) {
            PlanModeChatService service = new PlanModeChatService(client);

            assertThatThrownBy(() -> service.getPlan("user-001", "missing-session"))
                    .isInstanceOf(PlanModeChatService.PlanNotFoundException.class)
                    .hasMessageContaining("No Plan Mode session was found");
        }
    }

    private HarnessAgent buildAgent() {
        return buildAgent(new StubModel());
    }

    private HarnessAgent buildAgent(Model model) {
        return HarnessAgent.builder()
                .name("plan-test-agent")
                .model(model)
                .workspace(workspace)
                .stateStore(new InMemoryAgentStateStore())
                .enablePlanMode()
                .enableTaskList()
                .disableSubagents()
                .disableMemoryTools()
                .disableWebTools()
                .build();
    }

    private static final class PlanningModel implements Model {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            int call = calls.getAndIncrement();
            if (call < 2) {
                String name = call == 0 ? "plan_write" : "plan_exit";
                Map<String, Object> input = call == 0
                        ? Map.of("content", "Test plan") : Map.of("summary", "Approve test plan");
                String content = call == 0
                        ? "{\"content\":\"Test plan\"}" : "{\"summary\":\"Approve test plan\"}";
                return Flux.just(ChatResponse.builder()
                        .content(List.of(ToolUseBlock.builder()
                                .id("call-" + name).name(name).input(input).content(content).build()))
                        .finishReason("tool_calls").build());
            }
            return Flux.just(ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text("Plan executed").build()))
                    .finishReason("stop").build());
        }

        @Override
        public String getModelName() {
            return "planning-test-model";
        }
    }

    private static final class StubModel implements Model {

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages,
                List<ToolSchema> tools,
                GenerateOptions options) {
            return Flux.just(ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text("stub response").build()))
                    .finishReason("stop")
                    .build());
        }

        @Override
        public String getModelName() {
            return "stub-model";
        }
    }
}
