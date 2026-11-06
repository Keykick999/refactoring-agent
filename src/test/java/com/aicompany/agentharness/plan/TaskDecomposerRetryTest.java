package com.aicompany.agentharness.plan;

import com.aicompany.agentharness.graph.CodeRelationshipGraph;
import com.aicompany.agentharness.llm.Llm;
import com.aicompany.agentharness.llm.LlmException;
import com.aicompany.agentharness.llm.LlmRequest;
import com.aicompany.agentharness.llm.LlmResponse;
import com.aicompany.agentharness.llm.Message;
import com.aicompany.agentharness.llm.LlmRetryPolicy;
import com.aicompany.agentharness.llm.RetryableLlmException;
import com.aicompany.agentharness.llm.RetryingLlm;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskDecomposerRetryTest {

	private static final String VALID = """
			{"tasks":[{"id":"t1","objective":"Analyze OrderService","dependsOn":[]}]}
			""";
	private static final String UNKNOWN_DEPENDENCY = """
			{"tasks":[{"id":"1","objective":"Analyze types","dependsOn":["parseJavaSources"]}]}
			""";
	private static final String DUPLICATE_ID = """
			{"tasks":[{"id":"a","objective":"One","dependsOn":[]},{"id":"a","objective":"Two","dependsOn":[]}]}
			""";
	private static final String CYCLE = """
			{"tasks":[{"id":"a","objective":"A","dependsOn":["b"]},{"id":"b","objective":"B","dependsOn":["a"]}]}
			""";

	private final AnalysisRequest request = AnalysisRequest.of(Path.of("graph"), "Check dependencies");
	private final CodeRelationshipGraph graph = new CodeRelationshipGraph(List.of(), List.of());

	@Test
	void regeneratesAfterUnknownDependencyAndReturnsValidPlan() {
		List<String> prompts = new ArrayList<>();
		ScriptedLlm llm = new ScriptedLlm(prompts, UNKNOWN_DEPENDENCY, VALID);

		List<PlannedTask> tasks = new TaskDecomposer(llm).decompose(request, graph);

		assertThat(tasks).extracting(PlannedTask::id).containsExactly("t1");
		assertThat(llm.calls).isEqualTo(2);
		assertThat(prompts.get(0)).contains("No existing plan. This is the initial plan.")
				.contains("Current Task IDs:");
		assertThat(prompts.get(1)).contains("Previous plan was rejected")
				.contains("unknown task parseJavaSources")
				.contains("Current Task IDs:");
	}

	@Test
	void stopsAfterThreeInvalidPlans() {
		List<String> prompts = new ArrayList<>();
		ScriptedLlm llm = new ScriptedLlm(prompts, UNKNOWN_DEPENDENCY, UNKNOWN_DEPENDENCY, UNKNOWN_DEPENDENCY, VALID);

		assertThatThrownBy(() -> new TaskDecomposer(llm).decompose(request, graph))
				.isInstanceOf(PlanGenerationException.class)
				.hasMessageContaining("Plan generation failed")
				.hasMessageContaining("unknown task parseJavaSources")
				.extracting(thrown -> ((PlanGenerationException) thrown).semanticRetries())
				.isEqualTo(2);
		assertThat(llm.calls).isEqualTo(3);
		assertThat(prompts.get(2)).contains("Previous plan was rejected");
	}

	@Test
	void stillRejectsDuplicateTaskIdsAfterSemanticRetries() {
		ScriptedLlm llm = new ScriptedLlm(new ArrayList<>(), DUPLICATE_ID, DUPLICATE_ID, DUPLICATE_ID);

		assertThatThrownBy(() -> new TaskDecomposer(llm).decompose(request, graph))
				.isInstanceOf(PlanGenerationException.class)
				.hasMessageContaining("Duplicate");
		assertThat(llm.calls).isEqualTo(3);
	}

	@Test
	void stillRejectsCyclesAfterSemanticRetries() {
		ScriptedLlm llm = new ScriptedLlm(new ArrayList<>(), CYCLE, CYCLE, CYCLE);

		assertThatThrownBy(() -> new TaskDecomposer(llm).decompose(request, graph))
				.isInstanceOf(PlanGenerationException.class)
				.hasMessageContaining("cycle");
		assertThat(llm.calls).isEqualTo(3);
	}

	@Test
	void keepsHttpRetryIndependentFromSemanticRetry() {
		AtomicInteger providerCalls = new AtomicInteger();
		Llm provider = request -> {
			if (providerCalls.incrementAndGet() == 1) {
				throw new RetryableLlmException("temporary", new java.io.IOException("reset"));
			}
			return new LlmResponse(UNKNOWN_DEPENDENCY);
		};
		RetryingLlm retrying = new RetryingLlm(provider,
				new LlmRetryPolicy(2, Duration.ofMillis(1), 2.0, Duration.ofMillis(1), 0),
				duration -> {
				});

		assertThatThrownBy(() -> new TaskDecomposer(retrying).decompose(request, graph))
				.isInstanceOf(PlanGenerationException.class);
		assertThat(providerCalls).hasValue(4);
	}

	@Test
	void acceptsDependencyOnAnExistingTaskId() {
		AnalysisPlan current = new AnalysisPlan(1, request, PlanStatus.RUNNING, List.of(
				PlannedTask.pending("task-1", "Read types", List.of())
						.withOutcome(TaskOutcome.completed(List.of("OrderService depends on a concrete type"),
								"DIP is visible", List.of(new TaskEvidence("OrderService.java", "OrderService", "", "field")),
								List.of("Is there an interface?")))), null);
		List<String> prompts = new ArrayList<>();
		ScriptedLlm llm = new ScriptedLlm(prompts, """
				{"tasks":[{"id":"task-2","objective":"Check the interface","dependsOn":["task-1"]}]}
				""");

		List<PlannedTask> tasks = new TaskDecomposer(llm).decompose(request, graph, current);

		assertThat(tasks).extracting(PlannedTask::id).containsExactly("task-2");
		assertThat(tasks.get(0).dependsOn()).containsExactly("task-1");
		assertThat(prompts.get(0)).contains("task-1 [COMPLETED]")
				.contains("fact: OrderService depends on a concrete type")
				.contains("Current Task IDs:")
				.contains("Do not change completed tasks");
		assertThat(llm.calls).isEqualTo(1);
	}

	@Test
	void regeneratesAdditionalTaskThatReferencesAnUnknownId() {
		AnalysisPlan current = new AnalysisPlan(1, request, PlanStatus.RUNNING,
				List.of(PlannedTask.pending("task-1", "Read types", List.of())), null);
		List<String> prompts = new ArrayList<>();
		ScriptedLlm llm = new ScriptedLlm(prompts, """
				{"tasks":[{"id":"task-2","objective":"Parse","dependsOn":["parseJavaSources"]}]}
				""", """
				{"tasks":[{"id":"task-2","objective":"Check task-1","dependsOn":["task-1"]}]}
				""");

		List<PlannedTask> tasks = new TaskDecomposer(llm).decompose(request, graph, current);

		assertThat(tasks).extracting(PlannedTask::id).containsExactly("task-2");
		assertThat(prompts.get(1)).contains("Previous plan was rejected")
				.contains("parseJavaSources")
				.contains("- task-1");
		assertThat(llm.calls).isEqualTo(2);
	}

	@Test
	void doesNotTreatHttpFailureAsSemanticRetry() {
		Llm failing = request -> {
			throw new LlmException("Gemini API key is not configured.");
		};

		assertThatThrownBy(() -> new TaskDecomposer(failing).decompose(request, graph))
				.isInstanceOf(LlmException.class)
				.isNotInstanceOf(PlanGenerationException.class)
				.hasMessageContaining("API key");
	}

	private static final class ScriptedLlm implements Llm {

		private final List<String> prompts;
		private final String[] responses;
		private int calls;

		private ScriptedLlm(List<String> prompts, String... responses) {
			this.prompts = prompts;
			this.responses = responses;
		}

		@Override
		public LlmResponse generate(LlmRequest request) {
			prompts.add(request.messages().stream().map(Message::content).reduce("", (left, right) -> left + "\n" + right));
			String body = responses[Math.min(calls, responses.length - 1)];
			calls++;
			return new LlmResponse(body);
		}

	}

}
