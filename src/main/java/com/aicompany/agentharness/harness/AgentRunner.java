package com.aicompany.agentharness.harness;

import com.aicompany.agentharness.tool.Tool;
import com.aicompany.agentharness.tool.ToolCall;
import com.aicompany.agentharness.tool.ToolPermissionPolicy;
import com.aicompany.agentharness.tool.ToolRegistry;
import com.aicompany.agentharness.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public class AgentRunner {

	private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

	private final ToolRegistry toolRegistry;
	private final ToolPermissionPolicy toolPermissionPolicy;
	private final int maxSteps;

	public AgentRunner(ToolRegistry toolRegistry, ToolPermissionPolicy toolPermissionPolicy, int maxSteps) {
		if (maxSteps < 1) {
			throw new IllegalArgumentException("maxSteps must be at least 1: " + maxSteps);
		}
		this.toolRegistry = toolRegistry;
		this.toolPermissionPolicy = toolPermissionPolicy;
		this.maxSteps = maxSteps;
	}

	public AgentResult run(Agent agent, Task task) {
		AgentState agentState = new AgentState(task);

		for (int step = 0; step < maxSteps; step++) {
			AgentDecision decision = agent.decide(agentState);

			if (decision.type() == AgentDecision.Type.FINISH) {
				return finish(agentState, decision.result());
			}

			ToolResult toolResult = executeTool(decision.toolCall());
			agentState.addToolResult(toolResult);
		}

		return finish(agentState, AgentResult.failure("Agent did not finish within " + maxSteps + " steps"));
	}

	private ToolResult executeTool(ToolCall toolCall) {
		String toolName = toolCall.toolName();
		// 알 수 없는 Tool과 거부된 Tool도 실패 결과로 State에 남겨, 다음 행동은 Agent가 판단하게 한다.
		Optional<Tool> tool = toolRegistry.findByName(toolName);
		if (tool.isEmpty()) {
			return ToolResult.failure(toolName, "Unknown tool: " + toolName);
		}
		// Agent에게 허용된 Tool만 보여주더라도 LLM 출력은 신뢰할 수 없으므로 실행 직전에 다시 확인한다.
		if (!toolPermissionPolicy.isAllowed(toolName)) {
			log.warn("Denied execution of tool not permitted by policy: {}", toolName);
			return ToolResult.failure(toolName, "Tool execution denied: " + toolName);
		}
		return tool.get().execute(toolCall.arguments());
	}

	private AgentResult finish(AgentState agentState, AgentResult agentResult) {
		agentState.changeStatus(agentResult.success() ? AgentStatus.COMPLETED : AgentStatus.FAILED);
		return agentResult;
	}

}
