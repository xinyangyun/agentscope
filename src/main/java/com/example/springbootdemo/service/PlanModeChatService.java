package com.example.springbootdemo.service;

import com.example.springbootdemo.config.PlanModeAgentConfiguration.PlanModeAgentClient;
import com.example.springbootdemo.model.PlanDetails;
import com.example.springbootdemo.model.PlanModeChatResult;
import com.example.springbootdemo.model.PlanTask;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.Task;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

@Service
public class PlanModeChatService {

    private static final Logger log = LoggerFactory.getLogger(PlanModeChatService.class);

    private final HarnessAgent agent;

    public PlanModeChatService(PlanModeAgentClient client) {
        this.agent = client.agent();
    }

    public PlanModeChatResult chat(
            String message,
            String userId,
            String sessionId,
            boolean approvePlan) {
        RuntimeContext context = RuntimeContext.builder()
                .userId(userId)
                .sessionId(sessionId)
                .build();

        AgentState state = agent.getDelegate().getAgentState(context);
        boolean planActive = state.getPlanModeContext().isPlanActive();
        List<ToolUseBlock> pendingPlanExits = pendingPlanExits(state);
        UserMessage userMessage;
        if (approvePlan) {
            if (!planActive && pendingPlanExits.isEmpty()) {
                throw new PlanApprovalException("No plan is awaiting approval for this session");
            }
            if (pendingPlanExits.isEmpty()) {
                agent.exitPlanMode(context);
                userMessage = new UserMessage(message);
            } else {
                List<ConfirmResult> confirmResults = pendingPlanExits.stream()
                        .map(toolCall -> new ConfirmResult(true, toolCall))
                        .toList();
                userMessage = UserMessage.builder()
                        .textContent(message)
                        .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, confirmResults))
                        .build();
            }
        } else {
            if (!planActive) {
                agent.enterPlanMode(context);
            }
            userMessage = new UserMessage(message);
        }

        Msg response;
        try {
            response = agent.call(userMessage, context).block();
        } catch (RuntimeException exception) {
            log.error("Plan Mode call failed [userId={}, sessionId={}]", userId, sessionId, exception);
            throw new AgentChatService.AgentChatException(
                    "Model request failed: " + rootMessage(exception), exception);
        }
        if (response == null) {
            throw new IllegalStateException("Plan Mode agent returned no response");
        }

        state = agent.getDelegate().getAgentState(context);
        boolean activeAfterCall = state.getPlanModeContext().isPlanActive();
        return new PlanModeChatResult(
                response.getTextContent(),
                activeAfterCall ? "PLAN" : "BUILD",
                state.getPlanModeContext().getCurrentPlanFile());
    }

    public PlanDetails getPlan(String userId, String sessionId) {
        if (!agent.getStateStore().exists(userId, sessionId)) {
            throw new PlanNotFoundException("No Plan Mode session was found for the supplied userId and sessionId");
        }

        RuntimeContext context = RuntimeContext.builder()
                .userId(userId)
                .sessionId(sessionId)
                .build();
        AgentState state = agent.getDelegate().getAgentState(context);
        String planFile = state.getPlanModeContext().getCurrentPlanFile();
        String planContent = planFile == null
                ? ""
                : agent.workspaceFor(userId, sessionId).readManagedWorkspaceFileUtf8(context, planFile);
        List<PlanTask> tasks = state.getTasksContext().getTasks().stream()
                .map(this::toPlanTask)
                .toList();

        return new PlanDetails(
                state.getPlanModeContext().isPlanActive() ? "PLAN" : "BUILD",
                planFile,
                planContent,
                !pendingPlanExits(state).isEmpty(),
                tasks);
    }

    private PlanTask toPlanTask(Task task) {
        return new PlanTask(
                task.getId(),
                task.getSubject(),
                task.getDescription(),
                task.getState().getWire());
    }

    private List<ToolUseBlock> pendingPlanExits(AgentState state) {
        return state.getContext().stream()
                .flatMap(message -> message.getContentBlocks(ToolUseBlock.class).stream())
                .filter(toolCall -> "plan_exit".equals(toolCall.getName()))
                .filter(toolCall -> toolCall.getState() == ToolCallState.ASKING)
                .toList();
    }

    private String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    public static class PlanApprovalException extends RuntimeException {

        public PlanApprovalException(String message) {
            super(message);
        }
    }

    public static class PlanNotFoundException extends RuntimeException {

        public PlanNotFoundException(String message) {
            super(message);
        }
    }
}
