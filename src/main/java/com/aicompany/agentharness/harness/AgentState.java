package com.aicompany.agentharness.harness;

import com.aicompany.agentharness.llm.Message;
import com.aicompany.agentharness.tool.ToolResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AgentState {

	private final Task task;
	private final List<Message> messages = new ArrayList<>();
	private final List<ToolResult> toolResults = new ArrayList<>();
	private final List<String> analysisResults = new ArrayList<>();
	private AgentStatus status = AgentStatus.RUNNING;

	public AgentState(Task task) {
		this.task = task;
	}

	public Task task() {
		return task;
	}

	public List<Message> messages() {
		return Collections.unmodifiableList(messages);
	}

	public List<ToolResult> toolResults() {
		return Collections.unmodifiableList(toolResults);
	}

	public List<String> analysisResults() {
		return Collections.unmodifiableList(analysisResults);
	}

	public AgentStatus status() {
		return status;
	}

	public void addMessage(Message message) {
		messages.add(message);
	}

	public void addToolResult(ToolResult toolResult) {
		toolResults.add(toolResult);
	}

	public void addAnalysisResult(String analysisResult) {
		analysisResults.add(analysisResult);
	}

	public void changeStatus(AgentStatus status) {
		this.status = status;
	}

}
