package com.aicompany.agentharness.config;

import com.aicompany.agentharness.tool.ToolRegistry;
import com.aicompany.agentharness.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * application.yml의 실제 workspace 설정으로 Filesystem MCP Server를 실행해, 접근 범위가 src로 제한되는지 검증한다.
 * Node.js가 필요하므로 {@code gradlew test -Pmcp.integration=true}로 실행한다.
 */
@SpringBootTest(properties = "agent-harness.filesystem-mcp-server.enabled=true")
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "mcp.integration", matches = "true")
class ProjectWorkspaceFilesystemMcpIntegrationTest {

	private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();

	@Autowired
	private ToolRegistry toolRegistry;

	@Autowired
	private AgentHarnessProperties properties;

	@Test
	void usesProjectSourceDirectoryAsOnlyAllowedDirectory() {
		assertThat(properties.filesystemMcpServer().workspaceDirectory()).isEqualTo(PROJECT_ROOT.resolve("src"));

		ToolResult allowedDirectories = callTool("list_allowed_directories", Map.of());

		assertThat(allowedDirectories.success()).isTrue();
		assertThat(allowedDirectories.output()).contains(PROJECT_ROOT.resolve("src").toString());
	}

	@Test
	void readsTargetResolvedRelativeToWorkspaceLikeCommandLineTarget() {
		Path target = properties.filesystemMcpServer().workspaceDirectory()
				.resolve("main/java/com/aicompany/agentharness/harness/Task.java").normalize();

		ToolResult toolResult = callTool("read_text_file", Map.of("path", target.toString()));

		assertThat(toolResult.success()).isTrue();
		assertThat(toolResult.output()).contains("public record Task");
	}

	@ParameterizedTest
	@ValueSource(strings = {"build.gradle", ".git/config", ".env", "gradle/wrapper/gradle-wrapper.properties",
			"build/resources/main/application.yml", "src/../build.gradle"})
	void deniesAccessOutsideSourceDirectory(String pathFromProjectRoot) {
		ToolResult toolResult = callTool("read_text_file", Map.of("path", PROJECT_ROOT.resolve(pathFromProjectRoot).toString()));

		assertThat(toolResult.success()).isFalse();
		assertThat(toolResult.output()).containsIgnoringCase("access denied");
	}

	private ToolResult callTool(String toolName, Map<String, Object> arguments) {
		return toolRegistry.findByName(toolName).orElseThrow().execute(arguments);
	}

}
