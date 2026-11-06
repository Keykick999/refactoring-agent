package com.aicompany.agentharness.llm.ollama;

import com.aicompany.agentharness.llm.LlmException;
import com.aicompany.agentharness.llm.LlmRequest;
import com.aicompany.agentharness.llm.LlmResponse;
import com.aicompany.agentharness.llm.Message;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OllamaLlmTest {

	private static final String TEXT_RESPONSE = """
			{"model": "test-model", "message": {"role": "assistant", "content": "hello"}, "done": true}
			""";

	private final JsonMapper jsonMapper = new JsonMapper();
	private final AtomicReference<ReceivedRequest> receivedRequest = new AtomicReference<>();
	private HttpServer fakeOllamaServer;
	private int responseStatus = 200;
	private String responseBody = TEXT_RESPONSE;

	private record ReceivedRequest(String path, JsonNode body) {
	}

	@BeforeEach
	void startFakeOllamaServer() throws IOException {
		fakeOllamaServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		fakeOllamaServer.createContext("/", exchange -> {
			String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			receivedRequest.set(new ReceivedRequest(exchange.getRequestURI().getPath(), jsonMapper.readTree(requestBody)));
			byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(responseStatus, response.length);
			exchange.getResponseBody().write(response);
			exchange.close();
		});
		fakeOllamaServer.start();
	}

	@AfterEach
	void stopFakeOllamaServer() {
		fakeOllamaServer.stop(0);
	}

	@Test
	void convertsLlmRequestToOllamaChatRequest() {
		LlmRequest llmRequest = new LlmRequest(List.of(
				Message.system("You are a reviewer."),
				Message.user("Review this."),
				Message.assistant("{\"type\":\"USE_TOOL\"}"),
				Message.user("Tool result")),
				"{\"type\":\"object\"}");

		createOllamaLlm().generate(llmRequest);

		ReceivedRequest request = receivedRequest.get();
		assertThat(request.path()).isEqualTo("/api/chat");
		assertThat(request.body().path("model").asString()).isEqualTo("test-model");
		assertThat(request.body().path("stream").asBoolean(true)).isFalse();
		assertThat(request.body().path("messages").valueStream().map(message -> message.path("role").asString()))
				.containsExactly("system", "user", "assistant", "user");
		assertThat(request.body().path("messages").path(0).path("content").asString()).isEqualTo("You are a reviewer.");
		assertThat(request.body().path("messages").path(2).path("content").asString()).isEqualTo("{\"type\":\"USE_TOOL\"}");
		assertThat(request.body().path("format").path("type").asString()).isEqualTo("object");
	}

	@Test
	void omitsFormatWhenNoResponseSchemaIsRequested() {
		createOllamaLlm().generate(new LlmRequest(List.of(Message.user("Hi"))));

		assertThat(receivedRequest.get().body().has("format")).isFalse();
	}

	@Test
	void convertsOllamaResponseToLlmResponse() {
		LlmResponse llmResponse = createOllamaLlm().generate(new LlmRequest(List.of(Message.user("Hi"))));

		assertThat(llmResponse.content()).isEqualTo("hello");
	}

	@Test
	void throwsLlmExceptionWithApiErrorMessageOnHttpError() {
		responseStatus = 503;
		responseBody = """
				{"error": "server overloaded"}
				""";

		assertThatThrownBy(() -> createOllamaLlm().generate(new LlmRequest(List.of(Message.user("Hi")))))
				.isInstanceOf(LlmException.class)
				.hasMessageContaining("HTTP 503")
				.hasMessageContaining("server overloaded");
	}

	@Test
	void throwsLlmExceptionWhenResponseHasNoContent() {
		responseBody = """
				{"model": "test-model", "message": {"role": "assistant"}, "done": true}
				""";

		assertThatThrownBy(() -> createOllamaLlm().generate(new LlmRequest(List.of(Message.user("Hi")))))
				.isInstanceOf(LlmException.class)
				.hasMessageContaining("empty response");
	}

	@Test
	void throwsLlmExceptionWhenSuccessBodyContainsError() {
		responseBody = """
				{"error": "model 'missing' not found"}
				""";

		assertThatThrownBy(() -> createOllamaLlm().generate(new LlmRequest(List.of(Message.user("Hi")))))
				.isInstanceOf(LlmException.class)
				.hasMessageContaining("model 'missing' not found");
	}

	@Test
	void throwsLlmExceptionWhenApiIsUnreachable() {
		OllamaLlm ollamaLlm = createOllamaLlm();
		fakeOllamaServer.stop(0);

		assertThatThrownBy(() -> ollamaLlm.generate(new LlmRequest(List.of(Message.user("Hi")))))
				.isInstanceOf(LlmException.class)
				.hasMessageContaining("Failed to call Ollama API at ")
				.hasMessageContaining("/api/chat")
				.hasMessageNotContaining("API: null")
				.hasCauseInstanceOf(IOException.class);
	}

	private OllamaLlm createOllamaLlm() {
		return new OllamaLlm("http://127.0.0.1:" + fakeOllamaServer.getAddress().getPort(), "test-model", Duration.ofSeconds(5));
	}

}
