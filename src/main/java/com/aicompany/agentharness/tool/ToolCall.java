package com.aicompany.agentharness.tool;

import java.util.Map;
import java.util.Objects;

public record ToolCall(String id, String toolName, Map<String, Object> arguments) {

	public ToolCall {
		Objects.requireNonNull(toolName, "toolName must not be null");
		arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
	}

	public ToolCall(String toolName, Map<String, Object> arguments) {
		this(null, toolName, arguments);
	}

}
