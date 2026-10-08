package com.aicompany.agentharness.tool;

public record ToolResult(String toolName, boolean success, String output) {

	public static ToolResult success(String toolName, String output) {
		return new ToolResult(toolName, true, output);
	}

	public static ToolResult failure(String toolName, String errorMessage) {
		return new ToolResult(toolName, false, errorMessage);
	}

}
