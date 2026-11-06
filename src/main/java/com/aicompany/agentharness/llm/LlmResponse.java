package com.aicompany.agentharness.llm;

import com.aicompany.agentharness.tool.ToolCall;

import java.util.List;

public record LlmResponse(String content, List<ToolCall> toolCalls) {

	public LlmResponse {
		content = content == null ? "" : content;
		toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
	}

	public LlmResponse(String content) {
		this(content, List.of());
	}

}
