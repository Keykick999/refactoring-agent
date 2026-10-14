package com.aicompany.agentharness.plan;

import java.util.List;

public record FinalCheckDecision(boolean needsMoreWork, String reason, List<TaskDraft> newTasks) {

	public FinalCheckDecision {
		reason = reason == null ? "" : reason;
		newTasks = newTasks == null ? List.of() : List.copyOf(newTasks);
	}

}
