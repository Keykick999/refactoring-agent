package com.aicompany.agentharness.mcp;

import com.aicompany.agentharness.tool.Tool;
import com.aicompany.agentharness.tool.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolTest {

	private McpClient mcpClient;

	@BeforeEach
	void setUp() {
		mcpClient = new McpClient(StdioMcpTransport.start(FakeMcpServer.command(), Duration.ofSeconds(20)));
		mcpClient.initialize();
	}

	@AfterEach
	void tearDown() {
		mcpClient.close();
	}

	@Test
	void exposesMcpToolDefinitionAsHarnessTool() {
		List<McpTool> tools = McpTool.discoverAll(mcpClient);

		Tool echoTool = tools.get(0);
		assertThat(tools).extracting(Tool::name).containsExactly("echo", "read_document", "fail");
		assertThat(echoTool.description()).isEqualTo("Echo arguments as JSON");
		assertThat(echoTool.inputSchema()).contains("\"text\"");
	}

	@Test
	void passesToolCallArgumentsToMcpToolsCall() {
		Tool echoTool = toolNamed("echo");

		ToolResult toolResult = echoTool.execute(Map.of("text", "hello", "count", 3));

		assertThat(toolResult.success()).isTrue();
		assertThat(toolResult.output()).contains("\"text\":\"hello\"").contains("\"count\":3");
	}

	@Test
	void convertsSuccessfulMcpResultToSuccessfulToolResult() {
		ToolResult toolResult = toolNamed("read_document").execute(Map.of("location", "notes.txt"));

		assertThat(toolResult).isEqualTo(ToolResult.success("read_document", "contents of notes.txt"));
	}

	@Test
	void convertsMcpToolErrorToFailedToolResult() {
		ToolResult toolResult = toolNamed("fail").execute(Map.of());

		assertThat(toolResult).isEqualTo(ToolResult.failure("fail", "Tool failed on purpose"));
	}

	@Test
	void convertsMcpProtocolErrorToFailedToolResult() {
		McpTool missingTool = new McpTool(new McpToolDefinition("missing_tool", "", "{}"), mcpClient);

		ToolResult toolResult = missingTool.execute(Map.of());

		assertThat(toolResult.success()).isFalse();
		assertThat(toolResult.output()).contains("-32602").contains("Unknown tool: missing_tool");
	}

	private Tool toolNamed(String toolName) {
		return McpTool.discoverAll(mcpClient).stream()
				.filter(tool -> tool.name().equals(toolName))
				.findFirst()
				.orElseThrow();
	}

}
