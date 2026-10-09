package com.aicompany.agentharness.analysis;

import java.util.List;

/**
 * 수집된 클래스 분석 결과를 하나의 텍스트 보고서로 만든다. 보고서를 만들 때 LLM을 호출하지 않는다.
 */
public record AnalysisReport(List<ClassAnalysisResult> results, JobStatus jobStatus, int pending) {

	public AnalysisReport {
		results = List.copyOf(results);
		if (pending < 0) {
			throw new IllegalArgumentException("pending must not be negative: " + pending);
		}
	}

	public AnalysisReport(List<ClassAnalysisResult> results) {
		this(results, null, 0);
	}

	public AnalysisReport(List<ClassAnalysisResult> results, JobStatus jobStatus) {
		this(results, jobStatus, 0);
	}

	public static AnalysisReport from(ClassAnalysisCollector collector) {
		return new AnalysisReport(collector.results());
	}

	public int total() {
		return results.size();
	}

	public int succeeded() {
		return (int) results.stream().filter(result -> result.status() == AnalysisStatus.SUCCEEDED).count();
	}

	public int failed() {
		return total() - succeeded();
	}

	public String render() {
		StringBuilder report = new StringBuilder();
		report.append("Code Quality Analysis Report\n");
		if (jobStatus != null) {
			report.append("Job: %s\n".formatted(jobStatus));
		}
		report.append("Targets: %d, Succeeded: %d, Failed: %d\n".formatted(total(), succeeded(), failed()));
		if (pending > 0) {
			report.append("Pending: %d\n".formatted(pending));
		}
		if (results.isEmpty()) {
			report.append("\nNo Java source files were found.\n");
			return report.toString();
		}
		for (ClassAnalysisResult result : results) {
			report.append("\n## %s\n".formatted(result.className()));
			report.append("File: %s\n".formatted(result.path()));
			report.append("Status: %s\n".formatted(result.status()));
			if (result.status() == AnalysisStatus.SUCCEEDED && result.issues().isEmpty()) {
				report.append("Issues: none\n");
			}
			if (result.error() != null) {
				report.append("Error: %s\n".formatted(result.error()));
			}
			if (result.errorCategory() != null) {
				report.append("Cause: %s\n".formatted(result.errorCategory()));
			}
			if (result.userAction() != null) {
				report.append("Action: %s\n".formatted(result.userAction()));
			}
			for (CodeIssue issue : result.issues()) {
				report.append("- [%s] %s\n".formatted(issue.severity(), issue.title()));
				report.append("  Description: %s\n".formatted(issue.description()));
				report.append("  Evidence: %s\n".formatted(issue.evidence()));
				report.append("  Impact: %s\n".formatted(issue.impact()));
				report.append("  Recommendation: %s\n".formatted(issue.recommendation()));
			}
		}
		return report.toString();
	}

}
