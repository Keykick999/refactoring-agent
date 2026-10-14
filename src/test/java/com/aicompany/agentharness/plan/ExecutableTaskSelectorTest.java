package com.aicompany.agentharness.plan;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutableTaskSelectorTest {

	private final ExecutableTaskSelector selector = new ExecutableTaskSelector();

	@Test
	void selectsPendingTaskWithCompletedDependencies() {
		AnalysisPlan plan = plan(
				PlannedTask.pending("a", "A", List.of()).withStatus(PlanTaskStatus.COMPLETED),
				PlannedTask.pending("b", "B", List.of("a")));

		assertThat(selector.next(plan)).get().extracting(PlannedTask::id).isEqualTo("b");
	}

	@Test
	void doesNotSelectTaskWhoseDependencyIsStillPending() {
		AnalysisPlan plan = plan(
				PlannedTask.pending("a", "A", List.of()),
				PlannedTask.pending("b", "B", List.of("a")));

		assertThat(selector.next(plan)).get().extracting(PlannedTask::id).isEqualTo("a");
		assertThat(selector.all(plan)).extracting(PlannedTask::id).containsExactly("a");
	}

	@Test
	void doesNotSelectTaskBlockedByFailedDependency() {
		AnalysisPlan plan = plan(
				PlannedTask.pending("a", "A", List.of()).withOutcome(TaskOutcome.failed("boom")),
				PlannedTask.pending("b", "B", List.of("a")));

		assertThat(selector.all(plan)).isEmpty();
	}

	private static AnalysisPlan plan(PlannedTask... tasks) {
		return new AnalysisPlan(1L, AnalysisRequest.of(Path.of("src"), "goal"), PlanStatus.RUNNING, List.of(tasks), null);
	}

}
