package com.aicompany.agentharness.plan;

import java.util.List;

/**
 * Decomposer가 유효한 계획을 만들지 못했다. 잘못된 계획은 저장하지 않으며, 애플리케이션 버그가 아니다.
 */
public class PlanGenerationException extends RuntimeException {

	private final String validationFailure;
	private final int decomposerAttempts;
	private final int semanticRetries;

	public PlanGenerationException(String validationFailure, int decomposerAttempts, int semanticRetries) {
		super("Plan generation failed after %d decomposer attempts (%d semantic retries): %s"
				.formatted(decomposerAttempts, semanticRetries, validationFailure));
		this.validationFailure = validationFailure == null || validationFailure.isBlank()
				? "The decomposer did not return a valid task plan."
				: validationFailure;
		this.decomposerAttempts = decomposerAttempts;
		this.semanticRetries = semanticRetries;
	}

	public String validationFailure() {
		return validationFailure;
	}

	public int decomposerAttempts() {
		return decomposerAttempts;
	}

	public int semanticRetries() {
		return semanticRetries;
	}

	public FinalAnalysisResult toResult(String objective) {
		String summary = """
				Plan generation failed
				Validation failure: %s
				Semantic retries: %d
				Analysis was not executed.
				""".formatted(validationFailure, semanticRetries);
		return new FinalAnalysisResult(objective, summary,
				List.of("Plan generation failed",
						"Validation failure: " + validationFailure,
						"Semantic retries: " + semanticRetries,
						"Analysis was not executed"),
				List.of(),
				List.of("Run the same command again, or use a model that only references task ids declared in the same plan."),
				"not executed", false);
	}

}
