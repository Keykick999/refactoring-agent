package com.aicompany.agentharness.tool;

import com.aicompany.agentharness.mcp.FakeMcpServer;
import com.aicompany.agentharness.mcp.McpClient;
import com.aicompany.agentharness.mcp.McpTool;
import com.aicompany.agentharness.mcp.StdioMcpTransport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolRegistryTest {

	@Test
	void findsRegisteredToolByName() {
		ToolRegistry toolRegistry = new ToolRegistry();
		Tool stubTool = new StubTool("stub");

		toolRegistry.register(stubTool);

		assertThat(toolRegistry.findByName("stub")).containsSame(stubTool);
	}

	@Test
	void returnsEmptyWhenToolIsNotRegistered() {
		ToolRegistry toolRegistry = new ToolRegistry();

		assertThat(toolRegistry.findByName("stub")).isEmpty();
	}

	@Test
	void rejectsDuplicateToolName() {
		ToolRegistry toolRegistry = new ToolRegistry();
		toolRegistry.register(new StubTool("stub"));

		assertThatThrownBy(() -> toolRegistry.register(new StubTool("stub")))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void providesDefinitionsOfRegisteredToolsInRegistrationOrder() {
		ToolRegistry toolRegistry = new ToolRegistry();
		toolRegistry.register(new StubTool("second"));
		toolRegistry.register(new StubTool("first"));

		assertThat(toolRegistry.toolDefinitions()).containsExactly(
				new ToolDefinition("second", "stub tool", "{}"),
				new ToolDefinition("first", "stub tool", "{}"));
	}

	@Test
	void registersMcpToolsAndFindsThemByMcpToolName() {
		try (McpClient mcpClient = new McpClient(StdioMcpTransport.start(FakeMcpServer.command(), Duration.ofSeconds(20)))) {
			mcpClient.initialize();
			ToolRegistry toolRegistry = new ToolRegistry();

			McpTool.discoverAll(mcpClient).forEach(toolRegistry::register);

			assertThat(toolRegistry.findByName("read_document")).get()
					.extracting(Tool::name).isEqualTo("read_document");
			assertThat(toolRegistry.findByName("echo")).isPresent();
			assertThat(toolRegistry.findByName("fail")).isPresent();
		}
	}

	private record StubTool(String name) implements Tool {

		@Override
		public String description() {
			return "stub tool";
		}

		@Override
		public String inputSchema() {
			return "{}";
		}

		@Override
		public ToolResult execute(Map<String, Object> arguments) {
			return ToolResult.success(name, "stub");
		}

	}

}
