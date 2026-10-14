package com.aicompany.agentharness.plan;

import java.util.List;

/**
 * 전체 문제가 어디까지 풀렸는지의 압축된 상태. 원본 LLM/MCP 출력은 넣지 않는다.
 */
public record AnalysisProgress(int completed, int pending, int blocked, int failed, int cancelled,
		List<String> unresolvedQuestions) {

	public AnalysisProgress {
		unresolvedQuestions = unresolvedQuestions == null ? List.of() : List.copyOf(unresolvedQuestions);
	}

	public static AnalysisProgress from(List<PlannedTask> tasks) {
		int completed = 0;
		int pending = 0;
		int blocked = 0;
		int failed = 0;
		int cancelled = 0;
		List<String> unresolved = new java.util.ArrayList<>();
		for (PlannedTask task : tasks) {
			switch (task.status()) {
				case COMPLETED -> completed++;
				case PENDING, IN_PROGRESS -> pending++;
				case BLOCKED -> blocked++;
				case FAILED -> failed++;
				case CANCELLED -> cancelled++;
			}
			if (task.outcome() != null) {
				unresolved.addAll(task.outcome().unresolvedQuestions());
			}
		}
		return new AnalysisProgress(completed, pending, blocked, failed, cancelled, unresolved);
	}

	public String render() {
		return "Progress: completed=%d pending=%d blocked=%d failed=%d cancelled=%d"
				.formatted(completed, pending, blocked, failed, cancelled);
	}

}
