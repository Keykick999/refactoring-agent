package com.aicompany.agentharness.llm;

import com.aicompany.agentharness.tool.ToolCall;

import java.util.List;

public record Message(MessageRole role, String content, List<ToolCall> toolCalls, String toolCallId) {

	public Message {
		toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
		content = content == null ? "" : content;
	}

	public Message(MessageRole role, String content) {
		this(role, content, List.of(), null);
	}

	public static Message system(String content) {
		return new Message(MessageRole.SYSTEM, content);
	}

	public static Message user(String content) {
		return new Message(MessageRole.USER, content);
	}

	public static Message assistant(String content) {
		return new Message(MessageRole.ASSISTANT, content);
	}

	public static Message assistantToolCall(ToolCall toolCall) {
		return new Message(MessageRole.ASSISTANT, "", List.of(toolCall), null);
	}

	public static Message toolResult(String toolCallId, String content) {
		return new Message(MessageRole.TOOL, content, List.of(), toolCallId);
	}

}
