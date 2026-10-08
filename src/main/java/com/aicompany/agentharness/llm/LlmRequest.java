package com.aicompany.agentharness.llm;

import java.util.List;

/**
 * @param responseJsonSchema 응답이 따라야 할 JSON Schema 문자열. null이면 자유 형식 텍스트 응답을 요청한다.
 */
public record LlmRequest(List<Message> messages, String responseJsonSchema) {

	public LlmRequest {
		messages = List.copyOf(messages);
	}

	public LlmRequest(List<Message> messages) {
		this(messages, null);
	}

}
