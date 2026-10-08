package com.aicompany.agentharness.llm;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryingLlmTest {

	private static final LlmRequest REQUEST = new LlmRequest(List.of(Message.user("Review this.")));

	private final List<Duration> sleeps = new ArrayList<>();
	private final AtomicInteger callCount = new AtomicInteger();

	@Test
	void increasesBackoffExponentiallyUpToMaxBackoff() {
		LlmRetryPolicy policy = new LlmRetryPolicy(5, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(5), 0);

		assertThatThrownBy(() -> new RetryingLlm(alwaysFailing(), policy, sleeps::add).generate(REQUEST))
				.isInstanceOf(LlmException.class);

		assertThat(callCount).hasValue(5);
		assertThat(sleeps).containsExactly(
				Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(5));
	}

	@Test
	void keepsJitteredBackoffWithinConfiguredRatioAndMaxBackoff() {
		LlmRetryPolicy policy = new LlmRetryPolicy(3, Duration.ofSeconds(1), 2.0, Duration.ofMillis(2100), 0.1);

		for (int i = 0; i < 100; i++) {
			assertThat(policy.delayAfter(1, Optional.empty()).toMillis()).isBetween(900L, 1100L);
			assertThat(policy.delayAfter(2, Optional.empty()).toMillis()).isBetween(1800L, 2100L);
		}
	}

	@Test
	void rethrowsOriginalExceptionWhenOnlyOneAttemptIsAllowed() {
		LlmRetryPolicy policy = new LlmRetryPolicy(1, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(10), 0);

		assertThatThrownBy(() -> new RetryingLlm(alwaysFailing(), policy, sleeps::add).generate(REQUEST))
				.isInstanceOf(RetryableLlmException.class)
				.hasMessage("HTTP 429");
		assertThat(callCount).hasValue(1);
		assertThat(sleeps).isEmpty();
	}

	@Test
	void stopsRetryingWhenInterruptedWhileWaiting() {
		LlmRetryPolicy policy = new LlmRetryPolicy(3, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(10), 0);
		RetrySleeper interruptedSleeper = duration -> {
			throw new InterruptedException();
		};

		try {
			assertThatThrownBy(() -> new RetryingLlm(alwaysFailing(), policy, interruptedSleeper).generate(REQUEST))
					.isInstanceOf(LlmException.class)
					.hasMessageContaining("Interrupted");
			assertThat(callCount).hasValue(1);
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
		}
		finally {
			Thread.interrupted();
		}
	}

	@Test
	void rejectsInvalidPolicy() {
		assertThatThrownBy(() -> new LlmRetryPolicy(0, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(10), 0))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new LlmRetryPolicy(3, Duration.ofSeconds(1), 0.5, Duration.ofSeconds(10), 0))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new LlmRetryPolicy(3, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(10), 1.0))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private Llm alwaysFailing() {
		return request -> {
			callCount.incrementAndGet();
			throw new RetryableLlmException("HTTP 429", (Duration) null);
		};
	}

}
