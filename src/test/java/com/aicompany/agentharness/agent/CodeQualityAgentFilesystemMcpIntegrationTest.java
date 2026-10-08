package com.aicompany.agentharness.agent;

import com.aicompany.agentharness.harness.AgentResult;
import com.aicompany.agentharness.harness.AgentRunner;
import com.aicompany.agentharness.harness.Target;
import com.aicompany.agentharness.harness.Task;
import com.aicompany.agentharness.llm.LlmRequest;
import com.aicompany.agentharness.llm.LlmResponse;
import com.aicompany.agentharness.llm.Message;
import com.aicompany.agentharness.tool.ToolPermissionPolicy;
import com.aicompany.agentharness.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 Filesystem MCP Server(npx)를 실행하므로 Node.js가 필요하다.
 * 기본 빌드에서는 실행하지 않고 {@code gradlew test -Pmcp.integration=true}로 실행한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "mcp.integration", matches = "true")
class CodeQualityAgentFilesystemMcpIntegrationTest {

	private static final Path WORKSPACE = createTempDirectory("mcp-workspace");
	private static final Path OUTSIDE_WORKSPACE = createTempDirectory("mcp-outside");

	@Autowired
	private AgentRunner agentRunner;

	@Autowired
	private ToolRegistry toolRegistry;

	@Autowired
	private ToolPermissionPolicy toolPermissionPolicy;

	private final List<LlmRequest> receivedLlmRequests = new ArrayList<>();
	private final JsonMapper jsonMapper = new JsonMapper();

	@DynamicPropertySource
	static void filesystemMcpServerProperties(DynamicPropertyRegistry registry) {
		registry.add("agent-harness.filesystem-mcp-server.enabled", () -> "true");
		registry.add("agent-harness.filesystem-mcp-server.workspace", WORKSPACE::toString);
	}

	@Test
	void readsTargetThroughFilesystemMcpServerAndFinishesWithAnalysis() throws IOException {
		Path sourceFile = Files.writeString(WORKSPACE.resolve("Sample.java"), "class Sample { int x = 1; }");

		AgentResult agentResult = agentRunner.run(createCodeQualityAgent(), new Task("Analyze code quality", new Target(sourceFile)));

		assertThat(agentResult.success()).isTrue();
		assertThat(agentResult.summary()).contains("class Sample { int x = 1; }");
		assertThat(receivedLlmRequests).hasSize(2);
	}

	@Test
	void failsWhenTargetIsOutsideAllowedWorkspace() throws IOException {
		Path outsideFile = Files.writeString(OUTSIDE_WORKSPACE.resolve("Secret.java"), "class Secret {}");

		AgentResult agentResult = agentRunner.run(createCodeQualityAgent(), new Task("Analyze code quality", new Target(outsideFile)));

		assertThat(agentResult.success()).isFalse();
		assertThat(agentResult.summary()).containsIgnoringCase("access denied");
	}

	@Test
	void doesNotExposeOrExecuteWriteToolDeniedByConfiguredPolicy() {
		Path fileToCreate = WORKSPACE.resolve("Created.java");
		CodeQualityAgent writingAgent = new CodeQualityAgent(request -> {
			receivedLlmRequests.add(request);
			if (request.messages().size() == 2) {
				return new LlmResponse(jsonMapper.writeValueAsString(Map.of(
						"type", "USE_TOOL",
						"toolCall", Map.of("toolName", "write_file",
								"arguments", Map.of("path", fileToCreate.toString(), "content", "class Created {}")))));
			}
			String lastToolResult = request.messages().get(request.messages().size() - 1).content();
			return new LlmResponse(jsonMapper.writeValueAsString(Map.of(
					"type", "FINISH", "result", Map.of("success", false, "summary", lastToolResult))));
		}, toolPermissionPolicy.allowedToolDefinitions(toolRegistry.toolDefinitions()));

		AgentResult agentResult = agentRunner.run(writingAgent, new Task("Analyze code quality", new Target(fileToCreate)));

		assertThat(toolRegistry.findByName("write_file")).isPresent();
		assertThat(receivedLlmRequests.get(0).messages().get(1).content())
				.contains("- name: read_text_file")
				.doesNotContain("write_file");
		assertThat(agentResult.summary()).contains("Tool execution denied: write_file");
		assertThat(fileToCreate).doesNotExist();
	}

	/**
	 * 실제 LLM 대신, 대상 파일을 read_text_file로 읽은 뒤 그 Tool 결과를 그대로 요약해 FINISH하는 Fake LLM.
	 */
	private CodeQualityAgent createCodeQualityAgent() {
		return new CodeQualityAgent(request -> {
			receivedLlmRequests.add(request);
			List<Message> messages = request.messages();
			if (messages.size() == 2) {
				String targetPath = messages.get(1).content().lines()
						.filter(line -> line.startsWith("Target: "))
						.findFirst().orElseThrow()
						.substring("Target: ".length());
				return new LlmResponse(jsonMapper.writeValueAsString(Map.of(
						"type", "USE_TOOL",
						"toolCall", Map.of("toolName", "read_text_file", "arguments", Map.of("path", targetPath)))));
			}
			String lastToolResult = messages.get(messages.size() - 1).content();
			return new LlmResponse(jsonMapper.writeValueAsString(Map.of(
					"type", "FINISH",
					"result", Map.of("success", lastToolResult.contains("(success)"), "summary", lastToolResult))));
		}, toolRegistry.toolDefinitions());
	}

	private static Path createTempDirectory(String prefix) {
		try {
			return Files.createTempDirectory(prefix).toRealPath();
		}
		catch (IOException exception) {
			throw new UncheckedIOException(exception);
		}
	}

}
