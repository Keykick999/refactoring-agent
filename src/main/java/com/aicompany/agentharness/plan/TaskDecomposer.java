package com.aicompany.agentharness.plan;

import com.aicompany.agentharness.graph.CodeRelationshipGraph;
import com.aicompany.agentharness.graph.CodeRelationshipGraphText;
import com.aicompany.agentharness.llm.Llm;
import com.aicompany.agentharness.llm.LlmRequest;
import com.aicompany.agentharness.llm.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public final class TaskDecomposer {

	static final int MAX_ATTEMPTS = 3;
	static final int MAX_SEMANTIC_RETRIES = MAX_ATTEMPTS - 1;

	private static final Logger log = LoggerFactory.getLogger(TaskDecomposer.class);
	private static final int GRAPH_TEXT_LIMIT = 8000;

	private static final String SYSTEM_PROMPT = """
			You decompose a software analysis goal into tasks a solver can finish one at a time.
			Each task must be related to the overall objective and small enough for one solver run.
			Do not make an infinite number of tiny tasks, and do not leave the whole goal as a single huge task.
			Dependencies are prerequisites, not a display order. Only add dependsOn when a task truly needs another result.
			Use the Current Plan in the user message. dependsOn may only use Current Task IDs or ids declared in this response.
			Do not invent task ids, tool names, or step names.
			Do not hard-code a fixed checklist. Use the analysis style as guidance, not as a mandatory task list.
			Do not judge the code yet. Return only the task plan JSON.
			""";

	private final Llm llm;
	private final DecompositionParser parser = new DecompositionParser();
	private final PlanValidator validator = new PlanValidator();

	public TaskDecomposer(Llm llm) {
		this.llm = llm;
	}

	public List<PlannedTask> decompose(AnalysisRequest request, CodeRelationshipGraph graph) {
		return decompose(request, graph, null);
	}

	public List<PlannedTask> decompose(AnalysisRequest request, CodeRelationshipGraph graph, AnalysisPlan currentPlan) {
		InvalidPlanException lastFailure = null;
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			String previousError = lastFailure == null ? null : lastFailure.getMessage();
			String response = llm.generate(new LlmRequest(
					List.of(Message.system(SYSTEM_PROMPT), Message.user(userMessage(request, graph, currentPlan, previousError))),
					DecompositionParser.RESPONSE_JSON_SCHEMA)).content();
			try {
				return accept(currentPlan, parser.parseDrafts(response));
			}
			catch (InvalidPlanException exception) {
				lastFailure = exception;
				log.warn("Decomposer plan failed validation (attempt {}/{}): {}", attempt, MAX_ATTEMPTS, exception.getMessage());
			}
		}
		throw new PlanGenerationException(lastFailure.getMessage(), MAX_ATTEMPTS, MAX_SEMANTIC_RETRIES);
	}

	private List<PlannedTask> accept(AnalysisPlan currentPlan, List<TaskDraft> drafts) {
		if (PlanContext.isInitial(currentPlan)) {
			return validator.validateDrafts(drafts);
		}
		if (drafts.isEmpty()) {
			return List.of();
		}
		List<TaskDraft> merged = new ArrayList<>();
		for (PlannedTask existing : currentPlan.tasks()) {
			merged.add(new TaskDraft(existing.id(), existing.objective(), existing.dependsOn()));
		}
		merged.addAll(drafts);
		validator.validateDrafts(merged);
		return drafts.stream().map(TaskDraft::toPending).toList();
	}

	private static String userMessage(AnalysisRequest request, CodeRelationshipGraph graph, AnalysisPlan currentPlan,
			String previousValidationError) {
		String graphText = new CodeRelationshipGraphText().render(graph);
		if (graphText.length() > GRAPH_TEXT_LIMIT) {
			graphText = graphText.substring(0, GRAPH_TEXT_LIMIT) + "\n... truncated graph ...";
		}
		boolean initial = PlanContext.isInitial(currentPlan);
		String instruction = initial
				? """
				This is the initial plan. Create the tasks needed to solve the objective.
				dependsOn may only contain ids declared in this response. Do not reference a task that is not in that list.
				"""
				: """
				A plan already exists. Do not recreate it and do not repeat existing task ids.
				Do not change completed tasks or make them run again.
				Return only new tasks that are still required. Return an empty tasks array if nothing else is needed.
				dependsOn may only contain Current Task IDs or ids declared in this response.
				""";
		String message = """
				## Objective
				%s

				## Analysis style
				%s (%s)

				## %s

				## Code structure
				%s

				## Planning rules
				%s""".formatted(request.objective(), request.style(), request.enforcementMode(), PlanContext.render(currentPlan),
				graphText, instruction.strip());
		if (previousValidationError == null) {
			return message;
		}
		return message + """

				## Previous plan was rejected
				Validation failure: %s
				dependsOn may only use Current Task IDs or ids declared in this response. Do not invent ids.
				Return a new task plan JSON that passes validation.
				""".formatted(previousValidationError);
	}

}
