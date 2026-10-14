package com.aicompany.agentharness.plan;

public enum PlanTaskStatus {

	PENDING,
	IN_PROGRESS,
	COMPLETED,
	BLOCKED,
	FAILED,
	CANCELLED;

	public boolean isTerminal() {
		return this == COMPLETED || this == BLOCKED || this == FAILED || this == CANCELLED;
	}

	public boolean blocksDependents() {
		return this == BLOCKED || this == FAILED || this == PENDING || this == IN_PROGRESS;
	}

	public boolean satisfiesDependency() {
		return this == COMPLETED || this == CANCELLED;
	}

}
