package com.aicompany.agentharness.plan;

import com.aicompany.agentharness.analysis.JobFileLock;
import com.aicompany.agentharness.graph.CodeRelationshipGraph;
import com.aicompany.agentharness.graph.CodeRelationshipGraphBuilder;
import com.aicompany.agentharness.harness.AgentResult;
import com.aicompany.agentharness.harness.AgentRunner;
import com.aicompany.agentharness.llm.Llm;
import com.aicompany.agentharness.llm.LlmErrorCategory;
import com.aicompany.agentharness.llm.LlmException;
import com.aicompany.agentharness.tool.Tool;
import com.aicompany.agentharness.tool.ToolPermissionPolicy;
import com.aicompany.agentharness.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 계획을 저장하고, 실행 가능한 작업을 하나 처리하고, 필요할 때 계획을 고친 뒤 종료를 판단한다.
 * 기존 클래스 단위 분석 경로와는 별도이다.
 */
public class ProblemSolvingCoordinator {

	private static final Logger log = LoggerFactory.getLogger(ProblemSolvingCoordinator.class);

	private final Llm llm;
	private final ToolRegistry filesystemTools;
	private final Collection<String> allowedTools;
	private final Collection<String> deniedTools;
	private final PlanStore store;
	private final Path lockDirectory;
	private final int maxSolverSteps;
	private final int maxPlanIterations;
	private final int maxFinalCheckRounds;
	private final CodeRelationshipGraphBuilder graphBuilder = new CodeRelationshipGraphBuilder();
	private final TaskDecomposer decomposer;
	private final FinalChecker finalChecker;
	private final ResultSynthesizer synthesizer;
	private final ExecutableTaskSelector selector = new ExecutableTaskSelector();

	public ProblemSolvingCoordinator(Llm llm, ToolRegistry filesystemTools, Collection<String> allowedTools,
			Collection<String> deniedTools, PlanStore store, Path lockDirectory, int maxSolverSteps, int maxPlanIterations,
			int maxFinalCheckRounds) {
		this.llm = Objects.requireNonNull(llm);
		this.filesystemTools = Objects.requireNonNull(filesystemTools);
		this.allowedTools = List.copyOf(allowedTools);
		this.deniedTools = List.copyOf(deniedTools);
		this.store = Objects.requireNonNull(store);
		this.lockDirectory = Objects.requireNonNull(lockDirectory);
		this.maxSolverSteps = maxSolverSteps;
		this.maxPlanIterations = maxPlanIterations;
		this.maxFinalCheckRounds = maxFinalCheckRounds;
		this.decomposer = new TaskDecomposer(llm);
		this.finalChecker = new FinalChecker(llm);
		this.synthesizer = new ResultSynthesizer(llm);
	}

	public FinalAnalysisResult solve(AnalysisRequest request) {
		PlanIdentity identity = PlanIdentity.of(request);
		try (JobFileLock ignored = JobFileLock.acquire(lockDirectory, identity.lockName())) {
			return run(request, identity);
		}
	}

	private FinalAnalysisResult run(AnalysisRequest request, PlanIdentity identity) {
		PlanStore.PlanSession session = store.begin(identity);
		Optional<AnalysisPlan> existing = session.existing();
		if (existing.isPresent() && existing.get().status() == PlanStatus.COMPLETED && existing.get().finalResult() != null) {
			return existing.get().finalResult();
		}

		CodeRelationshipGraph graph = graph(request.target());
		if (existing.isPresent()) {
			session.resetInProgress();
		}
		else {
			try {
				session.create(request, decomposer.decompose(request, graph));
			}
			catch (LlmException exception) {
				throw exception;
			}
			catch (RuntimeException exception) {
				throw new InvalidPlanException(exception.getMessage());
			}
		}

		int iterations = 0;
		int finalCheckRounds = 0;
		while (iterations < maxPlanIterations) {
			AnalysisPlan plan = session.load();
			Optional<PlannedTask> next = selector.next(plan);
			if (next.isPresent()) {
				iterations++;
				PlannedTask task = next.get();
				session.markInProgress(task.id());
				SolverDecision decision = solveTask(request, session.load(), task, graph);
				session.saveOutcome(task.id(), decision.outcome());
				applyChanges(session, decision.planChanges());
				continue;
			}

			if (finalCheckRounds++ >= maxFinalCheckRounds) {
				return finish(session, PlanStatus.BLOCKED, "Stopped after the final-check round limit.");
			}
			FinalCheckDecision check;
			try {
				check = finalChecker.check(session.load());
			}
			catch (LlmException exception) {
				if (exception.category() == LlmErrorCategory.AUTHENTICATION) {
					return finish(session, PlanStatus.FAILED, exception.getMessage());
				}
				return finish(session, PlanStatus.FAILED, exception.getMessage());
			}
			if (!check.needsMoreWork()) {
				return finishCompleted(session);
			}
			List<PlanChange> additions = check.newTasks().stream()
					.map(draft -> PlanChange.addTask(draft.id(), draft.objective(), draft.dependsOn()))
					.toList();
			int before = session.load().tasks().size();
			applyChanges(session, additions);
			if (session.load().tasks().size() == before) {
				return finish(session, PlanStatus.BLOCKED, check.reason().isBlank()
						? "Final check asked for more work but no valid task could be added."
						: check.reason());
			}
		}
		return finish(session, PlanStatus.BLOCKED, "Stopped after the plan iteration limit.");
	}

	private SolverDecision solveTask(AnalysisRequest request, AnalysisPlan plan, PlannedTask task, CodeRelationshipGraph graph) {
		List<PlannedTask> predecessors = new ArrayList<>();
		for (String dependency : task.dependsOn()) {
			plan.task(dependency).ifPresent(predecessors::add);
		}
		AgentRunner runner = solverRunner(graph);
		List<com.aicompany.agentharness.tool.ToolDefinition> tools = runnerTools(graph);
		TaskSolverAgent agent = new TaskSolverAgent(llm, request, task, predecessors, graph, tools);
		try {
			AgentResult result = runner.run(agent, new com.aicompany.agentharness.harness.Task(task.objective(),
					new com.aicompany.agentharness.harness.Target(request.target())));
			if (agent.finishDecision() != null) {
				return agent.finishDecision();
			}
			return SolverDecision.finish(TaskOutcome.failed(result.summary()), List.of());
		}
		catch (LlmException exception) {
			return SolverDecision.finish(TaskOutcome.failed("LLM call failed: " + exception.getMessage()), List.of());
		}
		catch (RuntimeException exception) {
			log.warn("Solver failed for {}", task.id(), exception);
			return SolverDecision.finish(TaskOutcome.failed(exception.getMessage()), List.of());
		}
	}

	private void applyChanges(PlanStore.PlanSession session, List<PlanChange> changes) {
		for (PlanChange change : changes) {
			try {
				session.applyChanges(List.of(change));
			}
			catch (InvalidPlanException exception) {
				log.warn("Ignored invalid plan change {}: {}", change.type(), exception.getMessage());
			}
		}
	}

	private FinalAnalysisResult finishCompleted(PlanStore.PlanSession session) {
		AnalysisPlan plan = session.load();
		boolean unfinished = plan.tasks().stream()
				.anyMatch(task -> task.status() == PlanTaskStatus.PENDING || task.status() == PlanTaskStatus.IN_PROGRESS
						|| task.status() == PlanTaskStatus.BLOCKED || task.status() == PlanTaskStatus.FAILED);
		if (unfinished) {
			return finish(session, PlanStatus.BLOCKED, "No executable task remains.");
		}
		FinalAnalysisResult result = synthesizer.synthesize(plan);
		session.complete(result);
		return result;
	}

	private FinalAnalysisResult finish(PlanStore.PlanSession session, PlanStatus status, String reason) {
		AnalysisPlan plan = session.load();
		FinalAnalysisResult result;
		try {
			result = synthesizer.synthesize(plan);
			result = new FinalAnalysisResult(result.objective(), result.summary(), result.findings(), result.relatedFiles(),
					result.recommendations(), result.coverage(), false);
		}
		catch (RuntimeException exception) {
			result = new FinalAnalysisResult(plan.request().objective(), reason, List.of(), List.of(), List.of(),
					plan.progress().render(), false);
		}
		if (status == PlanStatus.FAILED) {
			session.failed(reason);
		}
		else {
			session.blocked(reason);
		}
		return result;
	}

	private AgentRunner solverRunner(CodeRelationshipGraph graph) {
		ToolRegistry registry = solverRegistry(graph);
		List<String> allowed = new ArrayList<>(allowedTools);
		allowed.add(GraphQueryTool.NAME);
		allowed.add(GraphListTool.NAME);
		return new AgentRunner(registry, new ToolPermissionPolicy(allowed, deniedTools), maxSolverSteps);
	}

	private List<com.aicompany.agentharness.tool.ToolDefinition> runnerTools(CodeRelationshipGraph graph) {
		ToolPermissionPolicy policy = new ToolPermissionPolicy(concatAllowed(), deniedTools);
		return policy.allowedToolDefinitions(solverRegistry(graph).toolDefinitions());
	}

	private List<String> concatAllowed() {
		List<String> allowed = new ArrayList<>(allowedTools);
		allowed.add(GraphQueryTool.NAME);
		allowed.add(GraphListTool.NAME);
		return allowed;
	}

	private ToolRegistry solverRegistry(CodeRelationshipGraph graph) {
		ToolRegistry registry = new ToolRegistry();
		filesystemTools.toolDefinitions().forEach(definition ->
				filesystemTools.findByName(definition.name()).ifPresent(registry::register));
		registerIfAbsent(registry, new GraphQueryTool(graph));
		registerIfAbsent(registry, new GraphListTool(graph));
		return registry;
	}

	private static void registerIfAbsent(ToolRegistry registry, Tool tool) {
		if (registry.findByName(tool.name()).isEmpty()) {
			registry.register(tool);
		}
	}

	private CodeRelationshipGraph graph(Path target) {
		Path root = Files.isDirectory(target) ? target : target.getParent();
		if (root == null || !Files.isDirectory(root)) {
			return new CodeRelationshipGraph(List.of(), List.of());
		}
		try {
			return graphBuilder.build(root);
		}
		catch (RuntimeException exception) {
			log.warn("Could not build the code relationship graph from {}", root, exception);
			return new CodeRelationshipGraph(List.of(), List.of());
		}
	}

}
