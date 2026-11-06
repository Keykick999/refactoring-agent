package com.aicompany.agentharness.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 한 JVM 안에서 provider와 model별로 기억하는 rate-limit 스냅샷.
 * 값은 provider 응답으로만 갱신한다. TPM을 최근 60초 요청의 합으로 다시 계산하지 않고, TPD를 자정 기준으로 만들지 않는다.
 *
 * <p>같은 API Key를 쓰는 다른 프로세스와는 상태를 공유하지 않는다. 그 환경에는 별도의 distributed rate limiter가 필요하다.
 * 한 provider/model에 대해서는 요청을 한 번에 하나만 보내 스냅샷을 동시에 넘기지 않는다.
 */
public final class ProviderRateLimitStore {

	private static final Logger log = LoggerFactory.getLogger(ProviderRateLimitStore.class);

	private final ConcurrentHashMap<Key, Slot> slots = new ConcurrentHashMap<>();
	private final Clock clock;
	private final RetrySleeper sleeper;

	public ProviderRateLimitStore(Clock clock, RetrySleeper sleeper) {
		this.clock = clock;
		this.sleeper = sleeper;
	}

	public void record(String provider, String model, ProviderRateLimitState state) {
		if (state == null) {
			return;
		}
		Slot slot = slot(provider, model);
		synchronized (slot) {
			slot.state = state;
		}
	}

	public Optional<ProviderRateLimitState> snapshot(String provider, String model) {
		Slot slot = slots.get(key(provider, model));
		if (slot == null) {
			return Optional.empty();
		}
		synchronized (slot) {
			return Optional.ofNullable(slot.state);
		}
	}

	/**
	 * 남은 토큰이 예상 요청보다 적고 reset 시각이 남아 있으면 그 시각까지 기다린 뒤 다시 확인한다.
	 * reset이 지난 스냅샷은 더 이상 쓰지 않고 호출을 허용한다.
	 */
	public void acquire(String provider, String model, long estimatedTokens) {
		Slot slot = slot(provider, model);
		while (true) {
			Duration wait;
			synchronized (slot) {
				while (slot.inFlight) {
					waitForTurn(slot);
				}
				expireIfResetElapsed(slot);
				if (allows(slot.state, estimatedTokens)) {
					slot.inFlight = true;
					return;
				}
				wait = Duration.between(clock.instant(), slot.state.resetAt());
			}
			if (wait.isZero() || wait.isNegative()) {
				continue;
			}
			log.info("Waiting {}ms for {} {} rate-limit reset before sending an estimated {} token request",
					wait.toMillis(), provider, model, estimatedTokens);
			sleep(wait);
		}
	}

	public void finish(String provider, String model) {
		Slot slot = slots.get(key(provider, model));
		if (slot == null) {
			return;
		}
		synchronized (slot) {
			slot.inFlight = false;
			slot.notifyAll();
		}
	}

	private void expireIfResetElapsed(Slot slot) {
		if (slot.state == null || slot.state.resetAt() == null) {
			return;
		}
		if (!clock.instant().isBefore(slot.state.resetAt())) {
			slot.state = null;
		}
	}

	private static boolean allows(ProviderRateLimitState state, long estimatedTokens) {
		if (state == null || state.resetAt() == null) {
			return true;
		}
		return state.remainingTokens() >= estimatedTokens;
	}

	private void waitForTurn(Slot slot) {
		try {
			slot.wait();
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new LlmException("Interrupted while waiting for provider rate limit", exception);
		}
	}

	private void sleep(Duration wait) {
		try {
			sleeper.sleep(wait);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new LlmException("Interrupted while waiting for provider rate limit", exception);
		}
	}

	private Slot slot(String provider, String model) {
		return slots.computeIfAbsent(key(provider, model), ignored -> new Slot());
	}

	private static Key key(String provider, String model) {
		return new Key(provider, model);
	}

	private record Key(String provider, String model) {
	}

	private static final class Slot {

		private ProviderRateLimitState state;
		private boolean inFlight;

	}

}
