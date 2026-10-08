package com.aicompany.agentharness.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * 일시적인 실패({@link RetryableLlmException})만 backoff 후 다시 호출하는 Llm 데코레이터.
 *
 * <p>어떤 오류가 일시적인지는 Provider 구현체가 판단하고, 이 클래스는 재시도 횟수와 대기만 담당한다.
 * 모든 시도가 실패하면 LlmException을 던져 기존 실패 처리 흐름을 그대로 탄다.
 */
public class RetryingLlm implements Llm {

	private static final Logger log = LoggerFactory.getLogger(RetryingLlm.class);

	private final Llm delegate;
	private final LlmRetryPolicy retryPolicy;
	private final RetrySleeper sleeper;

	public RetryingLlm(Llm delegate, LlmRetryPolicy retryPolicy, RetrySleeper sleeper) {
		this.delegate = delegate;
		this.retryPolicy = retryPolicy;
		this.sleeper = sleeper;
	}

	@Override
	public LlmResponse generate(LlmRequest request) {
		for (int attempt = 1; ; attempt++) {
			try {
				return delegate.generate(request);
			}
			catch (RetryableLlmException exception) {
				if (attempt >= retryPolicy.maxAttempts()) {
					throw giveUp(exception, attempt);
				}
				Duration delay = retryPolicy.delayAfter(attempt, exception.retryAfter());
				// 요청 내용(프롬프트)은 로그에 남기지 않는다.
				log.warn("LLM request failed (provider={}, attempt={}/{}): {} Retrying in {}ms",
						providerName(), attempt, retryPolicy.maxAttempts(), exception.getMessage(), delay.toMillis());
				sleep(delay);
			}
		}
	}

	private LlmException giveUp(RetryableLlmException exception, int attempts) {
		if (attempts == 1) {
			return exception;
		}
		log.warn("LLM request failed (provider={}) after {} attempts; giving up", providerName(), attempts);
		return new LlmException("LLM request failed after " + attempts + " attempts: " + exception.getMessage(), exception);
	}

	private void sleep(Duration delay) {
		try {
			sleeper.sleep(delay);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new LlmException("Interrupted while waiting to retry LLM request", exception);
		}
	}

	private String providerName() {
		return delegate.getClass().getSimpleName();
	}

}
