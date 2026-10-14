package com.aicompany.agentharness.plan;

import java.util.ArrayList;
import java.util.List;

public final class PlanChangeApplier {

	private final PlanValidator validator = new PlanValidator();

	public List<PlanChange> apply(AnalysisPlan plan, List<PlanChange> changes) {
		List<PlanChange> applied = new ArrayList<>();
		for (PlanChange change : changes) {
			validator.validateChange(plan, change);
			switch (change.type()) {
				case ADD_TASK -> plan.replace(PlannedTask.pending(change.taskId(), change.objective(), change.dependsOn()));
				case CANCEL_TASK -> plan.replace(plan.task(change.taskId()).orElseThrow().withStatus(PlanTaskStatus.CANCELLED));
				case UPDATE_DEPENDENCY -> plan.replace(plan.task(change.taskId()).orElseThrow().withDependsOn(change.dependsOn()));
			}
			applied.add(change);
		}
		return List.copyOf(applied);
	}

}
