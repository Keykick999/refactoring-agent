package com.aicompany.agentharness.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 선행 조건이 모두 끝난 PENDING 작업만 실행한다. 우선순위는 아직 두지 않는다.
 */
public final class ExecutableTaskSelector {

	public Optional<PlannedTask> next(AnalysisPlan plan) {
		List<PlannedTask> executable = all(plan);
		return executable.stream().findFirst();
	}

	public List<PlannedTask> all(AnalysisPlan plan) {
		Map<String, PlannedTask> byId = plan.tasks().stream().collect(Collectors.toMap(PlannedTask::id, task -> task));
		List<PlannedTask> executable = new ArrayList<>();
		for (PlannedTask task : plan.tasks()) {
			if (task.status() == PlanTaskStatus.PENDING && dependenciesSatisfied(task, byId)) {
				executable.add(task);
			}
		}
		return List.copyOf(executable);
	}

	private static boolean dependenciesSatisfied(PlannedTask task, Map<String, PlannedTask> byId) {
		for (String dependencyId : task.dependsOn()) {
			PlannedTask dependency = byId.get(dependencyId);
			if (dependency == null || !dependency.status().satisfiesDependency()) {
				return false;
			}
		}
		return true;
	}

}
