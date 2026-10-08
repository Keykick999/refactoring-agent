package com.aicompany.agentharness.mcp;

import com.aicompany.agentharness.tool.Tool;
import com.aicompany.agentharness.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * MCP Server가 제공하는 Tool 하나를 Harness Tool로 노출하는 공통 Adapter.
 * Tool 이름과 schema는 tools/list 결과를 그대로 사용하므로 MCP Tool마다 클래스를 만들지 않는다.
 */
public class McpTool implements Tool {

	private static final Logger log = LoggerFactory.getLogger(McpTool.class);

	private final McpToolDefinition toolDefinition;
	private final McpClient mcpClient;

	public McpTool(McpToolDefinition toolDefinition, McpClient mcpClient) {
		this.toolDefinition = toolDefinition;
		this.mcpClient = mcpClient;
	}

	public static List<McpTool> discoverAll(McpClient mcpClient) {
		return mcpClient.listTools().stream()
				.map(toolDefinition -> new McpTool(toolDefinition, mcpClient))
				.toList();
	}

	@Override
	public String name() {
		return toolDefinition.name();
	}

	@Override
	public String description() {
		return toolDefinition.description();
	}

	@Override
	public String inputSchema() {
		return toolDefinition.inputSchema();
	}

	@Override
	public ToolResult execute(Map<String, Object> arguments) {
		try {
			McpToolCallResult callResult = mcpClient.callTool(name(), arguments);
			return callResult.isError()
					? ToolResult.failure(name(), callResult.text())
					: ToolResult.success(name(), callResult.text());
		}
		catch (McpException exception) {
			log.warn("MCP tool call failed: {}", name(), exception);
			return ToolResult.failure(name(), exception.getMessage());
		}
	}

}
