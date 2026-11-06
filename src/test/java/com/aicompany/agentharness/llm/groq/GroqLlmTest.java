package com.aicompany.agentharness.llm.groq;

import com.aicompany.agentharness.llm.LlmErrorCategory;
import com.aicompany.agentharness.llm.LlmException;
import com.aicompany.agentharness.llm.LlmRequest;
import com.aicompany.agentharness.llm.LlmResponse;
import com.aicompany.agentharness.llm.Message;
import com.aicompany.agentharness.llm.RetryableLlmException;
import com.aicompany.agentharness.tool.ToolCall;
import com.aicompany.agentharness.tool.ToolDefinition;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GroqLlmTest {

	private static final String API_KEY = "test-groq-key";
	private static final String TEXT_RESPONSE = """
			{"choices":[{"message":{"role":"assistant","content":"hello"},"finish_reason":"stop"}]}
			""";

	private final JsonMapper jsonMapper = new JsonMapper();
	private final AtomicReference<ReceivedRequest> receivedRequest = new AtomicReference<>();
	private final AtomicInteger requests = new AtomicInteger();
	private final List<Integer> scriptedStatuses = new ArrayList<>();
	private final List<String> scriptedBodies = new ArrayList<>();
	private HttpServer server;
	private int responseStatus = 200;
	private String responseBody = TEXT_RESPONSE;

	private record ReceivedRequest(String path, String authorization, JsonNode body) {
	}

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			receivedRequest.set(new ReceivedRequest(
					exchange.getRequestURI().getPath(),
					exchange.getRequestHeaders().getFirst("Authorization"),
					jsonMapper.readTree(requestBody)));
			int index = requests.getAndIncrement();
			int status = index < scriptedStatuses.size() ? scriptedStatuses.get(index) : responseStatus;
			String body = index < scriptedBodies.size() ? scriptedBodies.get(index) : responseBody;
			byte[] response = body.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(status, response.length);
			exchange.getResponseBody().write(response);
			exchange.close();
		});
		server.start();
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	@Test
	void convertsLlmRequestToGroqChatCompletion() {
		createGroqLlm(API_KEY).generate(new LlmRequest(List.of(
				Message.system("You are a reviewer."),
				Message.user("Review this.")),
				"{\"type\":\"object\"}"));

		ReceivedRequest request = receivedRequest.get();
		assertThat(request.path()).isEqualTo("/openai/v1/chat/completions");
		assertThat(request.authorization()).isEqualTo("Bearer " + API_KEY);
		assertThat(request.body().path("model").asString()).isEqualTo("openai/gpt-oss-20b");
		assertThat(request.body().path("messages").path(0).path("role").asString()).isEqualTo("system");
		assertThat(request.body().path("messages").path(2).path("content").asString())
				.contains("Do not make a native function call");
		assertThat(request.body().has("tools")).isFalse();
		assertThat(request.body().has("tool_choice")).isFalse();
		assertThat(request.body().path("response_format").path("type").asString()).isEqualTo("json_schema");
		assertThat(request.body().path("response_format").path("json_schema").path("schema").path("type").asString())
				.isEqualTo("object");
	}

	@Test
	void omitsResponseFormatWhenNoSchemaIsRequested() {
		createGroqLlm(API_KEY).generate(new LlmRequest(List.of(Message.user("Hi"))));

		assertThat(receivedRequest.get().body().has("response_format")).isFalse();
	}

	@Test
	void convertsGroqResponseToLlmResponseAndStripsCodeFence() {
		responseBody = """
				{"choices":[{"message":{"content":"```json\\n{\\"type\\":\\"FINISH\\"}\\n```"},"finish_reason":"stop"}]}
				""";

		LlmResponse response = createGroqLlm(API_KEY).generate(new LlmRequest(List.of(Message.user("Hi"))));

		assertThat(response.content()).isEqualTo("{\"type\":\"FINISH\"}");
	}

	@Test
	void classifiesRateLimitAsRetryableWithoutExposingTheApiKey() {
		responseStatus = 429;
		responseBody = """
				{"error":{"message":"rate limit for test-groq-key"}}
				""";

		assertThatThrownBy(() -> createGroqLlm(API_KEY).generate(new LlmRequest(List.of(Message.user("Hi")))))
				.isInstanceOf(RetryableLlmException.class)
				.hasMessageContaining("HTTP 429")
				.hasMessageNotContaining(API_KEY)
				.extracting(thrown -> ((LlmException) thrown).category())
				.isEqualTo(LlmErrorCategory.RATE_LIMIT);
	}

	@Test
	void convertsRejectedNativeToolCallIntoUseToolJson() {
		responseStatus = 400;
		responseBody = """
				{"error":{"message":"Tool choice is none, but model called a tool","code":"tool_use_failed","failed_generation":"{\\"name\\":\\"list_code_types\\",\\"arguments\\":{\\"name\\":\\"CodeNodeExtractor\\"}}"}}
				""";

		LlmResponse response = createGroqLlm(API_KEY).generate(new LlmRequest(
				List.of(Message.user("Find the type.")),
				"{\"type\":\"object\",\"properties\":{\"type\":{\"enum\":[\"USE_TOOL\",\"FINISH\"]}}}"));

		JsonNode decision = jsonMapper.readTree(response.content());
		assertThat(decision.path("type").asString()).isEqualTo("USE_TOOL");
		assertThat(decision.path("toolCall").path("toolName").asString()).isEqualTo("list_code_types");
		assertThat(decision.path("toolCall").path("arguments").path("name").asString()).isEqualTo("CodeNodeExtractor");
	}

	@Test
	void sendsNativeFunctionToolsAndParsesToolCalls() {
		responseBody = """
				{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_123","type":"function","function":{"name":"search_files","arguments":"{\\"pattern\\":\\"**/*.java\\",\\"path\\":\\"src\\"}"}}]},"finish_reason":"tool_calls"}]}
				""";
		ToolDefinition searchFiles = new ToolDefinition("search_files", "Search files",
				"{\"type\":\"object\",\"properties\":{\"pattern\":{\"type\":\"string\"}},\"required\":[\"pattern\"]}");

		LlmResponse response = createGroqLlm(API_KEY).generate(new LlmRequest(
				List.of(Message.user("Find Java files.")), null, List.of(searchFiles)));

		ReceivedRequest request = receivedRequest.get();
		assertThat(request.body().has("response_format")).isFalse();
		assertThat(request.body().path("messages")).noneMatch(message -> message.path("content").asString("").contains("native function call"));
		JsonNode function = request.body().path("tools").path(0).path("function");
		assertThat(function.path("name").asString()).isEqualTo("search_files");
		assertThat(function.path("description").asString()).isEqualTo("Search files");
		assertThat(function.path("parameters").path("required").path(0).asString()).isEqualTo("pattern");
		assertThat(response.toolCalls()).containsExactly(new ToolCall("call_123", "search_files",
				Map.of("pattern", "**/*.java", "path", "src")));
	}

	@Test
	void sendsToolResultBackOnTheNextNativeRequest() {
		responseBody = TEXT_RESPONSE;
		createGroqLlm(API_KEY).generate(new LlmRequest(List.of(
				Message.user("Find Java files."),
				Message.assistantToolCall(new ToolCall("call_123", "search_files", Map.of("pattern", "**/*.java"))),
				Message.toolResult("call_123", "src/A.java")),
				null,
				List.of(new ToolDefinition("search_files", "Search files", "{\"type\":\"object\"}"))));

		JsonNode messages = receivedRequest.get().body().path("messages");
		assertThat(messages.path(1).path("tool_calls").path(0).path("id").asString()).isEqualTo("call_123");
		assertThat(messages.path(1).path("tool_calls").path(0).path("function").path("name").asString()).isEqualTo("search_files");
		assertThat(messages.path(1).path("tool_calls").path(0).path("function").path("arguments").asString()).contains("**/*.java");
		assertThat(messages.path(2).path("role").asString()).isEqualTo("tool");
		assertThat(messages.path(2).path("tool_call_id").asString()).isEqualTo("call_123");
		assertThat(messages.path(2).path("content").asString()).isEqualTo("src/A.java");
	}

	@Test
	void keepsContentResponseWhenNativeToolsAreEnabledButUnused() {
		LlmResponse response = createGroqLlm(API_KEY).generate(new LlmRequest(
				List.of(Message.user("Summarize.")),
				null,
				List.of(new ToolDefinition("search_files", "Search files", "{\"type\":\"object\"}"))));

		assertThat(response.content()).isEqualTo("hello");
		assertThat(response.toolCalls()).isEmpty();
	}

	@Test
	void doesNotRecoverToolUseFailureWhenNativeToolsWereRequested() {
		responseStatus = 400;
		responseBody = """
				{"error":{"message":"Tool choice is none, but model called a tool","code":"tool_use_failed","failed_generation":"{\\"name\\":\\"search_files\\",\\"arguments\\":{}}"}}
				""";

		assertThatThrownBy(() -> createGroqLlm(API_KEY).generate(new LlmRequest(
				List.of(Message.user("Find Java files.")),
				null,
				List.of(new ToolDefinition("search_files", "Search files", "{\"type\":\"object\"}")))))
				.isInstanceOf(LlmException.class)
				.hasMessageContaining("HTTP 400");
		assertThat(requests).hasValue(1);
	}

	@Test
	void repairsMalformedNativeToolArgumentsWithoutRetrying() {
		responseStatus = 400;
		responseBody = """
				{"error":{"message":"Failed to parse tool call arguments as JSON","type":"invalid_request_error","code":"tool_use_failed","failed_generation":"{\\"name\\": \\"read_text_file\\", \\"arguments\\": {path: \\"src/A.java\\"}}"}}
				""";

		LlmResponse response = createGroqLlm(API_KEY).generate(new LlmRequest(
				List.of(Message.user("Read the class.")),
				null,
				List.of(new ToolDefinition("read_text_file", "Read a file", "{\"type\":\"object\"}"))));

		assertThat(requests).hasValue(1);
		assertThat(response.content()).isEmpty();
		assertThat(response.toolCalls()).containsExactly(new ToolCall("call-read_text_file", "read_text_file",
				Map.of("path", "src/A.java")));
	}

	@Test
	void repairsStrayBracketInFailedGenerationArguments() {
		responseStatus = 400;
		responseBody = """
				{"error":{"message":"Failed to parse tool call arguments as JSON","code":"tool_use_failed","failed_generation":"{\\"id\\":\\"call_9\\",\\"name\\":\\"terminal\\",\\"arguments\\":{\\"command\\":\\"pytest -q\\"], \\"timeout\\": 100000}}"}}
				""";

		LlmResponse response = createGroqLlm(API_KEY).generate(new LlmRequest(
				List.of(Message.user("Run tests.")),
				null,
				List.of(new ToolDefinition("terminal", "Run a command", "{\"type\":\"object\"}"))));

		assertThat(requests).hasValue(1);
		ToolCall call = response.toolCalls().get(0);
		assertThat(call.id()).isEqualTo("call_9");
		assertThat(call.toolName()).isEqualTo("terminal");
		assertThat(call.arguments()).containsEntry("command", "pytest -q");
		assertThat(call.arguments()).containsEntry("timeout", 100000);
	}

	@Test
	@ExtendWith(OutputCaptureExtension.class)
	void logsTheBadRequestBodyAndErrorFieldsWithoutTheApiKey(CapturedOutput output) {
		responseStatus = 400;
		responseBody = """
				{"error":{"message":"Parsing failed. The model generated output that could not be parsed.","type":"invalid_request_error","code":"output_parse_failed","failed_generation":"<|channel|>commentary to=functions.read_text_file {path: src/A.java"}}
				""";

		assertThatThrownBy(() -> createGroqLlm(API_KEY).generate(new LlmRequest(
				List.of(Message.user("Read the class.")),
				null,
				List.of(new ToolDefinition("read_text_file", "Read a file", "{\"type\":\"object\"}")))))
				.isInstanceOf(LlmException.class)
				.hasMessageContaining("Parsing failed");

		assertThat(requests).hasValue(1);
		assertThat(output).contains("Groq 400 error body: {\"error\"")
				.contains("message=Parsing failed. The model generated output that could not be parsed., code=output_parse_failed, type=invalid_request_error")
				.contains("Groq failed_generation: <|channel|>commentary to=functions.read_text_file {path: src/A.java")
				.doesNotContain(API_KEY);
	}

	@Test
	@ExtendWith(OutputCaptureExtension.class)
	void logsParsedToolNameAndArgumentsWhileRepairing(CapturedOutput output) {
		responseStatus = 400;
		responseBody = """
				{"error":{"message":"Failed to parse tool call arguments as JSON","code":"tool_use_failed","failed_generation":"{\\"name\\": \\"read_text_file\\", \\"arguments\\": \\"{path: 'src/A.java',}\\"}"}}
				""";

		createGroqLlm(API_KEY).generate(new LlmRequest(
				List.of(Message.user("Read the class.")),
				null,
				List.of(new ToolDefinition("read_text_file", "Read a file", "{\"type\":\"object\"}"))));

		assertThat(output).contains("Groq failed_generation tool call:")
				.contains("Groq parsed tool name: read_text_file")
				.contains("Groq raw tool arguments: {path: 'src/A.java',}")
				.contains("Groq repaired tool arguments: {\"path\":\"src/A.java\"}");
	}

	@Test
	@ExtendWith(OutputCaptureExtension.class)
	void logsWhenFailedGenerationIsMissing(CapturedOutput output) {
		responseStatus = 400;
		responseBody = """
				{"error":{"message":"Failed to parse tool call arguments as JSON","code":"tool_use_failed"}}
				""";

		assertThatThrownBy(() -> createGroqLlm(API_KEY).generate(new LlmRequest(
				List.of(Message.user("Read the class.")),
				null,
				List.of(new ToolDefinition("read_text_file", "Read a file", "{\"type\":\"object\"}")))))
				.isInstanceOf(LlmException.class);

		assertThat(requests).hasValue(2);
		assertThat(output).contains("Groq failed_generation: <missing>")
				.contains("Groq failed_generation is missing; cannot repair the tool call");
	}

	@Test
	@ExtendWith(OutputCaptureExtension.class)
	void logsWhenFailedGenerationIsNotAToolCall(CapturedOutput output) {
		String broken = """
				{"error":{"message":"Failed to parse tool call arguments as JSON","code":"tool_use_failed","failed_generation":"import csv"}}
				""";
		scriptedStatuses.addAll(List.of(400, 400));
		scriptedBodies.add(broken);
		scriptedBodies.add(broken);

		assertThatThrownBy(() -> createGroqLlm(API_KEY).generate(new LlmRequest(
				List.of(Message.user("Read the class.")),
				null,
				List.of(new ToolDefinition("read_text_file", "Read a file", "{\"type\":\"object\"}")))))
				.isInstanceOf(LlmException.class);

		assertThat(output).contains("Groq failed_generation: import csv")
				.contains("Groq failed_generation is not a parsable tool call object; cannot repair the tool call");
	}

	@Test
	void retriesMalformedNativeToolArgumentsOnceWhenTheyCannotBeRepaired() {
		String broken = """
				{"error":{"message":"Failed to parse tool call arguments as JSON","code":"tool_use_failed","failed_generation":"import csv\\nprint(1)"}}
				""";
		scriptedStatuses.addAll(List.of(400, 400));
		scriptedBodies.add(broken);
		scriptedBodies.add(broken);

		assertThatThrownBy(() -> createGroqLlm(API_KEY).generate(new LlmRequest(
				List.of(Message.user("Read the class.")),
				null,
				List.of(new ToolDefinition("read_text_file", "Read a file", "{\"type\":\"object\"}")))))
				.isInstanceOf(LlmException.class)
				.isNotInstanceOf(RetryableLlmException.class)
				.hasMessageContaining("Failed to parse tool call arguments as JSON");
		assertThat(requests).hasValue(2);
	}

	@Test
	void usesTheRetryResponseWhenMalformedArgumentsAreRepairedOnTheSecondAttempt() {
		scriptedStatuses.addAll(List.of(400, 200));
		scriptedBodies.add("""
				{"error":{"message":"Failed to parse tool call arguments as JSON","code":"tool_use_failed","failed_generation":"not a tool call"}}
				""");
		scriptedBodies.add("""
				{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_123","type":"function","function":{"name":"search_files","arguments":"{\\"pattern\\":\\"*.java\\"}"}}]},"finish_reason":"tool_calls"}]}
				""");

		LlmResponse response = createGroqLlm(API_KEY).generate(new LlmRequest(
				List.of(Message.user("Find Java files.")),
				null,
				List.of(new ToolDefinition("search_files", "Search files", "{\"type\":\"object\"}"))));

		assertThat(requests).hasValue(2);
		assertThat(response.toolCalls()).containsExactly(new ToolCall("call_123", "search_files", Map.of("pattern", "*.java")));
	}

	@Test
	void convertsUnknownNativeToolCallWithoutCheckingAnAllowlist() {
		responseStatus = 400;
		responseBody = """
				{"error":{"message":"Tool choice is none, but model called a tool","code":"tool_use_failed","failed_generation":"{\\"name\\":\\"container.exec\\",\\"arguments\\":{\\"command\\":\\"ls\\"}}"}}
				""";

		LlmResponse response = createGroqLlm(API_KEY).generate(new LlmRequest(
				List.of(Message.user("List the files.")),
				"{\"type\":\"object\",\"properties\":{\"type\":{\"enum\":[\"USE_TOOL\",\"FINISH\"]}}}"));

		JsonNode decision = jsonMapper.readTree(response.content());
		assertThat(decision.path("type").asString()).isEqualTo("USE_TOOL");
		assertThat(decision.path("toolCall").path("toolName").asString()).isEqualTo("container.exec");
		assertThat(decision.path("toolCall").path("arguments").path("command").asString()).isEqualTo("ls");
	}

	@Test
	void usesConstrainToolArgumentsAsTheResponse() {
		responseStatus = 400;
		responseBody = """
				{"error":{"message":"Tool choice is none, but model called a tool","code":"tool_use_failed","failed_generation":"{\\"name\\":\\"<|constrain|>json\\",\\"arguments\\":{\\"tasks\\":[{\\"id\\":\\"read-sources\\"}]}}"}}
				""";

		LlmResponse response = createGroqLlm(API_KEY).generate(new LlmRequest(
				List.of(Message.user("Plan the work.")),
				"{\"type\":\"object\"}"));

		assertThat(jsonMapper.readTree(response.content()).path("tasks").path(0).path("id").asString()).isEqualTo("read-sources");
	}

	@Test
	void keepsUnusableToolCallAsUnexpectedHttpError() {
		responseStatus = 400;
		responseBody = """
				{"error":{"message":"Tool choice is none, but model called a tool","code":"tool_use_failed","failed_generation":"{\\"name\\":\\"web_search\\",\\"arguments\\":{\\"query\\":\\"deps\\"}}"}}
				""";

		assertThatThrownBy(() -> createGroqLlm(API_KEY).generate(new LlmRequest(
				List.of(Message.user("Plan the work.")),
				"{\"type\":\"object\"}")))
				.isInstanceOf(LlmException.class)
				.isNotInstanceOf(RetryableLlmException.class)
				.hasMessageContaining("HTTP 400");
	}

	@Test
	void failsImmediatelyWhenApiKeyIsMissing() {
		assertThatThrownBy(() -> createGroqLlm("").generate(new LlmRequest(List.of(Message.user("Hi")))))
				.isInstanceOf(LlmException.class)
				.hasMessageContaining("GROQ_API_KEY")
				.extracting(thrown -> ((LlmException) thrown).category())
				.isEqualTo(LlmErrorCategory.AUTHENTICATION);
		assertThat(receivedRequest.get()).isNull();
	}

	private GroqLlm createGroqLlm(String apiKey) {
		return new GroqLlm("http://127.0.0.1:" + server.getAddress().getPort() + "/openai/v1", "openai/gpt-oss-20b", apiKey,
				Duration.ofSeconds(5));
	}

}
