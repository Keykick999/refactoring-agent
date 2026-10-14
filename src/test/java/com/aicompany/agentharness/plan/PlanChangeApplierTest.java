package com.aicompany.agentharness.plan;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanChangeApplierTest {

	private final PlanChangeApplier applier = new PlanChangeApplier();

	@Test
	void addsAndCancelsTasks() {
		AnalysisPlan plan = plan(PlannedTask.pending("a", "A", List.of()));

		applier.apply(plan, List.of(PlanChange.addTask("b", "B", List.of("a"))));
		applier.apply(plan, List.of(PlanChange.cancelTask("b")));

		assertThat(plan.task("b")).get().extracting(PlannedTask::status).isEqualTo(PlanTaskStatus.CANCELLED);
	}

	@Test
	void updatesDependencies() {
		AnalysisPlan plan = plan(
				PlannedTask.pending("a", "A", List.of()),
				PlannedTask.pending("b", "B", List.of()));

		applier.apply(plan, List.of(PlanChange.updateDependency("b", List.of("a"))));

		assertThat(plan.task("b")).get().extracting(PlannedTask::dependsOn).isEqualTo(List.of("a"));
	}

	@Test
	void doesNotChangeCompletedTask() {
		AnalysisPlan plan = plan(PlannedTask.pending("a", "A", List.of())
				.withOutcome(TaskOutcome.completed(List.of("fact"), "done", List.of(), List.of())));

		assertThatThrownBy(() -> applier.apply(plan, List.of(PlanChange.cancelTask("a"))))
				.isInstanceOf(InvalidPlanException.class)
				.hasMessageContaining("cannot be cancelled");
		assertThat(plan.task("a")).get().extracting(PlannedTask::status).isEqualTo(PlanTaskStatus.COMPLETED);
	}

	private static AnalysisPlan plan(PlannedTask... tasks) {
		return new AnalysisPlan(1L, AnalysisRequest.of(Path.of("src"), "goal"), PlanStatus.RUNNING, List.of(tasks), null);
	}

}
