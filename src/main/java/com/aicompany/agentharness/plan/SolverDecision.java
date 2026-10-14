package com.aicompany.agentharness.plan;

import com.aicompany.agentharness.tool.ToolCall;

import java.util.List;

public record SolverDecision(Kind kind, ToolCall toolCall, TaskOutcome outcome, List<PlanChange> planChanges) {

	public enum Kind {
		USE_TOOL,
		FINISH
	}

	public static SolverDecision useTool(ToolCall toolCall) {
		return new SolverDecision(Kind.USE_TOOL, toolCall, null, List.of());
	}

	public static SolverDecision finish(TaskOutcome outcome, List<PlanChange> planChanges) {
		return new SolverDecision(Kind.FINISH, null, outcome, planChanges == null ? List.of() : List.copyOf(planChanges));
	}

}
