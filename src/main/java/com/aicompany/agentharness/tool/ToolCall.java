package com.aicompany.agentharness.tool;

import java.util.Map;
import java.util.Objects;

public record ToolCall(String toolName, Map<String, Object> arguments) {

	public ToolCall {
		Objects.requireNonNull(toolName, "toolName must not be null");
		arguments = Map.copyOf(arguments);
	}

}
