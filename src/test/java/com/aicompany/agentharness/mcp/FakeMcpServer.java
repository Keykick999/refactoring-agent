package com.aicompany.agentharness.mcp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 테스트용 MCP Server.
 * 실제 stdio transport 경로를 검증하기 위해 별도 JVM 프로세스로 실행된다.
 *
 * <p>제공 Tool: tools/list 첫 페이지에 echo, read_document / 두 번째 페이지에 fail.
 */
public final class FakeMcpServer {

	public static final String FAIL_INITIALIZE = "--fail-initialize";
	public static final String CRASH_ON_TOOLS_LIST = "--crash-on-tools-list";

	private static final JsonMapper JSON_MAPPER = new JsonMapper();
	private static final String SECOND_PAGE_CURSOR = "page-2";

	private final Set<String> options;
	private final PrintStream output = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);

	private FakeMcpServer(Set<String> options) {
		this.options = options;
	}

	public static List<String> command(String... options) {
		String javaExecutable = ProcessHandle.current().info().command().orElseThrow();
		List<String> command = new ArrayList<>(List.of(
				javaExecutable, "-cp", System.getProperty("java.class.path"), FakeMcpServer.class.getName()));
		command.addAll(List.of(options));
		return command;
	}

	public static void main(String[] args) throws IOException {
		new FakeMcpServer(Set.of(args)).serve();
	}

	private void serve() throws IOException {
		BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
		String line;
		while ((line = input.readLine()) != null) {
			JsonNode message = JSON_MAPPER.readTree(line);
			if (message.has("id")) {
				handleRequest(message);
			}
		}
	}

	private void handleRequest(JsonNode request) {
		JsonNode params = request.path("params");
		switch (request.path("method").asString()) {
			case "initialize" -> handleInitialize(request, params);
			case "tools/list" -> handleToolsList(request, params);
			case "tools/call" -> handleToolsCall(request, params);
			default -> sendError(request, -32601, "Method not found");
		}
	}

	private void handleInitialize(JsonNode request, JsonNode params) {
		if (options.contains(FAIL_INITIALIZE)) {
			sendError(request, -32603, "Initialization refused");
			return;
		}
		ObjectNode result = JSON_MAPPER.createObjectNode();
		result.put("protocolVersion", params.path("protocolVersion").asString());
		result.putObject("capabilities").putObject("tools");
		result.putObject("serverInfo").put("name", "fake-mcp-server").put("version", "1.0.0");
		sendResult(request, result);
	}

	private void handleToolsList(JsonNode request, JsonNode params) {
		if (options.contains(CRASH_ON_TOOLS_LIST)) {
			System.exit(1);
		}
		ObjectNode result = JSON_MAPPER.createObjectNode();
		if (SECOND_PAGE_CURSOR.equals(params.path("cursor").asString(null))) {
			result.putArray("tools").add(toolDefinition("fail", "Always fails", "reason"));
		}
		else {
			result.putArray("tools")
					.add(toolDefinition("echo", "Echo arguments as JSON", "text"))
					.add(toolDefinition("read_document", "Read a document", "location"));
			result.put("nextCursor", SECOND_PAGE_CURSOR);
		}
		sendResult(request, result);
	}

	private void handleToolsCall(JsonNode request, JsonNode params) {
		sendNotification("notifications/message");

		JsonNode arguments = params.path("arguments");
		switch (params.path("name").asString()) {
			case "echo" -> sendResult(request, textContent(arguments.toString(), false));
			case "read_document" -> sendResult(request, textContent("contents of " + arguments.path("location").asString(), false));
			case "fail" -> sendResult(request, textContent("Tool failed on purpose", true));
			default -> sendError(request, -32602, "Unknown tool: " + params.path("name").asString());
		}
	}

	private ObjectNode toolDefinition(String name, String description, String argumentName) {
		ObjectNode tool = JSON_MAPPER.createObjectNode();
		tool.put("name", name);
		tool.put("description", description);
		ObjectNode inputSchema = tool.putObject("inputSchema");
		inputSchema.put("type", "object");
		inputSchema.putObject("properties").putObject(argumentName).put("type", "string");
		return tool;
	}

	private ObjectNode textContent(String text, boolean isError) {
		ObjectNode result = JSON_MAPPER.createObjectNode();
		result.putArray("content").addObject().put("type", "text").put("text", text);
		result.put("isError", isError);
		return result;
	}

	private void sendResult(JsonNode request, JsonNode result) {
		ObjectNode response = JSON_MAPPER.createObjectNode();
		response.put("jsonrpc", "2.0");
		response.set("id", request.get("id"));
		response.set("result", result);
		output.println(JSON_MAPPER.writeValueAsString(response));
	}

	private void sendError(JsonNode request, int code, String message) {
		ObjectNode response = JSON_MAPPER.createObjectNode();
		response.put("jsonrpc", "2.0");
		response.set("id", request.get("id"));
		response.putObject("error").put("code", code).put("message", message);
		output.println(JSON_MAPPER.writeValueAsString(response));
	}

	private void sendNotification(String method) {
		ObjectNode notification = JSON_MAPPER.createObjectNode();
		notification.put("jsonrpc", "2.0");
		notification.put("method", method);
		notification.putObject("params").put("level", "info").put("data", "fake notification");
		output.println(JSON_MAPPER.writeValueAsString(notification));
	}

}
