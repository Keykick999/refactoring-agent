package com.aicompany.agentharness.llm.groq;

import com.aicompany.agentharness.llm.Llm;
import com.aicompany.agentharness.llm.LlmErrorCategory;
import com.aicompany.agentharness.llm.LlmException;
import com.aicompany.agentharness.llm.LlmRequest;
import com.aicompany.agentharness.llm.LlmResponse;
import com.aicompany.agentharness.llm.Message;
import com.aicompany.agentharness.llm.MessageRole;
import com.aicompany.agentharness.llm.ProviderRateLimitStore;
import com.aicompany.agentharness.llm.RetryableLlmException;
import com.aicompany.agentharness.tool.ToolCall;
import com.aicompany.agentharness.tool.ToolDefinition;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Groq의 OpenAI 호환 /chat/completions를 사용하는 Llm 구현체.
 * 도구가 요청에 있으면 native tool call을 보내고, 응답의 tool_calls를 {@link ToolCall}로 전달한다.
 * 그 요청에서 모델 인자가 JSON이 아니어서 Groq가 400을 주면, failed_generation을 고쳐 ToolCall로 넘긴다.
 * 고칠 수 없으면 같은 요청을 한 번 더 보낸다.
 * 도구가 없는 요청에서만, tool_choice가 none인데 모델이 도구를 호출한 HTTP 400을 JSON content로 되돌린다.
 */
public class GroqLlm implements Llm {

	private static final Logger log = LoggerFactory.getLogger(GroqLlm.class);
	private static final int MAX_ERROR_BODY_LENGTH = 500;
	private static final int MAX_DEBUG_BODY_LENGTH = 4000;
	private static final int MAX_DEBUG_GENERATION_LENGTH = 3000;
	private static final Set<Integer> RETRYABLE_STATUS_CODES = Set.of(429, 500, 502, 503, 504);
	private static final String SCHEMA_NAME = "agent_response";
	private static final String ARGUMENT_PARSE_FAILURE = "Failed to parse tool call arguments as JSON";
	private static final String JSON_ONLY_INSTRUCTION = """
			Respond with one JSON object in the assistant message content.
			Do not make a native function call. Encode any tool use inside that JSON object.
			""";

	private final URI chatUri;
	private final String apiKey;
	private final String model;
	private final Duration timeout;
	private final HttpClient httpClient;
	private final ProviderRateLimitStore rateLimits;
	private final Clock clock;
	private static final TypeReference<Map<String, Object>> ARGUMENTS_TYPE = new TypeReference<>() {
	};

	private final JsonMapper jsonMapper = new JsonMapper();

	public GroqLlm(String baseUrl, String model, String apiKey, Duration timeout) {
		this(baseUrl, model, apiKey, timeout, null, Clock.systemUTC());
	}

	public GroqLlm(String baseUrl, String model, String apiKey, Duration timeout, ProviderRateLimitStore rateLimits, Clock clock) {
		this.chatUri = URI.create(baseUrl.replaceAll("/+$", "") + "/chat/completions");
		this.model = model;
		this.apiKey = apiKey;
		this.timeout = timeout;
		this.rateLimits = rateLimits;
		this.clock = clock;
		this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
	}

	public String model() {
		return model;
	}

	@Override
	public boolean nativeToolCalls() {
		return true;
	}

	@Override
	public LlmResponse generate(LlmRequest request) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new LlmException("Groq API key is not configured. Set the GROQ_API_KEY environment variable.",
					LlmErrorCategory.AUTHENTICATION);
		}
		if (model == null || model.isBlank()) {
			throw new LlmException("Groq model is not configured. Set llm.groq.model.", LlmErrorCategory.UNEXPECTED);
		}
		HttpResponse<String> httpResponse = send(toGroqRequest(request));
		rememberRateLimit(httpResponse);
		logBadRequest(httpResponse);
		if (httpResponse.statusCode() / 100 == 2) {
			return parseSuccess(httpResponse.body());
		}
		if (request.tools().isEmpty()) {
			String recovered = recoverToolUseFailure(httpResponse, request);
			if (recovered != null) {
				return new LlmResponse(stripCodeFence(recovered.strip()));
			}
			throw httpError(httpResponse);
		}
		if (isArgumentParseFailure(httpResponse)) {
			LlmResponse repaired = repairedToolCall(httpResponse);
			if (repaired != null) {
				log.warn("Groq rejected tool call arguments as JSON; using repaired arguments for {}",
						repaired.toolCalls().get(0).toolName());
				return repaired;
			}
			log.warn("Groq rejected tool call arguments as JSON; retrying the request once");
			HttpResponse<String> retry = send(toGroqRequest(request));
			rememberRateLimit(retry);
			logBadRequest(retry);
			if (retry.statusCode() / 100 == 2) {
				return parseSuccess(retry.body());
			}
			if (isArgumentParseFailure(retry)) {
				LlmResponse repairedRetry = repairedToolCall(retry);
				if (repairedRetry != null) {
					log.warn("Groq rejected tool call arguments as JSON; using repaired arguments for {}",
							repairedRetry.toolCalls().get(0).toolName());
					return repairedRetry;
				}
			}
			throw httpError(retry);
		}
		throw httpError(httpResponse);
	}

	private boolean isArgumentParseFailure(HttpResponse<String> httpResponse) {
		if (httpResponse.statusCode() != 400) {
			return false;
		}
		JsonNode error = readError(httpResponse.body());
		return error != null && error.path("message").asString("").contains(ARGUMENT_PARSE_FAILURE);
	}

	/**
	 * Groq가 거절한 failed_generation에서 도구 이름과 인자 객체를 복원한다.
	 * 인자 JSON만 깨진 경우에 ToolCall을 만들고, 이름이나 인자 객체를 만들 수 없으면 null을 반환한다.
	 */
	private LlmResponse repairedToolCall(HttpResponse<String> httpResponse) {
		JsonNode error = readError(httpResponse.body());
		if (error == null) {
			log.warn("Groq 400 error body is not JSON; cannot repair the tool call");
			return null;
		}
		JsonNode failedGeneration = error.path("failed_generation");
		boolean missing = failedGeneration.isMissingNode() || failedGeneration.isNull();
		if (missing) {
			log.warn("Groq failed_generation is missing; cannot repair the tool call");
		}
		JsonNode generation = argumentFailureGeneration(failedGeneration);
		if (generation == null) {
			if (!missing) {
				log.warn("Groq failed_generation is not a parsable tool call object; cannot repair the tool call");
			}
			return null;
		}
		log.warn("Groq failed_generation tool call: {}", debugText(generation.toString(), MAX_DEBUG_GENERATION_LENGTH));
		String name = toolName(generation);
		log.warn("Groq parsed tool name: {}", name == null ? "<missing>" : name);
		log.warn("Groq raw tool arguments: {}", debugText(rawArguments(generation), MAX_DEBUG_GENERATION_LENGTH));
		JsonNode arguments = name == null ? null : repairedArguments(generation);
		log.warn("Groq repaired tool arguments: {}",
				arguments == null ? "<could not repair>" : debugText(arguments.toString(), MAX_DEBUG_GENERATION_LENGTH));
		if (name == null || arguments == null) {
			return null;
		}
		String id = generation.path("id").asString("").strip();
		if (id.isBlank()) {
			id = generation.path("tool_call_id").asString("").strip();
		}
		if (id.isBlank()) {
			id = "call-" + name;
		}
		return new LlmResponse("", List.of(new ToolCall(id, name, argumentsOf(arguments))));
	}

	/**
	 * 원인 분석용으로 400 응답의 본문과 error 필드를 남긴다. 요청 헤더(API Key)는 응답에 없고, 본문에 있으면 가린다.
	 */
	private void logBadRequest(HttpResponse<String> httpResponse) {
		if (httpResponse.statusCode() != 400) {
			return;
		}
		String body = httpResponse.body() == null ? "" : httpResponse.body();
		log.warn("Groq 400 error body: {}", debugText(body, MAX_DEBUG_BODY_LENGTH));
		JsonNode error = readError(body);
		if (error == null || !error.isObject()) {
			log.warn("Groq 400 error body has no JSON error object");
			return;
		}
		log.warn("Groq 400 error fields: message={}, code={}, type={}",
				debugText(fieldText(error, "message"), MAX_ERROR_BODY_LENGTH),
				fieldText(error, "code"), fieldText(error, "type"));
		JsonNode failedGeneration = error.path("failed_generation");
		if (failedGeneration.isMissingNode() || failedGeneration.isNull()) {
			log.warn("Groq failed_generation: <missing>");
			return;
		}
		String generation = failedGeneration.isTextual() ? failedGeneration.asString() : failedGeneration.toString();
		log.warn("Groq failed_generation: {}", debugText(generation, MAX_DEBUG_GENERATION_LENGTH));
	}

	private static String fieldText(JsonNode node, String field) {
		JsonNode value = node.path(field);
		if (value.isMissingNode() || value.isNull()) {
			return "<missing>";
		}
		return value.isTextual() ? value.asString() : value.toString();
	}

	private static String rawArguments(JsonNode generation) {
		JsonNode arguments = generation.path("arguments");
		if (arguments.isMissingNode() || arguments.isNull()) {
			arguments = generation.path("function").path("arguments");
		}
		if (arguments.isMissingNode() || arguments.isNull()) {
			return "<missing>";
		}
		return arguments.isTextual() ? arguments.asString() : arguments.toString();
	}

	private String debugText(String text, int maxLength) {
		String masked = text;
		if (apiKey != null && !apiKey.isBlank() && masked.contains(apiKey)) {
			masked = masked.replace(apiKey, "<masked>");
		}
		if (masked.length() <= maxLength) {
			return masked;
		}
		return masked.substring(0, maxLength) + "... (" + masked.length() + " chars)";
	}

	private JsonNode argumentFailureGeneration(JsonNode failedGeneration) {
		JsonNode parsed = parseGeneration(failedGeneration);
		if (parsed != null && parsed.isObject() && repairedArguments(parsed) != null) {
			return parsed;
		}
		if (!failedGeneration.isTextual()) {
			return null;
		}
		try {
			JsonNode repaired = jsonMapper.readTree(repairLooseJson(failedGeneration.asString()));
			return repaired.isObject() ? repaired : null;
		}
		catch (JacksonException exception) {
			return null;
		}
	}

	private JsonNode repairedArguments(JsonNode generation) {
		JsonNode arguments = generation.path("arguments");
		if (arguments.isMissingNode() || arguments.isNull()) {
			arguments = generation.path("function").path("arguments");
		}
		if (arguments.isObject()) {
			return arguments;
		}
		if (!arguments.isTextual()) {
			return null;
		}
		JsonNode parsed = parseLooseObject(arguments.asString());
		return parsed != null && parsed.isObject() ? parsed : null;
	}

	private JsonNode parseLooseObject(String text) {
		try {
			JsonNode parsed = jsonMapper.readTree(text);
			if (parsed.isObject()) {
				return parsed;
			}
		}
		catch (JacksonException ignored) {
			// 모델이 따옴표 없는 키나 깨진 괄호를 넣은 경우 아래에서 고친다.
		}
		try {
			JsonNode repaired = jsonMapper.readTree(repairLooseJson(text));
			return repaired.isObject() ? repaired : null;
		}
		catch (JacksonException exception) {
			return null;
		}
	}

	/**
	 * 따옴표 없는 키, 작은따옴표, 후행 쉼표, Python 리터럴, 객체 안의 잘못된 대괄호를 JSON 객체로 고친다.
	 */
	static String repairLooseJson(String raw) {
		if (raw == null) {
			return "";
		}
		String text = stripCodeFence(raw.strip());
		int start = text.indexOf('{');
		int end = text.lastIndexOf('}');
		if (start < 0 || end <= start) {
			return text;
		}
		StringBuilder out = new StringBuilder(text.length());
		ArrayDeque<Character> containers = new ArrayDeque<>();
		boolean inString = false;
		char stringQuote = '"';
		for (int i = start; i <= end; i++) {
			char c = text.charAt(i);
			if (inString) {
				if (c == '\\' && i + 1 <= end) {
					char next = text.charAt(++i);
					if (stringQuote == '\'' && next == '\'') {
						out.append('\'');
					}
					else {
						out.append('\\').append(next);
					}
					continue;
				}
				if (c == stringQuote) {
					out.append('"');
					inString = false;
					continue;
				}
				if (c == '"' && stringQuote == '\'') {
					out.append("\\\"");
					continue;
				}
				out.append(c);
				continue;
			}
			if (Character.isWhitespace(c)) {
				out.append(c);
				continue;
			}
			if (c == '"' || c == '\'') {
				inString = true;
				stringQuote = c;
				out.append('"');
				continue;
			}
			if (c == '{' || c == '[') {
				containers.push(c);
				out.append(c);
				continue;
			}
			if (c == '}' || c == ']') {
				trimTrailingComma(out);
				char expected = c == '}' ? '{' : '[';
				if (containers.isEmpty() || containers.peek() != expected) {
					continue;
				}
				containers.pop();
				out.append(c);
				continue;
			}
			if (isIdentStart(c)) {
				int wordEnd = i + 1;
				while (wordEnd <= end && isIdentPart(text.charAt(wordEnd))) {
					wordEnd++;
				}
				String word = text.substring(i, wordEnd);
				int separator = wordEnd;
				while (separator <= end && Character.isWhitespace(text.charAt(separator))) {
					separator++;
				}
				if (separator <= end && text.charAt(separator) == ':') {
					out.append('"').append(word).append('"');
				}
				else if ("True".equals(word)) {
					out.append("true");
				}
				else if ("False".equals(word)) {
					out.append("false");
				}
				else if ("None".equals(word)) {
					out.append("null");
				}
				else {
					out.append(word);
				}
				i = wordEnd - 1;
				continue;
			}
			out.append(c);
		}
		return out.toString();
	}

	private static void trimTrailingComma(StringBuilder out) {
		int index = out.length() - 1;
		while (index >= 0 && Character.isWhitespace(out.charAt(index))) {
			index--;
		}
		if (index >= 0 && out.charAt(index) == ',') {
			out.deleteCharAt(index);
		}
	}

	private static boolean isIdentStart(char c) {
		return Character.isLetter(c) || c == '_';
	}

	private static boolean isIdentPart(char c) {
		return Character.isLetterOrDigit(c) || c == '_';
	}

	private ObjectNode toGroqRequest(LlmRequest request) {
		ObjectNode groqRequest = jsonMapper.createObjectNode();
		groqRequest.put("model", model);
		ArrayNode messages = groqRequest.putArray("messages");
		for (Message message : request.messages()) {
			messages.add(toGroqMessage(message));
		}
		if (request.tools().isEmpty()) {
			messages.addObject()
					.put("role", "system")
					.put("content", JSON_ONLY_INSTRUCTION);
		}
		else {
			ArrayNode tools = groqRequest.putArray("tools");
			for (ToolDefinition tool : request.tools()) {
				ObjectNode function = tools.addObject()
						.put("type", "function")
						.putObject("function");
				function.put("name", tool.name());
				function.put("description", tool.description() == null ? "" : tool.description());
				function.set("parameters", parameters(tool.inputSchema()));
			}
		}
		if (request.responseJsonSchema() != null) {
			ObjectNode responseFormat = groqRequest.putObject("response_format");
			responseFormat.put("type", "json_schema");
			ObjectNode jsonSchema = responseFormat.putObject("json_schema");
			jsonSchema.put("name", SCHEMA_NAME);
			jsonSchema.set("schema", jsonMapper.readTree(request.responseJsonSchema()));
		}
		return groqRequest;
	}

	private ObjectNode toGroqMessage(Message message) {
		ObjectNode groqMessage = jsonMapper.createObjectNode();
		if (message.role() == MessageRole.TOOL) {
			groqMessage.put("role", "tool");
			groqMessage.put("tool_call_id", message.toolCallId() == null ? "" : message.toolCallId());
			groqMessage.put("content", message.content());
			return groqMessage;
		}
		groqMessage.put("role", message.role().name().toLowerCase());
		groqMessage.put("content", message.content());
		if (!message.toolCalls().isEmpty()) {
			ArrayNode toolCalls = groqMessage.putArray("tool_calls");
			for (ToolCall toolCall : message.toolCalls()) {
				ObjectNode call = toolCalls.addObject();
				call.put("id", toolCall.id() == null || toolCall.id().isBlank() ? "call" : toolCall.id());
				call.put("type", "function");
				call.putObject("function")
						.put("name", toolCall.toolName())
						.put("arguments", jsonMapper.writeValueAsString(toolCall.arguments()));
			}
		}
		return groqMessage;
	}

	private JsonNode parameters(String inputSchema) {
		if (inputSchema == null || inputSchema.isBlank()) {
			return jsonMapper.readTree("{\"type\":\"object\",\"properties\":{}}");
		}
		try {
			return jsonMapper.readTree(inputSchema);
		}
		catch (JacksonException exception) {
			throw new LlmException("Tool input schema is not valid JSON", exception);
		}
	}

	private LlmResponse parseSuccess(String responseBody) {
		JsonNode response = readJson(responseBody);
		JsonNode choice = response.path("choices").path(0);
		if (choice.isMissingNode()) {
			throw new LlmException("Groq API returned no choices");
		}
		List<ToolCall> toolCalls = parseToolCalls(choice.path("message").path("tool_calls"));
		if (!toolCalls.isEmpty()) {
			return new LlmResponse(choice.path("message").path("content").asString(""), toolCalls);
		}
		return new LlmResponse(extractText(responseBody));
	}

	private List<ToolCall> parseToolCalls(JsonNode toolCalls) {
		List<ToolCall> calls = new ArrayList<>();
		if (!toolCalls.isArray()) {
			return calls;
		}
		for (JsonNode call : toolCalls) {
			String name = call.path("function").path("name").asString("").strip();
			if (name.isBlank()) {
				continue;
			}
			String id = call.path("id").asString("").strip();
			if (id.isBlank()) {
				id = "call-" + name;
			}
			calls.add(new ToolCall(id, name, argumentsOf(call.path("function").path("arguments"))));
		}
		return calls;
	}

	private Map<String, Object> argumentsOf(JsonNode arguments) {
		JsonNode parsed = arguments;
		if (arguments.isTextual()) {
			try {
				parsed = jsonMapper.readTree(arguments.asString());
			}
			catch (JacksonException exception) {
				return Map.of();
			}
		}
		if (!parsed.isObject()) {
			return Map.of();
		}
		Map<String, Object> values = jsonMapper.convertValue(parsed, ARGUMENTS_TYPE);
		if (values == null) {
			return Map.of();
		}
		values.values().removeIf(value -> value == null);
		return Map.copyOf(values);
	}

	private HttpResponse<String> send(ObjectNode groqRequest) {
		HttpRequest httpRequest = HttpRequest.newBuilder(chatUri)
				.timeout(timeout)
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + apiKey)
				.POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(groqRequest), StandardCharsets.UTF_8))
				.build();

		HttpResponse<String> httpResponse;
		try {
			httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		}
		catch (SSLException exception) {
			throw new LlmException("Failed to call Groq API: " + exception.getMessage(), exception);
		}
		catch (IOException exception) {
			throw new RetryableLlmException("Failed to call Groq API: " + describe(exception), exception);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new LlmException("Interrupted while calling Groq API", exception);
		}

		return httpResponse;
	}

	private void rememberRateLimit(HttpResponse<String> httpResponse) {
		if (rateLimits == null || model == null || model.isBlank()) {
			return;
		}
		GroqRateLimitHeaders.parse(httpResponse.headers(), clock.instant())
				.ifPresent(state -> rateLimits.record("groq", model, state));
	}

	/**
	 * Groq는 tool_choice가 none인데 모델이 도구를 호출하면 400과 failed_generation을 돌려준다.
	 * 구조화 출력용 constrain 호출이면 그 인자를, 도구 호출이면 USE_TOOL JSON을 content로 쓴다.
	 */
	private String recoverToolUseFailure(HttpResponse<String> httpResponse, LlmRequest request) {
		if (httpResponse.statusCode() != 400) {
			return null;
		}
		JsonNode error = readError(httpResponse.body());
		if (error == null || !isToolUseFailure(error)) {
			return null;
		}
		JsonNode generation = parseGeneration(error.path("failed_generation"));
		if (generation == null || !generation.isObject()) {
			return null;
		}
		String toolName = toolName(generation);
		JsonNode arguments = parseArguments(generation);
		if (toolName != null && isConstrainTool(toolName)) {
			if (arguments != null && arguments.isObject()) {
				log.warn("Groq returned a JSON constrainer tool call; using its arguments as the response");
				return jsonMapper.writeValueAsString(arguments);
			}
			return null;
		}
		if (toolName != null && schemaAllowsUseTool(request)) {
			log.warn("Groq rejected native tool call {}; converting it to USE_TOOL JSON", toolName);
			ObjectNode decision = jsonMapper.createObjectNode();
			decision.put("type", "USE_TOOL");
			ObjectNode toolCall = decision.putObject("toolCall");
			toolCall.put("toolName", toolName);
			if (arguments != null && arguments.isObject()) {
				toolCall.set("arguments", arguments);
			}
			else {
				toolCall.putObject("arguments");
			}
			return jsonMapper.writeValueAsString(decision);
		}
		if (toolName == null) {
			return jsonMapper.writeValueAsString(generation);
		}
		return null;
	}

	private JsonNode readError(String responseBody) {
		try {
			return jsonMapper.readTree(responseBody).path("error");
		}
		catch (JacksonException exception) {
			return null;
		}
	}

	private static boolean isToolUseFailure(JsonNode error) {
		if ("tool_use_failed".equals(error.path("code").asString(""))) {
			return true;
		}
		return error.path("message").asString("").contains("model called a tool");
	}

	private JsonNode parseGeneration(JsonNode generation) {
		if (generation.isMissingNode() || generation.isNull()) {
			return null;
		}
		if (!generation.isTextual()) {
			return generation.isObject() ? generation : null;
		}
		try {
			return jsonMapper.readTree(generation.asString());
		}
		catch (JacksonException exception) {
			return null;
		}
	}

	private static String toolName(JsonNode generation) {
		if (generation.hasNonNull("name")) {
			String name = generation.path("name").asString("");
			return name.isBlank() ? null : name;
		}
		String functionName = generation.path("function").path("name").asString("");
		return functionName.isBlank() ? null : functionName;
	}

	private JsonNode parseArguments(JsonNode generation) {
		JsonNode arguments = generation.path("arguments");
		if (arguments.isMissingNode() || arguments.isNull()) {
			arguments = generation.path("function").path("arguments");
		}
		if (arguments.isTextual()) {
			try {
				return jsonMapper.readTree(arguments.asString());
			}
			catch (JacksonException exception) {
				return null;
			}
		}
		return arguments.isObject() ? arguments : null;
	}

	private static boolean isConstrainTool(String toolName) {
		String normalized = toolName.toLowerCase();
		return normalized.contains("constrain") || normalized.equals("json");
	}

	private static boolean schemaAllowsUseTool(LlmRequest request) {
		return request.responseJsonSchema() != null && request.responseJsonSchema().contains("USE_TOOL");
	}

	private LlmException httpError(HttpResponse<String> httpResponse) {
		int statusCode = httpResponse.statusCode();
		String message = "Groq API request failed with HTTP " + statusCode + ": " + extractErrorMessage(httpResponse.body());
		if (message.contains(apiKey)) {
			message = message.replace(apiKey, "<masked>");
		}
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
		JsonNode response = readJson(responseBody);
		JsonNode choice = response.path("choices").path(0);
		if (choice.isMissingNode()) {
			throw new LlmException("Groq API returned no choices");
		}
		String text = choice.path("message").path("content").asString("");
		if (text.isBlank()) {
			throw new LlmException("Groq API returned an empty response (finish_reason: "
					+ choice.path("finish_reason").asString("unknown") + ")");
		}
		return stripCodeFence(text.strip());
	}

	private static String stripCodeFence(String text) {
		if (!text.startsWith("```")) {
			return text;
		}
		int firstLineEnd = text.indexOf('\n');
		int lastFence = text.lastIndexOf("```");
		if (firstLineEnd < 0 || lastFence <= firstLineEnd) {
			return text;
		}
		return text.substring(firstLineEnd + 1, lastFence).strip();
	}

	private String extractErrorMessage(String responseBody) {
		try {
			JsonNode error = jsonMapper.readTree(responseBody).path("error");
			String message = error.path("message").asString("");
			if (message.isEmpty() && error.isTextual()) {
				message = error.asString("");
			}
			if (!message.isEmpty()) {
				return message;
			}
		}
		catch (JacksonException ignored) {
			// 오류 본문이 JSON이 아닐 수 있다.
		}
		if (responseBody == null) {
			return "";
		}
		return responseBody.length() > MAX_ERROR_BODY_LENGTH ? responseBody.substring(0, MAX_ERROR_BODY_LENGTH) + "..." : responseBody;
	}

	private JsonNode readJson(String responseBody) {
		try {
			return jsonMapper.readTree(responseBody);
		}
		catch (JacksonException exception) {
			throw new LlmException("Groq API returned invalid JSON", exception);
		}
	}

	private static String describe(Exception exception) {
		StringBuilder text = new StringBuilder(exception.getClass().getSimpleName());
		if (exception.getMessage() != null && !exception.getMessage().isBlank()) {
			text.append(": ").append(exception.getMessage());
		}
		return text.toString();
	}

}
