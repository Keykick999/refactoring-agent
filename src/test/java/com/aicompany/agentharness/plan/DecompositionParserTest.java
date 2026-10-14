package com.aicompany.agentharness.plan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecompositionParserTest {

	private final DecompositionParser parser = new DecompositionParser();

	@Test
	void parsesValidTaskPlan() {
		List<PlannedTask> tasks = parser.parse("""
				{"tasks":[
				  {"id":"a","objective":"Look at OrderService","dependsOn":[]},
				  {"id":"b","objective":"Look at Repository","dependsOn":["a"]}
				]}
				""");

		assertThat(tasks).hasSize(2);
		assertThat(tasks.stream().filter(task -> task.id().equals("b")).findFirst().orElseThrow().dependsOn()).containsExactly("a");
	}

	@Test
	void rejectsInvalidSchema() {
		assertThatThrownBy(() -> parser.parse("{\"hello\":true}"))
				.isInstanceOf(InvalidPlanException.class);
	}

}
