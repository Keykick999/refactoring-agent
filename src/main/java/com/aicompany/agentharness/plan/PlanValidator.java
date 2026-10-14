package com.aicompany.agentharness.plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 작업 초안과 계획 변경을 저장하기 전에 검증한다. 순환 의존은 저장하지 않는다.
 */
public final class PlanValidator {

	public List<PlannedTask> validateDrafts(List<TaskDraft> drafts) {
		if (drafts == null || drafts.isEmpty()) {
			throw new InvalidPlanException("A plan requires at least one task.");
		}
		Map<String, PlannedTask> tasks = new HashMap<>();
		for (TaskDraft draft : drafts) {
			if (tasks.containsKey(draft.id())) {
				throw new InvalidPlanException("Duplicate task id: " + draft.id());
			}
			tasks.put(draft.id(), draft.toPending());
		}
		assertDependenciesExist(tasks);
		assertNoCycle(tasks);
		return List.copyOf(new ArrayList<>(tasks.values()));
	}

	public void validateChange(AnalysisPlan plan, PlanChange change) {
		switch (change.type()) {
			case ADD_TASK -> validateAdd(plan, change);
			case CANCEL_TASK -> validateCancel(plan, change);
			case UPDATE_DEPENDENCY -> validateUpdate(plan, change);
		}
	}

	private void validateAdd(AnalysisPlan plan, PlanChange change) {
		if (plan.task(change.taskId()).isPresent()) {
			throw new InvalidPlanException("Task already exists: " + change.taskId());
		}
		if (change.objective() == null || change.objective().isBlank()) {
			throw new InvalidPlanException("Added task requires an objective.");
		}
		Map<String, PlannedTask> next = copy(plan);
		next.put(change.taskId(), PlannedTask.pending(change.taskId(), change.objective(), change.dependsOn()));
		assertDependenciesExist(next);
		assertNoCycle(next);
	}

	private void validateCancel(AnalysisPlan plan, PlanChange change) {
		PlannedTask task = plan.task(change.taskId())
				.orElseThrow(() -> new InvalidPlanException("Unknown task: " + change.taskId()));
		if (task.status() == PlanTaskStatus.COMPLETED) {
			throw new InvalidPlanException("Completed task cannot be cancelled: " + change.taskId());
		}
	}

	private void validateUpdate(AnalysisPlan plan, PlanChange change) {
		PlannedTask task = plan.task(change.taskId())
				.orElseThrow(() -> new InvalidPlanException("Unknown task: " + change.taskId()));
		if (task.status() == PlanTaskStatus.COMPLETED) {
			throw new InvalidPlanException("Completed task dependencies cannot be changed: " + change.taskId());
		}
		Map<String, PlannedTask> next = copy(plan);
		next.put(task.id(), task.withDependsOn(change.dependsOn()));
		assertDependenciesExist(next);
		assertNoCycle(next);
	}

	private static Map<String, PlannedTask> copy(AnalysisPlan plan) {
		Map<String, PlannedTask> copy = new HashMap<>();
		for (PlannedTask task : plan.tasks()) {
			copy.put(task.id(), task);
		}
		return copy;
	}

	private static void assertDependenciesExist(Map<String, PlannedTask> tasks) {
		for (PlannedTask task : tasks.values()) {
			for (String dependency : task.dependsOn()) {
				if (!tasks.containsKey(dependency)) {
					throw new InvalidPlanException("Task %s depends on unknown task %s".formatted(task.id(), dependency));
				}
			}
		}
	}

	private static void assertNoCycle(Map<String, PlannedTask> tasks) {
		Map<String, Integer> remaining = new HashMap<>();
		Map<String, List<String>> dependents = new HashMap<>();
		for (PlannedTask task : tasks.values()) {
			remaining.put(task.id(), task.dependsOn().size());
			dependents.putIfAbsent(task.id(), new ArrayList<>());
		}
		for (PlannedTask task : tasks.values()) {
			for (String dependency : task.dependsOn()) {
				dependents.computeIfAbsent(dependency, key -> new ArrayList<>()).add(task.id());
			}
		}
		ArrayDeque<String> ready = new ArrayDeque<>();
		for (Map.Entry<String, Integer> entry : remaining.entrySet()) {
			if (entry.getValue() == 0) {
				ready.add(entry.getKey());
			}
		}
		int visited = 0;
		while (!ready.isEmpty()) {
			String current = ready.removeFirst();
			visited++;
			for (String dependent : dependents.getOrDefault(current, List.of())) {
				int left = remaining.merge(dependent, -1, Integer::sum);
				if (left == 0) {
					ready.add(dependent);
				}
			}
		}
		if (visited != tasks.size()) {
			throw new InvalidPlanException("Task plan contains a dependency cycle.");
		}
	}

	public static Set<String> ids(List<PlannedTask> tasks) {
		Set<String> ids = new HashSet<>();
		for (PlannedTask task : tasks) {
			ids.add(task.id());
		}
		return ids;
	}

}
