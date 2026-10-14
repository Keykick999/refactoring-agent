package com.aicompany.agentharness.persistence;

import com.aicompany.agentharness.plan.AnalysisPlan;
import com.aicompany.agentharness.plan.AnalysisRequest;
import com.aicompany.agentharness.plan.FinalAnalysisResult;
import com.aicompany.agentharness.plan.FinalAnalysisResultMapper;
import com.aicompany.agentharness.plan.InvalidPlanException;
import com.aicompany.agentharness.plan.PlanChange;
import com.aicompany.agentharness.plan.PlanChangeApplier;
import com.aicompany.agentharness.plan.PlanIdentity;
import com.aicompany.agentharness.plan.PlanStatus;
import com.aicompany.agentharness.plan.PlanTaskStatus;
import com.aicompany.agentharness.plan.PlannedTask;
import com.aicompany.agentharness.plan.TaskEvidence;
import com.aicompany.agentharness.plan.TaskOutcome;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class PlanPersistence {

	private final AnalysisPlanRepository plans;
	private final PlanChangeApplier changeApplier = new PlanChangeApplier();
	private final FinalAnalysisResultMapper resultMapper = new FinalAnalysisResultMapper();

	public PlanPersistence(AnalysisPlanRepository plans) {
		this.plans = plans;
	}

	@Transactional
	public Optional<AnalysisPlan> find(PlanIdentity identity) {
		return plans.findByTargetPathAndObjectiveAndSettingsFingerprint(identity.targetPath(), identity.objective(),
				identity.fingerprint()).map(this::toPlan);
	}

	@Transactional
	public AnalysisPlan create(PlanIdentity identity, AnalysisRequest request, List<PlannedTask> tasks) {
		AnalysisPlanEntity entity = new AnalysisPlanEntity(identity.targetPath(), identity.objective(), identity.fingerprint(),
				request.style(), request.enforcementMode(), Instant.now());
		for (PlannedTask task : tasks) {
			entity.addTask(new PlannedTaskEntity(task.id(), task.objective(), task.dependsOn()));
		}
		return toPlan(plans.saveAndFlush(entity));
	}

	@Transactional
	public AnalysisPlan load(long planId) {
		return toPlan(require(planId));
	}

	@Transactional
	public void resetInProgress(long planId) {
		AnalysisPlanEntity entity = require(planId);
		for (PlannedTaskEntity task : entity.getTasks()) {
			if (task.getStatus() == PlanTaskStatus.IN_PROGRESS) {
				task.setStatus(PlanTaskStatus.PENDING);
			}
		}
		entity.setStatus(PlanStatus.RUNNING, null);
		plans.saveAndFlush(entity);
	}

	@Transactional
	public void markInProgress(long planId, String taskId) {
		requireTask(planId, taskId).setStatus(PlanTaskStatus.IN_PROGRESS);
	}

	@Transactional
	public void saveOutcome(long planId, String taskId, TaskOutcome outcome) {
		requireTask(planId, taskId).replaceOutcome(outcome.status(), outcome.conclusion(), outcome.facts(),
				outcome.unresolvedQuestions(), outcome.evidence().stream().map(PersistedTaskEvidence::new).toList());
	}

	@Transactional
	public void applyChanges(long planId, List<PlanChange> changes) {
		AnalysisPlan plan = toPlan(require(planId));
		changeApplier.apply(plan, changes);
		syncTasks(require(planId), plan);
	}

	@Transactional
	public void complete(long planId, FinalAnalysisResult result) {
		finish(planId, PlanStatus.COMPLETED, null, result);
	}

	@Transactional
	public void blocked(long planId, String reason) {
		finish(planId, PlanStatus.BLOCKED, reason, null);
	}

	@Transactional
	public void failed(long planId, String reason) {
		finish(planId, PlanStatus.FAILED, reason, null);
	}

	@Transactional
	public void finish(long planId, PlanStatus status, String reason, FinalAnalysisResult result) {
		AnalysisPlanEntity entity = require(planId);
		entity.setStatus(status, reason);
		if (result != null) {
			entity.setFinalResultJson(resultMapper.write(result));
		}
	}

	private void syncTasks(AnalysisPlanEntity entity, AnalysisPlan plan) {
		java.util.Map<String, PlannedTaskEntity> existing = new java.util.LinkedHashMap<>();
		for (PlannedTaskEntity task : entity.getTasks()) {
			existing.put(task.getTaskKey(), task);
		}
		for (PlannedTask task : plan.tasks()) {
			PlannedTaskEntity stored = existing.get(task.id());
			if (stored == null) {
				stored = new PlannedTaskEntity(task.id(), task.objective(), task.dependsOn());
				entity.addTask(stored);
			}
			stored.setStatus(task.status());
			stored.setDependsOn(task.dependsOn());
			if (task.outcome() != null) {
				stored.replaceOutcome(task.outcome().status(), task.outcome().conclusion(), task.outcome().facts(),
						task.outcome().unresolvedQuestions(),
						task.outcome().evidence().stream().map(PersistedTaskEvidence::new).toList());
			}
		}
	}

	private AnalysisPlanEntity require(long planId) {
		return plans.findById(planId).orElseThrow(() -> new IllegalStateException("Analysis plan was not found: " + planId));
	}

	private PlannedTaskEntity requireTask(long planId, String taskId) {
		return require(planId).getTasks().stream()
				.filter(task -> task.getTaskKey().equals(taskId))
				.findFirst()
				.orElseThrow(() -> new InvalidPlanException("Unknown task: " + taskId));
	}

	private AnalysisPlan toPlan(AnalysisPlanEntity entity) {
		List<PlannedTask> tasks = new ArrayList<>();
		for (PlannedTaskEntity task : entity.getTasks()) {
			TaskOutcome outcome = (task.getStatus() == PlanTaskStatus.PENDING
					|| (task.getStatus() == PlanTaskStatus.IN_PROGRESS && task.getConclusion() == null))
					? null
					: toOutcome(task);
			tasks.add(new PlannedTask(task.getTaskKey(), task.getObjective(), task.getStatus(),
					List.copyOf(task.getDependsOn()), outcome));
		}
		AnalysisRequest request = new AnalysisRequest(Path.of(entity.getTargetPath()), entity.getObjective(), entity.getStyle(),
				entity.getEnforcementMode());
		FinalAnalysisResult finalResult = resultMapper.read(entity.getFinalResultJson(), entity.getObjective());
		return new AnalysisPlan(entity.getId(), request, entity.getStatus(), tasks, finalResult);
	}

	private TaskOutcome toOutcome(PlannedTaskEntity task) {
		if (task.getConclusion() == null && task.getFacts().isEmpty() && task.getEvidence().isEmpty()) {
			return null;
		}
		return new TaskOutcome(task.getStatus(), List.copyOf(task.getFacts()), task.getConclusion() == null ? "" : task.getConclusion(),
				task.getEvidence().stream().map(PersistedTaskEvidence::toEvidence).toList(),
				List.copyOf(task.getUnresolvedQuestions()));
	}

}
