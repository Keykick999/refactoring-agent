package com.aicompany.agentharness.plan;

import java.util.List;

public record PlanChange(PlanChangeType type, String taskId, String objective, List<String> dependsOn) {

	public PlanChange {
		if (type == null) {
			throw new IllegalArgumentException("change type must not be null");
		}
		if (taskId == null || taskId.isBlank()) {
			throw new IllegalArgumentException("task id must not be blank");
		}
		dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
	}

	public static PlanChange addTask(String taskId, String objective, List<String> dependsOn) {
		return new PlanChange(PlanChangeType.ADD_TASK, taskId, objective, dependsOn);
	}

	public static PlanChange cancelTask(String taskId) {
		return new PlanChange(PlanChangeType.CANCEL_TASK, taskId, null, List.of());
	}

	public static PlanChange updateDependency(String taskId, List<String> dependsOn) {
		return new PlanChange(PlanChangeType.UPDATE_DEPENDENCY, taskId, null, dependsOn);
	}

}
