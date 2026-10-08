package com.aicompany.agentharness.harness;

public record AgentResult(boolean success, String summary) {

	public static AgentResult success(String summary) {
		return new AgentResult(true, summary);
	}

	public static AgentResult failure(String reason) {
		return new AgentResult(false, reason);
	}

}
