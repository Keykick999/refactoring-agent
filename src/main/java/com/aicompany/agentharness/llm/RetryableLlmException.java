package com.aicompany.agentharness.llm;

import java.time.Duration;
import java.util.Optional;

/**
 * 다시 호출하면 성공할 수 있는 일시적인 LLM 호출 실패(rate limit, 서버 오류, 네트워크 오류).
 */
public class RetryableLlmException extends LlmException {

	private final Duration retryAfter;

	/**
	 * @param retryAfter Provider가 요청한 대기 시간. 알 수 없으면 null.
	 */
	public RetryableLlmException(String message, Duration retryAfter) {
		super(message);
		this.retryAfter = retryAfter;
	}

	public RetryableLlmException(String message, Throwable cause) {
		super(message, cause);
		this.retryAfter = null;
	}

	public Optional<Duration> retryAfter() {
		return Optional.ofNullable(retryAfter);
	}

}
