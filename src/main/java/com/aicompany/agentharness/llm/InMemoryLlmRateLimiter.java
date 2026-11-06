package com.aicompany.agentharness.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 최근 window 동안 보낸 요청 시각을 메모리에 두고, maxRequests를 넘지 않을 때만 요청을 허용한다(sliding window).
 *
 * <p>만료 제거, 개수 확인, 시각 등록은 한 번의 lock 안에서 끝낸다. 대기는 lock 밖에서 하고, 깨어나면 다시 확인한다.
 */
public final class InMemoryLlmRateLimiter implements LlmRateLimiter {

	private static final Logger log = LoggerFactory.getLogger(InMemoryLlmRateLimiter.class);
	private static final Duration MIN_WAIT = Duration.ofMillis(1);

	private final String provider;
	private final int maxRequests;
	private final Duration window;
	private final Clock clock;
	private final RetrySleeper sleeper;
	private final Deque<Instant> requestTimes = new ArrayDeque<>();

	public InMemoryLlmRateLimiter(String provider, int maxRequests, Duration window, Clock clock, RetrySleeper sleeper) {
		if (maxRequests < 1) {
			throw new IllegalArgumentException("maxRequests must be at least 1: " + maxRequests);
		}
		if (window == null || window.isZero() || window.isNegative()) {
			throw new IllegalArgumentException("window must be positive: " + window);
		}
		this.provider = provider;
		this.maxRequests = maxRequests;
		this.window = window;
		this.clock = clock;
		this.sleeper = sleeper;
	}

	@Override
	public void acquire() {
		while (true) {
			Duration wait = tryAcquire();
			if (wait.isZero()) {
				return;
			}
			log.info("{} rate limit reached. Waiting {} ms.", provider, wait.toMillis());
			try {
				sleeper.sleep(wait);
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new LlmException("Interrupted while waiting for the " + provider + " rate limit", exception);
			}
		}
	}

	/**
	 * 허용되면 시각을 등록하고 0을, 아니면 가장 오래된 요청이 window를 벗어날 때까지의 시간을 반환한다.
	 */
	private synchronized Duration tryAcquire() {
		Instant now = clock.instant();
		Instant windowStart = now.minus(window);
		while (!requestTimes.isEmpty() && !requestTimes.peekFirst().isAfter(windowStart)) {
			requestTimes.pollFirst();
		}
		if (requestTimes.size() < maxRequests) {
			requestTimes.addLast(now);
			return Duration.ZERO;
		}
		Duration wait = Duration.between(now, requestTimes.peekFirst().plus(window));
		return wait.compareTo(MIN_WAIT) < 0 ? MIN_WAIT : wait;
	}

	synchronized int trackedRequests() {
		return requestTimes.size();
	}

}
