package com.aicompany.agentharness.mcp;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * MCP 표준 stdio transport.
 * MCP Server를 하위 프로세스로 실행하고, 한 줄에 하나의 JSON-RPC 메시지를 주고받는다.
 */
public class StdioMcpTransport implements AutoCloseable {

	private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

	private final Process serverProcess;
	private final BufferedWriter serverInput;
	private final BlockingQueue<Optional<String>> receivedLines = new LinkedBlockingQueue<>();
	private final Duration responseTimeout;
	private final JsonMapper jsonMapper = new JsonMapper();

	private StdioMcpTransport(Process serverProcess, Duration responseTimeout) {
		this.serverProcess = serverProcess;
		this.serverInput = new BufferedWriter(
				new OutputStreamWriter(serverProcess.getOutputStream(), StandardCharsets.UTF_8));
		this.responseTimeout = responseTimeout;
		startReadingServerOutput();
	}

	public static StdioMcpTransport start(List<String> serverCommand, Duration responseTimeout) {
		try {
			Process serverProcess = new ProcessBuilder(serverCommand)
					.redirectError(ProcessBuilder.Redirect.INHERIT)
					.start();
			return new StdioMcpTransport(serverProcess, responseTimeout);
		}
		catch (IOException exception) {
			throw new McpException("Failed to start MCP server: " + serverCommand, exception);
		}
	}

	public void send(JsonNode message) {
		try {
			serverInput.write(jsonMapper.writeValueAsString(message));
			serverInput.newLine();
			serverInput.flush();
		}
		catch (IOException exception) {
			throw new McpException("Failed to send message to MCP server", exception);
		}
	}

	public JsonNode receive() {
		Optional<String> line = pollLine();
		if (line.isEmpty()) {
			throw new McpException("MCP server closed the connection" + describeExitStatus());
		}

		try {
			return jsonMapper.readTree(line.get());
		}
		catch (JacksonException exception) {
			throw new McpException("MCP server sent invalid JSON: " + line.get(), exception);
		}
	}

	@Override
	public void close() {
		try {
			serverInput.close();
		}
		catch (IOException ignored) {
			// 서버가 이미 종료되어 stdin이 닫혀 있을 수 있다. 아래에서 프로세스를 정리하는 것이 목적이다.
		}

		serverProcess.destroy();
		try {
			if (!serverProcess.waitFor(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
				serverProcess.destroyForcibly();
			}
		}
		catch (InterruptedException exception) {
			serverProcess.destroyForcibly();
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * 서버가 응답 없이 멈춰도 Harness가 무한히 대기하지 않도록,
	 * 별도 스레드에서 출력을 읽고 receive()에서는 timeout을 두고 기다린다.
	 */
	private void startReadingServerOutput() {
		Thread readerThread = new Thread(() -> {
			try (BufferedReader serverOutput = new BufferedReader(
					new InputStreamReader(serverProcess.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = serverOutput.readLine()) != null) {
					if (!line.isBlank()) {
						receivedLines.add(Optional.of(line));
					}
				}
			}
			catch (IOException ignored) {
				// 스트림 오류도 연결 종료와 동일하게 receive()에서 McpException으로 드러난다.
			}
			finally {
				receivedLines.add(Optional.empty());
			}
		}, "mcp-stdio-reader");
		readerThread.setDaemon(true);
		readerThread.start();
	}

	private Optional<String> pollLine() {
		try {
			Optional<String> line = receivedLines.poll(responseTimeout.toMillis(), TimeUnit.MILLISECONDS);
			if (line == null) {
				throw new McpException("Timed out waiting for MCP server response after " + responseTimeout);
			}
			return line;
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new McpException("Interrupted while waiting for MCP server response", exception);
		}
	}

	private String describeExitStatus() {
		try {
			if (serverProcess.waitFor(1, TimeUnit.SECONDS)) {
				return " (exit code " + serverProcess.exitValue() + ")";
			}
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
		return "";
	}

}
