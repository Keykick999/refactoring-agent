package com.aicompany.agentharness.llm.gemini;

import com.aicompany.agentharness.llm.LlmErrorCategory;
import com.aicompany.agentharness.llm.LlmException;
import com.aicompany.agentharness.llm.LlmRequest;
import com.aicompany.agentharness.llm.LlmResponse;
import com.aicompany.agentharness.llm.LlmRetryPolicy;
import com.aicompany.agentharness.llm.Message;
import com.aicompany.agentharness.llm.RetryableLlmException;
import com.aicompany.agentharness.llm.RetryingLlm;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.aicompany.agentharness.llm.gemini.FakeGeminiServer.error;
import static com.aicompany.agentharness.llm.gemini.FakeGeminiServer.rawError;
import static com.aicompany.agentharness.llm.gemini.FakeGeminiServer.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 GeminiLlm의 오류 분류와 RetryingLlm의 재시도를 Fake HTTP 서버로 함께 검증한다. 실제로 대기하지 않는다.
 */
class GeminiLlmRetryTest {

	private static final LlmRequest REQUEST = new LlmRequest(List.of(Message.user("Review this.")));
	private static final LlmRetryPolicy RETRY_POLICY =
			new LlmRetryPolicy(3, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(10), 0);

	private final List<Duration> sleeps = new ArrayList<>();
	private FakeGeminiServer server;

	@AfterEach
	void stopServer() {
		server.close();
	}

	@Test
	void doesNotRetryWhenFirstCallSucceeds() {
		server = FakeGeminiServer.start(text("ok"));

		LlmResponse response = retryingLlm().generate(REQUEST);

		assertThat(response.content()).isEqualTo("ok");
		assertThat(server.requestCount()).isEqualTo(1);
		assertThat(sleeps).isEmpty();
	}

	@Test
	void retriesAfterRateLimitAndReturnsSuccess() {
		server = FakeGeminiServer.start(error(429, "Resource has been exhausted"), text("ok"));

		LlmResponse response = retryingLlm().generate(REQUEST);

		assertThat(response.content()).isEqualTo("ok");
		assertThat(server.requestCount()).isEqualTo(2);
		assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
	}

	@Test
	void failsAfterMaxAttemptsWhenRateLimitPersists() {
		server = FakeGeminiServer.start(error(429, "quota exceeded"), error(429, "quota exceeded"), error(429, "quota exceeded"));

		assertThatThrownBy(() -> retryingLlm().generate(REQUEST))
				.isInstanceOf(LlmException.class)
				.hasMessageContaining("after 3 attempts")
				.hasMessageContaining("HTTP 429")
				.hasMessageNotContaining("test-api-key")
				.extracting(thrown -> ((LlmException) thrown).category())
				.isEqualTo(LlmErrorCategory.RATE_LIMIT);
		assertThat(server.requestCount()).isEqualTo(3);
		assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
	}

	@ParameterizedTest
	@ValueSource(ints = {500, 502, 503, 504})
	void retriesAfterTransientServerErrorAndReturnsSuccess(int status) {
		server = FakeGeminiServer.start(error(status, "server error"), text("ok"));

		LlmResponse response = retryingLlm().generate(REQUEST);

		assertThat(response.content()).isEqualTo("ok");
		assertThat(server.requestCount()).isEqualTo(2);
	}

	@ParameterizedTest
	@ValueSource(ints = {400, 401, 403, 404, 501})
	void failsImmediatelyWithoutRetryOnNonTransientError(int status) {
		server = FakeGeminiServer.start(error(status, "API key not valid"), text("ok"));

		assertThatThrownBy(() -> retryingLlm().generate(REQUEST))
				.isInstanceOf(LlmException.class)
				.isNotInstanceOf(RetryableLlmException.class)
				.hasMessageContaining("HTTP " + status);
		assertThat(server.requestCount()).isEqualTo(1);
		assertThat(sleeps).isEmpty();
	}

	@Test
	void waitsForRetryAfterHeaderInsteadOfExponentialBackoff() {
		server = FakeGeminiServer.start(error(429, "slow down").withHeader("Retry-After", "5"), text("ok"));

		retryingLlm().generate(REQUEST);

		assertThat(sleeps).containsExactly(Duration.ofSeconds(5));
	}

	@Test
	void capsRetryAfterAtMaxBackoff() {
		server = FakeGeminiServer.start(error(429, "slow down").withHeader("Retry-After", "60"), text("ok"));

		retryingLlm().generate(REQUEST);

		assertThat(sleeps).containsExactly(Duration.ofSeconds(10));
	}

	@Test
	void waitsForRetryDelayInGeminiErrorDetailsWhenHeaderIsMissing() {
		server = FakeGeminiServer.start(rawError(429, """
				{"error": {"code": 429, "message": "You exceeded your current quota", "status": "RESOURCE_EXHAUSTED",
				  "details": [{"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "3s"}]}}
				"""), text("ok"));

		retryingLlm().generate(REQUEST);

		assertThat(sleeps).containsExactly(Duration.ofSeconds(3));
	}

	@Test
	void retriesNetworkErrorAndFailsAfterMaxAttempts() {
		server = FakeGeminiServer.start();
		RetryingLlm retryingLlm = retryingLlm();
		server.close();

		assertThatThrownBy(() -> retryingLlm.generate(REQUEST))
				.isInstanceOf(LlmException.class)
				.hasMessageContaining("after 3 attempts")
				.hasMessageContaining("Failed to call Gemini API")
				.hasRootCauseInstanceOf(IOException.class)
				.extracting(thrown -> ((LlmException) thrown).category())
				.isEqualTo(LlmErrorCategory.NETWORK);
		assertThat(sleeps).hasSize(2);
	}

	@Test
	void classifiesAuthenticationWithoutRetry() {
		server = FakeGeminiServer.start(error(401, "API key not valid"), text("ok"));

		assertThatThrownBy(() -> retryingLlm().generate(REQUEST))
				.isInstanceOf(LlmException.class)
				.isNotInstanceOf(RetryableLlmException.class)
				.extracting(thrown -> ((LlmException) thrown).category())
				.isEqualTo(LlmErrorCategory.AUTHENTICATION);
		assertThat(server.requestCount()).isEqualTo(1);
	}

	@Test
	void classifiesTokenLimitWithoutRetry() {
		server = FakeGeminiServer.start(error(400, "the input exceeds the context length"), text("ok"));

		assertThatThrownBy(() -> retryingLlm().generate(REQUEST))
				.isInstanceOf(LlmException.class)
				.isNotInstanceOf(RetryableLlmException.class)
				.extracting(thrown -> ((LlmException) thrown).category())
				.isEqualTo(LlmErrorCategory.TOKEN_LIMIT);
		assertThat(server.requestCount()).isEqualTo(1);
	}

	private RetryingLlm retryingLlm() {
		return new RetryingLlm(server.createGeminiLlm(), RETRY_POLICY, sleeps::add);
	}

}
