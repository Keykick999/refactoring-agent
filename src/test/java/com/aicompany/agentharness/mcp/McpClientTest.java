package com.aicompany.agentharness.mcp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpClientTest {

	private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(20);

	private McpClient mcpClient;

	@AfterEach
	void tearDown() {
		if (mcpClient != null) {
			mcpClient.close();
		}
	}

	@Test
	void initializeNegotiatesProtocolVersion() {
		mcpClient = connect();

		assertThat(mcpClient.initialize()).isEqualTo("2025-06-18");
	}

	@Test
	void listToolsReturnsToolsFromAllPages() {
		mcpClient = connectAndInitialize();

		List<McpToolDefinition> toolDefinitions = mcpClient.listTools();

		assertThat(toolDefinitions).extracting(McpToolDefinition::name)
				.containsExactly("echo", "read_document", "fail");
		assertThat(toolDefinitions.get(0).description()).isEqualTo("Echo arguments as JSON");
		assertThat(toolDefinitions.get(0).inputSchema())
				.isEqualTo("{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}");
	}

	@Test
	void callToolReturnsTextContentAndSkipsServerNotifications() {
		mcpClient = connectAndInitialize();

		McpToolCallResult callResult = mcpClient.callTool("read_document", Map.of("location", "notes.txt"));

		assertThat(callResult).isEqualTo(new McpToolCallResult(false, "contents of notes.txt"));
	}

	@Test
	void callToolReportsToolLevelError() {
		mcpClient = connectAndInitialize();

		McpToolCallResult callResult = mcpClient.callTool("fail", Map.of());

		assertThat(callResult).isEqualTo(new McpToolCallResult(true, "Tool failed on purpose"));
	}

	@Test
	void callToolThrowsWithJsonRpcErrorForUnknownTool() {
		mcpClient = connectAndInitialize();

		assertThatThrownBy(() -> mcpClient.callTool("missing_tool", Map.of()))
				.isInstanceOf(McpException.class)
				.hasMessageContaining("-32602")
				.hasMessageContaining("Unknown tool: missing_tool");
	}

	@Test
	void initializeThrowsWhenServerReturnsError() {
		mcpClient = connect(FakeMcpServer.FAIL_INITIALIZE);

		assertThatThrownBy(() -> mcpClient.initialize())
				.isInstanceOf(McpException.class)
				.hasMessageContaining("Initialization refused");
	}

	@Test
	void listToolsThrowsWhenServerProcessExits() {
		mcpClient = connect(FakeMcpServer.CRASH_ON_TOOLS_LIST);
		mcpClient.initialize();

		assertThatThrownBy(() -> mcpClient.listTools())
				.isInstanceOf(McpException.class)
				.hasMessageContaining("closed the connection");
	}

	@Test
	void requiresInitializeBeforeUsingTools() {
		mcpClient = connect();

		assertThatThrownBy(() -> mcpClient.listTools()).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void startThrowsWhenServerCommandCannotBeExecuted() {
		assertThatThrownBy(() -> StdioMcpTransport.start(List.of("command-that-does-not-exist"), RESPONSE_TIMEOUT))
				.isInstanceOf(McpException.class)
				.hasMessageContaining("Failed to start MCP server")
				.hasCauseInstanceOf(java.io.IOException.class);
	}

	private McpClient connect(String... serverOptions) {
		return new McpClient(StdioMcpTransport.start(FakeMcpServer.command(serverOptions), RESPONSE_TIMEOUT));
	}

	private McpClient connectAndInitialize() {
		McpClient client = connect();
		client.initialize();
		return client;
	}

}
