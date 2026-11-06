package com.aicompany.agentharness.llm;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryLlmRateLimiterTest {

	private static final Instant START = Instant.parse("2026-11-06T00:00:00Z");
	private static final Duration WINDOW = Duration.ofSeconds(60);

	private final MutableClock clock = new MutableClock(START);
	private final List<Duration> sleeps = new ArrayList<>();

	@Test
	void allowsFiveRequestsImmediately() {
		InMemoryLlmRateLimiter limiter = limiter(sleeps::add);

		for (int i = 0; i < 5; i++) {
			limiter.acquire();
		}

		assertThat(sleeps).isEmpty();
		assertThat(limiter.trackedRequests()).isEqualTo(5);
	}

	@Test
	void sixthRequestWaitsUntilTheOldestRequestLeavesTheWindow() {
		InMemoryLlmRateLimiter limiter = limiter(duration -> {
			sleeps.add(duration);
			clock.advance(duration);
		});
		for (int i = 0; i < 5; i++) {
			limiter.acquire();
			clock.advance(Duration.ofSeconds(5));
		}
		clock.set(START.plusSeconds(20));

		limiter.acquire();

		assertThat(sleeps).containsExactly(Duration.ofSeconds(40));
		assertThat(clock.instant()).isEqualTo(START.plusSeconds(60));
		assertThat(limiter.trackedRequests()).isEqualTo(5);
	}

	@Test
	void allowsTheNextRequestExactlyWhenTheOldestIsSixtySecondsOld() {
		InMemoryLlmRateLimiter limiter = limiter(sleeps::add);
		for (int i = 0; i < 5; i++) {
			limiter.acquire();
		}

		clock.set(START.plus(WINDOW));
		limiter.acquire();

		assertThat(sleeps).isEmpty();
	}

	@Test
	void removesExpiredTimestamps() {
		InMemoryLlmRateLimiter limiter = limiter(sleeps::add);
		for (int i = 0; i < 5; i++) {
			limiter.acquire();
		}

		clock.advance(Duration.ofSeconds(61));
		limiter.acquire();

		assertThat(sleeps).isEmpty();
		assertThat(limiter.trackedRequests()).isEqualTo(1);
	}

	@Test
	void restoresTheInterruptFlagWhenWaitingIsInterrupted() {
		InMemoryLlmRateLimiter limiter = new InMemoryLlmRateLimiter("Gemini", 1, WINDOW, clock, duration -> {
			throw new InterruptedException();
		});
		limiter.acquire();

		try {
			assertThatThrownBy(limiter::acquire)
					.isInstanceOf(LlmException.class)
					.hasMessageContaining("Interrupted");
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
		}
		finally {
			Thread.interrupted();
		}
	}

	/**
	 * 시간을 멈춘 채 20개 thread가 동시에 들어오면 5개만 통과해야 한다.
	 * 대기 중인 thread가 lock을 잡고 있으면 나머지는 sleep까지 오지 못하므로, sleep 호출 수로 lock 밖 대기도 확인한다.
	 */
	@Test
	void neverAllowsMoreThanFiveRequestsPerWindowAcrossThreads() throws Exception {
		int threads = 20;
		AtomicInteger acquired = new AtomicInteger();
		AtomicInteger sleepCalls = new AtomicInteger();
		Semaphore wakeUp = new Semaphore(0);
		InMemoryLlmRateLimiter limiter = limiter(duration -> {
			sleepCalls.incrementAndGet();
			wakeUp.acquire();
		});
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		try {
			for (int i = 0; i < threads; i++) {
				executor.submit(() -> {
					start.await();
					limiter.acquire();
					acquired.incrementAndGet();
					return null;
				});
			}
			start.countDown();

			awaitUntil(() -> acquired.get() == 5 && sleepCalls.get() == 15);
			assertThat(acquired.get()).isEqualTo(5);
			assertThat(limiter.trackedRequests()).isEqualTo(5);

			clock.advance(WINDOW);
			wakeUp.release(15);

			awaitUntil(() -> acquired.get() == 10 && sleepCalls.get() == 25);
			assertThat(acquired.get()).isEqualTo(10);
			assertThat(limiter.trackedRequests()).isEqualTo(5);
		}
		finally {
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	private InMemoryLlmRateLimiter limiter(RetrySleeper sleeper) {
		return new InMemoryLlmRateLimiter("Gemini", 5, WINDOW, clock, sleeper);
	}

	private static void awaitUntil(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("Condition was not reached in time");
			}
			Thread.sleep(10);
		}
		Thread.sleep(50);
	}

	private static final class MutableClock extends Clock {

		private volatile Instant now;

		private MutableClock(Instant now) {
			this.now = now;
		}

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		void set(Instant instant) {
			now = instant;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}

	}

}
