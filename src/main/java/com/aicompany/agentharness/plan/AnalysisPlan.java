package com.aicompany.agentharness.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class AnalysisPlan {

	private final long id;
	private final AnalysisRequest request;
	private PlanStatus status;
	private final Map<String, PlannedTask> tasks;
	private FinalAnalysisResult finalResult;

	public AnalysisPlan(long id, AnalysisRequest request, PlanStatus status, List<PlannedTask> tasks, FinalAnalysisResult finalResult) {
		this.id = id;
		this.request = request;
		this.status = status;
		this.tasks = new LinkedHashMap<>();
		for (PlannedTask task : tasks) {
			this.tasks.put(task.id(), task);
		}
		this.finalResult = finalResult;
	}

	public long id() {
		return id;
	}

	public AnalysisRequest request() {
		return request;
	}

	public PlanStatus status() {
		return status;
	}

	public void setStatus(PlanStatus status) {
		this.status = status;
	}

	public List<PlannedTask> tasks() {
		return List.copyOf(tasks.values());
	}

	public Optional<PlannedTask> task(String taskId) {
		return Optional.ofNullable(tasks.get(taskId));
	}

	public void replace(PlannedTask task) {
		tasks.put(task.id(), task);
	}

	public FinalAnalysisResult finalResult() {
		return finalResult;
	}

	public void setFinalResult(FinalAnalysisResult finalResult) {
		this.finalResult = finalResult;
	}

	public AnalysisProgress progress() {
		return AnalysisProgress.from(tasks());
	}

	public List<PlannedTask> completedOutcomes() {
		List<PlannedTask> completed = new ArrayList<>();
		for (PlannedTask task : tasks.values()) {
			if (task.status() == PlanTaskStatus.COMPLETED && task.outcome() != null) {
				completed.add(task);
			}
		}
		return List.copyOf(completed);
	}

}
