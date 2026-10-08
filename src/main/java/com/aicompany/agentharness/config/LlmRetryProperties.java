package com.aicompany.agentharness.config;

import com.aicompany.agentharness.llm.LlmRetryPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * LLM 호출 Retry 설정. 각 값의 의미는 {@link LlmRetryPolicy} 참고.
 *
 * @param enabled false이면 Retry 없이 첫 실패를 그대로 전달한다.
 */
@ConfigurationProperties("llm.retry")
public record LlmRetryProperties(
		@DefaultValue("true") boolean enabled,
		@DefaultValue("3") int maxAttempts,
		@DefaultValue("1s") Duration initialBackoff,
		@DefaultValue("2.0") double multiplier,
		@DefaultValue("10s") Duration maxBackoff,
		@DefaultValue("0.1") double jitter) {

	public LlmRetryPolicy toPolicy() {
		return new LlmRetryPolicy(maxAttempts, initialBackoff, multiplier, maxBackoff, jitter);
	}

}
