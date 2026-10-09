package com.aicompany.agentharness.llm;

public class LlmException extends RuntimeException {

	private final LlmErrorCategory category;

	public LlmException(String message) {
		this(message, LlmErrorCategory.UNEXPECTED, null);
	}

	public LlmException(String message, Throwable cause) {
		this(message, categoryOf(cause), cause);
	}

	public LlmException(String message, LlmErrorCategory category) {
		this(message, category, null);
	}

	public LlmException(String message, LlmErrorCategory category, Throwable cause) {
		super(message, cause);
		this.category = category == null ? LlmErrorCategory.UNEXPECTED : category;
	}

	public LlmErrorCategory category() {
		return category;
	}

	private static LlmErrorCategory categoryOf(Throwable cause) {
		if (cause instanceof LlmException llmException) {
			return llmException.category();
		}
		return LlmErrorCategory.UNEXPECTED;
	}

}
