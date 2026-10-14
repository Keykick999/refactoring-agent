package com.aicompany.agentharness.plan;

import com.aicompany.agentharness.graph.CodeRelationshipGraph;
import com.aicompany.agentharness.harness.Agent;
import com.aicompany.agentharness.harness.AgentDecision;
import com.aicompany.agentharness.harness.AgentResult;
import com.aicompany.agentharness.harness.AgentState;
import com.aicompany.agentharness.llm.Llm;
import com.aicompany.agentharness.llm.LlmException;
import com.aicompany.agentharness.llm.LlmRequest;
import com.aicompany.agentharness.llm.Message;
import com.aicompany.agentharness.llm.MessageRole;
import com.aicompany.agentharness.tool.ToolDefinition;
import com.aicompany.agentharness.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 한 작업을 해결한다. 정보는 Graph/MCP/이전 결과에서 필요한 것만 가져오고, 원문 전체를 미리 넣지 않는다.
 */
public final class TaskSolverAgent implements Agent {

	private static final Logger log = LoggerFactory.getLogger(TaskSolverAgent.class);

	private static final String SYSTEM_PROMPT = """
			You solve one analysis task.
			Decide whether the current information is enough. If not, use a tool to fetch only what you need.
			Use the code graph to find related types, then read source with filesystem tools when you need the actual code.
			Do not request the whole project. Do not repeat a tool call whose result is already in this conversation.
			When you can answer the task, FINISH with facts, conclusion, evidence, and unresolvedQuestions.
			Facts must be observable. Conclusion is your judgment for this task only.
			If a new task is required, add it through planChanges. Do not rewrite completed tasks.
			""";

	private final Llm llm;
	private final AnalysisRequest request;
	private final PlannedTask task;
	private final List<PlannedTask> predecessors;
	private final CodeRelationshipGraph graph;
	private final List<ToolDefinition> availableTools;
	private final SolverDecisionParser parser = new SolverDecisionParser();
	private SolverDecision finishDecision;

	public TaskSolverAgent(Llm llm, AnalysisRequest request, PlannedTask task, List<PlannedTask> predecessors,
			CodeRelationshipGraph graph, List<ToolDefinition> availableTools) {
		this.llm = llm;
		this.request = request;
		this.task = task;
		this.predecessors = List.copyOf(predecessors);
		this.graph = graph;
		this.availableTools = List.copyOf(availableTools);
	}

	public SolverDecision finishDecision() {
		return finishDecision;
	}

	@Override
	public AgentDecision decide(AgentState agentState) {
		String response;
		try {
			response = llm.generate(new LlmRequest(createMessages(agentState), SolverDecisionParser.RESPONSE_JSON_SCHEMA)).content();
		}
		catch (LlmException exception) {
			log.warn("Solver LLM call failed for {}", task.id(), exception);
			finishDecision = SolverDecision.finish(TaskOutcome.failed("LLM call failed: " + exception.getMessage()), List.of());
			return AgentDecision.finish(AgentResult.failure(finishDecision.outcome().conclusion()));
		}
		agentState.addMessage(Message.assistant(response));
		SolverDecision decision = parser.parse(response);
		if (decision.kind() == SolverDecision.Kind.USE_TOOL) {
			return AgentDecision.useTool(decision.toolCall());
		}
		finishDecision = decision;
		boolean success = decision.outcome().status() == PlanTaskStatus.COMPLETED;
		return AgentDecision.finish(success
				? AgentResult.success(decision.outcome().conclusion())
				: AgentResult.failure(decision.outcome().conclusion()));
	}

	private List<Message> createMessages(AgentState agentState) {
		List<Message> messages = new ArrayList<>();
		messages.add(Message.system(SYSTEM_PROMPT));
		messages.add(Message.user(workingSet()));
		List<Message> previous = agentState.messages().stream().filter(message -> message.role() == MessageRole.ASSISTANT).toList();
		List<ToolResult> toolResults = agentState.toolResults();
		for (int step = 0; step < toolResults.size(); step++) {
			if (step < previous.size()) {
				messages.add(previous.get(step));
			}
			ToolResult result = toolResults.get(step);
			messages.add(Message.user("Tool result of %s (%s):\n%s".formatted(
					result.toolName(), result.success() ? "success" : "failure", result.output())));
		}
		return messages;
	}

	private String workingSet() {
		return """
				## Overall objective
				%s

				## Analysis style
				%s (%s)

				## Current task
				id: %s
				objective: %s

				## Predecessor results
				%s

				## Graph index
				%d project types. Use list_code_types or find_related_types. Do not assume source is already loaded.

				## Available tools
				%s
				""".formatted(request.objective(), request.style(), request.enforcementMode(), task.id(), task.objective(),
				predecessorText(), graph.getNodes().size(), toolText());
	}

	private String predecessorText() {
		if (predecessors.isEmpty()) {
			return "none";
		}
		StringBuilder text = new StringBuilder();
		for (PlannedTask predecessor : predecessors) {
			text.append("- ").append(predecessor.id()).append(": ").append(predecessor.objective()).append('\n');
			if (predecessor.outcome() != null) {
				text.append("  conclusion: ").append(predecessor.outcome().conclusion()).append('\n');
				for (String fact : predecessor.outcome().facts()) {
					text.append("  fact: ").append(fact).append('\n');
				}
			}
		}
		return text.toString();
	}

	private String toolText() {
		StringBuilder text = new StringBuilder();
		for (ToolDefinition tool : availableTools) {
			text.append("- name: ").append(tool.name()).append('\n');
			text.append("  description: ").append(tool.description()).append('\n');
			text.append("  inputSchema: ").append(tool.inputSchema()).append('\n');
		}
		return text.toString();
	}

}
