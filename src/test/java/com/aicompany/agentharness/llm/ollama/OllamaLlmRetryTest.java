package com.aicompany.agentharness.llm.ollama;

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

import static com.aicompany.agentharness.llm.ollama.FakeOllamaServer.error;
import static com.aicompany.agentharness.llm.ollama.FakeOllamaServer.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OllamaLlm의 오류 분류와 공통 RetryingLlm 재시도를 Fake HTTP 서버로 검증한다.
 */
class OllamaLlmRetryTest {

	private static final LlmRequest REQUEST = new LlmRequest(List.of(Message.user("Review this.")));
	private static final LlmRetryPolicy RETRY_POLICY =
			new LlmRetryPolicy(3, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(10), 0);

	private final List<Duration> sleeps = new ArrayList<>();
	private FakeOllamaServer server;

	@AfterEach
	void stopServer() {
		server.close();
	}

	@Test
	void retriesAfterTransientServerErrorAndReturnsSuccess() {
		server = FakeOllamaServer.start(error(503, "server overloaded"), text("ok"));

		LlmResponse response = retryingLlm().generate(REQUEST);

		assertThat(response.content()).isEqualTo("ok");
		assertThat(server.requestCount()).isEqualTo(2);
		assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
	}

	@ParameterizedTest
	@ValueSource(ints = {400, 401, 403, 404})
	void failsImmediatelyWithoutRetryOnNonTransientError(int status) {
		server = FakeOllamaServer.start(error(status, "model not found"), text("ok"));

		assertThatThrownBy(() -> retryingLlm().generate(REQUEST))
				.isInstanceOf(LlmException.class)
				.isNotInstanceOf(RetryableLlmException.class)
				.hasMessageContaining("HTTP " + status);
		assertThat(server.requestCount()).isEqualTo(1);
		assertThat(sleeps).isEmpty();
	}

	@Test
	void classifiesTokenLimitWithoutRetry() {
		server = FakeOllamaServer.start(error(400, "the input exceeds the context length"), text("ok"));

		assertThatThrownBy(() -> retryingLlm().generate(REQUEST))
				.isInstanceOf(LlmException.class)
				.isNotInstanceOf(RetryableLlmException.class)
				.extracting(thrown -> ((LlmException) thrown).category())
				.isEqualTo(LlmErrorCategory.TOKEN_LIMIT);
		assertThat(server.requestCount()).isEqualTo(1);
	}

	@Test
	void retriesNetworkErrorAndFailsAfterMaxAttempts() {
		server = FakeOllamaServer.start();
		RetryingLlm retryingLlm = retryingLlm();
		server.close();

		assertThatThrownBy(() -> retryingLlm.generate(REQUEST))
				.isInstanceOf(LlmException.class)
				.hasMessageContaining("after 3 attempts")
				.hasMessageContaining("Failed to call Ollama API at ")
				.hasRootCauseInstanceOf(IOException.class)
				.extracting(thrown -> ((LlmException) thrown).category())
				.isEqualTo(LlmErrorCategory.NETWORK);
		assertThat(sleeps).hasSize(2);
	}

	@Test
	void waitsForRetryAfterHeaderInsteadOfExponentialBackoff() {
		server = FakeOllamaServer.start(error(429, "slow down").withHeader("Retry-After", "5"), text("ok"));

		retryingLlm().generate(REQUEST);

		assertThat(sleeps).containsExactly(Duration.ofSeconds(5));
	}

	private RetryingLlm retryingLlm() {
		return new RetryingLlm(server.createOllamaLlm(), RETRY_POLICY, sleeps::add);
	}

}
