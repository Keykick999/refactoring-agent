package com.aicompany.agentharness.harness;

import com.aicompany.agentharness.mcp.FakeMcpServer;
import com.aicompany.agentharness.mcp.McpClient;
import com.aicompany.agentharness.mcp.McpTool;
import com.aicompany.agentharness.mcp.StdioMcpTransport;
import com.aicompany.agentharness.tool.Tool;
import com.aicompany.agentharness.tool.ToolCall;
import com.aicompany.agentharness.tool.ToolPermissionPolicy;
import com.aicompany.agentharness.tool.ToolRegistry;
import com.aicompany.agentharness.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRunnerTest {

	private static final Task TASK = new Task("test goal", new Target(Path.of("Sample.java")));

	private RecordingTool echoTool;
	private RecordingTool writeTool;
	private ToolRegistry toolRegistry;
	private final ToolPermissionPolicy permissionPolicy =
			new ToolPermissionPolicy(List.of("echo", "read_document", "write"), List.of("write"));

	@BeforeEach
	void setUp() {
		echoTool = new RecordingTool("echo");
		writeTool = new RecordingTool("write");
		toolRegistry = new ToolRegistry();
		toolRegistry.register(echoTool);
		toolRegistry.register(writeTool);
	}

	@Test
	void executesToolWhenAgentDecidesToUseTool() {
		Agent agent = agentState -> agentState.toolResults().isEmpty()
				? AgentDecision.useTool(new ToolCall("echo", Map.of("text", "hello")))
				: AgentDecision.finish(AgentResult.success("done"));

		new AgentRunner(toolRegistry, permissionPolicy, 5).run(agent, TASK);

		assertThat(echoTool.receivedArguments).containsExactly(Map.of("text", "hello"));
	}

	@Test
	void reflectsToolResultInAgentStateBeforeNextDecision() {
		Agent agent = agentState -> agentState.toolResults().isEmpty()
				? AgentDecision.useTool(new ToolCall("echo", Map.of("text", "hello")))
				: AgentDecision.finish(AgentResult.success(agentState.toolResults().get(0).output()));

		AgentResult agentResult = new AgentRunner(toolRegistry, permissionPolicy, 5).run(agent, TASK);

		assertThat(agentResult.success()).isTrue();
		assertThat(agentResult.summary()).isEqualTo("hello");
	}

	@Test
	void stopsAndReturnsResultWhenAgentDecidesToFinish() {
		AtomicReference<AgentState> capturedState = new AtomicReference<>();
		Agent agent = agentState -> {
			capturedState.set(agentState);
			return AgentDecision.finish(AgentResult.success("done"));
		};

		AgentResult agentResult = new AgentRunner(toolRegistry, permissionPolicy, 5).run(agent, TASK);

		assertThat(agentResult).isEqualTo(AgentResult.success("done"));
		assertThat(echoTool.receivedArguments).isEmpty();
		assertThat(capturedState.get().task()).isEqualTo(TASK);
		assertThat(capturedState.get().status()).isEqualTo(AgentStatus.COMPLETED);
	}

	@Test
	void returnsFailureWhenMaxStepsExceeded() {
		AtomicReference<AgentState> capturedState = new AtomicReference<>();
		Agent neverFinishingAgent = agentState -> {
			capturedState.set(agentState);
			return AgentDecision.useTool(new ToolCall("echo", Map.of("text", "again")));
		};

		AgentResult agentResult = new AgentRunner(toolRegistry, permissionPolicy, 3).run(neverFinishingAgent, TASK);

		assertThat(agentResult.success()).isFalse();
		assertThat(echoTool.receivedArguments).hasSize(3);
		assertThat(capturedState.get().status()).isEqualTo(AgentStatus.FAILED);
	}

	@Test
	void recordsFailureResultWhenToolIsNotRegistered() {
		Agent agent = agentState -> agentState.toolResults().isEmpty()
				? AgentDecision.useTool(new ToolCall("unknown_tool", Map.of()))
				: AgentDecision.finish(AgentResult.success(agentState.toolResults().get(0).output()));

		AgentResult agentResult = new AgentRunner(toolRegistry, permissionPolicy, 5).run(agent, TASK);

		assertThat(agentResult.summary()).isEqualTo("Unknown tool: unknown_tool");
	}

	@Test
	void doesNotExecuteDeniedToolAndRecordsDenialInAgentState() {
		AtomicReference<AgentState> capturedState = new AtomicReference<>();
		Agent agent = agentState -> {
			capturedState.set(agentState);
			return agentState.toolResults().isEmpty()
					? AgentDecision.useTool(new ToolCall("write", Map.of("text", "overwrite")))
					: AgentDecision.finish(AgentResult.success("done"));
		};

		AgentResult agentResult = new AgentRunner(toolRegistry, permissionPolicy, 5).run(agent, TASK);

		assertThat(writeTool.receivedArguments).isEmpty();
		assertThat(capturedState.get().toolResults())
				.containsExactly(ToolResult.failure("write", "Tool execution denied: write"));
		assertThat(agentResult).isEqualTo(AgentResult.success("done"));
	}

	@Test
	void doesNotExecuteRegisteredToolThatIsNotAllowed() {
		ToolPermissionPolicy echoOnlyPolicy = new ToolPermissionPolicy(List.of("echo"), List.of());
		Agent agent = agentState -> agentState.toolResults().isEmpty()
				? AgentDecision.useTool(new ToolCall("write", Map.of("text", "overwrite")))
				: AgentDecision.finish(AgentResult.success(agentState.toolResults().get(0).output()));

		AgentResult agentResult = new AgentRunner(toolRegistry, echoOnlyPolicy, 5).run(agent, TASK);

		assertThat(writeTool.receivedArguments).isEmpty();
		assertThat(agentResult.summary()).isEqualTo("Tool execution denied: write");
	}

	@Test
	void runsAgentDecisionThroughMcpToolAndReflectsResultInState() {
		try (McpClient mcpClient = new McpClient(StdioMcpTransport.start(FakeMcpServer.command(), Duration.ofSeconds(20)))) {
			mcpClient.initialize();
			ToolRegistry mcpToolRegistry = new ToolRegistry();
			McpTool.discoverAll(mcpClient).forEach(mcpToolRegistry::register);

			AtomicReference<AgentState> capturedState = new AtomicReference<>();
			Agent agent = agentState -> {
				capturedState.set(agentState);
				return agentState.toolResults().isEmpty()
						? AgentDecision.useTool(new ToolCall("read_document", Map.of("location", "notes.txt")))
						: AgentDecision.finish(AgentResult.success(agentState.toolResults().get(0).output()));
			};

			AgentResult agentResult = new AgentRunner(mcpToolRegistry, permissionPolicy, 5).run(agent, TASK);

			assertThat(agentResult).isEqualTo(AgentResult.success("contents of notes.txt"));
			assertThat(capturedState.get().toolResults())
					.containsExactly(ToolResult.success("read_document", "contents of notes.txt"));
			assertThat(capturedState.get().status()).isEqualTo(AgentStatus.COMPLETED);
		}
	}

	private static class RecordingTool implements Tool {

		private final String name;
		private final List<Map<String, Object>> receivedArguments = new ArrayList<>();

		RecordingTool(String name) {
			this.name = name;
		}

		@Override
		public String name() {
			return name;
		}

		@Override
		public String description() {
			return "Records arguments and echoes the text argument";
		}

		@Override
		public String inputSchema() {
			return "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}";
		}

		@Override
		public ToolResult execute(Map<String, Object> arguments) {
			receivedArguments.add(arguments);
			return ToolResult.success(name, String.valueOf(arguments.get("text")));
		}

	}

}
