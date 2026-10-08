package com.aicompany.agentharness.harness;

import com.aicompany.agentharness.tool.ToolCall;

import java.util.Objects;

public record AgentDecision(Type type, ToolCall toolCall, AgentResult result) {

	public enum Type {
		USE_TOOL,
		FINISH
	}

	public static AgentDecision useTool(ToolCall toolCall) {
		return new AgentDecision(Type.USE_TOOL, Objects.requireNonNull(toolCall, "toolCall must not be null"), null);
	}

	public static AgentDecision finish(AgentResult result) {
		return new AgentDecision(Type.FINISH, null, Objects.requireNonNull(result, "result must not be null"));
	}

}
