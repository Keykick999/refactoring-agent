package com.aicompany.agentharness.cli;

import com.aicompany.agentharness.agent.CodeQualityAgent;
import com.aicompany.agentharness.config.AgentHarnessProperties;
import com.aicompany.agentharness.harness.AgentResult;
import com.aicompany.agentharness.harness.AgentRunner;
import com.aicompany.agentharness.harness.Target;
import com.aicompany.agentharness.harness.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 명령행 인자로 받은 Task 하나를 AgentRunner로 실행하고 결과를 출력하는 CLI 진입점.
 */
@Component
@ConditionalOnBooleanProperty("agent-harness.filesystem-mcp-server.enabled")
public class CodeQualityCommandLineRunner implements ApplicationRunner, ExitCodeGenerator {

	static final int EXIT_SUCCESS = 0;
	static final int EXIT_AGENT_FAILURE = 1;
	static final int EXIT_USAGE_ERROR = 2;
	static final String DEFAULT_OBJECTIVE = "Analyze the code quality of the target and propose improvements.";

	private static final Logger log = LoggerFactory.getLogger(CodeQualityCommandLineRunner.class);
	private static final String OBJECTIVE_OPTION = "objective";
	private static final String TARGET_OPTION = "target";
	private static final String SEPARATOR = "=".repeat(40);

	private final AgentRunner agentRunner;
	private final CodeQualityAgent codeQualityAgent;
	private final Path workspaceDirectory;
	private int exitCode = EXIT_SUCCESS;

	public CodeQualityCommandLineRunner(AgentRunner agentRunner, CodeQualityAgent codeQualityAgent,
			AgentHarnessProperties properties) {
		this.agentRunner = agentRunner;
		this.codeQualityAgent = codeQualityAgent;
		this.workspaceDirectory = properties.filesystemMcpServer().workspaceDirectory();
	}

	@Override
	public void run(ApplicationArguments arguments) {
		Optional<Task> task = toTask(arguments);
		if (task.isEmpty()) {
			printUsage();
			exitCode = EXIT_USAGE_ERROR;
			return;
		}

		AgentResult agentResult = runAgent(task.get());
		printResult(task.get(), agentResult);
		exitCode = agentResult.success() ? EXIT_SUCCESS : EXIT_AGENT_FAILURE;
	}

	@Override
	public int getExitCode() {
		return exitCode;
	}

	private Optional<Task> toTask(ApplicationArguments arguments) {
		Optional<String> target = singleOptionValue(arguments, TARGET_OPTION);
		if (target.isEmpty()) {
			return Optional.empty();
		}
		String objective = singleOptionValue(arguments, OBJECTIVE_OPTION).orElse(DEFAULT_OBJECTIVE);
		return Optional.of(new Task(objective, new Target(resolveTargetPath(target.get()))));
	}

	/**
	 * 상대 경로는 MCP Server가 허용한 workspace 기준으로 해석한다.
	 * workspace 밖의 경로 차단은 MCP Server의 접근 제한에 맡긴다.
	 */
	private Path resolveTargetPath(String target) {
		return workspaceDirectory.resolve(target).normalize();
	}

	private Optional<String> singleOptionValue(ApplicationArguments arguments, String optionName) {
		List<String> values = arguments.getOptionValues(optionName);
		if (values == null || values.size() != 1 || values.get(0).isBlank()) {
			return Optional.empty();
		}
		return Optional.of(values.get(0).strip());
	}

	private AgentResult runAgent(Task task) {
		try {
			return agentRunner.run(codeQualityAgent, task);
		}
		catch (RuntimeException exception) {
			log.error("Agent execution failed unexpectedly", exception);
			return AgentResult.failure("Agent execution failed unexpectedly: " + exception.getMessage());
		}
	}

	private void printResult(Task task, AgentResult agentResult) {
		System.out.println("""

				%s
				Code Quality Agent
				%s

				Objective: %s
				Target: %s

				Status: %s

				Summary:
				%s

				%s
				""".formatted(SEPARATOR, SEPARATOR, task.goal(), task.target().path(),
				agentResult.success() ? "SUCCESS" : "FAILURE", agentResult.summary(), SEPARATOR));
	}

	private void printUsage() {
		System.out.println("""

				Usage: ./gradlew bootRun --args="--target=<file> [--objective=<text>]"

				  --target     File to analyze (required).
				               Relative paths are resolved against the workspace: %s
				  --objective  Task objective (default: "%s")
				""".formatted(workspaceDirectory, DEFAULT_OBJECTIVE));
	}

}
