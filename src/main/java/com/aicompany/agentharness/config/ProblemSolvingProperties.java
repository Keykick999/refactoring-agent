package com.aicompany.agentharness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("agent-harness.problem-solving")
public record ProblemSolvingProperties(
		@DefaultValue("8") int maxSolverSteps,
		@DefaultValue("20") int maxPlanIterations,
		@DefaultValue("3") int maxFinalCheckRounds) {

	public ProblemSolvingProperties {
		if (maxSolverSteps < 1 || maxPlanIterations < 1 || maxFinalCheckRounds < 1) {
			throw new IllegalArgumentException("problem-solving limits must be at least 1");
		}
	}

}
