package com.aicompany.agentharness.plan;

import java.util.List;

public record FinalAnalysisResult(String objective, String summary, List<String> findings, List<String> relatedFiles,
		List<String> recommendations, String coverage, boolean complete) {

	public FinalAnalysisResult {
		if (objective == null || objective.isBlank()) {
			throw new IllegalArgumentException("objective must not be blank");
		}
		summary = summary == null ? "" : summary;
		findings = findings == null ? List.of() : List.copyOf(findings);
		relatedFiles = relatedFiles == null ? List.of() : List.copyOf(relatedFiles);
		recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
		coverage = coverage == null ? "" : coverage;
	}

	public String render() {
		StringBuilder text = new StringBuilder();
		text.append("Plan-based Analysis Report\n");
		text.append("Objective: ").append(objective).append('\n');
		text.append("Complete: ").append(complete).append('\n');
		if (!coverage.isBlank()) {
			text.append("Coverage: ").append(coverage).append('\n');
		}
		if (!summary.isBlank()) {
			text.append('\n').append(summary).append('\n');
		}
		appendSection(text, "Findings", findings);
		appendSection(text, "Related files", relatedFiles);
		appendSection(text, "Recommendations", recommendations);
		return text.toString();
	}

	private static void appendSection(StringBuilder text, String title, List<String> values) {
		if (values.isEmpty()) {
			return;
		}
		text.append('\n').append(title).append(":\n");
		for (String value : values) {
			text.append("- ").append(value).append('\n');
		}
	}

}
