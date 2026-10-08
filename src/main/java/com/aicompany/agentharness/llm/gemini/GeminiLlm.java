package com.aicompany.agentharness.llm.gemini;

import com.aicompany.agentharness.llm.Llm;
import com.aicompany.agentharness.llm.LlmException;
import com.aicompany.agentharness.llm.LlmRequest;
import com.aicompany.agentharness.llm.LlmResponse;
import com.aicompany.agentharness.llm.Message;
import com.aicompany.agentharness.llm.MessageRole;
import com.aicompany.agentharness.llm.RetryableLlmException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Gemini generateContent API를 사용하는 Llm 구현체.
 */
public class GeminiLlm implements Llm {

	/**
	 * API Key를 URL query가 아닌 헤더로 보내, 예외 메시지나 접근 로그에 URL과 함께 노출되지 않게 한다.
	 */
	private static final String API_KEY_HEADER = "x-goog-api-key";
	private static final int MAX_ERROR_BODY_LENGTH = 500;
	/**
	 * Rate limit(429)과 일시적인 서버 오류만 재시도 대상이다. 그 외 4xx(잘못된 Key, 요청, 모델)는 다시 호출해도 실패한다.
	 */
	private static final Set<Integer> RETRYABLE_STATUS_CODES = Set.of(429, 500, 502, 503, 504);

	private final URI generateContentUri;
	private final String apiKey;
	private final Duration timeout;
	private final HttpClient httpClient;
	private final JsonMapper jsonMapper = new JsonMapper();

	public GeminiLlm(String baseUrl, String model, String apiKey, Duration timeout) {
		this.generateContentUri = URI.create(baseUrl.replaceAll("/+$", "") + "/models/" + model + ":generateContent");
		this.apiKey = apiKey;
		this.timeout = timeout;
		this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
	}

	@Override
	public LlmResponse generate(LlmRequest request) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new LlmException("Gemini API key is not configured. Set the GEMINI_API_KEY environment variable.");
		}
		String responseBody = send(toGeminiRequest(request));
		return new LlmResponse(extractText(responseBody));
	}

	private ObjectNode toGeminiRequest(LlmRequest request) {
		ObjectNode geminiRequest = jsonMapper.createObjectNode();

		String systemInstruction = request.messages().stream()
				.filter(message -> message.role() == MessageRole.SYSTEM)
				.map(Message::content)
				.collect(Collectors.joining("\n\n"));
		if (!systemInstruction.isEmpty()) {
			geminiRequest.putObject("systemInstruction").putArray("parts").addObject().put("text", systemInstruction);
		}

		ArrayNode contents = geminiRequest.putArray("contents");
		request.messages().stream()
				.filter(message -> message.role() != MessageRole.SYSTEM)
				.forEach(message -> contents.addObject()
						.put("role", message.role() == MessageRole.ASSISTANT ? "model" : "user")
						.putArray("parts").addObject().put("text", message.content()));

		if (request.responseJsonSchema() != null) {
			ObjectNode generationConfig = geminiRequest.putObject("generationConfig");
			generationConfig.put("responseMimeType", "application/json");
			generationConfig.set("responseJsonSchema", jsonMapper.readTree(request.responseJsonSchema()));
		}
		return geminiRequest;
	}

	private String send(ObjectNode geminiRequest) {
		HttpRequest httpRequest = HttpRequest.newBuilder(generateContentUri)
				.timeout(timeout)
				.header("Content-Type", "application/json")
				.header(API_KEY_HEADER, apiKey)
				.POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(geminiRequest), StandardCharsets.UTF_8))
				.build();

		HttpResponse<String> httpResponse;
		try {
			httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		}
		catch (SSLException exception) {
			// 인증서/프로토콜 문제는 다시 호출해도 해결되지 않는다.
			throw new LlmException("Failed to call Gemini API: " + exception.getMessage(), exception);
		}
		catch (IOException exception) {
			// 연결 거부, 연결/응답 timeout, 연결 끊김 등 네트워크 오류는 일시적일 수 있다.
			throw new RetryableLlmException("Failed to call Gemini API: " + exception.getMessage(), exception);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new LlmException("Interrupted while calling Gemini API", exception);
		}

		if (httpResponse.statusCode() / 100 != 2) {
			throw httpError(httpResponse);
		}
		return httpResponse.body();
	}

	private LlmException httpError(HttpResponse<String> httpResponse) {
		int statusCode = httpResponse.statusCode();
		String message = "Gemini API request failed with HTTP " + statusCode + ": " + extractErrorMessage(httpResponse.body());
		if (!RETRYABLE_STATUS_CODES.contains(statusCode)) {
			return new LlmException(message);
		}
		return new RetryableLlmException(message, retryAfter(httpResponse));
	}

	/**
	 * Retry-After 헤더(초 단위)를 우선하고, 없으면 Gemini 오류 본문의 RetryInfo.retryDelay(예: "37s")를 사용한다.
	 * HTTP-date 형식의 Retry-After는 지원하지 않으며, 이 경우 exponential backoff를 사용한다.
	 */
	private Duration retryAfter(HttpResponse<String> httpResponse) {
		Optional<String> retryAfterHeader = httpResponse.headers().firstValue("Retry-After");
		if (retryAfterHeader.isPresent()) {
			try {
				long seconds = Long.parseLong(retryAfterHeader.get().strip());
				return seconds >= 0 ? Duration.ofSeconds(seconds) : null;
			}
			catch (NumberFormatException ignored) {
				return null;
			}
		}
		try {
			for (JsonNode detail : jsonMapper.readTree(httpResponse.body()).path("error").path("details")) {
				String retryDelay = detail.path("retryDelay").asString("");
				if (detail.path("@type").asString("").endsWith("RetryInfo") && !retryDelay.isEmpty()) {
					return Duration.parse("PT" + retryDelay.toUpperCase());
				}
			}
		}
		catch (JacksonException | DateTimeParseException ignored) {
			// 대기 시간을 알 수 없으면 exponential backoff를 사용한다.
		}
		return null;
	}

	private String extractText(String responseBody) {
		JsonNode geminiResponse = readJson(responseBody);
		JsonNode candidate = geminiResponse.path("candidates").path(0);
		if (candidate.isMissingNode()) {
			String blockReason = geminiResponse.path("promptFeedback").path("blockReason").asString("");
			throw new LlmException("Gemini API returned no candidates" + (blockReason.isEmpty() ? "" : " (blockReason: " + blockReason + ")"));
		}

		StringBuilder text = new StringBuilder();
		for (JsonNode part : candidate.path("content").path("parts")) {
			// thinking 모델은 사고 과정을 별도 part로 반환하며, 이는 응답 본문이 아니다.
			if (!part.path("thought").asBoolean(false)) {
				text.append(part.path("text").asString(""));
			}
		}
		if (text.isEmpty()) {
			throw new LlmException("Gemini API returned an empty response (finishReason: "
					+ candidate.path("finishReason").asString("unknown") + ")");
		}
		return text.toString();
	}

	private String extractErrorMessage(String responseBody) {
		try {
			String message = jsonMapper.readTree(responseBody).path("error").path("message").asString("");
			if (!message.isEmpty()) {
				return message;
			}
		}
		catch (JacksonException ignored) {
			// 오류 본문이 JSON이 아닐 수 있다(프록시 오류 페이지 등). 아래에서 원문 일부를 사용한다.
		}
		return responseBody.length() > MAX_ERROR_BODY_LENGTH ? responseBody.substring(0, MAX_ERROR_BODY_LENGTH) + "..." : responseBody;
	}

	private JsonNode readJson(String responseBody) {
		try {
			return jsonMapper.readTree(responseBody);
		}
		catch (JacksonException exception) {
			throw new LlmException("Gemini API returned invalid JSON", exception);
		}
	}

}
