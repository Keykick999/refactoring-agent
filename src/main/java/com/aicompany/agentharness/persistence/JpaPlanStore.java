package com.aicompany.agentharness.persistence;

import com.aicompany.agentharness.plan.AnalysisPlan;
import com.aicompany.agentharness.plan.AnalysisRequest;
import com.aicompany.agentharness.plan.FinalAnalysisResult;
import com.aicompany.agentharness.plan.PlanChange;
import com.aicompany.agentharness.plan.PlanIdentity;
import com.aicompany.agentharness.plan.PlanStore;
import com.aicompany.agentharness.plan.PlannedTask;
import com.aicompany.agentharness.plan.TaskOutcome;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class JpaPlanStore implements PlanStore {

	private final PlanPersistence persistence;

	public JpaPlanStore(PlanPersistence persistence) {
		this.persistence = persistence;
	}

	@Override
	public PlanSession begin(PlanIdentity identity) {
		return new PersistencePlanSession(persistence, identity, persistence.find(identity));
	}

	private static final class PersistencePlanSession implements PlanSession {

		private final PlanPersistence persistence;
		private final PlanIdentity identity;
		private Long planId;

		private PersistencePlanSession(PlanPersistence persistence, PlanIdentity identity, Optional<AnalysisPlan> existing) {
			this.persistence = persistence;
			this.identity = identity;
			this.planId = existing.map(AnalysisPlan::id).orElse(null);
		}

		@Override
		public Optional<AnalysisPlan> existing() {
			return planId == null ? Optional.empty() : Optional.of(persistence.load(planId));
		}

		@Override
		public AnalysisPlan create(AnalysisRequest request, List<PlannedTask> tasks) {
			AnalysisPlan created = persistence.create(identity, request, tasks);
			this.planId = created.id();
			return created;
		}

		@Override
		public AnalysisPlan load() {
			return persistence.load(requireId());
		}

		@Override
		public void resetInProgress() {
			persistence.resetInProgress(requireId());
		}

		@Override
		public void markInProgress(String taskId) {
			persistence.markInProgress(requireId(), taskId);
		}

		@Override
		public void saveOutcome(String taskId, TaskOutcome outcome) {
			persistence.saveOutcome(requireId(), taskId, outcome);
		}

		@Override
		public void applyChanges(List<PlanChange> changes) {
			if (!changes.isEmpty()) {
				persistence.applyChanges(requireId(), changes);
			}
		}

		@Override
		public void complete(FinalAnalysisResult result) {
			persistence.complete(requireId(), result);
		}

		@Override
		public void blocked(String reason) {
			persistence.blocked(requireId(), reason);
		}

		@Override
		public void failed(String reason) {
			persistence.failed(requireId(), reason);
		}

		private long requireId() {
			if (planId == null) {
				throw new IllegalStateException("Analysis plan has not been created.");
			}
			return planId;
		}

	}

}
