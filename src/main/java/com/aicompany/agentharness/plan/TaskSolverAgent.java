package com.aicompany.agentharness.plan;

import com.aicompany.agentharness.graph.CodeNode;
import com.aicompany.agentharness.graph.CodeNodeType;
import com.aicompany.agentharness.graph.CodeRelationship;
import com.aicompany.agentharness.graph.CodeRelationshipGraph;
import com.aicompany.agentharness.harness.Agent;
import com.aicompany.agentharness.harness.AgentDecision;
import com.aicompany.agentharness.harness.AgentResult;
import com.aicompany.agentharness.harness.AgentState;
import com.aicompany.agentharness.llm.Llm;
import com.aicompany.agentharness.llm.LlmException;
import com.aicompany.agentharness.llm.LlmRequest;
import com.aicompany.agentharness.llm.LlmResponse;
import com.aicompany.agentharness.llm.Message;
import com.aicompany.agentharness.llm.MessageRole;
import com.aicompany.agentharness.tool.ToolCall;
import com.aicompany.agentharness.tool.ToolDefinition;
import com.aicompany.agentharness.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 한 작업을 해결한다. 정보는 Graph/MCP/이전 결과에서 필요한 것만 가져오고, 원문 전체를 미리 넣지 않는다.
 */
public final class TaskSolverAgent implements Agent {

	private static final Logger log = LoggerFactory.getLogger(TaskSolverAgent.class);

	private static final int MAX_LISTED_SOURCE_FILES = 30;

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

	private static final String NATIVE_TOOL_ADDENDUM = """
			The available tools are provided by the API. Call one of those tools when you need information.
			Do not invent a tool name and do not describe the tool call as JSON.
			When you can answer, return one JSON object and do not call a tool:
			{"type":"FINISH","taskDecision":{"status":"COMPLETED","facts":[],"conclusion":"","evidence":[],"unresolvedQuestions":[],"planChanges":[]}}
			status must be COMPLETED, BLOCKED, or FAILED.
			""";

	@Override
	public AgentDecision decide(AgentState agentState) {
		try {
			return interpret(agentState, generate(createMessages(agentState)), true);
		}
		catch (LlmException exception) {
			return failLlm(exception);
		}
	}

	private LlmResponse generate(List<Message> messages) {
		LlmRequest request = llm.nativeToolCalls()
				? new LlmRequest(messages, null, availableTools)
				: new LlmRequest(messages, responseSchema());
		return llm.generate(request);
	}

	private AgentDecision interpret(AgentState agentState, LlmResponse response, boolean canRetry) {
		if (!response.toolCalls().isEmpty()) {
			ToolCall toolCall = response.toolCalls().get(0);
			if (isUnavailableName(toolCall.toolName())) {
				return rejectUnavailable(agentState, toolCall.toolName(), canRetry);
			}
			agentState.addMessage(Message.assistantToolCall(toolCall));
			return AgentDecision.useTool(toolCall);
		}
		SolverDecision decision = parser.parse(response.content());
		if (isUnavailable(decision)) {
			return rejectUnavailable(agentState, decision.toolCall().toolName(), canRetry);
		}
		agentState.addMessage(Message.assistant(response.content()));
		if (decision.kind() == SolverDecision.Kind.USE_TOOL) {
			return AgentDecision.useTool(decision.toolCall());
		}
		finishDecision = decision;
		boolean success = decision.outcome().status() == PlanTaskStatus.COMPLETED;
		return AgentDecision.finish(success
				? AgentResult.success(decision.outcome().conclusion())
				: AgentResult.failure(decision.outcome().conclusion()));
	}

	private AgentDecision rejectUnavailable(AgentState agentState, String toolName, boolean canRetry) {
		if (!canRetry) {
			return failUnavailable(toolName);
		}
		try {
			return interpret(agentState, generate(retryMessages(agentState, toolName)), false);
		}
		catch (LlmException exception) {
			return failLlm(exception);
		}
	}

	private String responseSchema() {
		return SolverDecisionParser.responseSchema(availableTools.stream().map(ToolDefinition::name).toList());
	}

	private boolean isUnavailable(SolverDecision decision) {
		return decision.kind() == SolverDecision.Kind.USE_TOOL && isUnavailableName(decision.toolCall().toolName());
	}

	private boolean isUnavailableName(String toolName) {
		return availableTools.stream().noneMatch(tool -> tool.name().equals(toolName));
	}

	private List<Message> retryMessages(AgentState agentState, String rejectedToolName) {
		List<Message> messages = createMessages(agentState);
		String names = availableTools.stream().map(ToolDefinition::name).collect(Collectors.joining(", "));
		messages.add(Message.user("Unavailable tool '%s'. Choose one of: %s.".formatted(rejectedToolName, names)));
		return messages;
	}

	private AgentDecision failLlm(LlmException exception) {
		log.warn("Solver LLM call failed for {}", task.id(), exception);
		finishDecision = SolverDecision.finish(TaskOutcome.failed("LLM call failed: " + exception.getMessage()), List.of());
		return AgentDecision.finish(AgentResult.failure(finishDecision.outcome().conclusion()));
	}

	private AgentDecision failUnavailable(String toolName) {
		log.warn("Unavailable tool {} for {}", toolName, task.id());
		String reason = "Unavailable tool '" + toolName + "'";
		finishDecision = SolverDecision.finish(TaskOutcome.failed(reason), List.of());
		return AgentDecision.finish(AgentResult.failure(reason));
	}

	private List<Message> createMessages(AgentState agentState) {
		List<Message> messages = new ArrayList<>();
		messages.add(Message.system(systemPrompt()));
		messages.add(Message.user(workingSet()));
		appendToolHistory(messages, agentState);
		return messages;
	}

	private String systemPrompt() {
		return llm.nativeToolCalls() ? SYSTEM_PROMPT + "\n" + NATIVE_TOOL_ADDENDUM : SYSTEM_PROMPT;
	}

	private void appendToolHistory(List<Message> messages, AgentState agentState) {
		List<Message> previous = agentState.messages().stream().filter(message -> message.role() == MessageRole.ASSISTANT).toList();
		List<ToolResult> toolResults = agentState.toolResults();
		for (int step = 0; step < toolResults.size(); step++) {
			String rendered = renderToolResult(toolResults.get(step));
			if (step < previous.size()) {
				Message prior = previous.get(step);
				messages.add(prior);
				if (!prior.toolCalls().isEmpty()) {
					messages.add(Message.toolResult(prior.toolCalls().get(0).id(), rendered));
					continue;
				}
			}
			messages.add(Message.user(rendered));
		}
	}

	private static String renderToolResult(ToolResult result) {
		return "Tool result of %s (%s):\n%s".formatted(
				result.toolName(), result.success() ? "success" : "failure", result.output());
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

				## Target
				%s
				Only analyze code under this path. Do not search or list directories outside it.

				## Code graph
				%s
				File paths come from the code graph and can be passed to filesystem tools as they are.
				Source is not loaded yet. Decide which files you need. Use find_related_types for more relationships.

				## Available tools
				%s
				""".formatted(request.objective(), request.style(), request.enforcementMode(), task.id(), task.objective(),
				predecessorText(), request.target(), graphText(),
				llm.nativeToolCalls() ? "Provided as native tools." : toolText());
	}

	private String graphText() {
		List<CodeNode> nodes = targetNodes();
		if (nodes.isEmpty()) {
			return "No project types under the target.";
		}
		StringBuilder text = new StringBuilder();
		text.append(nodes.size()).append(" project types under the target.\n");
		nodes.stream().limit(MAX_LISTED_SOURCE_FILES).forEach(node -> appendNode(text, node));
		if (nodes.size() > MAX_LISTED_SOURCE_FILES) {
			text.append("- ... ").append(nodes.size() - MAX_LISTED_SOURCE_FILES)
					.append(" more types. Use list_code_types or find_related_types to see them.\n");
		}
		return text.toString();
	}

	private List<CodeNode> targetNodes() {
		Path target = request.target().toAbsolutePath().normalize();
		String objective = task.objective();
		return graph.getNodes().stream()
				.filter(node -> node.filePath().toAbsolutePath().normalize().startsWith(target))
				.sorted(Comparator.comparing((CodeNode node) -> !namedIn(objective, node)))
				.toList();
	}

	private static boolean namedIn(String text, CodeNode node) {
		return Pattern.compile("(?<![\\w$])" + Pattern.quote(node.simpleName()) + "(?![\\w$])").matcher(text).find();
	}

	private void appendNode(StringBuilder text, CodeNode node) {
		text.append("- ").append(node.type() == CodeNodeType.INTERFACE ? "Interface" : "Class").append(": ")
				.append(node.qualifiedName()).append('\n');
		text.append("  File: ").append(node.filePath()).append('\n');
		text.append("  Uses: ").append(relationshipText(graph.findRelationshipsFrom(node.qualifiedName()), true)).append('\n');
		text.append("  Used by: ").append(relationshipText(graph.findRelationshipsTo(node.qualifiedName()), false)).append('\n');
	}

	private static String relationshipText(List<CodeRelationship> relationships, boolean outgoing) {
		if (relationships.isEmpty()) {
			return "none";
		}
		return relationships.stream()
				.map(relationship -> (outgoing ? relationship.target() : relationship.source()).qualifiedName()
						+ " (" + relationship.type().name() + ")")
				.collect(Collectors.joining(", "));
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
