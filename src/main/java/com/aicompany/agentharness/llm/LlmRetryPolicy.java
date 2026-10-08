package com.aicompany.agentharness.llm;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * @param maxAttempts    첫 호출을 포함한 최대 호출 횟수
 * @param initialBackoff 첫 실패 후 대기 시간
 * @param multiplier     실패할 때마다 대기 시간에 곱하는 값
 * @param maxBackoff     대기 시간 상한. Provider가 요청한 대기 시간에도 적용한다.
 * @param jitter         대기 시간을 ±비율만큼 무작위로 흔든다(0.1 = ±10%). 0이면 사용하지 않는다.
 */
public record LlmRetryPolicy(int maxAttempts, Duration initialBackoff, double multiplier, Duration maxBackoff,
		double jitter) {

	public LlmRetryPolicy {
		if (maxAttempts < 1) {
			throw new IllegalArgumentException("maxAttempts must be at least 1: " + maxAttempts);
		}
		if (initialBackoff.isNegative() || maxBackoff.isNegative()) {
			throw new IllegalArgumentException("backoff must not be negative");
		}
		if (multiplier < 1) {
			throw new IllegalArgumentException("multiplier must be at least 1: " + multiplier);
		}
		if (jitter < 0 || jitter >= 1) {
			throw new IllegalArgumentException("jitter must be in [0, 1): " + jitter);
		}
	}

	/**
	 * Provider가 대기 시간을 지정하면 그 값을, 아니면 exponential backoff 값을 사용한다. 어느 쪽이든 maxBackoff를 넘지 않는다.
	 */
	public Duration delayAfter(int failedAttempt, Optional<Duration> retryAfter) {
		if (retryAfter.isPresent()) {
			return retryAfter.get().compareTo(maxBackoff) > 0 ? maxBackoff : retryAfter.get();
		}
		double backoffMillis = initialBackoff.toMillis() * Math.pow(multiplier, failedAttempt - 1);
		if (jitter > 0) {
			backoffMillis *= 1 + jitter * ThreadLocalRandom.current().nextDouble(-1, 1);
		}
		return Duration.ofMillis(Math.round(Math.min(backoffMillis, maxBackoff.toMillis())));
	}

}
