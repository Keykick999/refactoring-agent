package com.aicompany.agentharness.plan;

import com.aicompany.agentharness.llm.Llm;
import com.aicompany.agentharness.llm.LlmRequest;
import com.aicompany.agentharness.llm.LlmResponse;
import com.aicompany.agentharness.tool.Tool;
import com.aicompany.agentharness.tool.ToolRegistry;
import com.aicompany.agentharness.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemSolvingCoordinatorTest {

	@TempDir
	Path workspace;

	private final InMemoryPlanStore store = new InMemoryPlanStore();
	private final List<LlmRequest> requests = new ArrayList<>();

	@Test
	void decomposesPersistsAndCompletesWhenFinalCheckNeedsNoMoreWork() throws IOException {
		writeSources();
		RecordingLlm llm = new RecordingLlm(request -> {
			String prompt = promptOf(request);
			if (prompt.contains("You decompose")) {
				return json("""
						{"tasks":[{"id":"t1","objective":"Analyze OrderService","dependsOn":[]}]}
						""");
			}
			if (prompt.contains("You solve one analysis task")) {
				return json("""
						{"type":"FINISH","taskDecision":{
						  "status":"COMPLETED",
						  "facts":["OrderService depends on OrderRepository"],
						  "conclusion":"DIP risk is visible in the constructor.",
						  "evidence":[{"filePath":"OrderService.java","className":"OrderService","methodName":"","detail":"constructor"}],
						  "unresolvedQuestions":[],
						  "planChanges":[]
						}}
						""");
			}
			if (prompt.contains("You review whether")) {
				return json("""
						{"needsMoreWork":false,"reason":"enough","newTasks":[]}
						""");
			}
			return json("""
					{"summary":"SOLID review complete","findings":["constructor depends on concrete type"],
					 "relatedFiles":["OrderService.java"],"recommendations":["depend on the interface"],
					 "coverage":"OrderService","complete":true}
					""");
		});

		FinalAnalysisResult result = coordinator(llm).solve(request());

		assertThat(result.complete()).isTrue();
		assertThat(result.findings()).isNotEmpty();
		AnalysisPlan plan = store.begin(PlanIdentity.of(request())).existing().orElseThrow();
		assertThat(plan.status()).isEqualTo(PlanStatus.COMPLETED);
		assertThat(plan.task("t1")).get().extracting(PlannedTask::status).isEqualTo(PlanTaskStatus.COMPLETED);
		assertThat(plan.task("t1").orElseThrow().outcome().facts()).contains("OrderService depends on OrderRepository");
	}

	@Test
	void doesNotRunATaskUntilDependenciesAreCompleted() throws IOException {
		writeSources();
		List<String> solved = new ArrayList<>();
		RecordingLlm llm = new RecordingLlm(request -> {
			String prompt = promptOf(request);
			if (prompt.contains("You decompose")) {
				return json("""
						{"tasks":[
						  {"id":"a","objective":"First","dependsOn":[]},
						  {"id":"b","objective":"Second","dependsOn":["a"]}
						]}
						""");
			}
			if (prompt.contains("You solve one analysis task")) {
				if (prompt.contains("id: b")) {
					solved.add("b");
				}
				else {
					solved.add("a");
				}
				return finish("done");
			}
			if (prompt.contains("You review whether")) {
				return json("{\"needsMoreWork\":false,\"reason\":\"done\",\"newTasks\":[]}");
			}
			return synthesis();
		});

		coordinator(llm).solve(request());

		assertThat(solved).containsExactly("a", "b");
	}

	@Test
	void addsTaskWhenFinalCheckAsksForMoreWork() throws IOException {
		writeSources();
		AtomicInteger checks = new AtomicInteger();
		RecordingLlm llm = new RecordingLlm(request -> {
			String prompt = promptOf(request);
			if (prompt.contains("You decompose")) {
				return json("""
						{"tasks":[{"id":"t1","objective":"First look","dependsOn":[]}]}
						""");
			}
			if (prompt.contains("You solve one analysis task")) {
				return finish("looked");
			}
			if (prompt.contains("You review whether")) {
				if (checks.getAndIncrement() == 0) {
					return json("""
							{"needsMoreWork":true,"reason":"need repository","newTasks":[
							  {"id":"t2","objective":"Analyze repository","dependsOn":["t1"]}
							]}
							""");
				}
				return json("{\"needsMoreWork\":false,\"reason\":\"enough\",\"newTasks\":[]}");
			}
			return synthesis();
		});

		FinalAnalysisResult result = coordinator(llm).solve(request());

		assertThat(result.complete()).isTrue();
		AnalysisPlan plan = store.begin(PlanIdentity.of(request())).existing().orElseThrow();
		assertThat(plan.tasks()).extracting(PlannedTask::id).contains("t1", "t2");
		assertThat(plan.task("t2")).get().extracting(PlannedTask::status).isEqualTo(PlanTaskStatus.COMPLETED);
	}

	@Test
	void solverCanAddATaskThroughPlanChanges() throws IOException {
		writeSources();
		RecordingLlm llm = new RecordingLlm(request -> {
			String prompt = promptOf(request);
			if (prompt.contains("You decompose")) {
				return json("""
						{"tasks":[{"id":"t1","objective":"Start","dependsOn":[]}]}
						""");
			}
			if (prompt.contains("id: t1")) {
				return json("""
						{"type":"FINISH","taskDecision":{
						  "status":"COMPLETED","facts":["need OrderPolicy"],"conclusion":"Need another task",
						  "evidence":[],"unresolvedQuestions":["OrderPolicy?"],
						  "planChanges":[{"type":"ADD_TASK","taskId":"t2","objective":"Analyze OrderPolicy","dependsOn":["t1"]}]
						}}
						""");
			}
			if (prompt.contains("You solve one analysis task")) {
				return finish("policy checked");
			}
			if (prompt.contains("You review whether")) {
				return json("{\"needsMoreWork\":false,\"reason\":\"enough\",\"newTasks\":[]}");
			}
			return synthesis();
		});

		coordinator(llm).solve(request());

		AnalysisPlan plan = store.begin(PlanIdentity.of(request())).existing().orElseThrow();
		assertThat(plan.task("t2")).get().extracting(PlannedTask::status).isEqualTo(PlanTaskStatus.COMPLETED);
	}

	@Test
	void resumesInProgressTaskAndKeepsCompletedResults() throws IOException {
		writeSources();
		AnalysisRequest request = request();
		PlanStore.PlanSession session = store.begin(PlanIdentity.of(request));
		session.create(request, List.of(
				PlannedTask.pending("done", "Done", List.of())
						.withOutcome(TaskOutcome.completed(List.of("kept fact"), "already done", List.of(), List.of())),
				PlannedTask.pending("left", "Left", List.of()).withStatus(PlanTaskStatus.IN_PROGRESS)));

		RecordingLlm llm = new RecordingLlm(requestLlm -> {
			String prompt = promptOf(requestLlm);
			if (prompt.contains("You decompose")) {
				throw new AssertionError("existing plan must not be decomposed again");
			}
			if (prompt.contains("id: done")) {
				throw new AssertionError("completed task must not run again");
			}
			if (prompt.contains("You solve one analysis task")) {
				return finish("resumed");
			}
			if (prompt.contains("You review whether")) {
				return json("{\"needsMoreWork\":false,\"reason\":\"enough\",\"newTasks\":[]}");
			}
			return synthesis();
		});

		coordinator(llm).solve(request);

		AnalysisPlan plan = store.begin(PlanIdentity.of(request)).existing().orElseThrow();
		assertThat(plan.task("done").orElseThrow().outcome().facts()).contains("kept fact");
		assertThat(plan.task("left")).get().extracting(PlannedTask::status).isEqualTo(PlanTaskStatus.COMPLETED);
	}

	@Test
	void reusesCompletedPlanWithoutCallingLlm() throws IOException {
		writeSources();
		AnalysisRequest request = request();
		FinalAnalysisResult stored = new FinalAnalysisResult(request.objective(), "stored", List.of("finding"), List.of(),
				List.of(), "all", true);
		PlanStore.PlanSession session = store.begin(PlanIdentity.of(request));
		session.create(request, List.of(PlannedTask.pending("t1", "A", List.of())
				.withOutcome(TaskOutcome.completed(List.of("f"), "c", List.of(), List.of()))));
		session.complete(stored);

		RecordingLlm llm = new RecordingLlm(ignored -> {
			throw new AssertionError("completed plan must reuse the stored report");
		});

		assertThat(coordinator(llm).solve(request).summary()).isEqualTo("stored");
	}

	@Test
	void stopsWhenBlockedTasksLeaveNothingExecutable() throws IOException {
		writeSources();
		RecordingLlm llm = new RecordingLlm(request -> {
			String prompt = promptOf(request);
			if (prompt.contains("You decompose")) {
				return json("""
						{"tasks":[{"id":"t1","objective":"Blocked work","dependsOn":[]}]}
						""");
			}
			if (prompt.contains("You solve one analysis task")) {
				return json("""
						{"type":"FINISH","taskDecision":{
						  "status":"BLOCKED","facts":[],"conclusion":"missing info",
						  "evidence":[],"unresolvedQuestions":["need another service"],
						  "planChanges":[]
						}}
						""");
			}
			if (prompt.contains("You review whether")) {
				return json("{\"needsMoreWork\":false,\"reason\":\"cannot continue\",\"newTasks\":[]}");
			}
			return synthesis();
		});

		FinalAnalysisResult result = coordinator(llm, 8, 5, 2).solve(request());

		assertThat(result.complete()).isFalse();
		assertThat(store.begin(PlanIdentity.of(request())).existing().orElseThrow().status()).isEqualTo(PlanStatus.BLOCKED);
		assertThat(requests.stream().filter(item -> content(item).contains("You review whether")).count()).isEqualTo(1);
	}

	@Test
	void doesNotPutUnreadSourceIntoTheFirstSolverPrompt() throws IOException {
		writeSources();
		Files.writeString(workspace.resolve("SecretService.java"), "class SecretService { int hidden = 42; }");
		RecordingLlm llm = new RecordingLlm(request -> {
			String prompt = promptOf(request);
			if (prompt.contains("You decompose")) {
				return json("""
						{"tasks":[{"id":"t1","objective":"Analyze OrderService","dependsOn":[]}]}
						""");
			}
			if (prompt.contains("You solve one analysis task") && !prompt.contains("Tool result")) {
				assertThat(prompt).doesNotContain("hidden = 42");
				return json("""
						{"type":"USE_TOOL","toolCall":{"toolName":"find_related_types","arguments":{"typeName":"OrderService"}}}
						""");
			}
			if (prompt.contains("You solve one analysis task")) {
				return finish("used graph only");
			}
			if (prompt.contains("You review whether")) {
				return json("{\"needsMoreWork\":false,\"reason\":\"enough\",\"newTasks\":[]}");
			}
			return synthesis();
		});

		coordinator(llm).solve(request());
	}

	private ProblemSolvingCoordinator coordinator(Llm llm) {
		return coordinator(llm, 8, 20, 3);
	}

	private ProblemSolvingCoordinator coordinator(Llm llm, int solverSteps, int iterations, int checks) {
		ToolRegistry tools = new ToolRegistry();
		tools.register(new RecordingReadTool());
		return new ProblemSolvingCoordinator(llm, tools, List.of("read_text_file", GraphQueryTool.NAME, GraphListTool.NAME),
				List.of(), store, workspace.resolve("locks"), solverSteps, iterations, checks);
	}

	private AnalysisRequest request() {
		return new AnalysisRequest(workspace, "Check SOLID", AnalysisStyle.SOLID, EnforcementMode.ADVISORY);
	}

	private void writeSources() throws IOException {
		Files.writeString(workspace.resolve("OrderRepository.java"), "interface OrderRepository {}");
		Files.writeString(workspace.resolve("OrderService.java"), """
				class OrderService {
				    private OrderRepository repository;
				    OrderService(OrderRepository repository) { this.repository = repository; }
				}
				""");
	}

	private static LlmResponse json(String body) {
		return new LlmResponse(body);
	}

	private static LlmResponse finish(String conclusion) {
		return json("""
				{"type":"FINISH","taskDecision":{
				  "status":"COMPLETED","facts":["observed"],"conclusion":"%s",
				  "evidence":[],"unresolvedQuestions":[],"planChanges":[]
				}}
				""".formatted(conclusion));
	}

	private static LlmResponse synthesis() {
		return json("""
				{"summary":"ok","findings":["f"],"relatedFiles":[],"recommendations":[],"coverage":"c","complete":true}
				""");
	}

	private String promptOf(LlmRequest request) {
		requests.add(request);
		return content(request);
	}

	private static String content(LlmRequest request) {
		return request.messages().stream().map(message -> message.content()).reduce("", (left, right) -> left + "\n" + right);
	}

	private final class RecordingLlm implements Llm {

		private final Function<LlmRequest, LlmResponse> handler;

		private RecordingLlm(Function<LlmRequest, LlmResponse> handler) {
			this.handler = handler;
		}

		@Override
		public LlmResponse generate(LlmRequest request) {
			return handler.apply(request);
		}

	}

	private static final class RecordingReadTool implements Tool {

		@Override
		public String name() {
			return "read_text_file";
		}

		@Override
		public String description() {
			return "Read a file";
		}

		@Override
		public String inputSchema() {
			return "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}";
		}

		@Override
		public ToolResult execute(Map<String, Object> arguments) {
			return ToolResult.success(name(), "unread on purpose");
		}

	}

}
