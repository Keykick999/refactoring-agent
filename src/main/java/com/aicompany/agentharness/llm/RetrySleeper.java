package com.aicompany.agentharness.llm;

import java.time.Duration;

/**
 * Retry 대기. 테스트에서 실제 시간을 기다리지 않고 대기 요청만 검증할 수 있도록 분리한다.
 */
@FunctionalInterface
public interface RetrySleeper {

	RetrySleeper THREAD_SLEEP = duration -> Thread.sleep(duration.toMillis());

	void sleep(Duration duration) throws InterruptedException;

}
