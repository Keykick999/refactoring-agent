package com.aicompany.agentharness.cli;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * CLI 인자로 시작해 실제 Gemini와 실제 Filesystem MCP Server를 거쳐 결과가 출력되는지 검증한다.
 * 기본 빌드에서는 실행하지 않고 {@code gradlew test -Pllm.integration=true}로 실행하며,
 * GEMINI_API_KEY 환경변수와 Node.js가 필요하다.
 */
@SpringBootTest(args = {"--target=OrderService.java", "--objective=Analyze code quality and propose improvements. Do not modify any file."})
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
@EnabledIfSystemProperty(named = "llm.integration", matches = "true")
class CodeQualityCommandLineRunnerGeminiIntegrationTest {

	private static final Path WORKSPACE = createWorkspaceWithSourceFile();

	@Autowired
	private CodeQualityCommandLineRunner commandLineRunner;

	@DynamicPropertySource
	static void harnessProperties(DynamicPropertyRegistry registry) {
		registry.add("agent-harness.filesystem-mcp-server.enabled", () -> "true");
		registry.add("agent-harness.filesystem-mcp-server.workspace", WORKSPACE::toString);
	}

	@BeforeAll
	static void requireApiKey() {
		String apiKey = System.getenv("GEMINI_API_KEY");
		assumeTrue(apiKey != null && !apiKey.isBlank(), "GEMINI_API_KEY is not set");
	}

	@Test
	void runsTaskFromCommandLineArgumentsAndPrintsAgentResult(CapturedOutput output) {
		assertThat(commandLineRunner.getExitCode()).as("CLI output:%n%s", output.getOut())
				.isEqualTo(CodeQualityCommandLineRunner.EXIT_SUCCESS);
		assertThat(output.getOut())
				.contains("Code Quality Agent")
				.contains("Target: " + WORKSPACE.resolve("OrderService.java"))
				.contains("Status: SUCCESS")
				.contains("Summary:");
	}

	private static Path createWorkspaceWithSourceFile() {
		try {
			Path workspace = Files.createTempDirectory("cli-workspace").toRealPath();
			Files.writeString(workspace.resolve("OrderService.java"), """
					public class OrderService {
					    public double calc(int a, int b, int c) {
					        double x = 0;
					        if (a > 0) { if (b > 0) { if (c > 0) { x = a * b * c * 1.1; } } }
					        return x;
					    }
					}
					""");
			return workspace;
		}
		catch (IOException exception) {
			throw new UncheckedIOException(exception);
		}
	}

}
