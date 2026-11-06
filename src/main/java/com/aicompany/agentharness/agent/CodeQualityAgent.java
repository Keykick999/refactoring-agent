package com.aicompany.agentharness.agent;

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

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class CodeQualityAgent implements Agent {

	private static final Logger log = LoggerFactory.getLogger(CodeQualityAgent.class);

	private static final String SYSTEM_PROMPT = """
			You are a Code Quality Agent.
			Your responsibility is to analyze code quality and propose meaningful improvements.
			Evaluate readability, maintainability, duplication, complexity, responsibility,
			naming, coupling, cohesion, and design.

			Decision rules:
			- Analyze the current state and decide the single next action.
			- Use a tool only when you need information or an action that the current state does not provide.
			- Do not repeat a tool call whose result is already available.
			- Use only the available tools, with arguments that match each tool's inputSchema.
			- Use absolute paths as given in the task.
			- Stay within the scope of the task: code quality of the target.
			- Do not modify any file unless the task objective explicitly asks for code changes.
			- When the analysis is sufficient, return FINISH with success=true and a summary of
			  the quality issues found and the suggested improvements.
			- If the task cannot be completed (for example, the target cannot be read),
			  return FINISH with success=false and the reason.

			Respond with a single JSON object only, in one of these forms:
			{"type": "USE_TOOL", "toolCall": {"toolName": "<available tool name>", "arguments": {<arguments>}}}
			{"type": "FINISH", "result": {"success": true, "summary": "<analysis summary>"}}
			""";

	private final Llm llm;
	private final List<ToolDefinition> availableTools;
	private final AgentDecisionParser decisionParser = new AgentDecisionParser();

	public CodeQualityAgent(Llm llm, List<ToolDefinition> availableTools) {
		this.llm = llm;
		this.availableTools = List.copyOf(availableTools);
	}

	private static final String NATIVE_SYSTEM_PROMPT = """
			You are a Code Quality Agent.
			Your responsibility is to analyze code quality and propose meaningful improvements.
			Evaluate readability, maintainability, duplication, complexity, responsibility,
			naming, coupling, cohesion, and design.

			Decision rules:
			- Analyze the current state and decide the single next action.
			- Call one of the provided tools only when you need information that the current state does not provide.
			- Do not invent a tool name. Do not describe the tool call as JSON.
			- Do not repeat a tool call whose result is already available.
			- Use absolute paths as given in the task.
			- Stay within the scope of the task: code quality of the target.
			- Do not modify any file unless the task objective explicitly asks for code changes.
			- When the analysis is sufficient, return one JSON object and do not call a tool:
			  {"type": "FINISH", "result": {"success": true, "summary": "<analysis summary>"}}
			- If the task cannot be completed, return
			  {"type": "FINISH", "result": {"success": false, "summary": "<reason>"}}
			""";

	@Override
	public AgentDecision decide(AgentState agentState) {
		try {
			return interpret(agentState, generate(createMessages(agentState)), true);
		}
		catch (LlmException exception) {
			log.warn("LLM call failed", exception);
			return AgentDecision.finish(AgentResult.failure("LLM call failed: " + exception.getMessage()));
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
		AgentDecision decision = decisionParser.parse(response.content());
		if (isUnavailable(decision)) {
			return rejectUnavailable(agentState, decision.toolCall().toolName(), canRetry);
		}
		agentState.addMessage(Message.assistant(response.content()));
		if (decision.type() == AgentDecision.Type.FINISH && decision.result().success()) {
			agentState.addAnalysisResult(decision.result().summary());
		}
		return decision;
	}

	private AgentDecision rejectUnavailable(AgentState agentState, String toolName, boolean canRetry) {
		if (!canRetry) {
			log.warn("Unavailable tool {}", toolName);
			return AgentDecision.finish(AgentResult.failure("Unavailable tool '" + toolName + "'"));
		}
		try {
			return interpret(agentState, generate(retryMessages(agentState, toolName)), false);
		}
		catch (LlmException exception) {
			log.warn("LLM call failed", exception);
			return AgentDecision.finish(AgentResult.failure("LLM call failed: " + exception.getMessage()));
		}
	}

	private String responseSchema() {
		return AgentDecisionParser.responseSchema(availableTools.stream().map(ToolDefinition::name).toList());
	}

	private boolean isUnavailable(AgentDecision decision) {
		return decision.type() == AgentDecision.Type.USE_TOOL && isUnavailableName(decision.toolCall().toolName());
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

	/**
	 * AgentRunner는 USE_TOOL 결정마다 정확히 하나의 ToolResult를 State에 추가하므로,
	 * i번째 LLM 결정과 i번째 ToolResult를 한 쌍의 대화로 재구성한다.
	 */
	private List<Message> createMessages(AgentState agentState) {
		List<Message> messages = new ArrayList<>();
		messages.add(Message.system(llm.nativeToolCalls() ? NATIVE_SYSTEM_PROMPT : SYSTEM_PROMPT));
		messages.add(Message.user(describeTaskAndTools(agentState)));

		List<Message> previousDecisions = agentState.messages().stream()
				.filter(message -> message.role() == MessageRole.ASSISTANT)
				.toList();
		List<ToolResult> toolResults = agentState.toolResults();
		for (int step = 0; step < toolResults.size(); step++) {
			String rendered = describeToolResult(toolResults.get(step));
			if (step < previousDecisions.size()) {
				Message prior = previousDecisions.get(step);
				messages.add(prior);
				if (!prior.toolCalls().isEmpty()) {
					messages.add(Message.toolResult(prior.toolCalls().get(0).id(), rendered));
					continue;
				}
			}
			messages.add(Message.user(rendered));
		}
		return messages;
	}

	private String describeTaskAndTools(AgentState agentState) {
		return """
				## Task
				Objective: %s
				Target: %s

				## Current state
				Status: %s
				Tool calls so far: %d
				Analysis results so far: %s

				## Available tools
				%s
				""".formatted(
				agentState.task().goal(),
				agentState.task().target().path().toAbsolutePath(),
				agentState.status(),
				agentState.toolResults().size(),
				agentState.analysisResults().isEmpty() ? "none" : String.join("\n", agentState.analysisResults()),
				describeAvailableTools());
	}

	private String describeAvailableTools() {
		if (llm.nativeToolCalls()) {
			return "Provided as native tools.";
		}
		return availableTools.stream()
				.map(tool -> """
						- name: %s
						  description: %s
						  inputSchema: %s""".formatted(tool.name(), tool.description(), tool.inputSchema()))
				.collect(Collectors.joining("\n"));
	}

	private String describeToolResult(ToolResult toolResult) {
		return "Tool result of %s (%s):\n%s".formatted(
				toolResult.toolName(), toolResult.success() ? "success" : "failure", toolResult.output());
	}

}
