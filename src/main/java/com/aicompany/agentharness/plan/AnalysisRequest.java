package com.aicompany.agentharness.plan;

import java.nio.file.Path;
import java.util.Objects;

public record AnalysisRequest(Path target, String objective, AnalysisStyle style, EnforcementMode enforcementMode) {

	public AnalysisRequest {
		Objects.requireNonNull(target, "target must not be null");
		if (objective == null || objective.isBlank()) {
			throw new IllegalArgumentException("objective must not be blank");
		}
		style = style == null ? AnalysisStyle.GENERAL : style;
		enforcementMode = enforcementMode == null ? EnforcementMode.ADVISORY : enforcementMode;
	}

	public static AnalysisRequest of(Path target, String objective) {
		return new AnalysisRequest(target, objective, AnalysisStyle.GENERAL, EnforcementMode.ADVISORY);
	}

}
