package com.aicompany.agentharness.plan;

import java.util.List;

public record PlannedTask(String id, String objective, PlanTaskStatus status, List<String> dependsOn, TaskOutcome outcome) {

	public PlannedTask {
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("task id must not be blank");
		}
		if (objective == null || objective.isBlank()) {
			throw new IllegalArgumentException("task objective must not be blank");
		}
		status = status == null ? PlanTaskStatus.PENDING : status;
		dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
	}

	public PlannedTask withStatus(PlanTaskStatus newStatus) {
		return new PlannedTask(id, objective, newStatus, dependsOn, outcome);
	}

	public PlannedTask withDependsOn(List<String> newDependsOn) {
		return new PlannedTask(id, objective, status, newDependsOn, outcome);
	}

	public PlannedTask withOutcome(TaskOutcome newOutcome) {
		return new PlannedTask(id, objective, newOutcome.status(), dependsOn, newOutcome);
	}

	public static PlannedTask pending(String id, String objective, List<String> dependsOn) {
		return new PlannedTask(id, objective, PlanTaskStatus.PENDING, dependsOn, null);
	}

}
