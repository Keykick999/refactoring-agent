package com.aicompany.agentharness.llm.gemini;

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

class GeminiLlmTest {

	private static final String DUMMY_API_KEY = "test-api-key";
	private static final String TEXT_RESPONSE = """
			{"candidates": [{"content": {"role": "model", "parts": [{"text": "hello"}]}, "finishReason": "STOP"}]}
			""";

	private final JsonMapper jsonMapper = new JsonMapper();
	private final AtomicReference<ReceivedRequest> receivedRequest = new AtomicReference<>();
	private HttpServer fakeGeminiServer;
	private int responseStatus = 200;
	private String responseBody = TEXT_RESPONSE;

	private record ReceivedRequest(String path, String apiKeyHeader, JsonNode body) {
	}

	@BeforeEach
	void startFakeGeminiServer() throws IOException {
		fakeGeminiServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		fakeGeminiServer.createContext("/", exchange -> {
			String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			receivedRequest.set(new ReceivedRequest(
					exchange.getRequestURI().getPath(),
					exchange.getRequestHeaders().getFirst("x-goog-api-key"),
					jsonMapper.readTree(requestBody)));
			byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(responseStatus, response.length);
			exchange.getResponseBody().write(response);
			exchange.close();
		});
		fakeGeminiServer.start();
	}

	@AfterEach
	void stopFakeGeminiServer() {
		fakeGeminiServer.stop(0);
	}

	@Test
	void convertsLlmRequestToGeminiGenerateContentRequest() {
		LlmRequest llmRequest = new LlmRequest(List.of(
				Message.system("You are a reviewer."),
				Message.user("Review this."),
				Message.assistant("{\"type\":\"USE_TOOL\"}"),
				Message.user("Tool result")),
				"{\"type\":\"object\"}");

		createGeminiLlm(DUMMY_API_KEY).generate(llmRequest);

		ReceivedRequest request = receivedRequest.get();
		assertThat(request.path()).isEqualTo("/v1beta/models/test-model:generateContent");
		assertThat(request.apiKeyHeader()).isEqualTo(DUMMY_API_KEY);
		assertThat(request.body().path("systemInstruction").path("parts").path(0).path("text").asString())
				.isEqualTo("You are a reviewer.");
		assertThat(request.body().path("contents").valueStream().map(content -> content.path("role").asString()))
				.containsExactly("user", "model", "user");
		assertThat(request.body().path("contents").path(1).path("parts").path(0).path("text").asString())
				.isEqualTo("{\"type\":\"USE_TOOL\"}");
		assertThat(request.body().path("generationConfig").path("responseMimeType").asString()).isEqualTo("application/json");
		assertThat(request.body().path("generationConfig").path("responseJsonSchema").path("type").asString()).isEqualTo("object");
	}

	@Test
	void omitsGenerationConfigWhenNoResponseSchemaIsRequested() {
		createGeminiLlm(DUMMY_API_KEY).generate(new LlmRequest(List.of(Message.user("Hi"))));

		assertThat(receivedRequest.get().body().has("generationConfig")).isFalse();
		assertThat(receivedRequest.get().body().has("systemInstruction")).isFalse();
	}

	@Test
	void convertsGeminiResponseToLlmResponseWithoutThoughtParts() {
		responseBody = """
				{"candidates": [{"content": {"parts": [
				  {"text": "internal reasoning", "thought": true},
				  {"text": "{\\"type\\": "},
				  {"text": "\\"FINISH\\"}"}
				]}}]}
				""";

		LlmResponse llmResponse = createGeminiLlm(DUMMY_API_KEY).generate(new LlmRequest(List.of(Message.user("Hi"))));

		assertThat(llmResponse.content()).isEqualTo("{\"type\": \"FINISH\"}");
	}

	@Test
	void throwsLlmExceptionWithApiErrorMessageOnHttpError() {
		responseStatus = 503;
		responseBody = """
				{"error": {"code": 503, "message": "The model is overloaded.", "status": "UNAVAILABLE"}}
				""";

		assertThatThrownBy(() -> createGeminiLlm(DUMMY_API_KEY).generate(new LlmRequest(List.of(Message.user("Hi")))))
				.isInstanceOf(LlmException.class)
				.hasMessageContaining("HTTP 503")
				.hasMessageContaining("The model is overloaded.")
				.hasMessageNotContaining(DUMMY_API_KEY);
	}

	@Test
	void throwsLlmExceptionWhenResponseHasNoCandidates() {
		responseBody = """
				{"promptFeedback": {"blockReason": "SAFETY"}}
				""";

		assertThatThrownBy(() -> createGeminiLlm(DUMMY_API_KEY).generate(new LlmRequest(List.of(Message.user("Hi")))))
				.isInstanceOf(LlmException.class)
				.hasMessageContaining("no candidates")
				.hasMessageContaining("SAFETY");
	}

	@Test
	void throwsLlmExceptionWithoutCallingApiWhenApiKeyIsMissing() {
		assertThatThrownBy(() -> createGeminiLlm("").generate(new LlmRequest(List.of(Message.user("Hi")))))
				.isInstanceOf(LlmException.class)
				.hasMessageContaining("GEMINI_API_KEY");
		assertThat(receivedRequest.get()).isNull();
	}

	@Test
	void throwsLlmExceptionWhenApiIsUnreachable() {
		GeminiLlm geminiLlm = createGeminiLlm(DUMMY_API_KEY);
		fakeGeminiServer.stop(0);

		assertThatThrownBy(() -> geminiLlm.generate(new LlmRequest(List.of(Message.user("Hi")))))
				.isInstanceOf(LlmException.class)
				.hasMessageContaining("Failed to call Gemini API")
				.hasCauseInstanceOf(IOException.class);
	}

	private GeminiLlm createGeminiLlm(String apiKey) {
		String baseUrl = "http://127.0.0.1:" + fakeGeminiServer.getAddress().getPort() + "/v1beta/";
		return new GeminiLlm(baseUrl, "test-model", apiKey, Duration.ofSeconds(5));
	}

}
