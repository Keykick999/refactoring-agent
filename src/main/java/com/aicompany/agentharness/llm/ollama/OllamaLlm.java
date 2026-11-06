package com.aicompany.agentharness.llm.ollama;

import com.aicompany.agentharness.llm.Llm;
import com.aicompany.agentharness.llm.LlmErrorCategory;
import com.aicompany.agentharness.llm.LlmException;
import com.aicompany.agentharness.llm.LlmRequest;
import com.aicompany.agentharness.llm.LlmResponse;
import com.aicompany.agentharness.llm.Message;
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
import java.util.Optional;
import java.util.Set;

/**
 * Ollama /api/chat를 사용하는 Llm 구현체. 응답 본문의 message.content만 꺼내 기존 Llm 계약에 맞춘다.
 */
public class OllamaLlm implements Llm {

	private static final int MAX_ERROR_BODY_LENGTH = 500;
	private static final Set<Integer> RETRYABLE_STATUS_CODES = Set.of(429, 500, 502, 503, 504);

	private final URI chatUri;
	private final String model;
	private final Duration timeout;
	private final HttpClient httpClient;
	private final JsonMapper jsonMapper = new JsonMapper();

	public OllamaLlm(String baseUrl, String model, Duration timeout) {
		this.chatUri = URI.create(baseUrl.replaceAll("/+$", "") + "/api/chat");
		this.model = model;
		this.timeout = timeout;
		this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
	}

	@Override
	public LlmResponse generate(LlmRequest request) {
		if (model == null || model.isBlank()) {
			throw new LlmException("Ollama model is not configured. Set llm.ollama.model.",
					LlmErrorCategory.UNEXPECTED);
		}
		String responseBody = send(toOllamaRequest(request));
		return new LlmResponse(extractText(responseBody));
	}

	private ObjectNode toOllamaRequest(LlmRequest request) {
		ObjectNode ollamaRequest = jsonMapper.createObjectNode();
		ollamaRequest.put("model", model);
		ollamaRequest.put("stream", false);

		ArrayNode messages = ollamaRequest.putArray("messages");
		for (Message message : request.messages()) {
			messages.addObject()
					.put("role", message.role().name().toLowerCase())
					.put("content", message.content());
		}

		if (request.responseJsonSchema() != null) {
			ollamaRequest.set("format", jsonMapper.readTree(request.responseJsonSchema()));
		}
		return ollamaRequest;
	}

	private String send(ObjectNode ollamaRequest) {
		HttpRequest httpRequest = HttpRequest.newBuilder(chatUri)
				.timeout(timeout)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(ollamaRequest), StandardCharsets.UTF_8))
				.build();

		HttpResponse<String> httpResponse;
		try {
			httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		}
		catch (SSLException exception) {
			throw new LlmException("Failed to call Ollama API at " + chatUri + ": " + describe(exception), exception);
		}
		catch (IOException exception) {
			throw new RetryableLlmException("Failed to call Ollama API at " + chatUri + ": " + describe(exception), exception);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new LlmException("Interrupted while calling Ollama API", exception);
		}

		if (httpResponse.statusCode() / 100 != 2) {
			throw httpError(httpResponse);
		}
		return httpResponse.body();
	}

	private LlmException httpError(HttpResponse<String> httpResponse) {
		int statusCode = httpResponse.statusCode();
		String message = "Ollama API request failed with HTTP " + statusCode + ": " + extractErrorMessage(httpResponse.body());
		if (statusCode == 401 || statusCode == 403) {
			return new LlmException(message, LlmErrorCategory.AUTHENTICATION);
		}
		if (statusCode == 400 && looksLikeTokenLimit(httpResponse.body())) {
			return new LlmException(message, LlmErrorCategory.TOKEN_LIMIT);
		}
		if (statusCode == 429) {
			return new RetryableLlmException(message, LlmErrorCategory.RATE_LIMIT, retryAfter(httpResponse));
		}
		if (RETRYABLE_STATUS_CODES.contains(statusCode)) {
			return new RetryableLlmException(message, LlmErrorCategory.SERVER, retryAfter(httpResponse));
		}
		return new LlmException(message, LlmErrorCategory.UNEXPECTED);
	}

	private boolean looksLikeTokenLimit(String body) {
		String normalized = body == null ? "" : body.toLowerCase();
		return normalized.contains("token") || normalized.contains("context length") || normalized.contains("too large");
	}

	private Duration retryAfter(HttpResponse<String> httpResponse) {
		Optional<String> retryAfterHeader = httpResponse.headers().firstValue("Retry-After");
		if (retryAfterHeader.isEmpty()) {
			return null;
		}
		try {
			long seconds = Long.parseLong(retryAfterHeader.get().strip());
			return seconds >= 0 ? Duration.ofSeconds(seconds) : null;
		}
		catch (NumberFormatException ignored) {
			return null;
		}
	}

	private String extractText(String responseBody) {
		JsonNode ollamaResponse = readJson(responseBody);
		String error = errorMessage(ollamaResponse);
		if (!error.isEmpty()) {
			throw new LlmException("Ollama API returned an error: " + error, LlmErrorCategory.UNEXPECTED);
		}
		String text = ollamaResponse.path("message").path("content").asString("");
		if (text.isEmpty()) {
			throw new LlmException("Ollama API returned an empty response");
		}
		return text;
	}

	private String extractErrorMessage(String responseBody) {
		try {
			String message = errorMessage(jsonMapper.readTree(responseBody));
			if (!message.isEmpty()) {
				return message;
			}
		}
		catch (JacksonException ignored) {
			// 오류 본문이 JSON이 아닐 수 있다.
		}
		return responseBody.length() > MAX_ERROR_BODY_LENGTH ? responseBody.substring(0, MAX_ERROR_BODY_LENGTH) + "..." : responseBody;
	}

	private static String errorMessage(JsonNode body) {
		JsonNode error = body.path("error");
		if (error.isMissingNode() || error.isNull()) {
			return "";
		}
		if (error.isTextual()) {
			return error.asString("");
		}
		return error.path("message").asString("");
	}

	private static String describe(Exception exception) {
		StringBuilder text = new StringBuilder(exception.getClass().getSimpleName());
		if (exception.getMessage() != null && !exception.getMessage().isBlank()) {
			text.append(": ").append(exception.getMessage());
		}
		Throwable cause = exception.getCause();
		if (cause != null && cause != exception) {
			text.append(" (").append(cause.getClass().getSimpleName());
			if (cause.getMessage() != null && !cause.getMessage().isBlank()) {
				text.append(": ").append(cause.getMessage());
			}
			text.append(')');
		}
		return text.toString();
	}

	private JsonNode readJson(String responseBody) {
		try {
			return jsonMapper.readTree(responseBody);
		}
		catch (JacksonException exception) {
			throw new LlmException("Ollama API returned invalid JSON", exception);
		}
	}

}
