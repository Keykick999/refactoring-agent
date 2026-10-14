package com.aicompany.agentharness.plan;

import com.aicompany.agentharness.graph.CodeRelationshipGraph;
import com.aicompany.agentharness.graph.CodeRelationshipGraphText;
import com.aicompany.agentharness.llm.Llm;
import com.aicompany.agentharness.llm.LlmRequest;
import com.aicompany.agentharness.llm.Message;

import java.util.List;

public final class TaskDecomposer {

	private static final int GRAPH_TEXT_LIMIT = 8000;

	private static final String SYSTEM_PROMPT = """
			You decompose a software analysis goal into tasks a solver can finish one at a time.
			Each task must be related to the overall objective and small enough for one solver run.
			Do not make an infinite number of tiny tasks, and do not leave the whole goal as a single huge task.
			Dependencies are prerequisites, not a display order. Only add dependsOn when a task truly needs another result.
			Do not hard-code a fixed checklist. Use the analysis style as guidance, not as a mandatory task list.
			Do not judge the code yet. Return only the task plan JSON.
			""";

	private final Llm llm;
	private final DecompositionParser parser = new DecompositionParser();

	public TaskDecomposer(Llm llm) {
		this.llm = llm;
	}

	public List<PlannedTask> decompose(AnalysisRequest request, CodeRelationshipGraph graph) {
		String response = llm.generate(new LlmRequest(List.of(Message.system(SYSTEM_PROMPT), Message.user(userMessage(request, graph))),
				DecompositionParser.RESPONSE_JSON_SCHEMA)).content();
		return parser.parse(response);
	}

	private static String userMessage(AnalysisRequest request, CodeRelationshipGraph graph) {
		String graphText = new CodeRelationshipGraphText().render(graph);
		if (graphText.length() > GRAPH_TEXT_LIMIT) {
			graphText = graphText.substring(0, GRAPH_TEXT_LIMIT) + "\n... truncated graph ...";
		}
		return """
				## Objective
				%s

				## Analysis style
				%s (%s)

				## Code structure
				%s
				""".formatted(request.objective(), request.style(), request.enforcementMode(), graphText);
	}

}
