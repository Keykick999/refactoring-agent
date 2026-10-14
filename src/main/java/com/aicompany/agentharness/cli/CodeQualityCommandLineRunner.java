package com.aicompany.agentharness.cli;

import com.aicompany.agentharness.analysis.AnalysisReport;
import com.aicompany.agentharness.analysis.ClassAnalysisCoordinator;
import com.aicompany.agentharness.config.AgentHarnessProperties;
import com.aicompany.agentharness.harness.AgentResult;
import com.aicompany.agentharness.harness.Target;
import com.aicompany.agentharness.harness.Task;
import com.aicompany.agentharness.plan.AnalysisRequest;
import com.aicompany.agentharness.plan.AnalysisStyle;
import com.aicompany.agentharness.plan.EnforcementMode;
import com.aicompany.agentharness.plan.FinalAnalysisResult;
import com.aicompany.agentharness.plan.ProblemSolvingCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 명령행 인자로 받은 대상의 Java 클래스를 하나씩 분석하고 통합 보고서를 출력하는 CLI 진입점.
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
	private static final String MODE_OPTION = "mode";
	private static final String STYLE_OPTION = "style";
	private static final String SEPARATOR = "=".repeat(40);

	private final ClassAnalysisCoordinator classAnalysisCoordinator;
	private final ProblemSolvingCoordinator problemSolvingCoordinator;
	private final Path workspaceDirectory;
	private int exitCode = EXIT_SUCCESS;

	public CodeQualityCommandLineRunner(ClassAnalysisCoordinator classAnalysisCoordinator, AgentHarnessProperties properties) {
		this(classAnalysisCoordinator, properties, null);
	}

	@Autowired
	public CodeQualityCommandLineRunner(ClassAnalysisCoordinator classAnalysisCoordinator, AgentHarnessProperties properties,
			ProblemSolvingCoordinator problemSolvingCoordinator) {
		this.classAnalysisCoordinator = classAnalysisCoordinator;
		this.problemSolvingCoordinator = problemSolvingCoordinator;
		this.workspaceDirectory = properties.filesystemMcpServer().workspaceDirectory();
	}

	@Override
	public void run(ApplicationArguments arguments) {
		Optional<String> target = singleOptionValue(arguments, TARGET_OPTION);
		if (target.isEmpty()) {
			printUsage();
			exitCode = EXIT_USAGE_ERROR;
			return;
		}

		Path targetPath;
		try {
			targetPath = resolveTargetPath(target.get());
		}
		catch (IllegalArgumentException exception) {
			System.out.println("\nInvalid --target: " + exception.getMessage());
			printUsage();
			exitCode = EXIT_USAGE_ERROR;
			return;
		}

		String objective = singleOptionValue(arguments, OBJECTIVE_OPTION).orElse(DEFAULT_OBJECTIVE);
		Task task = new Task(objective, new Target(targetPath));
		String mode = singleOptionValue(arguments, MODE_OPTION).orElse("class");
		AgentResult agentResult;
		if ("plan".equalsIgnoreCase(mode)) {
			agentResult = analyzePlan(task, arguments);
		}
		else if ("class".equalsIgnoreCase(mode)) {
			agentResult = analyze(task);
		}
		else {
			System.out.println("\nInvalid --mode: " + mode);
			printUsage();
			exitCode = EXIT_USAGE_ERROR;
			return;
		}
		printResult(task, agentResult);
		exitCode = agentResult.success() ? EXIT_SUCCESS : EXIT_AGENT_FAILURE;
	}

	@Override
	public int getExitCode() {
		return exitCode;
	}

	/**
	 * 상대 경로는 MCP Server가 허용한 workspace 기준으로만 해석한다. 절대 경로는 workspace 안에 있을 때만 허용한다.
	 *
	 * <p>여기서의 검사는 LLM을 호출하기 전에 잘못된 입력을 알리기 위한 것이며, 접근 제한의 최종 책임은 MCP Server에 있다.
	 * 경로는 이 메서드에서 한 번만 workspace와 결합하고, 이후 계층에는 절대 경로로 전달한다.
	 */
	private Path resolveTargetPath(String target) {
		Path targetPath = workspaceDirectory.resolve(target).normalize();
		if (!targetPath.startsWith(workspaceDirectory)) {
			throw new IllegalArgumentException("%s is outside the workspace %s".formatted(target, workspaceDirectory));
		}
		if (!Files.exists(targetPath)) {
			throw new IllegalArgumentException("%s does not exist (resolved against the workspace: %s)%s"
					.formatted(target, targetPath, workspacePrefixHint(target)));
		}
		try {
			// symlink로 workspace 밖을 가리키는 경로를 막는다.
			if (!targetPath.toRealPath().startsWith(workspaceDirectory.toRealPath())) {
				throw new IllegalArgumentException("%s points outside the workspace %s".formatted(target, workspaceDirectory));
			}
		}
		catch (IOException exception) {
			throw new IllegalArgumentException("%s cannot be accessed: %s".formatted(target, exception.getMessage()));
		}
		return targetPath;
	}

	/**
	 * workspace 디렉터리 이름으로 시작하는 상대 경로(예: workspace가 src일 때 src/main/...)는
	 * 프로젝트 루트 기준으로 쓴 경우가 대부분이다. 다른 의미로 해석하지 않고 안내만 한다.
	 */
	private String workspacePrefixHint(String target) {
		Path targetPath = Path.of(target);
		Path workspaceName = workspaceDirectory.getFileName();
		if (targetPath.isAbsolute() || workspaceName == null || !targetPath.startsWith(workspaceName)) {
			return "";
		}
		return ". --target is relative to the workspace; remove the leading '%s/'".formatted(workspaceName);
	}

	private Optional<String> singleOptionValue(ApplicationArguments arguments, String optionName) {
		List<String> values = arguments.getOptionValues(optionName);
		if (values == null || values.size() != 1 || values.get(0).isBlank()) {
			return Optional.empty();
		}
		return Optional.of(values.get(0).strip());
	}

	private AgentResult analyzePlan(Task task, ApplicationArguments arguments) {
		if (problemSolvingCoordinator == null) {
			return AgentResult.failure("Plan-based analysis is not available.");
		}
		try {
			AnalysisStyle style = AnalysisStyle.valueOf(singleOptionValue(arguments, STYLE_OPTION).orElse("GENERAL"));
			FinalAnalysisResult result = problemSolvingCoordinator.solve(
					new AnalysisRequest(task.target().path(), task.goal(), style, EnforcementMode.ADVISORY));
			return result.complete() ? AgentResult.success(result.render()) : AgentResult.failure(result.render());
		}
		catch (IllegalArgumentException exception) {
			return AgentResult.failure("Invalid --style: " + exception.getMessage());
		}
		catch (RuntimeException exception) {
			log.error("Plan-based analysis failed unexpectedly", exception);
			return AgentResult.failure("Agent execution failed unexpectedly: " + exception.getMessage());
		}
	}

	private AgentResult analyze(Task task) {
		try {
			AnalysisReport report = classAnalysisCoordinator.analyze(task);
			AgentResult agentResult = report.succeeded() > 0 ? AgentResult.success(report.render()) : AgentResult.failure(report.render());
			return agentResult;
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

				Usage: ./gradlew bootRun --args="--target=<file> [--objective=<text>] [--mode=class|plan]"

				  --target     Java file or directory to analyze (required), relative to the workspace: %s
				               Example: --target=main/java/com/example
				  --objective  Task objective (default: "%s")
				  --mode       class (default) analyzes each Java file. plan decomposes the objective into stored tasks.
				  --style      analysis style for --mode=plan (default: GENERAL)
				""".formatted(workspaceDirectory, DEFAULT_OBJECTIVE));
	}

}
