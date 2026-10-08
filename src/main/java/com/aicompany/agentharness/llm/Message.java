package com.aicompany.agentharness.llm;

public record Message(MessageRole role, String content) {

	public static Message system(String content) {
		return new Message(MessageRole.SYSTEM, content);
	}

	public static Message user(String content) {
		return new Message(MessageRole.USER, content);
	}

	public static Message assistant(String content) {
		return new Message(MessageRole.ASSISTANT, content);
	}

}
