package com.aicompany.agentharness.analysis;

import com.aicompany.agentharness.harness.AgentRunner;
import com.aicompany.agentharness.harness.Target;
import com.aicompany.agentharness.harness.Task;
import com.aicompany.agentharness.llm.LlmException;
import com.aicompany.agentharness.llm.LlmRequest;
import com.aicompany.agentharness.llm.LlmResponse;
import com.aicompany.agentharness.tool.ToolPermissionPolicy;
import com.aicompany.agentharness.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClassAnalysisCoordinatorTest {

	private static final Path ORDER = Path.of("order/OrderService.java").toAbsolutePath();
	private static final Path PAYMENT = Path.of("payment/PaymentService.java").toAbsolutePath();
	private static final String ORDER_SOURCE = "public class OrderService { int x; }";
	private static final String PAYMENT_SOURCE = "public class PaymentService { int y; }";
	private static final String NO_ISSUES = "{\"issues\": []}";

	private final List<String> prompts = new ArrayList<>();

	@TempDir
	Path lockDirectory;

	@Test
	void analyzesOneClassFromItsFullSource() {
		AnalysisReport report = coordinator(request -> analysisResponse("OrderService", "MEDIUM"),
				List.of(DiscoveredJavaSource.readable(ORDER, ORDER_SOURCE))).analyze(taskOf(ORDER));

		assertThat(report.succeeded()).isEqualTo(1);
		assertThat(report.results().get(0).className()).isEqualTo("OrderService");
		assertThat(report.results().get(0).issues()).singleElement().extracting(CodeIssue::title).isEqualTo("Issue in OrderService");
		assertThat(prompts).singleElement().asString().contains(ORDER_SOURCE).contains("Class: OrderService");
	}

	@Test
	void collectsEachClassSeparatelyWithoutReusingEarlierSource() {
		AnalysisReport report = coordinator(request -> {
			String prompt = request.messages().get(1).content();
			return prompt.contains("OrderService") ? analysisResponse("OrderService", "HIGH") : analysisResponse("PaymentService", "LOW");
		}).analyze(task());

		assertThat(report.results()).extracting(ClassAnalysisResult::className).containsExactly("OrderService", "PaymentService");
		assertThat(report.results()).allMatch(result -> result.issues().size() == 1);
		assertThat(prompts.get(0)).contains(ORDER_SOURCE).doesNotContain(PAYMENT_SOURCE);
		assertThat(prompts.get(1)).contains(PAYMENT_SOURCE).doesNotContain(ORDER_SOURCE);
	}

	@Test
	void keepsSuccessfulClassWithNoIssues() {
		AnalysisReport report = coordinator(request -> new LlmResponse(NO_ISSUES),
				List.of(DiscoveredJavaSource.readable(ORDER, ORDER_SOURCE))).analyze(taskOf(ORDER));

		assertThat(report.results()).singleElement().satisfies(result -> {
			assertThat(result.status()).isEqualTo(AnalysisStatus.SUCCEEDED);
			assertThat(result.issues()).isEmpty();
			assertThat(result.error()).isNull();
		});
		assertThat(report.render()).contains("Issues: none");
	}

	@Test
	void continuesAfterOneClassFails() {
		AnalysisReport report = coordinator(request -> {
			if (request.messages().get(1).content().contains("OrderService")) {
				throw new LlmException("Gemini API request failed with HTTP 429: quota exceeded");
			}
			return new LlmResponse(NO_ISSUES);
		}).analyze(task());

		assertThat(report.total()).isEqualTo(2);
		assertThat(report.succeeded()).isEqualTo(1);
		assertThat(report.failed()).isEqualTo(1);
		assertThat(report.results().get(0).status()).isEqualTo(AnalysisStatus.FAILED);
		assertThat(report.results().get(0).error()).contains("HTTP 429");
		assertThat(report.results().get(1).status()).isEqualTo(AnalysisStatus.SUCCEEDED);
	}

	@Test
	void recordsInvalidLlmResponseAsFailedAnalysis() {
		AnalysisReport report = coordinator(request -> new LlmResponse("I found some issues."),
				List.of(DiscoveredJavaSource.readable(ORDER, ORDER_SOURCE))).analyze(taskOf(ORDER));

		assertThat(report.succeeded()).isZero();
		assertThat(report.results()).singleElement().satisfies(result -> {
			assertThat(result.status()).isEqualTo(AnalysisStatus.FAILED);
			assertThat(result.issues()).isEmpty();
			assertThat(result.error()).contains("Invalid analysis response");
		});
	}

	@Test
	void rendersEveryClassIntoOneReport() {
		AnalysisReport report = coordinator(request -> analysisResponse("OrderService", "HIGH"),
				List.of(DiscoveredJavaSource.readable(ORDER, ORDER_SOURCE))).analyze(taskOf(ORDER));

		assertThat(report.render())
				.contains("Targets: 1, Succeeded: 1, Failed: 0")
				.contains("## OrderService")
				.contains("File: " + ORDER)
				.contains("Status: SUCCEEDED")
				.contains("[HIGH] Issue in OrderService")
				.contains("Description: Nested conditions are hard to read.")
				.contains("Evidence: if (a > 0)")
				.contains("Impact: The method is hard to test.")
				.contains("Recommendation: Extract a method.");
	}

	@Test
	void reportsNoJavaSourcesWithoutCallingLlm() {
		ClassAnalysisCoordinator coordinator = coordinator(request -> new LlmResponse(NO_ISSUES), List.of());

		AnalysisReport report = coordinator.analyze(task());

		assertThat(report.total()).isZero();
		assertThat(report.render()).contains("No Java source files were found.");
		assertThat(prompts).isEmpty();
	}

	@Test
	void analyzesDuplicatePathOnlyOnce() {
		DiscoveredJavaSource order = DiscoveredJavaSource.readable(ORDER, ORDER_SOURCE);
		AnalysisReport report = coordinator(request -> new LlmResponse(NO_ISSUES), List.of(order, order)).analyze(task());

		assertThat(report.total()).isEqualTo(1);
		assertThat(prompts).hasSize(1);
	}

	@Test
	void doesNotSendSourceThatExceedsTheLimit() {
		String oversized = "a".repeat(11);
		ClassAnalysisCoordinator coordinator = new ClassAnalysisCoordinator(
				target -> List.of(DiscoveredJavaSource.readable(ORDER, oversized)),
				request -> {
					throw new AssertionError("LLM must not be called");
				},
				agentRunner(), settings(10), new InMemoryAnalysisJobStore(), lockDirectory);

		AnalysisReport report = coordinator.analyze(taskOf(ORDER));

		assertThat(report.results()).singleElement().satisfies(result -> {
			assertThat(result.status()).isEqualTo(AnalysisStatus.UNSUPPORTED);
			assertThat(result.error()).contains("was not truncated");
		});
		assertThat(report.failed()).isEqualTo(1);
	}

	@Test
	void keepsOnlyOneCopyOfTheSameIssueInsideAClass() {
		LlmResponse duplicated = new LlmResponse("""
				{"issues": [
				  {"title": "Same", "description": "d", "evidence": "e", "impact": "i", "recommendation": "r", "severity": "LOW"},
				  {"title": "Same", "description": "other", "evidence": "e", "impact": "i", "recommendation": "r", "severity": "HIGH"}
				]}
				""");

		AnalysisReport report = coordinator(request -> duplicated,
				List.of(DiscoveredJavaSource.readable(ORDER, ORDER_SOURCE))).analyze(taskOf(ORDER));

		assertThat(report.results().get(0).issues()).singleElement().extracting(CodeIssue::severity).isEqualTo(Severity.LOW);
	}

	private ClassAnalysisCoordinator coordinator(java.util.function.Function<LlmRequest, LlmResponse> responses) {
		return coordinator(responses, List.of(
				DiscoveredJavaSource.readable(ORDER, ORDER_SOURCE),
				DiscoveredJavaSource.readable(PAYMENT, PAYMENT_SOURCE)));
	}

	private ClassAnalysisCoordinator coordinator(java.util.function.Function<LlmRequest, LlmResponse> responses,
			List<DiscoveredJavaSource> sources) {
		return new ClassAnalysisCoordinator(target -> sources, request -> {
			prompts.add(request.messages().get(1).content());
			return responses.apply(request);
		}, agentRunner(), settings(1000), new InMemoryAnalysisJobStore(), lockDirectory);
	}

	private static AnalysisSettings settings(int maxSourceCharacters) {
		return new AnalysisSettings(maxSourceCharacters, "test-model", "search_files", "read_text_file");
	}

	private static AgentRunner agentRunner() {
		return new AgentRunner(new ToolRegistry(), new ToolPermissionPolicy(List.of(), List.of()), 5);
	}

	private static Task task() {
		return taskOf(Path.of("src").toAbsolutePath());
	}

	private static Task taskOf(Path path) {
		return new Task("Analyze code quality", new Target(path));
	}

	private static LlmResponse analysisResponse(String className, String severity) {
		return new LlmResponse("""
				{"issues": [{
				  "title": "Issue in %s",
				  "description": "Nested conditions are hard to read.",
				  "evidence": "if (a > 0)",
				  "impact": "The method is hard to test.",
				  "recommendation": "Extract a method.",
				  "severity": "%s"
				}]}
				""".formatted(className, severity));
	}

}
