package com.aicompany.agentharness.cli;

import com.aicompany.agentharness.analysis.AnalysisSettings;
import com.aicompany.agentharness.analysis.ClassAnalysisCoordinator;
import com.aicompany.agentharness.analysis.DiscoveredJavaSource;
import com.aicompany.agentharness.analysis.InMemoryAnalysisJobStore;
import com.aicompany.agentharness.analysis.JavaSourceExplorer;
import com.aicompany.agentharness.config.AgentHarnessProperties;
import com.aicompany.agentharness.harness.AgentRunner;
import com.aicompany.agentharness.llm.Llm;
import com.aicompany.agentharness.llm.LlmRequest;
import com.aicompany.agentharness.llm.LlmResponse;
import com.aicompany.agentharness.tool.ToolPermissionPolicy;
import com.aicompany.agentharness.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@ExtendWith(OutputCaptureExtension.class)
class CodeQualityCommandLineRunnerTest {

	private static final String ANALYSIS_WITH_ISSUE = """
			{"issues": [{
			  "title": "Extract the nested conditions into a method.",
			  "description": "Nested conditions are hard to read.",
			  "evidence": "if (a > 0)",
			  "impact": "The method is hard to test.",
			  "recommendation": "Extract a method.",
			  "severity": "MEDIUM"
			}]}
			""";
	private static final String INVALID_ANALYSIS = "{\"type\": \"FINISH\"}";
	private static final String TASK_FILE = "main/java/com/example/Task.java";

	@TempDir
	Path projectRoot;

	/**
	 * 실제 설정과 같이 프로젝트 안의 src 디렉터리를 workspace로 사용한다.
	 */
	private Path workspace;

	private final List<LlmRequest> receivedLlmRequests = new ArrayList<>();

	@BeforeEach
	void createWorkspace() throws IOException {
		workspace = Files.createDirectories(projectRoot.resolve("src"));
		Files.createDirectories(workspace.resolve(TASK_FILE).getParent());
		Files.writeString(workspace.resolve(TASK_FILE), "public record Task(String goal) {}");
		Files.writeString(projectRoot.resolve("build.gradle"), "plugins {}");
	}

	@Test
	void resolvesRelativeTargetAgainstWorkspaceAndRunsItThroughAgentRunner(CapturedOutput output) {
		CodeQualityCommandLineRunner commandLineRunner = createCommandLineRunner(ANALYSIS_WITH_ISSUE);

		commandLineRunner.run(new DefaultApplicationArguments("--objective=Improve naming", "--target=" + TASK_FILE));

		assertThat(receivedLlmRequests).hasSize(1);
		assertThat(receivedLlmRequests.get(0).messages().get(1).content())
				.contains("Objective: Improve naming")
				.contains("File: " + workspace.resolve(TASK_FILE))
				.contains("public record Task(String goal) {}");
		assertThat(output.getOut()).contains("Target: " + workspace.resolve(TASK_FILE));
		assertThat(commandLineRunner.getExitCode()).isEqualTo(CodeQualityCommandLineRunner.EXIT_SUCCESS);
	}

	@Test
	void printsSuccessfulAgentResult(CapturedOutput output) {
		createCommandLineRunner(ANALYSIS_WITH_ISSUE).run(new DefaultApplicationArguments("--target=" + TASK_FILE));

		assertThat(output.getOut())
				.contains("Code Quality Agent")
				.contains("Objective: " + CodeQualityCommandLineRunner.DEFAULT_OBJECTIVE)
				.contains("Status: SUCCESS")
				.contains("Succeeded: 1")
				.contains("[MEDIUM] Extract the nested conditions into a method.")
				.contains("Evidence: if (a > 0)");
	}

	@Test
	void rejectsTargetWrittenRelativeToProjectRootWithHintInsteadOfReinterpretingIt(CapturedOutput output) {
		CodeQualityCommandLineRunner commandLineRunner = createCommandLineRunner(ANALYSIS_WITH_ISSUE);

		commandLineRunner.run(new DefaultApplicationArguments("--target=src/" + TASK_FILE));

		assertThat(output.getOut())
				.contains("Invalid --target")
				.contains(workspace.resolve("src/" + TASK_FILE).toString())
				.contains("remove the leading 'src/'");
		assertRejectedWithoutRunningAgent(commandLineRunner);
	}

	@Test
	void rejectsTargetThatDoesNotExist(CapturedOutput output) {
		CodeQualityCommandLineRunner commandLineRunner = createCommandLineRunner(ANALYSIS_WITH_ISSUE);

		commandLineRunner.run(new DefaultApplicationArguments("--target=main/java/com/example/Missing.java"));

		assertThat(output.getOut()).contains("Invalid --target").contains("does not exist").doesNotContain("remove the leading");
		assertRejectedWithoutRunningAgent(commandLineRunner);
	}

	@ParameterizedTest
	@ValueSource(strings = {"../build.gradle", "../../etc/passwd", "main/../../build.gradle"})
	void rejectsRelativeTargetEscapingWorkspace(String target, CapturedOutput output) {
		CodeQualityCommandLineRunner commandLineRunner = createCommandLineRunner(ANALYSIS_WITH_ISSUE);

		commandLineRunner.run(new DefaultApplicationArguments("--target=" + target));

		assertThat(output.getOut()).contains("Invalid --target").contains("outside the workspace");
		assertRejectedWithoutRunningAgent(commandLineRunner);
	}

	@Test
	void acceptsAbsoluteTargetInsideWorkspace() {
		Path absoluteTarget = workspace.resolve(TASK_FILE).toAbsolutePath();
		CodeQualityCommandLineRunner commandLineRunner = createCommandLineRunner(ANALYSIS_WITH_ISSUE);

		commandLineRunner.run(new DefaultApplicationArguments("--target=" + absoluteTarget));

		assertThat(receivedLlmRequests.get(0).messages().get(1).content()).contains("File: " + absoluteTarget);
		assertThat(commandLineRunner.getExitCode()).isEqualTo(CodeQualityCommandLineRunner.EXIT_SUCCESS);
	}

	@Test
	void rejectsAbsoluteTargetOutsideWorkspace(CapturedOutput output) {
		CodeQualityCommandLineRunner commandLineRunner = createCommandLineRunner(ANALYSIS_WITH_ISSUE);

		commandLineRunner.run(new DefaultApplicationArguments("--target=" + projectRoot.resolve("build.gradle").toAbsolutePath()));

		assertThat(output.getOut()).contains("outside the workspace");
		assertRejectedWithoutRunningAgent(commandLineRunner);
	}

	@Test
	void rejectsSymbolicLinkPointingOutsideWorkspace(CapturedOutput output) throws IOException {
		Path link = workspace.resolve("escape.gradle");
		try {
			Files.createSymbolicLink(link, projectRoot.resolve("build.gradle"));
		}
		catch (IOException | UnsupportedOperationException exception) {
			assumeTrue(false, "Symbolic links are not available: " + exception.getMessage());
		}
		CodeQualityCommandLineRunner commandLineRunner = createCommandLineRunner(ANALYSIS_WITH_ISSUE);

		commandLineRunner.run(new DefaultApplicationArguments("--target=escape.gradle"));

		assertThat(output.getOut()).contains("points outside the workspace");
		assertRejectedWithoutRunningAgent(commandLineRunner);
	}

	@Test
	void printsFailedAgentResultAndReturnsNonZeroExitCode(CapturedOutput output) {
		CodeQualityCommandLineRunner commandLineRunner = createCommandLineRunner(INVALID_ANALYSIS);

		commandLineRunner.run(new DefaultApplicationArguments("--target=" + TASK_FILE));

		assertThat(output.getOut()).contains("Status: FAILURE").contains("Invalid analysis response");
		assertThat(commandLineRunner.getExitCode()).isEqualTo(CodeQualityCommandLineRunner.EXIT_AGENT_FAILURE);
	}

	@Test
	void printsShortMessageInsteadOfStackTraceWhenExecutionFailsUnexpectedly(CapturedOutput output) {
		JavaSourceExplorer brokenExplorer = target -> {
			throw new IllegalStateException("unexpected bug");
		};
		CodeQualityCommandLineRunner commandLineRunner = createCommandLineRunner(request -> new LlmResponse(ANALYSIS_WITH_ISSUE), brokenExplorer);

		commandLineRunner.run(new DefaultApplicationArguments("--target=" + TASK_FILE));

		assertThat(output.getOut())
				.contains("Status: FAILURE")
				.contains("Agent execution failed unexpectedly: unexpected bug");
		assertThat(commandLineRunner.getExitCode()).isEqualTo(CodeQualityCommandLineRunner.EXIT_AGENT_FAILURE);
	}

	@Test
	void printsUsageWithoutRunningAgentWhenTargetIsMissing(CapturedOutput output) {
		CodeQualityCommandLineRunner commandLineRunner = createCommandLineRunner(ANALYSIS_WITH_ISSUE);

		commandLineRunner.run(new DefaultApplicationArguments("--objective=Improve naming"));

		assertThat(output.getOut()).contains("Usage:").contains("--target");
		assertRejectedWithoutRunningAgent(commandLineRunner);
	}

	private void assertRejectedWithoutRunningAgent(CodeQualityCommandLineRunner commandLineRunner) {
		assertThat(receivedLlmRequests).isEmpty();
		assertThat(commandLineRunner.getExitCode()).isEqualTo(CodeQualityCommandLineRunner.EXIT_USAGE_ERROR);
	}

	private CodeQualityCommandLineRunner createCommandLineRunner(String llmResponse) {
		return createCommandLineRunner(request -> {
			receivedLlmRequests.add(request);
			return new LlmResponse(llmResponse);
		}, target -> {
			try {
				return List.of(DiscoveredJavaSource.readable(target, Files.readString(target)));
			}
			catch (IOException exception) {
				throw new UncheckedIOException(exception);
			}
		});
	}

	private CodeQualityCommandLineRunner createCommandLineRunner(Llm llm, JavaSourceExplorer explorer) {
		ToolRegistry toolRegistry = new ToolRegistry();
		AgentHarnessProperties properties = new AgentHarnessProperties(5,
				new AgentHarnessProperties.FilesystemMcpServer(true, List.of(), workspace.toString(), Duration.ofSeconds(1)),
				new AgentHarnessProperties.Tools(List.of(), List.of()),
				new AgentHarnessProperties.ClassAnalysis(100000, "search_files", "read_text_file"));
		AgentHarnessProperties.ClassAnalysis classAnalysis = properties.classAnalysis();
		ClassAnalysisCoordinator coordinator = new ClassAnalysisCoordinator(explorer, llm,
				new AgentRunner(toolRegistry, new ToolPermissionPolicy(List.of(), List.of()), properties.maxSteps()),
				new AnalysisSettings(classAnalysis.maxSourceCharacters(), "test-model", classAnalysis.searchTool(), classAnalysis.readTool()),
				new InMemoryAnalysisJobStore(), projectRoot.resolve("locks"));
		return new CodeQualityCommandLineRunner(coordinator, properties);
	}

}
