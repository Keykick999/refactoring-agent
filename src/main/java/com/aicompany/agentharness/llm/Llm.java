package com.aicompany.agentharness.llm;

public interface Llm {

	LlmResponse generate(LlmRequest request);

	/**
	 * Provider API의 tool call을 응답으로 받을 수 있으면 true다.
	 * 도구 이름 허용과 실행은 Agent와 AgentRunner가 담당한다.
	 */
	default boolean nativeToolCalls() {
		return false;
	}

}
