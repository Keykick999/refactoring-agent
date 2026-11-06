package com.aicompany.agentharness.plan;

/**
 * Decomposer가 다음 계획을 판단할 때 보는 현재 계획 상태.
 * 원문 전체를 다시 넣지 않고, Task ID와 이미 저장된 결과만 담는다.
 */
public final class PlanContext {

	static final String NO_PLAN = """
			Current Plan:
			No existing plan. This is the initial plan.

			Current Task IDs:
			(none)""";

	private static final int MAX_FACTS = 5;
	private static final int MAX_EVIDENCE = 3;
	private static final int MAX_QUESTIONS = 3;
	private static final int TEXT_LIMIT = 240;
	private static final int CONTEXT_LIMIT = 6000;

	private PlanContext() {
	}

	public static boolean isInitial(AnalysisPlan plan) {
		return plan == null || plan.tasks().isEmpty();
	}

	public static String render(AnalysisPlan plan) {
		if (isInitial(plan)) {
			return NO_PLAN;
		}
		StringBuilder text = new StringBuilder();
		text.append("Current Plan:\n");
		for (PlannedTask task : plan.tasks()) {
			text.append("- ").append(task.id()).append(" [").append(task.status()).append("] objective: ")
					.append(task.objective()).append('\n');
			appendOutcome(text, task.outcome());
		}
		text.append("Current Task IDs:\n");
		for (PlannedTask task : plan.tasks()) {
			text.append("- ").append(task.id()).append('\n');
		}
		if (text.length() > CONTEXT_LIMIT) {
			return text.substring(0, CONTEXT_LIMIT) + "\n... truncated plan context ...";
		}
		return text.toString().stripTrailing();
	}

	private static void appendOutcome(StringBuilder text, TaskOutcome outcome) {
		if (outcome == null) {
			return;
		}
		if (!outcome.conclusion().isBlank()) {
			text.append("  conclusion: ").append(limit(outcome.conclusion())).append('\n');
		}
		int facts = 0;
		for (String fact : outcome.facts()) {
			if (facts++ >= MAX_FACTS) {
				break;
			}
			text.append("  fact: ").append(limit(fact)).append('\n');
		}
		int evidenceCount = 0;
		for (TaskEvidence evidence : outcome.evidence()) {
			if (evidenceCount++ >= MAX_EVIDENCE) {
				break;
			}
			text.append("  evidence: ").append(evidence.filePath()).append(' ').append(evidence.className()).append(' ')
					.append(evidence.methodName()).append(' ').append(limit(evidence.detail())).append('\n');
		}
		int questions = 0;
		for (String question : outcome.unresolvedQuestions()) {
			if (questions++ >= MAX_QUESTIONS) {
				break;
			}
			text.append("  unresolved: ").append(limit(question)).append('\n');
		}
	}

	private static String limit(String value) {
		if (value == null || value.length() <= TEXT_LIMIT) {
			return value == null ? "" : value;
		}
		return value.substring(0, TEXT_LIMIT) + "...";
	}

}
