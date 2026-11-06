package com.aicompany.agentharness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 한 번의 LLM 요청에 Harness가 허용하는 최대 예상 토큰 수.
 * Provider의 TPM/TPD와는 별도이다.
 */
@ConfigurationProperties("llm.token-budget")
public record RequestTokenBudgetProperties(@DefaultValue("6000") long maxRequestTokens) {

	public RequestTokenBudgetProperties {
		if (maxRequestTokens < 1) {
			throw new IllegalArgumentException("llm.token-budget.max-request-tokens must be at least 1: " + maxRequestTokens);
		}
	}

}
