package com.aicompany.agentharness.plan;

import java.util.List;

/**
 * 한 작업의 기억. 다음 작업이 참고하는 사실과 판단이며, 최종 보고서가 아니다.
 */
public record TaskOutcome(PlanTaskStatus status, List<String> facts, String conclusion, List<TaskEvidence> evidence,
		List<String> unresolvedQuestions) {

	public TaskOutcome {
		if (status == null) {
			throw new IllegalArgumentException("status must not be null");
		}
		facts = facts == null ? List.of() : List.copyOf(facts);
		evidence = evidence == null ? List.of() : List.copyOf(evidence);
		unresolvedQuestions = unresolvedQuestions == null ? List.of() : List.copyOf(unresolvedQuestions);
		conclusion = conclusion == null ? "" : conclusion;
	}

	public static TaskOutcome completed(List<String> facts, String conclusion, List<TaskEvidence> evidence,
			List<String> unresolvedQuestions) {
		return new TaskOutcome(PlanTaskStatus.COMPLETED, facts, conclusion, evidence, unresolvedQuestions);
	}

	public static TaskOutcome blocked(String reason) {
		return new TaskOutcome(PlanTaskStatus.BLOCKED, List.of(), reason, List.of(), List.of(reason));
	}

	public static TaskOutcome failed(String reason) {
		return new TaskOutcome(PlanTaskStatus.FAILED, List.of(), reason, List.of(), List.of());
	}

}
