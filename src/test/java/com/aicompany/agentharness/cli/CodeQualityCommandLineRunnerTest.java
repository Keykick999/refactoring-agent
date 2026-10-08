package com.aicompany.agentharness.cli;

import com.aicompany.agentharness.agent.CodeQualityAgent;
import com.aicompany.agentharness.config.AgentHarnessProperties;
import com.aicompany.agentharness.harness.AgentRunner;
import com.aicompany.agentharness.llm.Llm;
import com.aicompany.agentharness.llm.LlmRequest;
import com.aicompany.agentharness.llm.LlmResponse;
import com.aicompany.agentharness.tool.ToolPermissionPolicy;
import com.aicompany.agentharness.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class CodeQualityCommandLineRunnerTest {

	private static final String FINISH_WITH_SUCCESS = """
			{"type": "FINISH", "result": {"success": true, "summary": "Extract the nested conditions into a method."}}
			""";
	private static final String FINISH_WITH_FAILURE = """
			{"type": "FINISH", "result": {"success": false, "summary": "Target file cannot be read."}}
			""";

	@TempDir
	Path workspace;

	private final List<LlmRequest> receivedLlmRequests = new ArrayList<>();

	@Test
	void convertsArgumentsToTaskAndRunsItThroughAgentRunner(CapturedOutput output) {
		CodeQualityCommandLineRunner commandLineRunner = createCommandLineRunner(FINISH_WITH_SUCCESS);

		commandLineRunner.run(new DefaultApplicationArguments("--objective=Improve naming", "--target=src/Sample.java"));

		assertThat(receivedLlmRequests).hasSize(1);
		assertThat(receivedLlmRequests.get(0).messages().get(1).content())
				.contains("Objective: Improve naming")
				.contains("Target: " + workspace.resolve("src/Sample.java"));
		assertThat(commandLineRunner.getExitCode()).isEqualTo(CodeQualityCommandLineRunner.EXIT_SUCCESS);
	}

	@Test
	void printsSuccessfulAgentResult(CapturedOutput output) {
		createCommandLineRunner(FINISH_WITH_SUCCESS).run(new DefaultApplicationArguments("--target=Sample.java"));

		assertThat(output.getOut())
				.contains("Code Quality Agent")
				.contains("Objective: " + CodeQualityCommandLineRunner.DEFAULT_OBJECTIVE)
				.contains("Status: SUCCESS")
				.contains("Summary:\nExtract the nested conditions into a method.");
	}

	@Test
	void keepsAbsoluteTargetPath() {
		Path absoluteTarget = workspace.resolve("nested/Absolute.java").toAbsolutePath();

		createCommandLineRunner(FINISH_WITH_SUCCESS).run(new DefaultApplicationArguments("--target=" + absoluteTarget));

		assertThat(receivedLlmRequests.get(0).messages().get(1).content()).contains("Target: " + absoluteTarget);
	}

	@Test
	void printsFailedAgentResultAndReturnsNonZeroExitCode(CapturedOutput output) {
		CodeQualityCommandLineRunner commandLineRunner = createCommandLineRunner(FINISH_WITH_FAILURE);

		commandLineRunner.run(new DefaultApplicationArguments("--target=Missing.java"));

		assertThat(output.getOut()).contains("Status: FAILURE").contains("Target file cannot be read.");
		assertThat(commandLineRunner.getExitCode()).isEqualTo(CodeQualityCommandLineRunner.EXIT_AGENT_FAILURE);
	}

	@Test
	void printsShortMessageInsteadOfStackTraceWhenExecutionFailsUnexpectedly(CapturedOutput output) {
		Llm brokenLlm = request -> {
			throw new IllegalStateException("unexpected bug");
		};
		CodeQualityCommandLineRunner commandLineRunner = createCommandLineRunner(brokenLlm);

		commandLineRunner.run(new DefaultApplicationArguments("--target=Sample.java"));

		assertThat(output.getOut())
				.contains("Status: FAILURE")
				.contains("Agent execution failed unexpectedly: unexpected bug");
		assertThat(commandLineRunner.getExitCode()).isEqualTo(CodeQualityCommandLineRunner.EXIT_AGENT_FAILURE);
	}

	@Test
	void printsUsageWithoutRunningAgentWhenTargetIsMissing(CapturedOutput output) {
		CodeQualityCommandLineRunner commandLineRunner = createCommandLineRunner(FINISH_WITH_SUCCESS);

		commandLineRunner.run(new DefaultApplicationArguments("--objective=Improve naming"));

		assertThat(output.getOut()).contains("Usage:").contains("--target");
		assertThat(receivedLlmRequests).isEmpty();
		assertThat(commandLineRunner.getExitCode()).isEqualTo(CodeQualityCommandLineRunner.EXIT_USAGE_ERROR);
	}

	private CodeQualityCommandLineRunner createCommandLineRunner(String llmResponse) {
		return createCommandLineRunner(request -> {
			receivedLlmRequests.add(request);
			return new LlmResponse(llmResponse);
		});
	}

	private CodeQualityCommandLineRunner createCommandLineRunner(Llm llm) {
		ToolRegistry toolRegistry = new ToolRegistry();
		AgentHarnessProperties properties = new AgentHarnessProperties(5,
				new AgentHarnessProperties.FilesystemMcpServer(true, List.of(), workspace.toString(), Duration.ofSeconds(1)),
				new AgentHarnessProperties.Tools(List.of(), List.of()));
		return new CodeQualityCommandLineRunner(
				new AgentRunner(toolRegistry, new ToolPermissionPolicy(List.of(), List.of()), properties.maxSteps()),
				new CodeQualityAgent(llm, toolRegistry.toolDefinitions()),
				properties);
	}

}
