package com.aicompany.agentharness.llm;

/**
 * Provider API로 요청을 보내기 직전에 허용될 때까지 기다린다.
 * 단일 JVM에서는 {@link InMemoryLlmRateLimiter}를 쓰고, 여러 서버가 같은 한도를 나누려면 분산 구현으로 바꾼다.
 */
@FunctionalInterface
public interface LlmRateLimiter {

	LlmRateLimiter UNLIMITED = () -> {
	};

	void acquire();

}
