package com.aicompany.agentharness.harness;

import java.util.Objects;

public record Task(String goal, Target target) {

	public Task {
		Objects.requireNonNull(goal, "goal must not be null");
		Objects.requireNonNull(target, "target must not be null");
	}

}
