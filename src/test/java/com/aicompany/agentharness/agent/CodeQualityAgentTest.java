package com.aicompany.agentharness.agent;

import com.aicompany.agentharness.harness.AgentDecision;
import com.aicompany.agentharness.harness.AgentResult;
import com.aicompany.agentharness.harness.AgentRunner;
import com.aicompany.agentharness.harness.AgentState;
import com.aicompany.agentharness.harness.Target;
import com.aicompany.agentharness.harness.Task;
import com.aicompany.agentharness.llm.Llm;
import com.aicompany.agentharness.llm.LlmException;
import com.aicompany.agentharness.llm.LlmRequest;
import com.aicompany.agentharness.llm.LlmResponse;
import com.aicompany.agentharness.llm.LlmRetryPolicy;
import com.aicompany.agentharness.llm.RetryableLlmException;
import com.aicompany.agentharness.llm.RetryingLlm;
import com.aicompany.agentharness.llm.Message;
import com.aicompany.agentharness.llm.MessageRole;
import com.aicompany.agentharness.mcp.FakeMcpServer;
import com.aicompany.agentharness.mcp.McpClient;
import com.aicompany.agentharness.mcp.McpTool;
import com.aicompany.agentharness.mcp.StdioMcpTransport;
import com.aicompany.agentharness.tool.Tool;
import com.aicompany.agentharness.tool.ToolCall;
import com.aicompany.agentharness.tool.ToolDefinition;
import com.aicompany.agentharness.tool.ToolPermissionPolicy;
import com.aicompany.agentharness.tool.ToolRegistry;
import com.aicompany.agentharness.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CodeQualityAgentTest {

	private static final List<ToolDefinition> AVAILABLE_TOOLS = List.of(
			new ToolDefinition("read_text_file", "Read a text file",
					"{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}"),
			new ToolDefinition("list_directory", "List a directory",
					"{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}"));

	private static final String USE_READ_TEXT_FILE = """
			{
			  "type": "USE_TOOL",
			  "toolCall": {
			    "toolName": "read_text_file",
			    "arguments": {
			      "path": "test.java"
			    }
			  }
			}
			""";

	private static final String FINISH_WITH_SUCCESS = """
			{"type": "FINISH", "result": {"success": true, "summary": "Rename variable 'x' to describe its role."}}
			""";

	private final Task task = new Task("Analyze code quality", new Target(Path.of("test.java")));

	@Test
	void convertsUseToolResponseToToolCallDecision() {
		ScriptedLlm llm = new ScriptedLlm(USE_READ_TEXT_FILE);
		AgentState agentState = new AgentState(task);

		AgentDecision decision = new CodeQualityAgent(llm, AVAILABLE_TOOLS).decide(agentState);

		assertThat(decision.type()).isEqualTo(AgentDecision.Type.USE_TOOL);
		assertThat(decision.toolCall()).isEqualTo(new ToolCall("read_text_file", Map.of("path", "test.java")));
		assertThat(llm.receivedRequests).hasSize(1);
		assertThat(agentState.messages()).containsExactly(Message.assistant(USE_READ_TEXT_FILE));
	}

	@Test
	void sendsRoleTaskStateAndToolDefinitionsWithDecisionSchemaToLlm() {
		ScriptedLlm llm = new ScriptedLlm(USE_READ_TEXT_FILE);

		new CodeQualityAgent(llm, AVAILABLE_TOOLS).decide(new AgentState(task));

		LlmRequest request = llm.receivedRequests.get(0);
		assertThat(request.responseJsonSchema()).isEqualTo(AgentDecisionParser.RESPONSE_JSON_SCHEMA);
		assertThat(request.messages()).extracting(Message::role).containsExactly(MessageRole.SYSTEM, MessageRole.USER);
		assertThat(request.messages().get(0).content()).contains("You are a Code Quality Agent");
		assertThat(request.messages().get(1).content())
				.contains("Objective: Analyze code quality")
				.contains("Target: " + Path.of("test.java").toAbsolutePath())
				.contains("Status: RUNNING")
				.contains("- name: read_text_file", "description: Read a text file", AVAILABLE_TOOLS.get(0).inputSchema())
				.contains("- name: list_directory");
	}

	@Test
	void sendsPreviousDecisionAndToolResultInNextRequest() {
		ScriptedLlm llm = new ScriptedLlm(USE_READ_TEXT_FILE, FINISH_WITH_SUCCESS);
		CodeQualityAgent codeQualityAgent = new CodeQualityAgent(llm, AVAILABLE_TOOLS);
		AgentState agentState = new AgentState(task);

		codeQualityAgent.decide(agentState);
		agentState.addToolResult(ToolResult.success("read_text_file", "class Test { int x; }"));
		codeQualityAgent.decide(agentState);

		List<Message> secondRequestMessages = llm.receivedRequests.get(1).messages();
		assertThat(secondRequestMessages).extracting(Message::role)
				.containsExactly(MessageRole.SYSTEM, MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER);
		assertThat(secondRequestMessages.get(2).content()).isEqualTo(USE_READ_TEXT_FILE);
		assertThat(secondRequestMessages.get(3).content())
				.contains("read_text_file (success)")
				.contains("class Test { int x; }");
	}

	@Test
	void convertsFinishResponseToAgentResult() {
		AgentState agentState = new AgentState(task);

		AgentDecision decision = new CodeQualityAgent(new ScriptedLlm(FINISH_WITH_SUCCESS), AVAILABLE_TOOLS).decide(agentState);

		assertThat(decision.type()).isEqualTo(AgentDecision.Type.FINISH);
		assertThat(decision.result()).isEqualTo(AgentResult.success("Rename variable 'x' to describe its role."));
		assertThat(agentState.analysisResults()).containsExactly("Rename variable 'x' to describe its role.");
	}

	@Test
	void convertsFailedFinishResponseToFailedAgentResult() {
		AgentState agentState = new AgentState(task);
		ScriptedLlm llm = new ScriptedLlm("""
				{"type": "FINISH", "result": {"success": false, "summary": "Target file cannot be read."}}
				""");

		AgentDecision decision = new CodeQualityAgent(llm, AVAILABLE_TOOLS).decide(agentState);

		assertThat(decision.result()).isEqualTo(AgentResult.failure("Target file cannot be read."));
		assertThat(agentState.analysisResults()).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"I will read the file first.",
			"[\"USE_TOOL\"]",
			"{\"type\": \"DELETE_EVERYTHING\"}",
			"{\"type\": \"USE_TOOL\"}",
			"{\"type\": \"USE_TOOL\", \"toolCall\": {\"arguments\": {\"path\": \"test.java\"}}}",
			"{\"type\": \"USE_TOOL\", \"toolCall\": {\"toolName\": \"read_text_file\", \"arguments\": \"test.java\"}}",
			"{\"type\": \"USE_TOOL\", \"toolCall\": {\"toolName\": \"read_text_file\", \"arguments\": {\"path\": null}}}",
			"{\"type\": \"FINISH\", \"result\": {\"summary\": \"done\"}}"
	})
	void finishesWithFailureInsteadOfUsingToolWhenLlmResponseIsInvalid(String invalidResponse) {
		AgentDecision decision = new CodeQualityAgent(new ScriptedLlm(invalidResponse), AVAILABLE_TOOLS).decide(new AgentState(task));

		assertThat(decision.type()).isEqualTo(AgentDecision.Type.FINISH);
		assertThat(decision.result().success()).isFalse();
		assertThat(decision.result().summary()).startsWith("Invalid LLM decision:");
	}

	@Test
	void finishesWithFailureWhenLlmCallFails() {
		Llm failingLlm = request -> {
			throw new LlmException("Gemini API request failed with HTTP 503: The model is overloaded.");
		};

		AgentDecision decision = new CodeQualityAgent(failingLlm, AVAILABLE_TOOLS).decide(new AgentState(task));

		assertThat(decision.type()).isEqualTo(AgentDecision.Type.FINISH);
		assertThat(decision.result().success()).isFalse();
		assertThat(decision.result().summary()).contains("LLM call failed").contains("HTTP 503");
	}

	@Test
	void runsLlmSelectedToolThroughMcpAndFinishes() {
		try (McpClient mcpClient = new McpClient(StdioMcpTransport.start(FakeMcpServer.command(), Duration.ofSeconds(20)))) {
			mcpClient.initialize();
			ToolRegistry toolRegistry = new ToolRegistry();
			McpTool.discoverAll(mcpClient).forEach(toolRegistry::register);
			ScriptedLlm llm = new ScriptedLlm("""
					{"type": "USE_TOOL", "toolCall": {"toolName": "read_document", "arguments": {"location": "notes.txt"}}}
					""", FINISH_WITH_SUCCESS);

			ToolPermissionPolicy permissionPolicy = new ToolPermissionPolicy(List.of("read_document"), List.of());

			AgentResult agentResult = new AgentRunner(toolRegistry, permissionPolicy, 5)
					.run(new CodeQualityAgent(llm, permissionPolicy.allowedToolDefinitions(toolRegistry.toolDefinitions())), task);

			assertThat(agentResult).isEqualTo(AgentResult.success("Rename variable 'x' to describe its role."));
			assertThat(llm.receivedRequests.get(0).messages().get(1).content())
					.contains("- name: read_document")
					.doesNotContain("- name: echo");
			assertThat(llm.receivedRequests.get(1).messages().get(3).content()).contains("contents of notes.txt");
		}
	}

	@Test
	void doesNotExecuteDeniedToolEvenWhenLlmRequestsIt() {
		RecordingTool readTool = new RecordingTool("read_text_file");
		RecordingTool writeTool = new RecordingTool("write_file");
		ToolRegistry toolRegistry = new ToolRegistry();
		toolRegistry.register(readTool);
		toolRegistry.register(writeTool);
		ToolPermissionPolicy permissionPolicy =
				new ToolPermissionPolicy(List.of("read_text_file", "write_file"), List.of("write_file"));
		ScriptedLlm llm = new ScriptedLlm("""
				{"type": "USE_TOOL", "toolCall": {"toolName": "write_file", "arguments": {"path": "test.java", "content": ""}}}
				""", FINISH_WITH_SUCCESS);

		new AgentRunner(toolRegistry, permissionPolicy, 5)
				.run(new CodeQualityAgent(llm, permissionPolicy.allowedToolDefinitions(toolRegistry.toolDefinitions())), task);

		assertThat(writeTool.executionCount).isZero();
		assertThat(llm.receivedRequests.get(0).messages().get(1).content())
				.contains("- name: read_text_file")
				.doesNotContain("write_file");
		assertThat(llm.receivedRequests.get(1).messages().get(3).content())
				.contains("write_file (failure)")
				.contains("Tool execution denied: write_file");
	}

	@Test
	void continuesToToolExecutionWhenLlmCallSucceedsAfterRetry() {
		RecordingTool readTool = new RecordingTool("read_text_file");
		ToolRegistry toolRegistry = new ToolRegistry();
		toolRegistry.register(readTool);
		ToolPermissionPolicy permissionPolicy = new ToolPermissionPolicy(List.of("read_text_file"), List.of());
		ScriptedLlm scriptedLlm = new ScriptedLlm(USE_READ_TEXT_FILE, FINISH_WITH_SUCCESS);
		AtomicInteger rateLimitedCalls = new AtomicInteger();
		Llm rateLimitedOnceLlm = request -> {
			if (rateLimitedCalls.getAndIncrement() == 0) {
				throw new RetryableLlmException("Gemini API request failed with HTTP 429: quota exceeded", (Duration) null);
			}
			return scriptedLlm.generate(request);
		};
		List<Duration> sleeps = new ArrayList<>();
		Llm retryingLlm = new RetryingLlm(rateLimitedOnceLlm,
				new LlmRetryPolicy(3, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(10), 0), sleeps::add);

		AgentResult agentResult = new AgentRunner(toolRegistry, permissionPolicy, 5)
				.run(new CodeQualityAgent(retryingLlm, permissionPolicy.allowedToolDefinitions(toolRegistry.toolDefinitions())), task);

		assertThat(agentResult).isEqualTo(AgentResult.success("Rename variable 'x' to describe its role."));
		assertThat(readTool.executionCount).isEqualTo(1);
		assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
		assertThat(rateLimitedCalls).hasValue(3);
	}

	@Test
	void finishesWithFailureWhenLlmRetriesAreExhausted() {
		Llm alwaysRateLimitedLlm = request -> {
			throw new RetryableLlmException("Gemini API request failed with HTTP 429: quota exceeded", (Duration) null);
		};
		Llm retryingLlm = new RetryingLlm(alwaysRateLimitedLlm,
				new LlmRetryPolicy(3, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(10), 0), duration -> {
				});

		AgentResult agentResult = new AgentRunner(new ToolRegistry(), new ToolPermissionPolicy(List.of(), List.of()), 5)
				.run(new CodeQualityAgent(retryingLlm, AVAILABLE_TOOLS), task);

		assertThat(agentResult.success()).isFalse();
		assertThat(agentResult.summary()).contains("LLM call failed", "after 3 attempts", "HTTP 429");
	}

	private static class RecordingTool implements Tool {

		private final String name;
		private int executionCount;

		RecordingTool(String name) {
			this.name = name;
		}

		@Override
		public String name() {
			return name;
		}

		@Override
		public String description() {
			return "Records executions";
		}

		@Override
		public String inputSchema() {
			return "{\"type\":\"object\"}";
		}

		@Override
		public ToolResult execute(Map<String, Object> arguments) {
			executionCount++;
			return ToolResult.success(name, "executed");
		}

	}

	private static class ScriptedLlm implements Llm {

		private final Deque<String> scriptedResponses;
		private final List<LlmRequest> receivedRequests = new ArrayList<>();

		ScriptedLlm(String... scriptedResponses) {
			this.scriptedResponses = new ArrayDeque<>(List.of(scriptedResponses));
		}

		@Override
		public LlmResponse generate(LlmRequest request) {
			receivedRequests.add(request);
			return new LlmResponse(scriptedResponses.removeFirst());
		}

	}

}
