package com.aicompany.agentharness.plan;

import java.util.List;

public record TaskDraft(String id, String objective, List<String> dependsOn) {

	public TaskDraft {
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("task id must not be blank");
		}
		if (objective == null || objective.isBlank()) {
			throw new IllegalArgumentException("task objective must not be blank");
		}
		dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
	}

	public PlannedTask toPending() {
		return PlannedTask.pending(id, objective, dependsOn);
	}

}
