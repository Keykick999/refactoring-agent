package com.aicompany.agentharness.llm.groq;

import com.aicompany.agentharness.llm.Llm;
import com.aicompany.agentharness.llm.LlmRequest;
import com.aicompany.agentharness.llm.LlmResponse;
import com.aicompany.agentharness.llm.LlmRetryPolicy;
import com.aicompany.agentharness.llm.Message;
import com.aicompany.agentharness.llm.ProviderRateLimitLlm;
import com.aicompany.agentharness.llm.ProviderRateLimitState;
import com.aicompany.agentharness.llm.ProviderRateLimitStore;
import com.aicompany.agentharness.llm.RetryingLlm;
import com.aicompany.agentharness.llm.TokenEstimator;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class GroqRateLimitTest {

	private static final Instant OBSERVED_AT = Instant.parse("2026-10-15T04:00:00Z");
	private static final String MODEL = "openai/gpt-oss-20b";
	private static final String TEXT_RESPONSE = """
			{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}
			""";

	@Test
	void parsesGroqRateLimitHeadersIntoProviderState() throws IOException {
		ManualClock clock = new ManualClock(OBSERVED_AT);
		ProviderRateLimitStore store = new ProviderRateLimitStore(clock, duration -> {
		});
		try (ScriptedGroqServer server = new ScriptedGroqServer()) {
			server.enqueue(200, TEXT_RESPONSE, Map.of(
					"x-ratelimit-limit-tokens", "8000",
					"x-ratelimit-remaining-tokens", "3000",
					"x-ratelimit-reset-tokens", "7.66s"));
			groq(server, store, clock).generate(new LlmRequest(List.of(Message.user("Hi"))));
		}

		ProviderRateLimitState state = store.snapshot("groq", MODEL).orElseThrow();
		assertThat(state.limitTokens()).isEqualTo(8000);
		assertThat(state.remainingTokens()).isEqualTo(3000);
		assertThat(state.resetAt()).isEqualTo(OBSERVED_AT.plusMillis(7_660));
		assertThat(GroqRateLimitHeaders.parseResetMillis("2m59.56s")).contains(179_560L);
	}

	@Test
	void sendsImmediatelyWhenRemainingTokensCoverTheRequest() {
		Instant now = OBSERVED_AT;
		ManualClock clock = new ManualClock(now);
		List<Duration> sleeps = new ArrayList<>();
		ProviderRateLimitStore store = new ProviderRateLimitStore(clock, sleeps::add);
		store.record("groq", MODEL, new ProviderRateLimitState(8000, 8000, now.plusSeconds(30), now));
		AtomicInteger calls = new AtomicInteger();

		new ProviderRateLimitLlm(request -> {
			calls.incrementAndGet();
			return new LlmResponse("ok");
		}, store, "groq", MODEL, fixedEstimate(5000))
				.generate(new LlmRequest(List.of(Message.user("PAYLOAD"))));

		assertThat(calls).hasValue(1);
		assertThat(sleeps).isEmpty();
	}

	@Test
	void waitsForResetAndRechecksWhenRemainingTokensAreShort() {
		Instant now = OBSERVED_AT;
		ManualClock clock = new ManualClock(now);
		List<Duration> sleeps = new ArrayList<>();
		ProviderRateLimitStore store = new ProviderRateLimitStore(clock, duration -> {
			sleeps.add(duration);
			clock.advance(duration);
		});
		store.record("groq", MODEL, new ProviderRateLimitState(8000, 3000, now.plusSeconds(7), now));
		AtomicInteger calls = new AtomicInteger();

		new ProviderRateLimitLlm(request -> {
			calls.incrementAndGet();
			return new LlmResponse("ok");
		}, store, "groq", MODEL, fixedEstimate(5000))
				.generate(new LlmRequest(List.of(Message.user("PAYLOAD"))));

		assertThat(sleeps).containsExactly(Duration.ofSeconds(7));
		assertThat(calls).hasValue(1);
		assertThat(store.snapshot("groq", MODEL)).isEmpty();
	}

	@Test
	void retries429UsingRetryAfterHeader() throws IOException {
		List<Duration> sleeps = new ArrayList<>();
		ProviderRateLimitStore store = new ProviderRateLimitStore(Clock.fixed(OBSERVED_AT, ZoneOffset.UTC), sleeps::add);
		try (ScriptedGroqServer server = new ScriptedGroqServer()) {
			server.enqueue(429, "{\"error\":{\"message\":\"slow down\"}}", Map.of(
					"Retry-After", "2",
					"x-ratelimit-remaining-tokens", "10",
					"x-ratelimit-reset-tokens", "2s"));
			server.enqueue(200, TEXT_RESPONSE, Map.of("x-ratelimit-remaining-tokens", "7000", "x-ratelimit-reset-tokens", "20s"));

			assertThat(stack(server, store, sleeps).generate(new LlmRequest(List.of(Message.user("Hi")))).content()).isEqualTo("ok");
		}

		assertThat(sleeps).containsExactly(Duration.ofSeconds(2));
		assertThat(store.snapshot("groq", MODEL).orElseThrow().remainingTokens()).isEqualTo(7000);
	}

	@Test
	void retries429WithExistingBackoffWhenRetryAfterIsMissing() throws IOException {
		List<Duration> sleeps = new ArrayList<>();
		ProviderRateLimitStore store = new ProviderRateLimitStore(Clock.fixed(OBSERVED_AT, ZoneOffset.UTC), duration -> {
		});
		try (ScriptedGroqServer server = new ScriptedGroqServer()) {
			server.enqueue(429, "{\"error\":{\"message\":\"slow down\"}}", Map.of());
			server.enqueue(200, TEXT_RESPONSE, Map.of());
			Llm retrying = new RetryingLlm(groq(server, store, Clock.fixed(OBSERVED_AT, ZoneOffset.UTC)),
					new LlmRetryPolicy(3, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(10), 0), sleeps::add);

			assertThat(retrying.generate(new LlmRequest(List.of(Message.user("Hi")))).content()).isEqualTo("ok");
		}

		assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
	}

	@Test
	void keepsRateLimitStateSeparatePerProviderAndModel() {
		ProviderRateLimitStore store = new ProviderRateLimitStore(Clock.fixed(OBSERVED_AT, ZoneOffset.UTC), duration -> {
		});
		store.record("groq", "model-a", new ProviderRateLimitState(8000, 1, OBSERVED_AT.plusSeconds(5), OBSERVED_AT));
		store.record("groq", "model-b", new ProviderRateLimitState(8000, 2, OBSERVED_AT.plusSeconds(5), OBSERVED_AT));
		store.record("gemini", "model-a", new ProviderRateLimitState(100, 3, null, OBSERVED_AT));

		assertThat(store.snapshot("groq", "model-a").orElseThrow().remainingTokens()).isEqualTo(1);
		assertThat(store.snapshot("groq", "model-b").orElseThrow().remainingTokens()).isEqualTo(2);
		assertThat(store.snapshot("gemini", "model-a").orElseThrow().remainingTokens()).isEqualTo(3);
	}

	@Test
	void doesNotLetConcurrentRequestsPassTheSameRemainingTokens() throws Exception {
		Instant now = OBSERVED_AT;
		ManualClock clock = new ManualClock(now);
		List<Duration> sleeps = new ArrayList<>();
		ProviderRateLimitStore store = new ProviderRateLimitStore(clock, duration -> {
			synchronized (sleeps) {
				sleeps.add(duration);
			}
			clock.advance(duration);
		});
		store.record("groq", MODEL, new ProviderRateLimitState(8000, 5000, now.plusSeconds(30), now));
		AtomicInteger calls = new AtomicInteger();
		CountDownLatch firstEntered = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		Llm gated = new ProviderRateLimitLlm(request -> {
			if (calls.incrementAndGet() == 1) {
				firstEntered.countDown();
				try {
					if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
						throw new IllegalStateException("timed out waiting to release the first request");
					}
				}
				catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException("interrupted while holding the first request", exception);
				}
				store.record("groq", MODEL, new ProviderRateLimitState(8000, 1000, clock.instant().plusSeconds(30), clock.instant()));
			}
			return new LlmResponse("ok");
		}, store, "groq", MODEL, fixedEstimate(4000));
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<LlmResponse> first = executor.submit(() -> gated.generate(new LlmRequest(List.of(Message.user("PAYLOAD")))));
			Future<LlmResponse> second = executor.submit(() -> gated.generate(new LlmRequest(List.of(Message.user("PAYLOAD")))));
			assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(calls).hasValue(1);
			releaseFirst.countDown();
			assertThat(first.get(5, TimeUnit.SECONDS).content()).isEqualTo("ok");
			assertThat(second.get(5, TimeUnit.SECONDS).content()).isEqualTo("ok");
		}
		finally {
			executor.shutdownNow();
		}
		assertThat(calls).hasValue(2);
		assertThat(sleeps).containsExactly(Duration.ofSeconds(30));
	}

	private static TokenEstimator fixedEstimate(long tokens) {
		return text -> "PAYLOAD".equals(text) ? tokens : 0;
	}

	private static Llm stack(ScriptedGroqServer server, ProviderRateLimitStore store, List<Duration> sleeps) {
		Llm retrying = new RetryingLlm(groq(server, store, Clock.fixed(OBSERVED_AT, ZoneOffset.UTC)),
				new LlmRetryPolicy(3, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(10), 0), sleeps::add);
		return new ProviderRateLimitLlm(retrying, store, "groq", MODEL, text -> 1);
	}

	private static GroqLlm groq(ScriptedGroqServer server, ProviderRateLimitStore store, Clock clock) {
		return new GroqLlm(server.baseUrl(), MODEL, "test-groq-key", Duration.ofSeconds(5), store, clock);
	}

	private static final class ManualClock extends Clock {

		private Instant current;

		private ManualClock(Instant current) {
			this.current = current;
		}

		private void advance(Duration duration) {
			current = current.plus(duration);
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
			return current;
		}

	}

	private static final class ScriptedGroqServer implements AutoCloseable {

		private final HttpServer server;
		private final List<Script> scripts = new ArrayList<>();
		private int sent;

		private ScriptedGroqServer() throws IOException {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/", exchange -> {
				exchange.getRequestBody().readAllBytes();
				Script script = scripts.get(sent++);
				script.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
				byte[] body = script.body().getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(script.status(), body.length);
				exchange.getResponseBody().write(body);
				exchange.close();
			});
			server.start();
		}

		private void enqueue(int status, String body, Map<String, String> headers) {
			scripts.add(new Script(status, body, headers));
		}

		private String baseUrl() {
			return "http://127.0.0.1:" + server.getAddress().getPort() + "/openai/v1";
		}

		@Override
		public void close() {
			server.stop(0);
		}

		private record Script(int status, String body, Map<String, String> headers) {
		}

	}

}
