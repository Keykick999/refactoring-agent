package com.aicompany.agentharness.llm;

import com.aicompany.agentharness.tool.ToolDefinition;

import java.util.List;

/**
 * @param responseJsonSchema 응답이 따라야 할 JSON Schema 문자열. null이면 자유 형식 텍스트 응답을 요청한다.
 * @param tools              Provider native tool call에 넘길 도구 정의. 비어 있으면 기존 텍스트 응답만 요청한다.
 */
public record LlmRequest(List<Message> messages, String responseJsonSchema, List<ToolDefinition> tools) {

	public LlmRequest {
		messages = List.copyOf(messages);
		tools = tools == null ? List.of() : List.copyOf(tools);
	}

	public LlmRequest(List<Message> messages, String responseJsonSchema) {
		this(messages, responseJsonSchema, List.of());
	}

	public LlmRequest(List<Message> messages) {
		this(messages, null, List.of());
	}

}
