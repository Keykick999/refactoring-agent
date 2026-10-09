package com.aicompany.agentharness.llm;

import java.time.Duration;
import java.util.Optional;

/**
 * 다시 호출하면 성공할 수 있는 일시적인 LLM 호출 실패(rate limit, 서버 오류, 네트워크 오류).
 */
public class RetryableLlmException extends LlmException {

	private final Duration retryAfter;

	public RetryableLlmException(String message, Duration retryAfter) {
		this(message, LlmErrorCategory.RATE_LIMIT, retryAfter, null);
	}

	public RetryableLlmException(String message, Throwable cause) {
		this(message, LlmErrorCategory.NETWORK, null, cause);
	}

	public RetryableLlmException(String message, LlmErrorCategory category, Duration retryAfter) {
		this(message, category, retryAfter, null);
	}

	private RetryableLlmException(String message, LlmErrorCategory category, Duration retryAfter, Throwable cause) {
		super(message, category, cause);
		this.retryAfter = retryAfter;
	}

	public Optional<Duration> retryAfter() {
		return Optional.ofNullable(retryAfter);
	}

}
