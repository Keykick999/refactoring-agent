package com.aicompany.agentharness.plan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanValidatorTest {

	private final PlanValidator validator = new PlanValidator();

	@Test
	void storesDependenciesForValidTasks() {
		List<PlannedTask> tasks = validator.validateDrafts(List.of(
				new TaskDraft("a", "Analyze A", List.of()),
				new TaskDraft("b", "Analyze B", List.of("a"))));

		assertThat(tasks).extracting(PlannedTask::id).containsExactlyInAnyOrder("a", "b");
		assertThat(tasks.stream().filter(task -> task.id().equals("b")).findFirst().orElseThrow().dependsOn()).containsExactly("a");
	}

	@Test
	void rejectsDuplicateTaskIds() {
		assertThatThrownBy(() -> validator.validateDrafts(List.of(
				new TaskDraft("a", "one", List.of()),
				new TaskDraft("a", "two", List.of()))))
				.isInstanceOf(InvalidPlanException.class)
				.hasMessageContaining("Duplicate");
	}

	@Test
	void rejectsUnknownDependency() {
		assertThatThrownBy(() -> validator.validateDrafts(List.of(
				new TaskDraft("a", "one", List.of("missing")))))
				.isInstanceOf(InvalidPlanException.class)
				.hasMessageContaining("unknown task");
	}

	@Test
	void rejectsCycles() {
		assertThatThrownBy(() -> validator.validateDrafts(List.of(
				new TaskDraft("a", "A", List.of("c")),
				new TaskDraft("b", "B", List.of("a")),
				new TaskDraft("c", "C", List.of("b")))))
				.isInstanceOf(InvalidPlanException.class)
				.hasMessageContaining("cycle");
	}

	@Test
	void rejectsEmptyPlan() {
		assertThatThrownBy(() -> validator.validateDrafts(List.of()))
				.isInstanceOf(InvalidPlanException.class);
	}

}
