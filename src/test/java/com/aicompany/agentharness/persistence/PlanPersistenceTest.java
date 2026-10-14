package com.aicompany.agentharness.persistence;

import com.aicompany.agentharness.plan.AnalysisPlan;
import com.aicompany.agentharness.plan.AnalysisRequest;
import com.aicompany.agentharness.plan.AnalysisStyle;
import com.aicompany.agentharness.plan.EnforcementMode;
import com.aicompany.agentharness.plan.PlanChange;
import com.aicompany.agentharness.plan.PlanIdentity;
import com.aicompany.agentharness.plan.PlanStatus;
import com.aicompany.agentharness.plan.PlanTaskStatus;
import com.aicompany.agentharness.plan.PlannedTask;
import com.aicompany.agentharness.plan.TaskEvidence;
import com.aicompany.agentharness.plan.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(PlanPersistence.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PlanPersistenceTest {

	@Autowired
	private PlanPersistence persistence;

	@Test
	void savesTaskOutcomeAndDoesNotRerunCompletedWorkAfterReset() {
		PlanIdentity identity = PlanIdentity.of(request("persist outcome"));
		long planId = persistence.create(identity, request("persist outcome"), List.of(
				PlannedTask.pending("t1", "First", List.of()),
				PlannedTask.pending("t2", "Second", List.of()))).id();

		persistence.markInProgress(planId, "t1");
		persistence.saveOutcome(planId, "t1", TaskOutcome.completed(List.of("fact"), "done",
				List.of(new TaskEvidence("A.java", "A", "m", "line 3")), List.of("later?")));
		persistence.markInProgress(planId, "t2");
		persistence.resetInProgress(planId);

		AnalysisPlan plan = persistence.load(planId);
		assertThat(plan.task("t1")).get().extracting(PlannedTask::status).isEqualTo(PlanTaskStatus.COMPLETED);
		assertThat(plan.task("t1").orElseThrow().outcome().facts()).containsExactly("fact");
		assertThat(plan.task("t1").orElseThrow().outcome().evidence()).singleElement().extracting(TaskEvidence::detail)
				.isEqualTo("line 3");
		assertThat(plan.task("t2")).get().extracting(PlannedTask::status).isEqualTo(PlanTaskStatus.PENDING);
	}

	@Test
	void appliesValidatedPlanChanges() {
		PlanIdentity identity = PlanIdentity.of(request("plan changes"));
		long planId = persistence.create(identity, request("plan changes"),
				List.of(PlannedTask.pending("t1", "First", List.of()))).id();

		persistence.applyChanges(planId, List.of(PlanChange.addTask("t2", "Second", List.of("t1"))));
		AnalysisPlan plan = persistence.load(planId);

		assertThat(plan.task("t2")).get().extracting(PlannedTask::dependsOn).isEqualTo(List.of("t1"));
		assertThat(plan.status()).isEqualTo(PlanStatus.RUNNING);
	}

	@Test
	void usesSeparatePlansForDifferentStyles() {
		long solid = persistence.create(PlanIdentity.of(request("same")), request("same"),
				List.of(PlannedTask.pending("t1", "A", List.of()))).id();
		AnalysisRequest layered = new AnalysisRequest(Path.of("src").toAbsolutePath(), "same",
				AnalysisStyle.LAYERED_ARCHITECTURE, EnforcementMode.ADVISORY);
		long other = persistence.create(PlanIdentity.of(layered), layered,
				List.of(PlannedTask.pending("t1", "A", List.of()))).id();

		assertThat(other).isNotEqualTo(solid);
	}

	private static AnalysisRequest request(String objective) {
		return new AnalysisRequest(Path.of("src").toAbsolutePath(), objective, AnalysisStyle.SOLID, EnforcementMode.ADVISORY);
	}

}
