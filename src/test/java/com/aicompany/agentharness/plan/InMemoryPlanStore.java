package com.aicompany.agentharness.plan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public final class InMemoryPlanStore implements PlanStore {

	private final Map<PlanIdentity, AnalysisPlan> plans = new LinkedHashMap<>();
	private final AtomicLong ids = new AtomicLong();
	private final PlanChangeApplier applier = new PlanChangeApplier();

	@Override
	public synchronized PlanSession begin(PlanIdentity identity) {
		return new Session(identity);
	}

	private final class Session implements PlanSession {

		private final PlanIdentity identity;

		private Session(PlanIdentity identity) {
			this.identity = identity;
		}

		@Override
		public Optional<AnalysisPlan> existing() {
			synchronized (InMemoryPlanStore.this) {
				return Optional.ofNullable(plans.get(identity));
			}
		}

		@Override
		public AnalysisPlan create(AnalysisRequest request, List<PlannedTask> tasks) {
			synchronized (InMemoryPlanStore.this) {
				AnalysisPlan plan = new AnalysisPlan(ids.incrementAndGet(), request, PlanStatus.RUNNING, tasks, null);
				plans.put(identity, plan);
				return plan;
			}
		}

		@Override
		public AnalysisPlan load() {
			return existing().orElseThrow(() -> new IllegalStateException("Analysis plan has not been created."));
		}

		@Override
		public void resetInProgress() {
			synchronized (InMemoryPlanStore.this) {
				AnalysisPlan plan = load();
				for (PlannedTask task : plan.tasks()) {
					if (task.status() == PlanTaskStatus.IN_PROGRESS) {
						plan.replace(task.withStatus(PlanTaskStatus.PENDING));
					}
				}
				plan.setStatus(PlanStatus.RUNNING);
			}
		}

		@Override
		public void markInProgress(String taskId) {
			synchronized (InMemoryPlanStore.this) {
				AnalysisPlan plan = load();
				plan.replace(plan.task(taskId).orElseThrow().withStatus(PlanTaskStatus.IN_PROGRESS));
			}
		}

		@Override
		public void saveOutcome(String taskId, TaskOutcome outcome) {
			synchronized (InMemoryPlanStore.this) {
				AnalysisPlan plan = load();
				plan.replace(plan.task(taskId).orElseThrow().withOutcome(outcome));
			}
		}

		@Override
		public void applyChanges(List<PlanChange> changes) {
			synchronized (InMemoryPlanStore.this) {
				applier.apply(load(), changes);
			}
		}

		@Override
		public void complete(FinalAnalysisResult result) {
			synchronized (InMemoryPlanStore.this) {
				AnalysisPlan plan = load();
				plan.setStatus(PlanStatus.COMPLETED);
				plan.setFinalResult(result);
			}
		}

		@Override
		public void blocked(String reason) {
			synchronized (InMemoryPlanStore.this) {
				load().setStatus(PlanStatus.BLOCKED);
			}
		}

		@Override
		public void failed(String reason) {
			synchronized (InMemoryPlanStore.this) {
				load().setStatus(PlanStatus.FAILED);
			}
		}

	}

}
