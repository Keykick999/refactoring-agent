package com.aicompany.agentharness.mcp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class McpClient implements AutoCloseable {

	private static final String JSON_RPC_VERSION = "2.0";
	private static final String PROTOCOL_VERSION = "2025-06-18";
	private static final Set<String> SUPPORTED_PROTOCOL_VERSIONS = Set.of("2024-11-05", "2025-03-26", PROTOCOL_VERSION);
	private static final int METHOD_NOT_FOUND = -32601;

	private final StdioMcpTransport transport;
	private final JsonMapper jsonMapper = new JsonMapper();
	private long nextRequestId = 1;
	private boolean initialized;

	public McpClient(StdioMcpTransport transport) {
		this.transport = transport;
	}

	/**
	 * @return 서버와 합의된 MCP protocol version
	 */
	public synchronized String initialize() {
		ObjectNode params = jsonMapper.createObjectNode();
		params.put("protocolVersion", PROTOCOL_VERSION);
		params.putObject("capabilities");
		params.putObject("clientInfo")
				.put("name", "agent-harness")
				.put("version", "0.0.1");

		JsonNode result = sendRequest("initialize", params);

		String negotiatedVersion = result.path("protocolVersion").asString();
		if (!SUPPORTED_PROTOCOL_VERSIONS.contains(negotiatedVersion)) {
			throw new McpException("Unsupported MCP protocol version from server: " + negotiatedVersion);
		}
		if (!result.path("capabilities").has("tools")) {
			throw new McpException("MCP server does not provide tools capability");
		}

		sendNotification("notifications/initialized");
		initialized = true;
		return negotiatedVersion;
	}

	public synchronized List<McpToolDefinition> listTools() {
		requireInitialized();
		List<McpToolDefinition> toolDefinitions = new ArrayList<>();
		String cursor = null;

		do {
			ObjectNode params = jsonMapper.createObjectNode();
			if (cursor != null) {
				params.put("cursor", cursor);
			}
			JsonNode result = sendRequest("tools/list", params);

			for (JsonNode tool : result.path("tools")) {
				toolDefinitions.add(toToolDefinition(tool));
			}
			cursor = result.path("nextCursor").isString() ? result.path("nextCursor").asString() : null;
		} while (cursor != null);

		return toolDefinitions;
	}

	public synchronized McpToolCallResult callTool(String toolName, Map<String, Object> arguments) {
		requireInitialized();
		ObjectNode params = jsonMapper.createObjectNode();
		params.put("name", toolName);
		params.set("arguments", jsonMapper.valueToTree(arguments));

		JsonNode result = sendRequest("tools/call", params);
		return new McpToolCallResult(result.path("isError").asBoolean(false), extractText(result.path("content")));
	}

	@Override
	public void close() {
		transport.close();
	}

	private McpToolDefinition toToolDefinition(JsonNode tool) {
		return new McpToolDefinition(
				tool.path("name").asString(),
				tool.path("description").asString(""),
				tool.path("inputSchema").toString());
	}

	private String extractText(JsonNode content) {
		List<String> texts = new ArrayList<>();
		for (JsonNode item : content) {
			String type = item.path("type").asString();
			texts.add("text".equals(type) ? item.path("text").asString() : "[" + type + " content omitted]");
		}
		return String.join("\n", texts);
	}

	private JsonNode sendRequest(String method, ObjectNode params) {
		long requestId = nextRequestId++;
		ObjectNode request = jsonMapper.createObjectNode();
		request.put("jsonrpc", JSON_RPC_VERSION);
		request.put("id", requestId);
		request.put("method", method);
		request.set("params", params);
		transport.send(request);

		while (true) {
			JsonNode message = transport.receive();
			if (isResponseTo(message, requestId)) {
				return extractResult(message, method);
			}
			if (isRequestFromServer(message)) {
				rejectServerRequest(message);
			}
			// 서버 notification(로그, 진행 상황 등)은 현재 Harness에서 사용하지 않으므로 무시한다.
		}
	}

	private void sendNotification(String method) {
		ObjectNode notification = jsonMapper.createObjectNode();
		notification.put("jsonrpc", JSON_RPC_VERSION);
		notification.put("method", method);
		transport.send(notification);
	}

	private boolean isResponseTo(JsonNode message, long requestId) {
		return !message.has("method") && message.path("id").asLong(-1) == requestId;
	}

	private boolean isRequestFromServer(JsonNode message) {
		return message.has("method") && message.has("id");
	}

	/**
	 * Client capability를 선언하지 않았으므로 서버 요청(sampling, roots 등)은 지원하지 않는다고 응답한다.
	 * 응답하지 않으면 서버가 응답을 기다리며 멈출 수 있다.
	 */
	private void rejectServerRequest(JsonNode serverRequest) {
		ObjectNode response = jsonMapper.createObjectNode();
		response.put("jsonrpc", JSON_RPC_VERSION);
		response.set("id", serverRequest.get("id"));
		response.putObject("error")
				.put("code", METHOD_NOT_FOUND)
				.put("message", "Method not supported by client: " + serverRequest.path("method").asString());
		transport.send(response);
	}

	private JsonNode extractResult(JsonNode response, String method) {
		JsonNode error = response.path("error");
		if (!error.isMissingNode()) {
			throw new McpException("MCP " + method + " failed with error " + error.path("code").asInt()
					+ ": " + error.path("message").asString());
		}
		return response.path("result");
	}

	private void requireInitialized() {
		if (!initialized) {
			throw new IllegalStateException("MCP client is not initialized. Call initialize() first.");
		}
	}

}
