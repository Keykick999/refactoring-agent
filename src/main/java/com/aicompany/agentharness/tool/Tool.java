package com.aicompany.agentharness.tool;

import java.util.Map;

public interface Tool {

	String name();

	String description();

	/**
	 * 입력 인자의 JSON Schema 문자열.
	 * Tool 제공자(MCP 등)의 표현을 그대로 유지해 LLM에 전달할 수 있도록 문자열로 둔다.
	 */
	String inputSchema();

	/**
	 * 실패는 예외 대신 실패 ToolResult로 반환한다.
	 * 실패 이후의 행동은 Tool이 아니라 Agent가 판단해야 하기 때문이다.
	 */
	ToolResult execute(Map<String, Object> arguments);

}
