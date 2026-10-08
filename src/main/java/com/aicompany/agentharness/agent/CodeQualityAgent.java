package com.aicompany.agentharness.agent;

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

	@Override
	public AgentDecision decide(AgentState agentState) {
		LlmRequest llmRequest = new LlmRequest(createMessages(agentState), AgentDecisionParser.RESPONSE_JSON_SCHEMA);

		String llmOutput;
		try {
			llmOutput = llm.generate(llmRequest).content();
		}
		catch (LlmException exception) {
			log.warn("LLM call failed", exception);
			return AgentDecision.finish(AgentResult.failure("LLM call failed: " + exception.getMessage()));
		}

		agentState.addMessage(Message.assistant(llmOutput));
		AgentDecision decision = decisionParser.parse(llmOutput);
		if (decision.type() == AgentDecision.Type.FINISH && decision.result().success()) {
			agentState.addAnalysisResult(decision.result().summary());
		}
		return decision;
	}

	/**
	 * AgentRunner는 USE_TOOL 결정마다 정확히 하나의 ToolResult를 State에 추가하므로,
	 * i번째 LLM 결정과 i번째 ToolResult를 한 쌍의 대화로 재구성한다.
	 */
	private List<Message> createMessages(AgentState agentState) {
		List<Message> messages = new ArrayList<>();
		messages.add(Message.system(SYSTEM_PROMPT));
		messages.add(Message.user(describeTaskAndTools(agentState)));

		List<Message> previousDecisions = agentState.messages().stream()
				.filter(message -> message.role() == MessageRole.ASSISTANT)
				.toList();
		List<ToolResult> toolResults = agentState.toolResults();
		for (int step = 0; step < toolResults.size(); step++) {
			if (step < previousDecisions.size()) {
				messages.add(previousDecisions.get(step));
			}
			messages.add(Message.user(describeToolResult(toolResults.get(step))));
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
