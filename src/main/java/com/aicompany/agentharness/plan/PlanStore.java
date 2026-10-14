package com.aicompany.agentharness.plan;

import java.util.List;
import java.util.Optional;

/**
 * 계획과 작업 결과를 저장한다. LLM 호출은 포함하지 않는다.
 */
public interface PlanStore {

	PlanSession begin(PlanIdentity identity);

	interface PlanSession {

		Optional<AnalysisPlan> existing();

		AnalysisPlan create(AnalysisRequest request, List<PlannedTask> tasks);

		AnalysisPlan load();

		void resetInProgress();

		void markInProgress(String taskId);

		void saveOutcome(String taskId, TaskOutcome outcome);

		void applyChanges(List<PlanChange> changes);

		void complete(FinalAnalysisResult result);

		void blocked(String reason);

		void failed(String reason);

	}

}
